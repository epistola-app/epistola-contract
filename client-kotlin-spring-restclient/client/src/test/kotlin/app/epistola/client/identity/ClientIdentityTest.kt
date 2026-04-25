package app.epistola.client.identity

import io.mockk.every
import io.mockk.mockk
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpResponse
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClientIdentityTest {

    @Test
    fun `builder with no products produces only contract token`() {
        val identity = ClientIdentity.builder()
            .nodeId("test-pod")
            .build()

        assertTrue(identity.userAgent.startsWith("epistola-contract/"))
        assertEquals("test-pod", identity.nodeId)
    }

    @Test
    fun `builder with products appends them in order`() {
        val identity = ClientIdentity.builder()
            .nodeId("test-pod")
            .product("valtimo-epistola-plugin", "1.2.0")
            .product("gzac", "5.0.0")
            .build()

        val tokens = identity.userAgent.split(" ")
        assertEquals(3, tokens.size)
        assertTrue(tokens[0].startsWith("epistola-contract/"))
        assertEquals("valtimo-epistola-plugin/1.2.0", tokens[1])
        assertEquals("gzac/5.0.0", tokens[2])
    }

    @Test
    fun `builder defaults nodeId to hostname`() {
        val identity = ClientIdentity.builder().build()

        assertEquals(InetAddress.getLocalHost().hostName, identity.nodeId)
    }

    @Test
    fun `builder rejects blank product name`() {
        assertFailsWith<IllegalArgumentException> {
            ClientIdentity.builder().product("", "1.0.0")
        }
    }

    @Test
    fun `builder rejects blank product version`() {
        assertFailsWith<IllegalArgumentException> {
            ClientIdentity.builder().product("my-app", "")
        }
    }

    @Test
    fun `builder rejects product name with slash`() {
        assertFailsWith<IllegalArgumentException> {
            ClientIdentity.builder().product("my/app", "1.0.0")
        }
    }

    @Test
    fun `builder rejects product name with space`() {
        assertFailsWith<IllegalArgumentException> {
            ClientIdentity.builder().product("my app", "1.0.0")
        }
    }

    @Test
    fun `interceptor sets both headers on request`() {
        val identity = ClientIdentity.builder()
            .nodeId("pod-123")
            .product("test-app", "2.0.0")
            .build()

        val interceptor = identity.interceptor()
        val headers = HttpHeaders()
        val request = mockk<HttpRequest> {
            every { getHeaders() } returns headers
        }
        val execution = mockk<ClientHttpRequestExecution> {
            every { execute(any(), any()) } returns mockk<ClientHttpResponse>()
        }

        interceptor.intercept(request, ByteArray(0), execution)

        assertEquals(identity.userAgent, headers.getFirst(HttpHeaders.USER_AGENT))
        assertEquals("pod-123", headers.getFirst(ClientIdentity.HEADER_NODE_ID))
    }
}
