package app.epistola.api.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClientInfoTest {

    @Test
    fun `parseUserAgent with full stack`() {
        val products = ClientInfo.parseUserAgent(
            "epistola-contract/0.3.1 valtimo-epistola-plugin/1.2.0 gzac/5.0.0",
        )

        assertEquals(3, products.size)
        assertEquals(ClientInfo.Product("epistola-contract", "0.3.1"), products[0])
        assertEquals(ClientInfo.Product("valtimo-epistola-plugin", "1.2.0"), products[1])
        assertEquals(ClientInfo.Product("gzac", "5.0.0"), products[2])
    }

    @Test
    fun `parseUserAgent with single token`() {
        val products = ClientInfo.parseUserAgent("epistola-contract/0.3.0")

        assertEquals(1, products.size)
        assertEquals("epistola-contract", products[0].name)
        assertEquals("0.3.0", products[0].version)
    }

    @Test
    fun `parseUserAgent with null returns empty`() {
        assertEquals(emptyList(), ClientInfo.parseUserAgent(null))
    }

    @Test
    fun `parseUserAgent with blank returns empty`() {
        assertEquals(emptyList(), ClientInfo.parseUserAgent("  "))
    }

    @Test
    fun `parseUserAgent with token without version`() {
        val products = ClientInfo.parseUserAgent("curl")

        assertEquals(1, products.size)
        assertEquals("curl", products[0].name)
        assertEquals("", products[0].version)
    }

    @Test
    fun `contractVersion extracts from first epistola-contract token`() {
        val info = ClientInfo(
            products = ClientInfo.parseUserAgent("epistola-contract/0.3.1 my-app/1.0.0"),
            nodeId = "pod-1",
        )

        assertEquals("0.3.1", info.contractVersion)
    }

    @Test
    fun `contractVersion returns null when no contract token`() {
        val info = ClientInfo(
            products = ClientInfo.parseUserAgent("some-other-client/1.0.0"),
            nodeId = "pod-1",
        )

        assertNull(info.contractVersion)
    }

    @Test
    fun `productVersion looks up specific product`() {
        val info = ClientInfo(
            products = ClientInfo.parseUserAgent(
                "epistola-contract/0.3.0 valtimo-epistola-plugin/1.2.0 gzac/5.0.0",
            ),
            nodeId = "pod-1",
        )

        assertEquals("1.2.0", info.productVersion("valtimo-epistola-plugin"))
        assertEquals("5.0.0", info.productVersion("gzac"))
        assertNull(info.productVersion("nonexistent"))
    }

    @Test
    fun `nodeId is preserved`() {
        val info = ClientInfo(
            products = emptyList(),
            nodeId = "my-pod-abc123",
        )

        assertEquals("my-pod-abc123", info.nodeId)
    }
}
