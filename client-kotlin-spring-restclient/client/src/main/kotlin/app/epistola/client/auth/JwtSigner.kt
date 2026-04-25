package app.epistola.client.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

/**
 * Creates and signs short-lived JWTs for self-signed JWT authentication with Epistola.
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
    private val consumerId: String,
    private val privateKey: PrivateKey,
    private val algorithm: JWSAlgorithm,
    private val tokenLifetime: Duration,
) {
    companion object {
        fun builder(): Builder = Builder()

        /**
         * Loads a private key from a PEM file. Supports RSA and Ed25519 keys
         * in PKCS#8 format (BEGIN PRIVATE KEY).
         */
        fun loadPrivateKey(path: Path): PrivateKey {
            val pem = Files.readString(path)
            return parsePrivateKeyPem(pem)
        }

        /**
         * Parses a PEM-encoded private key string. Supports RSA and Ed25519 keys
         * in PKCS#8 format (BEGIN PRIVATE KEY).
         */
        fun parsePrivateKeyPem(pem: String): PrivateKey {
            val base64 = pem.lines()
                .filter { !it.startsWith("-----") }
                .joinToString("")
            val keyBytes = Base64.getDecoder().decode(base64)
            val keySpec = PKCS8EncodedKeySpec(keyBytes)

            return try {
                KeyFactory.getInstance("RSA").generatePrivate(keySpec)
            } catch (_: Exception) {
                try {
                    KeyFactory.getInstance("EC").generatePrivate(keySpec)
                } catch (_: Exception) {
                    throw IllegalArgumentException(
                        "Failed to parse private key. Supported formats: RSA, EC (P-256) in PKCS#8 PEM format.",
                    )
                }
            }
        }

        private fun detectAlgorithm(key: PrivateKey): JWSAlgorithm = when (key) {
            is RSAPrivateKey -> JWSAlgorithm.RS256
            is ECPrivateKey -> JWSAlgorithm.ES256
            else -> throw IllegalArgumentException(
                "Unsupported key type: ${key::class.simpleName} (algorithm: ${key.algorithm}). " +
                    "Supported: RSA (2048+), EC (P-256)",
            )
        }
    }

    /**
     * Creates a signed JWT token with a fresh `iat`, `exp`, and `jti`.
     */
    fun createToken(): String {
        val now = Instant.now()

        val claims = JWTClaimsSet.Builder()
            .issuer(consumerId)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(tokenLifetime)))
            .jwtID(UUID.randomUUID().toString())
            .build()

        val header = JWSHeader.Builder(algorithm).build()
        val jwt = SignedJWT(header, claims)

        val signer = when (algorithm) {
            JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512 ->
                RSASSASigner(privateKey as RSAPrivateKey)

            JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512 ->
                ECDSASigner(privateKey as ECPrivateKey)

            else -> throw IllegalStateException("Unsupported algorithm: $algorithm")
        }

        jwt.sign(signer)
        return jwt.serialize()
    }

    /**
     * Creates a Spring [ClientHttpRequestInterceptor] that sets the
     * `Authorization: Bearer <jwt>` header on every outgoing request.
     * A fresh token is created for each request.
     */
    fun interceptor(): ClientHttpRequestInterceptor = JwtSignerInterceptor(this)

    class Builder {
        private var consumerId: String? = null
        private var privateKey: PrivateKey? = null
        private var tokenLifetime: Duration = Duration.ofSeconds(60)

        /** Sets the consumer ID used as the JWT `iss` claim. */
        fun consumerId(consumerId: String) = apply {
            require(consumerId.isNotBlank()) { "consumerId must not be blank" }
            this.consumerId = consumerId
        }

        /** Sets the private key used to sign tokens. */
        fun privateKey(privateKey: PrivateKey) = apply {
            this.privateKey = privateKey
        }

        /** Sets the token lifetime (default: 60 seconds). */
        fun tokenLifetime(lifetime: Duration) = apply {
            require(!lifetime.isNegative && !lifetime.isZero) { "tokenLifetime must be positive" }
            this.tokenLifetime = lifetime
        }

        fun build(): JwtSigner {
            val id = requireNotNull(consumerId) { "consumerId is required" }
            val key = requireNotNull(privateKey) { "privateKey is required" }
            val algo = detectAlgorithm(key)

            return JwtSigner(
                consumerId = id,
                privateKey = key,
                algorithm = algo,
                tokenLifetime = tokenLifetime,
            )
        }
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
