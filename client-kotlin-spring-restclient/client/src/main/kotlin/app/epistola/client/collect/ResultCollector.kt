package app.epistola.client.collect

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.GZIPInputStream
import kotlin.concurrent.withLock

/**
 * Collects generation results via the `/generation/collect` endpoint with
 * NDJSON streaming, compression, and adaptive polling.
 *
 * Results are processed one at a time — the response is never loaded into memory.
 * Results from your node are returned first; orphaned results from dead nodes follow.
 *
 * Features:
 * - NDJSON streaming (constant memory)
 * - Compression: gzip built-in, lz4/zstd auto-detected if libraries on classpath
 * - Adaptive polling (immediate on hasMore, exponential backoff when idle)
 * - Sequence-based acknowledgment (safe on restart, partial ack)
 * - Partition-aware routing key helpers
 * - Metrics via [MetricsListener]
 * - Thread-safe [collectOnce] for custom scheduling
 * - Shutdown hook for graceful stop
 *
 * Example:
 * ```
 * val collector = ResultCollector.builder()
 *     .restClient(restClient)
 *     .tenantId("acme-corp")
 *     .handler { result ->
 *         when (result.status) {
 *             "COMPLETED" -> downloadAndProcess(result.documentId, result.correlationId)
 *             "FAILED" -> logFailure(result.correlationId, result.error)
 *         }
 *     }
 *     .build()
 *
 * collector.start()  // blocks, runs adaptive poll loop
 * ```
 */
