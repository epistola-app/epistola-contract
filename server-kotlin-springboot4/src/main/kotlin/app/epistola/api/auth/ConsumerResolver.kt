package app.epistola.api.auth

import app.epistola.api.identity.ClientInfo
import jakarta.servlet.http.HttpServletRequest

/**
 * Resolves consumer identity from JWT claims and request headers.
 *
 * Works for both authentication paths:
 * - **OAuth**: consumer ID is the `client_id` claim (or `azp` as fallback)
 * - **Self-signed JWT**: consumer ID is the `iss` claim
 *
 * The server implementation is responsible for parsing the JWT and passing the
 * claims map to this resolver. This class does not handle JWT validation itself.
 *
 * Example usage:
 * ```
 * // In a Spring filter or controller
 * val claims: Map<String, Any> = jwtParser.parseClaims(token)
 * val consumerId = ConsumerResolver.resolveConsumerId(claims)
 * val nodeId = ConsumerResolver.resolveNodeId(request)
 * ```
 */
object ConsumerResolver {

    /**
     * Resolves the consumer ID from JWT claims.
     *
     * Checks claims in this order:
     * 1. `client_id` — standard OAuth 2.0 Client Credentials claim
     * 2. `azp` — authorized party claim (Keycloak, some OIDC providers)
     * 3. `iss` — issuer claim (used by self-signed JWT consumers)
     *
     * @throws IllegalArgumentException if no consumer ID can be resolved
     */
    fun resolveConsumerId(claims: Map<String, Any?>): String {
        val clientId = claims["client_id"]?.toString()
        if (!clientId.isNullOrBlank()) return clientId

        val azp = claims["azp"]?.toString()
        if (!azp.isNullOrBlank()) return azp

        val iss = claims["iss"]?.toString()
        if (!iss.isNullOrBlank()) return iss

        throw IllegalArgumentException(
            "Cannot resolve consumer ID: JWT contains none of 'client_id', 'azp', or 'iss' claims",
        )
    }

    /**
     * Resolves the node ID from the `X-EP-Node-Id` request header.
     *
     * @throws IllegalArgumentException if the header is missing
     */
    fun resolveNodeId(request: HttpServletRequest): String {
        val nodeId = request.getHeader(ClientInfo.HEADER_NODE_ID)
        require(!nodeId.isNullOrBlank()) { "Missing required header: ${ClientInfo.HEADER_NODE_ID}" }
        return nodeId
    }
}
