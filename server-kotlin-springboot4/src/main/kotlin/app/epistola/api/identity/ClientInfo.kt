package app.epistola.api.identity

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
        const val HEADER_NODE_ID = "X-EP-Node-Id"
        internal const val CONTRACT_PRODUCT = "epistola-contract"

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
            if (userAgent.isNullOrBlank()) return emptyList()
            return userAgent.trim().split("\\s+".toRegex()).map { token ->
                val slash = token.indexOf('/')
                if (slash >= 0) {
                    Product(token.substring(0, slash), token.substring(slash + 1))
                } else {
                    Product(token, "")
                }
            }
        }
    }
}

/** Extension function for convenient access to [ClientInfo] from a request. */
fun HttpServletRequest.clientInfo(): ClientInfo = ClientInfo.from(this)
