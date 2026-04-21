package app.epistola.client.identity

import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import java.net.InetAddress

/**
 * Manages client identity headers (`User-Agent` and `X-EP-Node-Id`) for all Epistola API requests.
 *
 * The `User-Agent` always starts with `epistola-contract/{contractVersion}`.
 * Additional product tokens can be added to describe the full software stack.
 *
 * Example:
 * ```
 * val identity = ClientIdentity.builder()
 *     .nodeId("my-pod-123")
 *     .product("valtimo-epistola-plugin", "1.2.0")
 *     .product("gzac", "5.0.0")
 *     .build()
 *
 * val restClient = RestClient.builder()
 *     .baseUrl("http://localhost:8080/api")
 *     .requestInterceptor(identity.interceptor())
 *     .build()
 * ```
 *
 * Produces headers:
 * ```
 * User-Agent: epistola-contract/0.3.0 valtimo-epistola-plugin/1.2.0 gzac/5.0.0
 * X-EP-Node-Id: my-pod-123
 * ```
 */
class ClientIdentity private constructor(
    val userAgent: String,
    val nodeId: String,
) {
    companion object {
        const val HEADER_NODE_ID = "X-EP-Node-Id"
        internal const val CONTRACT_PRODUCT = "epistola-contract"

        /**
         * The contract version this client library was built against.
         * Read from the bundled `epistola-contract-version.txt` resource at build time.
         */
        val contractVersion: String by lazy {
            ClientIdentity::class.java.getResourceAsStream("/epistola-contract-version.txt")
                ?.bufferedReader()?.readLine()?.trim()
                ?: "unknown"
        }

        fun builder(): Builder = Builder()
    }

    /**
     * Creates a Spring [ClientHttpRequestInterceptor] that adds the `User-Agent`
     * and `X-EP-Node-Id` headers to every outgoing request.
     */
    fun interceptor(): ClientHttpRequestInterceptor = ClientIdentityInterceptor(this)

    class Builder {
        private var nodeId: String? = null
        private val products = mutableListOf<Pair<String, String>>()

        /**
         * Sets the node identifier (e.g. Kubernetes pod name, hostname).
         * If not set, defaults to the local hostname.
         */
        fun nodeId(nodeId: String) = apply { this.nodeId = nodeId }

        /**
         * Adds a product/version pair to the `User-Agent` header.
         * Products are appended in the order they are added, after the
         * `epistola-contract/{version}` token.
         */
        fun product(name: String, version: String) = apply {
            require(name.isNotBlank()) { "Product name must not be blank" }
            require(version.isNotBlank()) { "Product version must not be blank" }
            require(!name.contains('/') && !name.contains(' ')) {
                "Product name must not contain '/' or spaces"
            }
            products.add(name to version)
        }

        fun build(): ClientIdentity {
            val tokens = mutableListOf("$CONTRACT_PRODUCT/$contractVersion")
            products.forEach { (name, version) -> tokens.add("$name/$version") }

            return ClientIdentity(
                userAgent = tokens.joinToString(" "),
                nodeId = nodeId ?: InetAddress.getLocalHost().hostName,
            )
        }
    }

    private class ClientIdentityInterceptor(
        private val identity: ClientIdentity,
    ) : ClientHttpRequestInterceptor {
        override fun intercept(
            request: HttpRequest,
            body: ByteArray,
            execution: ClientHttpRequestExecution,
        ): ClientHttpResponse {
            request.headers.set("User-Agent", identity.userAgent)
            request.headers.set(HEADER_NODE_ID, identity.nodeId)
            return execution.execute(request, body)
        }
    }
}
