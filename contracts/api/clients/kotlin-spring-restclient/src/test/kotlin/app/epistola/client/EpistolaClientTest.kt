// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client

import app.epistola.client.api.ConsumersApi
import app.epistola.client.api.SystemApi
import app.epistola.client.api.TenantsApi
import app.epistola.client.auth.JwtSigner
import app.epistola.client.error.ProblemDetailException
import app.epistola.client.identity.ClientIdentity
import app.epistola.client.model.UpdateConsumerRequest
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClientResponseException
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.time.Duration

/**
 * Proves the assembly, not the pieces — [ClientIdentity], [app.epistola.client.auth.ApiKeyAuth] and
 * [JwtSigner] each have their own unit tests already. What only this test can catch is a wiring
 * mistake in [EpistolaClient.Builder.build] itself: an interceptor left out, the two opt-in pieces
 * ([epistolaMessageConverters], [app.epistola.client.error.installProblemDetailHandler]) not both
 * installed, or a timeout profile not actually reaching the request factory. Every assertion here
 * goes through a real loopback [HttpServer], because a stubbed `ClientHttpResponse` cannot show any
 * of that.
 */
class EpistolaClientTest {

    @Test
    fun `baseUrl is required`() {
        assertThrows(IllegalArgumentException::class.java) { EpistolaClient.builder().build() }
    }

    @Test
    fun `identity headers, the JSON configuration, and the problem handler are all wired at once`() {
        var captured: com.sun.net.httpserver.HttpExchange? = null
        val server = jsonServer(200, """{"id":"acme","tenantId":"t","name":"n","authMethod":"api-key","status":"active","createdAt":"2026-01-01T00:00:00Z","requestedPermissions":{},"grantedPermissions":{},"nodes":[]}""") {
            captured = it
        }
        try {
            val restClient = EpistolaClient.builder(baseUrl(server), "epk_test")
                .identity(ClientIdentity.builder().nodeId("pod-7").build())
                .build()

            // A field the caller never set must not appear as an explicit null — that is
            // epistolaMessageConverters()'s whole job, and it is opt-in at the low level.
            ConsumersApi(restClient)
                .updateConsumer("acme-corp", "billing-service", UpdateConsumerRequest(name = "Billing"))

            val headers = captured!!.requestHeaders
            assertEquals("pod-7", headers.getFirst("X-EP-Node-Id"))
            assertTrue(headers.getFirst("Authorization")!!.startsWith("ApiKey epk_test"))
            assertTrue(headers.getFirst("User-Agent")!!.startsWith("epistola-contract/"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `the request body omits properties the caller never set`() {
        var body: String? = null
        val server = jsonServer(200, """{"id":"acme","tenantId":"t","name":"n","authMethod":"api-key","status":"active","createdAt":"2026-01-01T00:00:00Z","requestedPermissions":{},"grantedPermissions":{},"nodes":[]}""") {
            body = String(it.requestBody.readAllBytes())
        }
        try {
            val restClient = EpistolaClient.builder(baseUrl(server), "epk_test").build()

            ConsumersApi(restClient)
                .updateConsumer("acme-corp", "billing-service", UpdateConsumerRequest(name = "Billing"))

            assertEquals("""{"name":"Billing"}""", body)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `an application problem+json error becomes a typed ProblemDetailException, not a bare one`() {
        val server = jsonServer(
            404,
            """{"type":"https://epistola.app/errors/not-found","title":"Not Found","status":404,"detail":"gone"}""",
            contentType = "application/problem+json",
        ) {}
        try {
            val restClient = EpistolaClient.builder(baseUrl(server), "epk_test").build()

            val ex = assertThrows(RestClientResponseException::class.java) {
                TenantsApi(restClient).getTenant("acme-corp")
            }

            // This is the exact failure mode the unified builder exists to close off: without
            // installProblemDetailHandler() this is still a RestClientResponseException, just not
            // this one, and the catch (e: ProblemDetailException) block a consumer wrote never runs.
            assertTrue(ex is ProblemDetailException, "expected ProblemDetailException, got ${ex::class}")
            assertEquals("not-found", (ex as ProblemDetailException).typeSlug)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `apiKey and jwtSigner are mutually exclusive, whichever was set last wins`() {
        var headers: com.sun.net.httpserver.Headers? = null
        val server = jsonServer(200, """{"status":"UP","timestamp":"2026-01-01T00:00:00Z"}""") { headers = it.requestHeaders }
        try {
            val signer = JwtSigner.builder().consumerId("svc").privateKey(rsaKey()).build()

            val apiKeyWins = EpistolaClient.builder(baseUrl(server)).jwtSigner(signer).apiKey("epk_test").build()
            SystemApi(apiKeyWins).ping(null)
            assertEquals("ApiKey epk_test", headers!!.getFirst("Authorization"))

            val jwtWins = EpistolaClient.builder(baseUrl(server)).apiKey("epk_test").jwtSigner(signer).build()
            SystemApi(jwtWins).ping(null)
            assertTrue(headers!!.getFirst("Authorization")!!.startsWith("Bearer "))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `no auth configured means no Authorization header`() {
        var headers: com.sun.net.httpserver.Headers? = null
        val server = jsonServer(200, """{"status":"UP","timestamp":"2026-01-01T00:00:00Z"}""") { headers = it.requestHeaders }
        try {
            val restClient = EpistolaClient.builder(baseUrl(server)).build()
            SystemApi(restClient).ping(null)
            assertNull(headers!!.getFirst("Authorization"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `readTimeout(null) and a bounded readTimeout are genuinely different profiles, from one builder`() {
        // The server delay only needs to outlast the bounded profile's timeout; it does not need to
        // approach the unbounded profile's actual (JDK-default) ceiling for that profile to prove
        // its point — it just has to not throw where the bounded one does.
        val delay = Duration.ofMillis(400)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                Thread.sleep(delay.toMillis())
                val body = """{"status":"UP","timestamp":"2026-01-01T00:00:00Z"}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/vnd.epistola.v1+json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val builder = EpistolaClient.builder(baseUrl(server))

            val bounded = builder.readTimeout(Duration.ofMillis(100)).build()
            assertThrows(org.springframework.web.client.ResourceAccessException::class.java) {
                SystemApi(bounded).ping(null)
            }

            val unbounded = builder.readTimeout(null).build()
            val pong = SystemApi(unbounded).ping(null)
            assertEquals("UP", pong.status.toString())
        } finally {
            server.stop(0)
        }
    }

    // --- Fixtures ---

    private fun baseUrl(server: HttpServer) = "http://127.0.0.1:${server.address.port}"

    private fun rsaKey() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private

    private fun jsonServer(
        status: Int,
        body: String,
        contentType: String = "application/vnd.epistola.v1+json",
        capture: (com.sun.net.httpserver.HttpExchange) -> Unit,
    ): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            capture(exchange)
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
}
