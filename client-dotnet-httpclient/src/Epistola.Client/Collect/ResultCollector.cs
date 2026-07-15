using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Numerics;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Epistola.Client.Http;
using Newtonsoft.Json.Linq;

namespace Epistola.Client.Collect;

/// <summary>
/// Collects generation results via the <c>/generation/collect</c> endpoint with NDJSON streaming,
/// compression, and adaptive polling.
///
/// Results are processed one at a time — the response is never loaded into memory. Results from your
/// node are returned first; orphaned results from dead nodes follow.
///
/// Features: NDJSON streaming (constant memory); compression (gzip built-in, lz4/zstd auto-detected
/// when the libraries are present); adaptive polling (immediate on hasMore, exponential backoff when
/// idle); sequence-based acknowledgment; partition-aware routing-key helpers; metrics via
/// <see cref="IMetricsListener"/>; thread-safe <see cref="CollectOnce"/>; and a process-exit hook for
/// graceful stop.
///
/// The collector is driven by an <see cref="HttpClient"/> — build one with
/// <see cref="EpistolaHttpClientBuilder"/> so identity/JWT/media-type handlers are applied.
/// </summary>
public sealed class ResultCollector
{
    private readonly HttpClient _http;
    private readonly string _tenantId;
    private readonly int _batchSize;
    private readonly TimeSpan _minInterval;
    private readonly TimeSpan _maxInterval;
    private readonly TimeSpan _kickInterval;
    private readonly double _backoffMultiplier;
    private readonly Action<GenerationResult> _handler;
    private readonly Action<Exception>? _errorHandler;
    private readonly IMetricsListener? _metricsListener;
    private readonly bool _registerShutdownHook;

    private const string VendorJson = "application/vnd.epistola.v1+json";
    private const string Ndjson = "application/vnd.epistola.v1+ndjson";

    private int _running; // 0/1 flag
    private long _currentInterval;
    private long? _lastAcknowledgedSequence;
    private readonly SemaphoreSlim _pollLock = new(1, 1);
    private readonly SemaphoreSlim _wake = new(0, 1);
    private EventHandler? _shutdownHook;

    private static readonly Func<Stream, Stream>? Lz4Decompressor = TryLoadLz4();
    private static readonly Func<Stream, Stream>? ZstdDecompressor = TryLoadZstd();

    private ResultCollector(
        HttpClient http,
        string tenantId,
        int batchSize,
        TimeSpan minInterval,
        TimeSpan maxInterval,
        TimeSpan kickInterval,
        double backoffMultiplier,
        Action<GenerationResult> handler,
        Action<Exception>? errorHandler,
        IMetricsListener? metricsListener,
        bool registerShutdownHook)
    {
        _http = http;
        _tenantId = tenantId;
        _batchSize = batchSize;
        _minInterval = minInterval;
        _maxInterval = maxInterval;
        _kickInterval = kickInterval;
        _backoffMultiplier = backoffMultiplier;
        _handler = handler;
        _errorHandler = errorHandler;
        _metricsListener = metricsListener;
        _registerShutdownHook = registerShutdownHook;
        _currentInterval = (long)minInterval.TotalMilliseconds;
    }

    /// <summary>Creates a new <see cref="ResultCollectorBuilder"/>.</summary>
    public static ResultCollectorBuilder Builder() => new();

    /// <summary>Current partition assignment, updated on each poll from the <c>_meta</c> line.</summary>
    public PartitionAssignment? CurrentPartitionAssignment { get; private set; }

    // --- Partition routing helpers ---

    /// <summary>
    /// Computes the partition number for a routing key using the server's hash (murmur3 x86 32-bit,
    /// seed 0). Returns <c>null</c> if the assignment is not yet known (call after the first poll).
    /// </summary>
    public int? PartitionFor(string routingKey)
    {
        var assignment = CurrentPartitionAssignment;
        if (assignment == null) return null;
        var hash = Murmur3X86_32(Encoding.UTF8.GetBytes(routingKey), 0);
        return (int)((hash & 0x7FFFFFFF) % assignment.Total);
    }

    /// <summary>Checks whether a routing key would land on one of this node's partitions.</summary>
    public bool IsMyPartition(string routingKey)
    {
        var partition = PartitionFor(routingKey);
        if (partition == null) return false;
        return CurrentPartitionAssignment?.Mine.Contains(partition.Value) ?? false;
    }

