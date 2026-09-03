// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using System.Text;
using System.Text.Json;
using Epistola.Client.Api;
using Epistola.Client.Auth;
using Epistola.Client.Collect;
using Epistola.Client.Error;
using Epistola.Client.Http;
using Epistola.Client.Identity;
using Epistola.Client.Model;

namespace Epistola.Conformance;

/// <summary>
/// The .NET client's conformance driver.
///
/// Asks the conformance server what to do, does it with the published client, and reports back. It
/// asserts nothing — the server judges the requests, so the four clients are held to one set of
/// expectations rather than four that drift. See ../../README.md for the driver contract.
/// </summary>
public static class Driver
{
    private static readonly HttpClient Control = new();

    public static int Main(string[] args)
    {
        if (args.Length < 1)
        {
            Console.Error.WriteLine("usage: driver <conformance server base url>");
            return 2;
        }

        var baseUrl = args[0];
        var instruction = Get($"{baseUrl}/__conformance/action");
        var config = instruction.GetProperty("config");

        try
        {
            switch (instruction.GetProperty("action").GetString())
            {
                case "ping": Ping(baseUrl, config); break;
                case "list-templates": ListTemplates(baseUrl, config); break;
                case "collect": Collect(baseUrl, config); break;
                case "problem": Problem(baseUrl, config); break;
                case "routing": Routing(baseUrl, config); break;
                case "generate-document": GenerateDocument(baseUrl, config); break;
                default: throw new ArgumentException($"unknown action {instruction.GetProperty("action")}");
            }

            Done(baseUrl, null);
            return 0;
        }
        catch (Exception e)
        {
            Done(baseUrl, $"{e.GetType().Name}: {e.Message}");
            Console.Error.WriteLine(e);
            return 1;
        }
    }

    // --- Actions ---

    private static void Ping(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        new SystemApi(http, apiBase).Ping(new PingRequest(
            name: "Conformance Driver",
            description: "Drives the .NET client through one conformance scenario",
            contact: "conformance@epistola.app"));
    }

    private static void ListTemplates(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var api = new TemplatesApi(http, apiBase);
        for (var i = 0; i < Int(config, "repeat", 1); i++)
        {
            api.ListTemplates(Str(config, "tenantId"), Str(config, "catalogId"));
        }
    }

    private static void Problem(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        try
        {
            new TemplatesApi(http, apiBase).ListTemplates(Str(config, "tenantId"), Str(config, "catalogId"));
            Report(baseUrl, new Dictionary<string, object> { ["problemTypeSlug"] = "<no exception was thrown>" });
        }
        catch (ProblemDetailException e)
        {
            Report(baseUrl, new Dictionary<string, object>
            {
                ["problemTypeSlug"] = e.TypeSlug ?? "<null>",
                ["problemStatus"] = (int)e.StatusCode,
                ["problemTitle"] = e.Title ?? "<null>",
                ["problemFieldErrors"] = string.Join(",", e.Errors.Select(error => $"{error.Field}:{error.Message}")),
            });
        }
    }

    private static void Collect(string baseUrl, JsonElement config)
    {
        var (http, _) = Client(baseUrl, config);
        var handled = new List<ResultCollector.GenerationResult>();
        var handledLock = new object();
        var failOnSequence = config.TryGetProperty("failHandlerOnSequence", out var fail) ? fail.GetInt64() : -1L;

        var collector = ResultCollector.Builder()
            .HttpClient(http)
            .TenantId(Str(config, "tenantId"))
            .BatchSize(Int(config, "batchSize"))
            .MinInterval(TimeSpan.FromMilliseconds(Int(config, "minIntervalMs")))
            .MaxInterval(TimeSpan.FromMilliseconds(Int(config, "maxIntervalMs")))
            .BackoffMultiplier(config.GetProperty("multiplier").GetDouble())
            .RegisterShutdownHook(false)
            .Handler(result =>
            {
                lock (handledLock) { handled.Add(result); }
                if (result.Sequence == failOnSequence) throw new InvalidOperationException("conformance: deliberate handler failure");
            })
            // Without this the loop swallows collection failures and simply backs off, which reaches
            // the harness as "the client chose not to poll" rather than as the cause.
            .ErrorHandler(e => Console.Error.WriteLine(e))
            .Build();

        var thread = new Thread(collector.Start) { Name = "conformance-collector", IsBackground = true };
        thread.Start();
        Thread.Sleep(Int(config, "runForMs"));
        collector.Stop();
        thread.Join(TimeSpan.FromSeconds(5));

        lock (handledLock)
        {
            Report(baseUrl, new Dictionary<string, object>
            {
                ["resultsHandled"] = handled.Count,
                ["statuses"] = string.Join(",", handled.Select(r => r.Status)),
                ["correlationIds"] = string.Join(",", handled.Select(r => r.CorrelationId ?? "")),
                ["handledSequences"] = string.Join(",", handled.Select(r => r.Sequence)),
                ["partitionTotal"] = collector.CurrentPartitionAssignment?.Total ?? -1,
            });
        }
    }

