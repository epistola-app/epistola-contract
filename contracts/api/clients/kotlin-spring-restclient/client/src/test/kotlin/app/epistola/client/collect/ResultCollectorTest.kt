package app.epistola.client.collect

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.mockk.every
import io.mockk.mockk
import org.springframework.http.HttpHeaders
import org.springframework.web.client.RestClient
import java.io.ByteArrayInputStream
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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

        // Backoff is "settled" after 4 polls: currentInterval = 50 * 3^4 = 4050ms,
        // well above the 200ms kickInterval, so kick() will not be a no-op.
        val backoffSettled = CountDownLatch(4)
        // Latch installed by the test thread immediately before kick(); the next
        // onPoll callback decrements it. Using AtomicReference so the listener
        // sees the latest assignment without needing synchronization.
        val nextPollLatch = AtomicReference<CountDownLatch?>(null)
        val nextPollTime = AtomicLong(Long.MIN_VALUE)
        val totalPolls = AtomicInteger(0)

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
            .metricsListener(object : ResultCollector.MetricsListener {
                override fun onPoll(count: Int, hasMore: Boolean, durationMs: Long, error: Exception?) {
                    totalPolls.incrementAndGet()
                    backoffSettled.countDown()
                    val latch = nextPollLatch.get()
                    if (latch != null && latch.count > 0) {
                        nextPollTime.compareAndSet(Long.MIN_VALUE, System.currentTimeMillis())
                        latch.countDown()
                    }
                }
                override fun onPartitionChange(
                    oldAssignment: ResultCollector.PartitionAssignment?,
                    newAssignment: ResultCollector.PartitionAssignment,
                ) = Unit
            })
            .build()

        val thread = Thread { collector.start() }
        thread.start()

        try {
            // Wait deterministically for backoff to accumulate instead of
            // sleeping a fixed duration.
            assertTrue(
                backoffSettled.await(10, TimeUnit.SECONDS),
                "Backoff didn't accumulate (${totalPolls.get()} polls in 10s)",
            )

            // Install the kick latch *before* calling kick(), so we can't miss
            // the wake-up poll. kickAt is captured immediately before kick() —
            // the next decrement comes from a poll whose listener runs after
            // kick(), so its timestamp is ≥ kickAt.
            val kickLatch = CountDownLatch(1)
            nextPollLatch.set(kickLatch)
            val kickAt = System.currentTimeMillis()
            collector.kick()

            // Generous timeout — kickInterval is 200ms but CI runners can stall.
            assertTrue(
                kickLatch.await(5, TimeUnit.SECONDS),
                "No poll recorded within 5s after kick (totalPolls=${totalPolls.get()})",
            )

            val firstPostKickPoll = nextPollTime.get()
            check(firstPostKickPoll != Long.MIN_VALUE) { "kick latch released without timestamp" }
            val latencyMs = firstPostKickPoll - kickAt
            // kickInterval (200ms) + generous slack for scheduling jitter and
            // poll execution on loaded CI runners.
            assertTrue(
                latencyMs in 0..1000,
                "Kick should produce a poll within ~1s; got ${latencyMs}ms",
            )
        } finally {
            collector.stop()
            thread.join(2000)
        }
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