    /// <summary>
    /// Returns a routing key that targets one of this node's partitions: the original key if it
    /// already routes here, otherwise a prefixed key. Returns <c>null</c> if the assignment is unknown.
    /// </summary>
    public string? RoutingKeyToMe(string key)
    {
        var assignment = CurrentPartitionAssignment;
        if (assignment == null) return null;
        if (IsMyPartition(key)) return key;
        foreach (var p in assignment.Mine)
        {
            var candidate = $"{p}:{key}";
            if (IsMyPartition(candidate)) return candidate;
        }
        return $"{assignment.Mine.First()}:{key}";
    }

    // --- Poll loop ---

    /// <summary>Starts the adaptive poll loop, blocking the current thread until <see cref="Stop"/>.</summary>
    public void Start() => StartAsync().GetAwaiter().GetResult();

    /// <summary>Starts the adaptive poll loop asynchronously, completing when <see cref="Stop"/> is called.</summary>
    public async Task StartAsync(CancellationToken cancellationToken = default)
    {
        Interlocked.Exchange(ref _running, 1);
        _currentInterval = (long)_minInterval.TotalMilliseconds;

        if (_registerShutdownHook)
        {
            _shutdownHook = (_, _) => Stop();
            AppDomain.CurrentDomain.ProcessExit += _shutdownHook;
        }

        try
        {
            while (Volatile.Read(ref _running) == 1 && !cancellationToken.IsCancellationRequested)
            {
                try
                {
                    var result = await CollectOnceAsync(cancellationToken).ConfigureAwait(false);
                    if (Volatile.Read(ref _running) == 0) break;

                    _currentInterval = result.HasMore
                        ? 0
                        : result.Count > 0
                            ? (long)_minInterval.TotalMilliseconds
                            : Math.Min((long)(_currentInterval * _backoffMultiplier), (long)_maxInterval.TotalMilliseconds);

                    await SleepInterruptibly(_currentInterval, cancellationToken).ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                catch (Exception e)
                {
                    _errorHandler?.Invoke(e);
                    var jitter = Random.Shared.NextInt64(_currentInterval / 2 + 1);
                    _currentInterval = Math.Min((long)(_currentInterval * _backoffMultiplier), (long)_maxInterval.TotalMilliseconds);
                    try
                    {
                        await SleepInterruptibly(_currentInterval + jitter, cancellationToken).ConfigureAwait(false);
                    }
                    catch (OperationCanceledException)
                    {
                        break;
                    }
                }
            }
        }
        finally
        {
            RemoveShutdownHook();
        }
    }

    private async Task SleepInterruptibly(long durationMs, CancellationToken cancellationToken)
    {
        if (durationMs <= 0)
        {
            Drain();
            return;
        }

        await _wake.WaitAsync((int)Math.Min(durationMs, int.MaxValue), cancellationToken).ConfigureAwait(false);
        Drain();
    }

    private void Drain()
    {
        while (_wake.CurrentCount > 0)
        {
            _wake.Wait(0);
        }
    }

    private void Signal()
    {
        try
        {
            _wake.Release();
        }
        catch (SemaphoreFullException)
        {
            // Already signalled — capacity-1 collapse, matching the Kotlin behaviour.
        }
    }

    /// <summary>
    /// Hints that a result is expected soon — shortens the current backoff to <c>kickInterval</c> and
    /// wakes the poll loop. A no-op when already polling fast enough. Safe to call from any thread.
    /// </summary>
    public void Kick()
    {
        if (_currentInterval > (long)_kickInterval.TotalMilliseconds)
        {
            _currentInterval = (long)_kickInterval.TotalMilliseconds;
            Signal();
        }
    }

    /// <summary>Signals the poll loop to stop after the current collection completes.</summary>
    public void Stop()
    {
        Interlocked.Exchange(ref _running, 0);
        Signal();
    }

    /// <summary>Performs a single collection call synchronously. See <see cref="CollectOnceAsync"/>.</summary>
    public CollectResult CollectOnce() => CollectOnceAsync().GetAwaiter().GetResult();

    /// <summary>
    /// Performs a single collection call. Thread-safe — concurrent calls are serialized to prevent
    /// duplicate delivery. Streams the NDJSON response line by line, invoking the handler per result.
    /// If the handler throws, the sequence is not advanced and the batch is redelivered next call.
    /// </summary>
    public async Task<CollectResult> CollectOnceAsync(CancellationToken cancellationToken = default)
    {
        await _pollLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        var startTime = Environment.TickCount64;
        try
        {
            var body = _lastAcknowledgedSequence != null
                ? $"{{\"acknowledgeUpTo\":{_lastAcknowledgedSequence},\"limit\":{_batchSize}}}"
                : $"{{\"limit\":{_batchSize}}}";

            using var request = new HttpRequestMessage(HttpMethod.Post, $"tenants/{_tenantId}/generation/collect")
            {
                Content = new StringContent(body, Encoding.UTF8, VendorJson),
            };
            request.Headers.Accept.ParseAdd(Ndjson);
            request.Headers.TryAddWithoutValidation("Accept-Encoding", SupportedEncodings());

            using var response = await _http
                .SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
                .ConfigureAwait(false);
            response.EnsureSuccessStatusCode();

            var encoding = response.Content.Headers.ContentEncoding.FirstOrDefault();
            await using var raw = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
            using var stream = DecompressIfNeeded(raw, encoding);
            using var reader = new StreamReader(stream, Encoding.UTF8);

            var count = 0;
            var hasMore = false;
            long? lastSequenceInBatch = null;

            string? line;
            while ((line = await reader.ReadLineAsync(cancellationToken).ConfigureAwait(false)) != null)
            {
                if (string.IsNullOrWhiteSpace(line)) continue;
                var node = JObject.Parse(line);

                if (node["_meta"] != null && node["_meta"]!.Value<bool>())
                {
                    hasMore = node["hasMore"]?.Value<bool>() ?? false;
                    UpdatePartitionAssignment(node);
                    break;
                }

                var parsed = ParseResult(node);
                _handler(parsed);
                lastSequenceInBatch = parsed.Sequence;
                count++;
            }

            if (lastSequenceInBatch != null)
            {
                _lastAcknowledgedSequence = lastSequenceInBatch;
            }

            _metricsListener?.OnPoll(count, hasMore, Environment.TickCount64 - startTime, null);
            return new CollectResult(count, hasMore);
        }
        catch (Exception e)
        {
            _metricsListener?.OnPoll(0, false, Environment.TickCount64 - startTime, e);
            throw;
        }
        finally
        {
            _pollLock.Release();
        }
    }

    private void UpdatePartitionAssignment(JObject metaNode)
    {
        var partitions = metaNode["partitions"];
        if (partitions == null) return;
        var total = partitions["total"]?.Value<int>();
        var mine = (partitions["mine"] as JArray)?.Select(x => x.Value<int>()).ToList();
        if (total == null || mine == null) return;
        var hash = partitions["hash"]?.Value<string>() ?? "murmur3";

        var newAssignment = new PartitionAssignment(total.Value, mine, hash);
        var old = CurrentPartitionAssignment;
        if (!newAssignment.Equals(old))
        {
            CurrentPartitionAssignment = newAssignment;
            _metricsListener?.OnPartitionChange(old, newAssignment);
        }
    }

    private static GenerationResult ParseResult(JObject node) => new(
        Sequence: node["sequence"]!.Value<long>(),
        RequestId: node["requestId"]!.Value<string>() ?? string.Empty,
        BatchId: node["batchId"]?.Value<string>(),
        Status: node["status"]!.Value<string>() ?? string.Empty,
        DocumentId: node["documentId"]?.Value<string>(),
        CorrelationId: node["correlationId"]?.Value<string>(),
        RoutingKey: node["routingKey"]?.Value<string>(),
        TemplateId: node["templateId"]?.Value<string>(),
        VariantId: node["variantId"]?.Value<string>(),
        VersionId: node["versionId"]?.Value<int?>(),
        Filename: node["filename"]?.Value<string>(),
        ContentType: node["contentType"]?.Value<string>(),
        SizeBytes: node["sizeBytes"]?.Value<long?>(),
        Error: node["error"]?.Value<string>(),
        CompletedAt: node["completedAt"]?.Value<string>());

    private static string SupportedEncodings()
    {
        var encodings = new List<string>();
        if (Lz4Decompressor != null) encodings.Add("lz4");
        if (ZstdDecompressor != null) encodings.Add("zstd");
        encodings.Add("gzip");
        return string.Join(", ", encodings);
    }

    private static Stream DecompressIfNeeded(Stream input, string? encoding) => encoding switch
    {
        "gzip" => new GZipStream(input, CompressionMode.Decompress),
        "lz4" => Lz4Decompressor?.Invoke(input)
            ?? throw new InvalidOperationException("Server sent lz4 but K4os.Compression.LZ4.Streams is not available"),
        "zstd" => ZstdDecompressor?.Invoke(input)
            ?? throw new InvalidOperationException("Server sent zstd but ZstdSharp is not available"),
        _ => input,
    };

    private void RemoveShutdownHook()
    {
        if (_shutdownHook != null)
        {
            AppDomain.CurrentDomain.ProcessExit -= _shutdownHook;
            _shutdownHook = null;
        }
    }

    // Optional decompressors — loaded via reflection to avoid hard dependencies, mirroring Kotlin.
    private static Func<Stream, Stream>? TryLoadLz4()
    {
        try
        {
            // K4os.Compression.LZ4.Streams.LZ4Stream.Decode(Stream, LZ4DecoderSettings, bool)
            var type = Type.GetType("K4os.Compression.LZ4.Streams.LZ4Stream, K4os.Compression.LZ4.Streams");
            var method = type?.GetMethod("Decode", new[] { typeof(Stream) });
            if (method == null) return null;
            return input => (Stream)method.Invoke(null, new object[] { input })!;
        }
        catch
        {
            return null;
        }
    }

    private static Func<Stream, Stream>? TryLoadZstd()
    {
        try
        {
            // ZstdSharp.DecompressionStream(Stream)
            var type = Type.GetType("ZstdSharp.DecompressionStream, ZstdSharp");
            var ctor = type?.GetConstructor(new[] { typeof(Stream) });
            if (ctor == null) return null;
            return input => (Stream)ctor.Invoke(new object[] { input });
        }
        catch
        {
            return null;
        }
    }

    /// <summary>MurmurHash3 x86 32-bit with configurable seed (matches the server's Guava implementation).</summary>
    internal static int Murmur3X86_32(byte[] data, int seed)
    {
        unchecked
        {
            const int c1 = (int)0xcc9e2d51;
            const int c2 = 0x1b873593;
            var h1 = seed;
            var len = data.Length;
            var nblocks = len / 4;

            for (var i = 0; i < nblocks; i++)
            {
                var idx = i * 4;
                var k1 = (data[idx] & 0xFF)
                    | ((data[idx + 1] & 0xFF) << 8)
                    | ((data[idx + 2] & 0xFF) << 16)
                    | ((data[idx + 3] & 0xFF) << 24);

                k1 *= c1;
                k1 = (int)BitOperations.RotateLeft((uint)k1, 15);
                k1 *= c2;
                h1 ^= k1;
                h1 = (int)BitOperations.RotateLeft((uint)h1, 13);
                h1 = (h1 * 5) + unchecked((int)0xe6546b64);
            }

            var tail = nblocks * 4;
            var k = 0;
            switch (len & 3)
            {
                case 3:
                    k ^= (data[tail + 2] & 0xFF) << 16;
                    goto case 2;
                case 2:
                    k ^= (data[tail + 1] & 0xFF) << 8;
                    goto case 1;
                case 1:
                    k ^= data[tail] & 0xFF;
                    k *= c1;
                    k = (int)BitOperations.RotateLeft((uint)k, 15);
                    k *= c2;
                    h1 ^= k;
                    break;
            }

            h1 ^= len;
            h1 ^= (int)((uint)h1 >> 16);
            h1 *= unchecked((int)0x85ebca6b);
            h1 ^= (int)((uint)h1 >> 13);
            h1 *= unchecked((int)0xc2b2ae35);
            h1 ^= (int)((uint)h1 >> 16);

            return h1;
        }
    }

    /// <summary>A completed or failed generation result.</summary>
    public sealed record GenerationResult(
        long Sequence,
        string RequestId,
        string? BatchId,
        string Status,
        string? DocumentId,
        string? CorrelationId,
        string? RoutingKey,
        string? TemplateId,
        string? VariantId,
        int? VersionId,
        string? Filename,
        string? ContentType,
        long? SizeBytes,
        string? Error,
        string? CompletedAt);

    /// <summary>The outcome of one collection call.</summary>
    public sealed record CollectResult(int Count, bool HasMore);

    /// <summary>Partition assignment info from the server.</summary>
    public sealed record PartitionAssignment(int Total, IReadOnlyList<int> Mine, string Hash)
    {
        public bool Equals(PartitionAssignment? other) =>
            other != null && Total == other.Total && Hash == other.Hash && Mine.SequenceEqual(other.Mine);

        public override int GetHashCode() => HashCode.Combine(Total, Hash, Mine.Count);
    }

    /// <summary>Callback interface for observability.</summary>
    public interface IMetricsListener
    {
        /// <summary>Called after each poll completes.</summary>
        void OnPoll(int count, bool hasMore, long durationMs, Exception? error);

        /// <summary>Called when the partition assignment changes.</summary>
        void OnPartitionChange(PartitionAssignment? oldAssignment, PartitionAssignment newAssignment);
    }

    /// <summary>Fluent builder for <see cref="ResultCollector"/>.</summary>
    public sealed class ResultCollectorBuilder
    {
        private HttpClient? _http;
        private string? _tenantId;
        private int _batchSize = 100;
        private TimeSpan _minInterval = TimeSpan.FromSeconds(1);
        private TimeSpan _maxInterval = TimeSpan.FromSeconds(30);
        private TimeSpan _kickInterval = TimeSpan.FromSeconds(3);
        private double _backoffMultiplier = 3.0;
        private Action<GenerationResult>? _handler;
        private Action<Exception>? _errorHandler;
        private IMetricsListener? _metricsListener;
        private bool _registerShutdownHook = true;

        /// <summary>The <see cref="HttpClient"/> to poll with (build one via <see cref="EpistolaHttpClientBuilder"/>).</summary>
        public ResultCollectorBuilder HttpClient(HttpClient client) { _http = client; return this; }

        /// <summary>The tenant whose results to collect.</summary>
        public ResultCollectorBuilder TenantId(string tenantId) { _tenantId = tenantId; return this; }

        /// <summary>Maximum results per collection (default: 100).</summary>
        public ResultCollectorBuilder BatchSize(int size)
        {
            if (size is < 1 or > 10000) throw new ArgumentException("batchSize must be between 1 and 10000", nameof(size));
            _batchSize = size;
            return this;
        }

        /// <summary>Minimum poll interval when results are flowing (default: 1s).</summary>
        public ResultCollectorBuilder MinInterval(TimeSpan interval) { RequirePositive(interval, nameof(interval)); _minInterval = interval; return this; }

        /// <summary>Maximum poll interval when idle (default: 30s).</summary>
        public ResultCollectorBuilder MaxInterval(TimeSpan interval) { RequirePositive(interval, nameof(interval)); _maxInterval = interval; return this; }

        /// <summary>Wait time used by <see cref="Kick"/> to override the current backoff (default: 3s).</summary>
        public ResultCollectorBuilder KickInterval(TimeSpan interval) { RequirePositive(interval, nameof(interval)); _kickInterval = interval; return this; }

        /// <summary>Exponential backoff multiplier applied on each empty poll (default: 3.0).</summary>
        public ResultCollectorBuilder BackoffMultiplier(double multiplier)
        {
            if (multiplier <= 1.0) throw new ArgumentException("backoffMultiplier must be > 1.0", nameof(multiplier));
            _backoffMultiplier = multiplier;
            return this;
        }

        /// <summary>Handler called for each result as it streams in.</summary>
        public ResultCollectorBuilder Handler(Action<GenerationResult> handler) { _handler = handler; return this; }

        /// <summary>Optional error handler for collection failures.</summary>
        public ResultCollectorBuilder ErrorHandler(Action<Exception> handler) { _errorHandler = handler; return this; }

        /// <summary>Optional metrics listener for observability.</summary>
        public ResultCollectorBuilder MetricsListener(IMetricsListener listener) { _metricsListener = listener; return this; }

        /// <summary>Register a process-exit hook to stop polling gracefully (default: true).</summary>
        public ResultCollectorBuilder RegisterShutdownHook(bool register) { _registerShutdownHook = register; return this; }

        /// <summary>Builds the <see cref="ResultCollector"/>.</summary>
        public ResultCollector Build() => new(
            _http ?? throw new InvalidOperationException("httpClient is required"),
            _tenantId ?? throw new InvalidOperationException("tenantId is required"),
            _batchSize,
            _minInterval,
            _maxInterval,
            _kickInterval,
            _backoffMultiplier,
            _handler ?? throw new InvalidOperationException("handler is required"),
            _errorHandler,
            _metricsListener,
            _registerShutdownHook);

        private static void RequirePositive(TimeSpan value, string name)
        {
            if (value <= TimeSpan.Zero) throw new ArgumentException($"{name} must be positive", name);
        }
    }
}