    /// <summary>
    /// A request body with something in it: required fields, two of the optional ones set, the rest
    /// left alone, and a free-form <c>data</c> object carrying every JSON type. What the server
    /// receives is the generator's serialization, which is the part no client hand-writes and no
    /// client's own tests inspect.
    /// </summary>
    private static void GenerateDocument(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        new GenerationApi(http, apiBase).GenerateDocument(
            Str(config, "tenantId"),
            new GenerateDocumentRequest(
                catalogId: Str(config, "catalogId"),
                templateId: Str(config, "templateId"),
                // Parsed with Newtonsoft, not System.Text.Json: the generated models serialize
                // through Newtonsoft, which does not know what to do with a JsonElement and emits
                // an empty object for it.
                data: Newtonsoft.Json.JsonConvert.DeserializeObject(config.GetProperty("data").GetRawText()),
                correlationId: Str(config, "correlationId"),
                routingKey: Str(config, "routingKey")));
    }

    /// <summary>
    /// One poll to learn the partition assignment from the _meta line, then the routing helpers.
    /// The values are reported rather than asserted here: the harness holds all four clients to the
    /// same answers, which is the only way four independent murmur3 implementations stay in step.
    /// </summary>
    private static void Routing(string baseUrl, JsonElement config)
    {
        var (http, _) = Client(baseUrl, config);
        var collector = ResultCollector.Builder()
            .HttpClient(http)
            .TenantId(Str(config, "tenantId"))
            .RegisterShutdownHook(false)
            .Handler(_ => { })
            .Build();

        collector.CollectOnce();

        var keys = config.GetProperty("keys").EnumerateArray().Select(k => k.GetString()!).ToList();
        Report(baseUrl, new Dictionary<string, object>
        {
            ["partitionTotal"] = collector.CurrentPartitionAssignment?.Total ?? -1,
            ["partitions"] = string.Join(",", keys.Select(k => $"{k}:{Show(collector.PartitionFor(k))}")),
            ["routed"] = string.Join(",", keys.Select(k => $"{k}={Show(collector.RoutingKeyToMe(k))}")),
            ["routedPartitions"] = string.Join(",", keys.Select(k => Show(collector.PartitionFor(collector.RoutingKeyToMe(k)!)))),
            ["mineFlags"] = string.Join(",", keys.Select(k => collector.IsMyPartition(k) ? "true" : "false")),
        });
    }

    /// <summary>Renders a null the way the other drivers' languages print theirs, so the harness
    /// compares one spelling rather than four.</summary>
    private static string Show(object? value) => value?.ToString() ?? "null";

    // --- Client assembly ---

    /// <summary>
    /// Builds the client the way the README tells consumers to. The API base path is part of the
    /// contract's servers entry, so the driver appends it rather than the harness serving the API
    /// at the root; the generated APIs take it separately from the HttpClient.
    /// </summary>
    private static (HttpClient Http, string ApiBase) Client(string baseUrl, JsonElement config)
    {
        var apiBase = $"{baseUrl}/api";

        var identity = ClientIdentity.Builder().NodeId(Str(config, "nodeId"));
        if (config.TryGetProperty("products", out var products))
        {
            foreach (var product in products.EnumerateArray())
            {
                identity.Product(product.GetProperty("name").GetString()!, product.GetProperty("version").GetString()!);
            }
        }

        var builder = new EpistolaHttpClientBuilder()
            .BaseUrl(apiBase)
            .Identity(identity.Build())
            .InstallProblemDetailHandler();

        switch (Str(config, "auth", "none"))
        {
            case "api-key":
                builder.ApiKey(Str(config, "apiKey"));
                break;
            case "jwt":
                builder.JwtSigner(JwtSigner.Builder()
                    .ConsumerId(Str(config, "consumerId"))
                    .PrivateKey(JwtSigner.ParsePrivateKeyPem(Str(config, "privateKeyPem")))
                    .TokenLifetime(TimeSpan.FromSeconds(Int(config, "tokenLifetimeSeconds")))
                    .Build());
                break;
        }

        return (builder.Build(), apiBase);
    }

    // --- Control plane ---

    private static JsonElement Get(string url)
    {
        var body = Control.GetStringAsync(url).GetAwaiter().GetResult();
        return JsonDocument.Parse(body).RootElement.Clone();
    }

    private static void Post(string url, string body)
    {
        Control.PostAsync(url, new StringContent(body, Encoding.UTF8, "application/json"))
            .GetAwaiter().GetResult();
    }

    private static void Report(string baseUrl, Dictionary<string, object> values) =>
        Post($"{baseUrl}/__conformance/report", JsonSerializer.Serialize(values));

    private static void Done(string baseUrl, string? error) =>
        Post($"{baseUrl}/__conformance/done",
            error == null ? "{}" : JsonSerializer.Serialize(new Dictionary<string, string> { ["error"] = error }));

    // --- Config helpers ---

    private static string Str(JsonElement config, string name, string? fallback = null) =>
        config.TryGetProperty(name, out var value) ? value.GetString()! : fallback
            ?? throw new ArgumentException($"scenario config has no {name}");

    private static int Int(JsonElement config, string name, int? fallback = null) =>
        config.TryGetProperty(name, out var value) ? value.GetInt32() : fallback
            ?? throw new ArgumentException($"scenario config has no {name}");
}