class ResultCollector private constructor(
    private val restClient: RestClient,
    private val tenantId: String,
    private val batchSize: Int,
    private val minInterval: Duration,
    private val maxInterval: Duration,
    private val handler: (GenerationResult) -> Unit,
    private val errorHandler: ((Exception) -> Unit)?,
    private val metricsListener: MetricsListener?,
    private val objectMapper: ObjectMapper,
    private val registerShutdownHook: Boolean,
) {
    private val running = AtomicBoolean(false)
    private var currentInterval: Long = minInterval.toMillis()
    private var lastAcknowledgedSequence: Long? = null
    private val pollLock = ReentrantLock()
    private var shutdownHook: Thread? = null

    /** Current partition assignment, updated on each poll from the _meta line. */
    @Volatile
    var partitionAssignment: PartitionAssignment? = null
        private set

    companion object {
        private val NDJSON = MediaType.parseMediaType("application/vnd.epistola.v1+ndjson")
        private val EPISTOLA_JSON = MediaType.parseMediaType("application/vnd.epistola.v1+json")

        // Optional decompressors — loaded via reflection to avoid hard dependencies
        private val lz4Decompressor: ((InputStream) -> InputStream)? = tryLoadLz4()
        private val zstdDecompressor: ((InputStream) -> InputStream)? = tryLoadZstd()

        @Suppress("ktlint:standard:function-signature")
        private fun tryLoadLz4(): ((InputStream) -> InputStream)? =
            try {
                val ctor = Class.forName("net.jpountz.lz4.LZ4FrameInputStream")
                    .getConstructor(InputStream::class.java)
                val fn: (InputStream) -> InputStream = { input -> ctor.newInstance(input) as InputStream }
                fn
            } catch (_: Exception) {
                null
            }

        @Suppress("ktlint:standard:function-signature")
        private fun tryLoadZstd(): ((InputStream) -> InputStream)? =
            try {
                val ctor = Class.forName("com.github.luben.zstd.ZstdInputStream")
                    .getConstructor(InputStream::class.java)
                val fn: (InputStream) -> InputStream = { input -> ctor.newInstance(input) as InputStream }
                fn
            } catch (_: Exception) {
                null
            }

        fun builder(): Builder = Builder()
    }

    /** A completed or failed generation result. */
    data class GenerationResult(
        val sequence: Long,
        val requestId: String,
        val batchId: String?,
        val status: String,
        val documentId: String?,
        val correlationId: String?,
        val routingKey: String?,
        val templateId: String?,
        val variantId: String?,
        val versionId: Int?,
        val filename: String?,
        val contentType: String?,
        val sizeBytes: Long?,
        val error: String?,
        val completedAt: String?,
    )

    data class CollectResult(
        val count: Int,
        val hasMore: Boolean,
    )

    /** Partition assignment info from the server. */
    data class PartitionAssignment(
        val total: Int,
        val mine: List<Int>,
        val hash: String,
    )

    /** Callback interface for observability. */
    interface MetricsListener {
        /** Called after each poll completes. */
        fun onPoll(count: Int, hasMore: Boolean, durationMs: Long, error: Exception? = null)

        /** Called when partition assignment changes. */
        fun onPartitionChange(oldAssignment: PartitionAssignment?, newAssignment: PartitionAssignment)
    }

    // --- Partition routing helpers ---

    /**
     * Compute the partition number for a given routing key using the same
     * hash algorithm as the server (murmur3 x86 32-bit, seed 0).
     *
     * Returns null if the partition assignment is not yet known (call after first poll).
     */
    fun partitionFor(routingKey: String): Int? {
        val assignment = partitionAssignment ?: return null
        val hash = murmur3x86_32(routingKey.toByteArray(Charsets.UTF_8), 0)
        return (hash and 0x7FFFFFFF) % assignment.total
    }

    /**
     * Check if a routing key would land on one of this node's partitions.
     */
    fun isMyPartition(routingKey: String): Boolean {
        val partition = partitionFor(routingKey) ?: return false
        return partition in (partitionAssignment?.mine ?: emptyList())
    }

    /**
     * Find a routing key prefix that targets one of this node's partitions.
     * Appends a partition number prefix to the given key to ensure it routes to this node.
     *
     * Returns the original key if it already routes here, or a prefixed key if not.
     * Returns null if partition assignment is not yet known.
     */
    fun routingKeyToMe(key: String): String? {
        val assignment = partitionAssignment ?: return null
        if (isMyPartition(key)) return key
        for (p in assignment.mine) {
            val candidate = "$p:$key"
            if (isMyPartition(candidate)) return candidate
        }
        return "${assignment.mine.first()}:$key"
    }

    // --- Poll loop ---

    /**
     * Starts the adaptive poll loop. Blocks the current thread until [stop] is called.
     */
    fun start() {
        running.set(true)
        currentInterval = minInterval.toMillis()

        if (registerShutdownHook) {
            shutdownHook = Thread({ stop() }, "ResultCollector-shutdown").also {
                Runtime.getRuntime().addShutdownHook(it)
            }
        }

        try {
            while (running.get()) {
                try {
                    val result = collectOnce()
                    if (!running.get()) break

                    currentInterval = when {
                        result.hasMore -> 0
                        result.count > 0 -> minInterval.toMillis()
                        else -> (currentInterval * 2).coerceAtMost(maxInterval.toMillis())
                    }

                    if (currentInterval > 0) {
                        Thread.sleep(currentInterval)
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    errorHandler?.invoke(e)
                    val jitter = ThreadLocalRandom.current().nextLong(currentInterval / 2 + 1)
                    currentInterval = (currentInterval * 2).coerceAtMost(maxInterval.toMillis())
                    try {
                        Thread.sleep(currentInterval + jitter)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        } finally {
            removeShutdownHook()
        }
    }

    /** Signals the poll loop to stop after the current collection completes. */
    fun stop() {
        running.set(false)
    }

    /**
     * Performs a single collection call. Thread-safe — concurrent calls are serialized
     * via a lock to prevent duplicate delivery.
     *
     * Streams the NDJSON response line by line, calling the handler for each result.
     * If the handler throws, the sequence is not advanced and the batch will be
     * redelivered on the next call.
     */
    fun collectOnce(): CollectResult = pollLock.withLock {
        val startTime = System.currentTimeMillis()
        var error: Exception? = null

        try {
            val requestBody = buildString {
                append("{")
                if (lastAcknowledgedSequence != null) {
                    append("\"acknowledgeUpTo\":").append(lastAcknowledgedSequence)
                    append(",")
                }
                append("\"limit\":").append(batchSize)
                append("}")
            }

            val result = restClient.method(HttpMethod.POST)
                .uri("/tenants/{tenantId}/generation/collect", tenantId)
                .contentType(EPISTOLA_JSON)
                .accept(NDJSON)
                .header("Accept-Encoding", supportedEncodings())
                .body(requestBody)
                .exchange { _, response ->
                    val stream = decompressIfNeeded(
                        response.body,
                        response.headers.getFirst("Content-Encoding"),
                    )
                    val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))

                    var count = 0
                    var hasMore = false
                    var lastSequenceInBatch: Long? = null

                    reader.useLines { lines ->
                        for (line in lines) {
                            if (line.isBlank()) continue
                            val node = objectMapper.readTree(line)

                            if (node.has("_meta") && node["_meta"].asBoolean()) {
                                hasMore = node["hasMore"]?.asBoolean() ?: false
                                updatePartitionAssignment(node)
                                break
                            }

                            val parsed = parseResult(node)
                            handler(parsed)
                            lastSequenceInBatch = parsed.sequence
                            count++
                        }
                    }

                    if (lastSequenceInBatch != null) {
                        lastAcknowledgedSequence = lastSequenceInBatch
                    }

                    val collectResult = CollectResult(count = count, hasMore = hasMore)
                    metricsListener?.onPoll(count, hasMore, System.currentTimeMillis() - startTime)
                    collectResult
                }

            return result ?: CollectResult(count = 0, hasMore = false)
        } catch (e: Exception) {
            error = e
            metricsListener?.onPoll(0, false, System.currentTimeMillis() - startTime, e)
            throw e
        }
    }

    private fun updatePartitionAssignment(metaNode: com.fasterxml.jackson.databind.JsonNode) {
        val partitionsNode = metaNode["partitions"] ?: return
        val newAssignment = PartitionAssignment(
            total = partitionsNode["total"]?.asInt() ?: return,
            mine = partitionsNode["mine"]?.map { it.asInt() } ?: return,
            hash = partitionsNode["hash"]?.asText() ?: "murmur3",
        )
        val old = partitionAssignment
        if (old != newAssignment) {
            partitionAssignment = newAssignment
            metricsListener?.onPartitionChange(old, newAssignment)
        }
    }

    private fun parseResult(node: com.fasterxml.jackson.databind.JsonNode) = GenerationResult(
        sequence = node["sequence"].asLong(),
        requestId = node["requestId"].asText(),
        batchId = node["batchId"]?.asText(),
        status = node["status"].asText(),
        documentId = node["documentId"]?.asText(),
        correlationId = node["correlationId"]?.asText(),
        routingKey = node["routingKey"]?.asText(),
        templateId = node["templateId"]?.asText(),
        variantId = node["variantId"]?.asText(),
        versionId = node["versionId"]?.asInt(),
        filename = node["filename"]?.asText(),
        contentType = node["contentType"]?.asText(),
        sizeBytes = node["sizeBytes"]?.asLong(),
        error = node["error"]?.asText(),
        completedAt = node["completedAt"]?.asText(),
    )

    private fun supportedEncodings(): String = buildList {
        if (lz4Decompressor != null) add("lz4")
        if (zstdDecompressor != null) add("zstd")
        add("gzip")
    }.joinToString(", ")

    private fun decompressIfNeeded(input: InputStream, encoding: String?): InputStream = when (encoding) {
        "gzip" -> GZIPInputStream(input)
        "lz4" -> lz4Decompressor?.invoke(input)
            ?: throw IllegalStateException("Server sent lz4 but net.jpountz.lz4:lz4-java is not on classpath")
        "zstd" -> zstdDecompressor?.invoke(input)
            ?: throw IllegalStateException("Server sent zstd but com.github.luben:zstd-jni is not on classpath")
        else -> input
    }

    private fun removeShutdownHook() {
        shutdownHook?.let {
            try {
                Runtime.getRuntime().removeShutdownHook(it)
            } catch (_: IllegalStateException) {
                // JVM is already shutting down
            }
        }
        shutdownHook = null
    }

    class Builder {
        private var restClient: RestClient? = null
        private var tenantId: String? = null
        private var batchSize: Int = 100
        private var minInterval: Duration = Duration.ofSeconds(1)
        private var maxInterval: Duration = Duration.ofSeconds(30)
        private var handler: ((GenerationResult) -> Unit)? = null
        private var errorHandler: ((Exception) -> Unit)? = null
        private var metricsListener: MetricsListener? = null
        private var objectMapper: ObjectMapper? = null
        private var registerShutdownHook: Boolean = true

        fun restClient(client: RestClient) = apply { this.restClient = client }
        fun tenantId(tenantId: String) = apply { this.tenantId = tenantId }

        /** Maximum results per collection (default: 100). */
        fun batchSize(size: Int) = apply {
            require(size in 1..10000) { "batchSize must be between 1 and 10000" }
            this.batchSize = size
        }

        /** Minimum poll interval when results are flowing (default: 1s). */
        fun minInterval(interval: Duration) = apply {
            require(!interval.isNegative && !interval.isZero) { "minInterval must be positive" }
            this.minInterval = interval
        }

        /** Maximum poll interval when idle (default: 30s). */
        fun maxInterval(interval: Duration) = apply {
            require(!interval.isNegative && !interval.isZero) { "maxInterval must be positive" }
            this.maxInterval = interval
        }

        /** Handler called for each result as it streams in. */
        fun handler(handler: (GenerationResult) -> Unit) = apply { this.handler = handler }

        /** Optional error handler for collection failures. */
        fun errorHandler(handler: (Exception) -> Unit) = apply { this.errorHandler = handler }

        /** Optional metrics listener for observability. */
        fun metricsListener(listener: MetricsListener) = apply { this.metricsListener = listener }

        /** Custom ObjectMapper (default: Jackson with JavaTimeModule). */
        fun objectMapper(mapper: ObjectMapper) = apply { this.objectMapper = mapper }

        /** Register a JVM shutdown hook to stop polling gracefully (default: true). */
        fun registerShutdownHook(register: Boolean) = apply { this.registerShutdownHook = register }

        @Suppress("ktlint:standard:function-signature")
        fun build(): ResultCollector =
            ResultCollector(
                restClient = requireNotNull(restClient) { "restClient is required" },
                tenantId = requireNotNull(tenantId) { "tenantId is required" },
                batchSize = batchSize,
                minInterval = minInterval,
                maxInterval = maxInterval,
                handler = requireNotNull(handler) { "handler is required" },
                errorHandler = errorHandler,
                metricsListener = metricsListener,
                objectMapper = objectMapper ?: ObjectMapper().apply {
                    registerModule(JavaTimeModule())
                },
                registerShutdownHook = registerShutdownHook,
            )
    }
}

/**
 * MurmurHash3 x86 32-bit with configurable seed.
 * Matches the server-side implementation (Guava `Hashing.murmur3_32_fixed(seed)`).
 */
@Suppress("ktlint:standard:function-naming")
internal fun murmur3x86_32(data: ByteArray, seed: Int): Int {
    val c1 = 0xcc9e2d51.toInt()
    val c2 = 0x1b873593
    var h1 = seed
    val len = data.size
    val nblocks = len / 4

    for (i in 0 until nblocks) {
        val idx = i * 4
        var k1 = (data[idx].toInt() and 0xFF) or
            ((data[idx + 1].toInt() and 0xFF) shl 8) or
            ((data[idx + 2].toInt() and 0xFF) shl 16) or
            ((data[idx + 3].toInt() and 0xFF) shl 24)

        k1 *= c1
        k1 = Integer.rotateLeft(k1, 15)
        k1 *= c2
        h1 = h1 xor k1
        h1 = Integer.rotateLeft(h1, 13)
        h1 = h1 * 5 + 0xe6546b64.toInt()
    }

    val tail = nblocks * 4
    var k1 = 0
    @Suppress("ktlint:standard:statement-wrapping")
    when (len and 3) {
        3 -> {
            k1 = k1 xor ((data[tail + 2].toInt() and 0xFF) shl 16)
            k1 = k1 xor ((data[tail + 1].toInt() and 0xFF) shl 8)
            k1 = k1 xor (data[tail].toInt() and 0xFF)
            k1 *= c1
            k1 = Integer.rotateLeft(k1, 15)
            k1 *= c2
            h1 = h1 xor k1
        }
        2 -> {
            k1 = k1 xor ((data[tail + 1].toInt() and 0xFF) shl 8)
            k1 = k1 xor (data[tail].toInt() and 0xFF)
            k1 *= c1
            k1 = Integer.rotateLeft(k1, 15)
            k1 *= c2
            h1 = h1 xor k1
        }
        1 -> {
            k1 = k1 xor (data[tail].toInt() and 0xFF)
            k1 *= c1
            k1 = Integer.rotateLeft(k1, 15)
            k1 *= c2
            h1 = h1 xor k1
        }
    }

    h1 = h1 xor len
    h1 = h1 xor (h1 ushr 16)
    h1 *= 0x85ebca6b.toInt()
    h1 = h1 xor (h1 ushr 13)
    h1 *= 0xc2b2ae35.toInt()
    h1 = h1 xor (h1 ushr 16)

    return h1
}
