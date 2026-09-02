// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.api.identity

import app.epistola.protocol.UserAgent
import jakarta.servlet.http.HttpServletRequest

/**
 * Parsed client identity from the `User-Agent` and `X-EP-Node-Id` request headers.
 *
 * Example usage in a Spring controller or filter:
 * ```
 * @GetMapping("/tenants/{tenantId}")
 * fun getTenant(@PathVariable tenantId: String, request: HttpServletRequest): TenantDto {
 *     val client = ClientInfo.from(request)
 *     log.info("Request from ${client.nodeId}, contract ${client.contractVersion}")
 *     // ...
 * }
 * ```
 *
 * Or using the extension function:
 * ```
 * val client = request.clientInfo()
 * ```
 */
data class ClientInfo(
    /** All product/version pairs from the User-Agent header, in order. */
    val products: List<Product>,
    /** The X-EP-Node-Id header value, or null if not provided. */
    val nodeId: String?,
) {
    /**
     * The contract version extracted from the first User-Agent token
     * (expected format: `epistola-contract/{version}`).
     * Returns null if the User-Agent header is missing or doesn't start with `epistola-contract/`.
     */
    val contractVersion: String?
        get() = products.firstOrNull { it.name == CONTRACT_PRODUCT }?.version

    /**
     * Looks up the version of a specific product in the User-Agent header.
     * Returns null if the product is not present.
     */
    fun productVersion(name: String): String? = products.firstOrNull { it.name == name }?.version

    data class Product(val name: String, val version: String)

    companion object {
        /** Header carrying the node identifier, from the contract's `x-client-identity` registry. */
        const val HEADER_NODE_ID = ContractIdentity.NODE_ID_HEADER
        internal const val CONTRACT_PRODUCT = ContractIdentity.CONTRACT_PRODUCT

        private val USER_AGENT: UserAgent =
            UserAgent.of(ContractIdentity.PRODUCT_SEPARATOR, ContractIdentity.VERSION_SEPARATOR)

        /**
         * Parses client identity from an [HttpServletRequest].
         */
        fun from(request: HttpServletRequest): ClientInfo {
            val userAgent = request.getHeader("User-Agent")
            val nodeId = request.getHeader(HEADER_NODE_ID)
            return ClientInfo(
                products = parseUserAgent(userAgent),
                nodeId = nodeId,
            )
        }

        /**
         * Parses an RFC 9110 User-Agent string into a list of product tokens.
         * Each token is expected in `product/version` format.
         * Tokens without a `/` are included with an empty version.
         */
        fun parseUserAgent(userAgent: String?): List<Product> {
            // The same grammar the clients format with, so the two halves cannot drift.
            return USER_AGENT.parse(userAgent).map { Product(it.name(), it.version()) }
        }
    }
}

/** Extension function for convenient access to [ClientInfo] from a request. */
fun HttpServletRequest.clientInfo(): ClientInfo = ClientInfo.from(this)
