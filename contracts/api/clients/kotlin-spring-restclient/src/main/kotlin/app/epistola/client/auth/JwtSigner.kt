// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.auth

import app.epistola.protocol.ProtocolJwtSigner
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import java.nio.file.Path
import java.security.PrivateKey
import java.time.Duration

/**
 * Creates and signs short-lived JWTs for self-signed JWT authentication with Epistola.
 *
 * The signing itself is [ProtocolJwtSigner], shared with the Jakarta client: the JDK's own
 * `java.security` primitives, no JOSE library on the classpath. This class is the Spring face of
 * it.
 *
 * Each token contains:
 * - `iss`: the consumer ID
 * - `iat`: issued-at timestamp
 * - `exp`: expiry (iat + tokenLifetime)
 * - `jti`: unique nonce (UUID) for replay protection
 *
 * Example:
 * ```
 * val signer = JwtSigner.builder()
 *     .consumerId("invoice-service")
 *     .privateKey(JwtSigner.loadPrivateKey(Path.of("private.pem")))
 *     .build()
 *
 * val restClient = RestClient.builder()
 *     .baseUrl("http://localhost:8080/api")
 *     .requestInterceptor(identity.interceptor())   // User-Agent + X-EP-Node-Id
 *     .requestInterceptor(signer.interceptor())      // Authorization: Bearer
 *     .build()
 * ```
 */
class JwtSigner private constructor(
    private val signer: ProtocolJwtSigner,
) {
    companion object {
        fun builder(): Builder = Builder()

        /**
         * Loads a private key from a PEM file. Supports RSA and EC (P-256) keys
         * in PKCS#8 format (BEGIN PRIVATE KEY).
         */
        fun loadPrivateKey(path: Path): PrivateKey = ProtocolJwtSigner.loadPrivateKey(path)

        /**
         * Parses a PEM-encoded private key string. Supports RSA and EC (P-256) keys
         * in PKCS#8 format (BEGIN PRIVATE KEY).
         */
        fun parsePrivateKeyPem(pem: String): PrivateKey = ProtocolJwtSigner.parsePrivateKeyPem(pem)
    }

    /**
     * Creates a signed JWT token with a fresh `iat`, `exp`, and `jti`.
     */
    fun createToken(): String = signer.createToken()

    /**
     * Creates a Spring [ClientHttpRequestInterceptor] that sets the
     * `Authorization: Bearer <jwt>` header on every outgoing request.
     * A fresh token is created for each request.
     */
    fun interceptor(): ClientHttpRequestInterceptor = JwtSignerInterceptor(this)

    class Builder {
        private val delegate = ProtocolJwtSigner.builder()

        /** Sets the consumer ID used as the JWT `iss` claim. */
        fun consumerId(consumerId: String) = apply { delegate.consumerId(consumerId) }

        /** Sets the private key used to sign tokens. */
        fun privateKey(privateKey: PrivateKey) = apply { delegate.privateKey(privateKey) }

        /** Sets the token lifetime (default: 60 seconds). */
        fun tokenLifetime(lifetime: Duration) = apply { delegate.tokenLifetime(lifetime) }

        fun build(): JwtSigner = JwtSigner(delegate.build())
    }

    private class JwtSignerInterceptor(
        private val jwtSigner: JwtSigner,
    ) : ClientHttpRequestInterceptor {
        override fun intercept(
            request: HttpRequest,
            body: ByteArray,
            execution: ClientHttpRequestExecution,
        ): ClientHttpResponse {
            request.headers.setBearerAuth(jwtSigner.createToken())
            return execution.execute(request, body)
        }
    }
}
