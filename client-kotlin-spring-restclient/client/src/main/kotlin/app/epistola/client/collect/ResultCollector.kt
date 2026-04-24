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
import java.util.zip.GZIPInputStream

/**
 * Collects generation results via the `/generation/collect` endpoint with
 * NDJSON streaming, compression, and adaptive polling.
 *
 * Results are processed one at a time — the response is never loaded into memory.
 * Results from your node are returned first; orphaned results from dead nodes follow.
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
    private val objectMapper: ObjectMapper,
) {
    private val running = AtomicBoolean(false)
    private var currentInterval: Long = minInterval.toMillis()
    private var acknowledge = true

    companion object {
        private val NDJSON = MediaType.parseMediaType("application/x-ndjson")
        private val EPISTOLA_JSON = MediaType.parseMediaType("application/vnd.epistola.v1+json")

        fun builder(): Builder = Builder()
    }

    /**
     * A completed or failed generation result.
     */
    data class GenerationResult(
        val requestId: String,
        val batchId: String?,
        val status: String,
        val documentId: String?,
        val correlationId: String?,
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

    /**
     * Starts the adaptive poll loop. Blocks the current thread until [stop] is called.
     *
     * Backoff strategy:
     * - Got results → reset to [minInterval]
     * - hasMore = true → poll immediately
     * - Empty response → exponential backoff up to [maxInterval]
     * - Error → backoff with jitter, redeliver previous batch
     */
    fun start() {
        running.set(true)
        currentInterval = minInterval.toMillis()

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
                acknowledge = false
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
    }

    /** Signals the poll loop to stop after the current collection completes. */
    fun stop() {
        running.set(false)
    }

    /**
     * Performs a single collection call. Streams the NDJSON response line by line,
     * calling the handler for each result. Returns metadata about the response.
     *
     * If the handler throws, `acknowledge` is set to false and the batch will be
     * redelivered on the next call.
     */
    fun collectOnce(): CollectResult {
        val requestBody = buildString {
            append("{\"acknowledge\":").append(acknowledge)
            append(",\"limit\":").append(batchSize)
            append("}")
        }

        val result = restClient.method(HttpMethod.POST)
            .uri("/tenants/{tenantId}/generation/collect", tenantId)
            .contentType(EPISTOLA_JSON)
            .accept(NDJSON)
            .header("Accept-Encoding", "gzip") // TODO: add lz4, zstd when decompression is implemented
            .body(requestBody)
            .exchange { _, response ->
                val stream = decompressIfNeeded(
                    response.body,
                    response.headers.getFirst("Content-Encoding"),
                )
                val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))

                var count = 0
                var hasMore = false
                var handlerFailed = false

                reader.useLines { lines ->
                    for (line in lines) {
                        if (line.isBlank()) continue
                        val node = objectMapper.readTree(line)

                        if (node.has("_meta") && node["_meta"].asBoolean()) {
                            hasMore = node["hasMore"]?.asBoolean() ?: false
                            break
                        }

                        if (!handlerFailed) {
                            try {
                                handler(parseResult(node))
                                count++
                            } catch (e: Exception) {
                                handlerFailed = true
                                throw e
                            }
                        }
                    }
                }

                acknowledge = !handlerFailed
                CollectResult(count = count, hasMore = hasMore)
            }

        return result ?: CollectResult(count = 0, hasMore = false)
    }

    private fun parseResult(node: com.fasterxml.jackson.databind.JsonNode) = GenerationResult(
        requestId = node["requestId"].asText(),
        batchId = node["batchId"]?.asText(),
        status = node["status"].asText(),
        documentId = node["documentId"]?.asText(),
        correlationId = node["correlationId"]?.asText(),
        templateId = node["templateId"]?.asText(),
        variantId = node["variantId"]?.asText(),
        versionId = node["versionId"]?.asInt(),
        filename = node["filename"]?.asText(),
        contentType = node["contentType"]?.asText(),
        sizeBytes = node["sizeBytes"]?.asLong(),
        error = node["error"]?.asText(),
        completedAt = node["completedAt"]?.asText(),
    )

    // TODO: Add lz4 (net.jpountz.lz4:lz4-java) and zstd (com.github.luben:zstd-jni)
    //       decompression support. Currently only gzip is handled; lz4/zstd responses
    //       will fall through to raw reading which will fail on compressed data.
    //       Until then, clients should set Accept-Encoding to "gzip" only.
    private fun decompressIfNeeded(input: InputStream, encoding: String?): InputStream = when (encoding) {
        "gzip" -> GZIPInputStream(input)
        else -> input
    }

    class Builder {
        private var restClient: RestClient? = null
        private var tenantId: String? = null
        private var batchSize: Int = 100
        private var minInterval: Duration = Duration.ofSeconds(1)
        private var maxInterval: Duration = Duration.ofSeconds(30)
        private var handler: ((GenerationResult) -> Unit)? = null
        private var errorHandler: ((Exception) -> Unit)? = null
        private var objectMapper: ObjectMapper? = null

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

        /** Custom ObjectMapper (default: Jackson with JavaTimeModule). */
        fun objectMapper(mapper: ObjectMapper) = apply { this.objectMapper = mapper }

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
                objectMapper = objectMapper ?: ObjectMapper().apply {
                    registerModule(JavaTimeModule())
                },
            )
    }
}
