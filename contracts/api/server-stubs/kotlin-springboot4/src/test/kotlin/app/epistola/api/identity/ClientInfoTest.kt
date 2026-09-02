// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.api.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClientInfoTest {

    // --- The two halves of the wire contract ---

    @Test
    fun `the header name and product token come from the contract registry`() {
        // Both sides generate these from the spec's x-client-identity extension. Pinning the
        // literals here means a change to the registry shows up as a deliberate test edit rather
        // than a silent change to what every deployed client is expected to send.
        assertEquals("X-EP-Node-Id", ClientInfo.HEADER_NODE_ID)
        assertEquals("epistola-contract", ContractIdentity.CONTRACT_PRODUCT)
        assertEquals(" ", ContractIdentity.PRODUCT_SEPARATOR)
        assertEquals("/", ContractIdentity.VERSION_SEPARATOR)
    }

    @Test
    fun `a User-Agent assembled the way the clients assemble it parses back`() {
        // The clients build this string from the same generated constants. Assembling it here the
        // same way and parsing it back is what proves the writing and parsing halves agree —
        // neither module can depend on the other, so this is the seam where they meet.
        val stack = listOf(
            ContractIdentity.CONTRACT_PRODUCT to "1.1.0",
            "zaakafhandelcomponent" to "3.4.0",
            "gzac" to "5.0.0",
        )
        val userAgent = stack.joinToString(ContractIdentity.PRODUCT_SEPARATOR) { (name, version) ->
            name + ContractIdentity.VERSION_SEPARATOR + version
        }

        val info = ClientInfo(products = ClientInfo.parseUserAgent(userAgent), nodeId = "pod-7")

        assertEquals(stack.map { (name, version) -> ClientInfo.Product(name, version) }, info.products)
        assertEquals("1.1.0", info.contractVersion)
        assertEquals("3.4.0", info.productVersion("zaakafhandelcomponent"))
    }

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
