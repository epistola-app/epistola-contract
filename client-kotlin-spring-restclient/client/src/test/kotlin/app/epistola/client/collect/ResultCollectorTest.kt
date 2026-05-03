package app.epistola.client.collect

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.mockk.every
import io.mockk.mockk
import org.springframework.http.HttpHeaders
import org.springframework.web.client.RestClient
import java.io.ByteArrayInputStream
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResultCollectorTest {

    private val objectMapper = ObjectMapper().apply { registerModule(JavaTimeModule()) }

    private fun ndjsonResponse(vararg lines: String) = ByteArrayInputStream(lines.joinToString("\n").toByteArray())

    private fun resultLine(
        sequence: Long = 100,
        requestId: String = "req-1",
        status: String = "COMPLETED",
        documentId: String? = "doc-1",
        correlationId: String? = "corr-1",
    ): String = objectMapper.writeValueAsString(
        mapOf(
            "sequence" to sequence,
            "requestId" to requestId,
            "status" to status,
            "documentId" to documentId,
            "correlationId" to correlationId,
            "templateId" to "invoice",
            "completedAt" to "2026-04-24T10:00:00Z",
        ),
    )

    @Suppress("ktlint:standard:function-signature")
    private fun metaLine(hasMore: Boolean, count: Int, lastSequence: Long = 0) =
        """{"_meta":true,"hasMore":$hasMore,"count":$count,"lastSequence":$lastSequence,"partitions":{"total":12,"mine":[0,1,2,3],"hash":"murmur3"}}"""

    @Test
    fun `parses NDJSON results correctly`() {
        val results = mutableListOf<ResultCollector.GenerationResult>()

        val restClient = mockRestClient(
            ndjsonResponse(
                resultLine(sequence = 100, requestId = "r1", status = "COMPLETED", documentId = "d1"),
                resultLine(sequence = 101, requestId = "r2", status = "FAILED", documentId = null),
                metaLine(hasMore = false, count = 2, lastSequence = 101),
            ),
        )

        val collector = buildCollector(restClient) { results.add(it) }
        val result = collector.collectOnce()

        assertEquals(2, result.count)
        assertFalse(result.hasMore)
        assertEquals(100, results[0].sequence)
        assertEquals("r1", results[0].requestId)
        assertEquals("COMPLETED", results[0].status)
        assertEquals(101, results[1].sequence)
        assertEquals("r2", results[1].requestId)
        assertEquals("FAILED", results[1].status)
    }

    @Test
    fun `handles empty response`() {
        val results = mutableListOf<ResultCollector.GenerationResult>()
        val restClient = mockRestClient(ndjsonResponse(metaLine(hasMore = false, count = 0)))
        val collector = buildCollector(restClient) { results.add(it) }

        val result = collector.collectOnce()

        assertEquals(0, result.count)
        assertFalse(result.hasMore)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `hasMore true when more results available`() {
        val restClient = mockRestClient(
            ndjsonResponse(resultLine(), metaLine(hasMore = true, count = 1, lastSequence = 100)),
        )
        val collector = buildCollector(restClient) { }

        assertTrue(collector.collectOnce().hasMore)
    }

    @Test
    fun `updates partition assignment from meta`() {
        val restClient = mockRestClient(
            ndjsonResponse(metaLine(hasMore = false, count = 0)),
        )
        val collector = buildCollector(restClient) { }

        assertNull(collector.partitionAssignment)
        collector.collectOnce()
        assertNotNull(collector.partitionAssignment)
        assertEquals(12, collector.partitionAssignment!!.total)
        assertEquals(listOf(0, 1, 2, 3), collector.partitionAssignment!!.mine)
    }

    @Test
    fun `partitionFor computes correct partition`() {
        val restClient = mockRestClient(
            ndjsonResponse(metaLine(hasMore = false, count = 0)),
        )
        val collector = buildCollector(restClient) { }
        collector.collectOnce() // load partitions

        val partition = collector.partitionFor("test-key")
        assertNotNull(partition)
        assertTrue(partition in 0 until 12)
    }

    @Test
    fun `partitionFor returns null before first poll`() {
        val collector = buildCollector(mockk()) { }
        assertNull(collector.partitionFor("test-key"))
    }

    @Test
    fun `isMyPartition checks against assigned partitions`() {
        val restClient = mockRestClient(
            ndjsonResponse(metaLine(hasMore = false, count = 0)),
        )
        val collector = buildCollector(restClient) { }
        collector.collectOnce()

        // At least one key should route to our partitions [0,1,2,3] out of 12
        val foundMine = (0..100).any { collector.isMyPartition("key-$it") }
        assertTrue(foundMine, "Expected at least one key to route to our partitions")
    }

    @Test
    fun `routingKeyToMe returns original key if already mine`() {
        val restClient = mockRestClient(
            ndjsonResponse(metaLine(hasMore = false, count = 0)),
        )
        val collector = buildCollector(restClient) { }
        collector.collectOnce()

        // Find a key that already routes to our partitions
        val myKey = (0..1000).map { "key-$it" }.first { collector.isMyPartition(it) }
        assertEquals(myKey, collector.routingKeyToMe(myKey))
    }

    @Test
    fun `metrics listener receives poll events`() {
        var pollCount = 0
        var lastCount = -1
        val listener = object : ResultCollector.MetricsListener {
            override fun onPoll(count: Int, hasMore: Boolean, durationMs: Long, error: Exception?) {
                pollCount++
                lastCount = count
            }

            override fun onPartitionChange(
                oldAssignment: ResultCollector.PartitionAssignment?,
                newAssignment: ResultCollector.PartitionAssignment,
            ) {}
        }

        val restClient = mockRestClient(
            ndjsonResponse(
                resultLine(sequence = 100),
                metaLine(hasMore = false, count = 1, lastSequence = 100),
            ),
        )
        val collector = buildCollector(restClient, metricsListener = listener) { }
        collector.collectOnce()

        assertEquals(1, pollCount)
        assertEquals(1, lastCount)
    }

    @Test
    fun `stop terminates poll loop`() {
        val restClient = mockRestClient(ndjsonResponse(metaLine(hasMore = false, count = 0)))
        val collector = buildCollector(restClient, registerShutdownHook = false) { }

        val thread = Thread { collector.start() }
        thread.start()
        Thread.sleep(200)
        collector.stop()
        thread.join(1000)

        assertFalse(thread.isAlive)
    }

    @Test
    fun `kick wakes a backed-off collector within kickInterval`() {
        // Empty responses every poll → currentInterval will back off all the way
        // to maxInterval after the first couple of polls. With multiplier=3.0 and
        // min=50ms, max=10s, the backoff sequence is 50ms → 150ms → 450ms → … →
        // 10s, reached within ~5 polls.
        val restClient = mockRestClient(ndjsonResponse(metaLine(hasMore = false, count = 0)))
        val collector = ResultCollector.builder()
            .restClient(restClient)
            .tenantId("acme-corp")
            .minInterval(Duration.ofMillis(50))
            .maxInterval(Duration.ofSeconds(10))
            .kickInterval(Duration.ofMillis(200))
            .backoffMultiplier(3.0)
            .objectMapper(objectMapper)
            .registerShutdownHook(false)
            .handler { }
            .build()

        // Track how often collectOnce ran via a counter on the metrics listener
        // would be cleaner, but the simplest is to count the actual mock-server
        // exchanges. We can use the metricsListener for that.
        val pollCount = AtomicInteger(0)
        val pollTimes = mutableListOf<Long>()
        val collectorWithMetrics = ResultCollector.builder()
            .restClient(restClient)
            .tenantId("acme-corp")
            .minInterval(Duration.ofMillis(50))
            .maxInterval(Duration.ofSeconds(10))
            .kickInterval(Duration.ofMillis(200))
            .backoffMultiplier(3.0)
            .objectMapper(objectMapper)
            .registerShutdownHook(false)
            .handler { }
            .metricsListener(object : ResultCollector.MetricsListener {
                override fun onPoll(count: Int, hasMore: Boolean, durationMs: Long, error: Exception?) {
                    pollTimes.add(System.currentTimeMillis())
                    pollCount.incrementAndGet()
                }
                override fun onPartitionChange(
                    oldAssignment: ResultCollector.PartitionAssignment?,
                    newAssignment: ResultCollector.PartitionAssignment,
                ) = Unit
            })
            .build()

        val thread = Thread { collectorWithMetrics.start() }
        thread.start()

        // Let backoff accumulate. After ~700ms we've done at least 3 polls
        // (50ms + 150ms + 450ms = 650ms cumulative) and currentInterval is
        // somewhere in the seconds range — well above the 200ms kickInterval.
        Thread.sleep(800)
        val pollsBeforeKick = pollCount.get()
        val kickAt = System.currentTimeMillis()

        collectorWithMetrics.kick()

        // Wait long enough for kickInterval + slack to elapse and the next
        // poll to register.
        Thread.sleep(500)
        collectorWithMetrics.stop()
        thread.join(2000)

        val pollsAfterKick = pollCount.get()
        assertTrue(
            pollsAfterKick > pollsBeforeKick,
            "Expected at least one poll after kick (had $pollsBeforeKick, now $pollsAfterKick)",
        )
        // Find the first poll that happened after the kick.
        val firstPostKickPoll = pollTimes.firstOrNull { it > kickAt }
        assertTrue(firstPostKickPoll != null, "No poll recorded after kick")
        val latencyMs = firstPostKickPoll - kickAt
        // 200ms kickInterval + 200ms slack for scheduling jitter — generous.
        assertTrue(
            latencyMs <= 400,
            "Kick should produce a poll within ~400ms (kickInterval + slack); got ${latencyMs}ms",
        )

        // Suppress unused-variable warning on the first collector (kept to make
        // the original parameter shape obvious for readers comparing this test
        // with the sibling `builder rejects` ones). It's intentionally inert.
        @Suppress("UNUSED_EXPRESSION")
        collector
    }

    @Test
    fun `kick is a no-op when already polling fast`() {
        // currentInterval starts at minInterval = 50ms. kickInterval = 200ms.
        // Since 50 <= 200, kick() should be a no-op and the next poll
        // fires when minInterval expires, not when kick was called.
        val restClient = mockRestClient(ndjsonResponse(metaLine(hasMore = false, count = 0)))
        val pollCount = AtomicInteger(0)
        val collector = ResultCollector.builder()
            .restClient(restClient)
            .tenantId("acme-corp")
            .minInterval(Duration.ofMillis(50))
            .maxInterval(Duration.ofSeconds(10))
            .kickInterval(Duration.ofMillis(200))
            .objectMapper(objectMapper)
            .registerShutdownHook(false)
            .handler { }
            .metricsListener(object : ResultCollector.MetricsListener {
                override fun onPoll(count: Int, hasMore: Boolean, durationMs: Long, error: Exception?) {
                    pollCount.incrementAndGet()
                }
                override fun onPartitionChange(
                    oldAssignment: ResultCollector.PartitionAssignment?,
                    newAssignment: ResultCollector.PartitionAssignment,
                ) = Unit
            })
            .build()

        val thread = Thread { collector.start() }
        thread.start()
        Thread.sleep(20) // first poll fires immediately, currentInterval becomes 50ms
        // Kick repeatedly in fast-mode — should not change anything because
        // 50ms < 200ms kickInterval.
        repeat(10) { collector.kick() }
        Thread.sleep(80)
        val countAfter = pollCount.get()
        collector.stop()
        thread.join(1000)

        // Without kicks: should poll ~2-3 times in 100ms at 50ms minInterval.
        // With our broken kicks: also ~2-3. We assert it's not "way more".
        assertTrue(
            countAfter <= 5,
            "Kick in fast-mode should be a no-op; got $countAfter polls in ~100ms (expected ~2-3)",
        )
    }

    @Test
    fun `builder rejects invalid kick interval`() {
        assertFailsWith<IllegalArgumentException> { ResultCollector.builder().kickInterval(Duration.ZERO) }
    }

    @Test
    fun `builder rejects invalid backoff multiplier`() {
        assertFailsWith<IllegalArgumentException> { ResultCollector.builder().backoffMultiplier(1.0) }
        assertFailsWith<IllegalArgumentException> { ResultCollector.builder().backoffMultiplier(0.5) }
    }

    @Test
    fun `builder rejects invalid batch size`() {
        assertFailsWith<IllegalArgumentException> { ResultCollector.builder().batchSize(0) }
        assertFailsWith<IllegalArgumentException> { ResultCollector.builder().batchSize(10001) }
    }

    @Test
    fun `builder rejects zero min interval`() {
        assertFailsWith<IllegalArgumentException> { ResultCollector.builder().minInterval(Duration.ZERO) }
    }

    @Test
    fun `builder requires restClient`() {
        assertFailsWith<IllegalArgumentException> {
            ResultCollector.builder().tenantId("t").handler { }.build()
        }
    }

    @Test
    fun `builder requires tenantId`() {
        assertFailsWith<IllegalArgumentException> {
            ResultCollector.builder().restClient(mockk()).handler { }.build()
        }
    }

    @Test
    fun `builder requires handler`() {
        assertFailsWith<IllegalArgumentException> {
            ResultCollector.builder().restClient(mockk()).tenantId("t").build()
        }
    }

    @Test
    fun `murmur3 hash is deterministic`() {
        val hash1 = murmur3x86_32("test-key".toByteArray(), 0)
        val hash2 = murmur3x86_32("test-key".toByteArray(), 0)
        assertEquals(hash1, hash2)
    }

    @Test
    fun `murmur3 different keys produce different hashes`() {
        val hash1 = murmur3x86_32("key-a".toByteArray(), 0)
        val hash2 = murmur3x86_32("key-b".toByteArray(), 0)
        assertTrue(hash1 != hash2, "Different keys should produce different hashes")
    }

    // --- Helpers ---

    @Suppress("ktlint:standard:function-signature")
    private fun buildCollector(
        restClient: RestClient,
        metricsListener: ResultCollector.MetricsListener? = null,
        registerShutdownHook: Boolean = false,
        handler: (ResultCollector.GenerationResult) -> Unit,
    ) = ResultCollector.builder()
        .restClient(restClient)
        .tenantId("acme-corp")
        .minInterval(Duration.ofMillis(50))
        .handler(handler)
        .objectMapper(objectMapper)
        .registerShutdownHook(registerShutdownHook)
        .apply { if (metricsListener != null) metricsListener(metricsListener) }
        .build()

    @Suppress("UNCHECKED_CAST")
    private fun mockRestClient(responseBody: ByteArrayInputStream): RestClient {
        val convertibleResponse = mockk<RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse> {
            every { body } returns responseBody
            every { headers } returns HttpHeaders()
        }

        val requestBodyUriSpec = mockk<RestClient.RequestBodyUriSpec>()
        val requestBodySpec = mockk<RestClient.RequestBodySpec>()

        every { requestBodyUriSpec.uri(any<String>(), any<String>()) } returns requestBodySpec
        every { requestBodySpec.contentType(any()) } returns requestBodySpec
        every { requestBodySpec.accept(any()) } returns requestBodySpec
        every { requestBodySpec.header(any(), any<String>()) } returns requestBodySpec
        every { requestBodySpec.body(any<String>()) } returns requestBodySpec
        every { requestBodySpec.exchange<ResultCollector.CollectResult>(any()) } answers {
            val handler = firstArg<RestClient.RequestHeadersSpec.ExchangeFunction<ResultCollector.CollectResult>>()
            handler.exchange(mockk(), convertibleResponse)
        }

        val restClient = mockk<RestClient>()
        every { restClient.method(any()) } returns requestBodyUriSpec

        return restClient
    }
}
