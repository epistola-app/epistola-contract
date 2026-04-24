package app.epistola.client.collect

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.mockk.every
import io.mockk.mockk
import org.springframework.http.HttpHeaders
import org.springframework.web.client.RestClient
import java.io.ByteArrayInputStream
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResultCollectorTest {

    private val objectMapper = ObjectMapper().apply { registerModule(JavaTimeModule()) }

    private fun ndjsonResponse(vararg lines: String) = ByteArrayInputStream(lines.joinToString("\n").toByteArray())

    private fun resultLine(
        requestId: String = "req-1",
        status: String = "COMPLETED",
        documentId: String? = "doc-1",
        correlationId: String? = "corr-1",
    ): String = objectMapper.writeValueAsString(
        mapOf(
            "requestId" to requestId,
            "status" to status,
            "documentId" to documentId,
            "correlationId" to correlationId,
            "templateId" to "invoice",
            "completedAt" to "2026-04-24T10:00:00Z",
        ),
    )

    @Suppress("ktlint:standard:function-signature")
    private fun metaLine(hasMore: Boolean, count: Int) =
        """{"_meta":true,"hasMore":$hasMore,"count":$count,"partitions":{"total":12,"mine":[0,1,2],"hash":"murmur3"}}"""

    @Test
    fun `parses NDJSON results correctly`() {
        val results = mutableListOf<ResultCollector.GenerationResult>()

        val restClient = mockRestClient(
            ndjsonResponse(
                resultLine(requestId = "r1", status = "COMPLETED", documentId = "d1"),
                resultLine(requestId = "r2", status = "FAILED", documentId = null),
                metaLine(hasMore = false, count = 2),
            ),
        )

        val collector = ResultCollector.builder()
            .restClient(restClient)
            .tenantId("acme-corp")
            .handler { results.add(it) }
            .objectMapper(objectMapper)
            .build()

        val result = collector.collectOnce()

        assertEquals(2, result.count)
        assertFalse(result.hasMore)
        assertEquals(2, results.size)
        assertEquals("r1", results[0].requestId)
        assertEquals("COMPLETED", results[0].status)
        assertEquals("d1", results[0].documentId)
        assertEquals("r2", results[1].requestId)
        assertEquals("FAILED", results[1].status)
    }

    @Test
    fun `handles empty response`() {
        val results = mutableListOf<ResultCollector.GenerationResult>()

        val restClient = mockRestClient(
            ndjsonResponse(metaLine(hasMore = false, count = 0)),
        )

        val collector = ResultCollector.builder()
            .restClient(restClient)
            .tenantId("acme-corp")
            .handler { results.add(it) }
            .objectMapper(objectMapper)
            .build()

        val result = collector.collectOnce()

        assertEquals(0, result.count)
        assertFalse(result.hasMore)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `hasMore true when more results available`() {
        val restClient = mockRestClient(
            ndjsonResponse(
                resultLine(),
                metaLine(hasMore = true, count = 1),
            ),
        )

        val collector = ResultCollector.builder()
            .restClient(restClient)
            .tenantId("acme-corp")
            .handler { }
            .objectMapper(objectMapper)
            .build()

        val result = collector.collectOnce()

        assertTrue(result.hasMore)
    }

    @Test
    fun `builder rejects invalid batch size`() {
        assertFailsWith<IllegalArgumentException> {
            ResultCollector.builder().batchSize(0)
        }
        assertFailsWith<IllegalArgumentException> {
            ResultCollector.builder().batchSize(10001)
        }
    }

    @Test
    fun `builder rejects zero min interval`() {
        assertFailsWith<IllegalArgumentException> {
            ResultCollector.builder().minInterval(Duration.ZERO)
        }
    }

    @Test
    fun `builder requires restClient`() {
        assertFailsWith<IllegalArgumentException> {
            ResultCollector.builder()
                .tenantId("acme-corp")
                .handler { }
                .build()
        }
    }

    @Test
    fun `builder requires tenantId`() {
        assertFailsWith<IllegalArgumentException> {
            ResultCollector.builder()
                .restClient(mockk())
                .handler { }
                .build()
        }
    }

    @Test
    fun `builder requires handler`() {
        assertFailsWith<IllegalArgumentException> {
            ResultCollector.builder()
                .restClient(mockk())
                .tenantId("acme-corp")
                .build()
        }
    }

    @Test
    fun `stop terminates poll loop`() {
        val restClient = mockRestClient(
            ndjsonResponse(metaLine(hasMore = false, count = 0)),
        )

        val collector = ResultCollector.builder()
            .restClient(restClient)
            .tenantId("acme-corp")
            .minInterval(Duration.ofMillis(50))
            .handler { }
            .objectMapper(objectMapper)
            .build()

        val thread = Thread { collector.start() }
        thread.start()
        Thread.sleep(200)
        collector.stop()
        thread.join(1000)

        assertFalse(thread.isAlive)
    }

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
