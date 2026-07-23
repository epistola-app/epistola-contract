package app.epistola.client.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpRequest
import org.springframework.http.HttpStatus
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpResponse
import java.io.ByteArrayInputStream
import java.net.URI

class ApiKeyAuthTest {
    @Test
    fun `interceptor sets Authorization ApiKey header`() {
        val interceptor = ApiKeyAuth.of("epk_test").interceptor()
        val request = StubRequest()

        interceptor.intercept(request, ByteArray(0), ClientHttpRequestExecution { _, _ -> StubResponse() })

        assertEquals("ApiKey epk_test", request.requestHeaders.getFirst("Authorization"))
    }

    @Test
    fun `blank api key is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ApiKeyAuth.of(" ")
        }
    }

    private class StubRequest : HttpRequest {
        val requestHeaders = org.springframework.http.HttpHeaders()
        private val attributes = mutableMapOf<String, Any>()
        override fun getMethod() = org.springframework.http.HttpMethod.GET
        override fun getURI(): URI = URI.create("https://example.test")
        override fun getHeaders() = requestHeaders
        override fun getAttributes(): MutableMap<String, Any> = attributes
    }

    private class StubResponse : ClientHttpResponse {
        override fun getStatusCode() = HttpStatus.OK
        override fun getStatusText() = "OK"
        override fun close() = Unit
        override fun getBody() = ByteArrayInputStream(ByteArray(0))
        override fun getHeaders() = org.springframework.http.HttpHeaders()
    }
}
