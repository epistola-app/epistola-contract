package app.epistola.api.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConsumerResolverTest {

    @Test
    fun `resolves consumer ID from client_id claim`() {
        val claims = mapOf<String, Any?>("client_id" to "invoice-service", "iss" to "https://idp.example.com")
        assertEquals("invoice-service", ConsumerResolver.resolveConsumerId(claims))
    }

    @Test
    fun `resolves consumer ID from azp claim when client_id is absent`() {
        val claims = mapOf<String, Any?>("azp" to "invoice-service", "iss" to "https://idp.example.com")
        assertEquals("invoice-service", ConsumerResolver.resolveConsumerId(claims))
    }

    @Test
    fun `resolves consumer ID from iss claim as fallback`() {
        val claims = mapOf<String, Any?>("iss" to "invoice-service")
        assertEquals("invoice-service", ConsumerResolver.resolveConsumerId(claims))
    }

    @Test
    fun `client_id takes precedence over azp and iss`() {
        val claims = mapOf<String, Any?>(
            "client_id" to "from-client-id",
            "azp" to "from-azp",
            "iss" to "from-iss",
        )
        assertEquals("from-client-id", ConsumerResolver.resolveConsumerId(claims))
    }

    @Test
    fun `azp takes precedence over iss`() {
        val claims = mapOf<String, Any?>("azp" to "from-azp", "iss" to "from-iss")
        assertEquals("from-azp", ConsumerResolver.resolveConsumerId(claims))
    }

    @Test
    fun `throws when no consumer ID claims present`() {
        val claims = mapOf<String, Any?>("sub" to "some-subject")
        assertFailsWith<IllegalArgumentException> {
            ConsumerResolver.resolveConsumerId(claims)
        }
    }

    @Test
    fun `ignores blank client_id and falls through`() {
        val claims = mapOf<String, Any?>("client_id" to "  ", "iss" to "invoice-service")
        assertEquals("invoice-service", ConsumerResolver.resolveConsumerId(claims))
    }

    @Test
    fun `ignores null values and falls through`() {
        val claims = mapOf<String, Any?>("client_id" to null, "azp" to null, "iss" to "invoice-service")
        assertEquals("invoice-service", ConsumerResolver.resolveConsumerId(claims))
    }

    @Test
    fun `throws with empty claims map`() {
        assertFailsWith<IllegalArgumentException> {
            ConsumerResolver.resolveConsumerId(emptyMap())
        }
    }
}
