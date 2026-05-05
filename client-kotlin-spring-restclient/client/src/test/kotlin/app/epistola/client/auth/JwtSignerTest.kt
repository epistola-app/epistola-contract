package app.epistola.client.auth

import com.nimbusds.jwt.SignedJWT
import io.mockk.every
import io.mockk.mockk
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpResponse
import java.security.KeyPairGenerator
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JwtSignerTest {

    private val rsaKeyPair = KeyPairGenerator.getInstance("RSA").apply {
        initialize(2048)
    }.generateKeyPair()

    @Test
    fun `createToken produces valid JWT with correct claims`() {
        val signer = JwtSigner.builder()
            .consumerId("invoice-service")
            .privateKey(rsaKeyPair.private)
            .build()

        val token = signer.createToken()
        val jwt = SignedJWT.parse(token)
        val claims = jwt.jwtClaimsSet

        assertEquals("invoice-service", claims.issuer)
        assertNotNull(claims.issueTime)
        assertNotNull(claims.expirationTime)
        assertNotNull(claims.jwtid)
        assertTrue(claims.expirationTime.after(claims.issueTime))
    }

    @Test
    fun `token expiry matches configured lifetime`() {
        val signer = JwtSigner.builder()
            .consumerId("test-app")
            .privateKey(rsaKeyPair.private)
            .tokenLifetime(Duration.ofSeconds(30))
            .build()

        val jwt = SignedJWT.parse(signer.createToken())
        val claims = jwt.jwtClaimsSet
        val diffSeconds = (claims.expirationTime.time - claims.issueTime.time) / 1000

        assertEquals(30, diffSeconds)
    }

    @Test
    fun `each token has a unique jti`() {
        val signer = JwtSigner.builder()
            .consumerId("test-app")
            .privateKey(rsaKeyPair.private)
            .build()

        val jti1 = SignedJWT.parse(signer.createToken()).jwtClaimsSet.jwtid
        val jti2 = SignedJWT.parse(signer.createToken()).jwtClaimsSet.jwtid

        assertNotEquals(jti1, jti2)
    }

    @Test
    fun `token is signed and verifiable with public key`() {
        val signer = JwtSigner.builder()
            .consumerId("test-app")
            .privateKey(rsaKeyPair.private)
            .build()

        val jwt = SignedJWT.parse(signer.createToken())
        val verifier = com.nimbusds.jose.crypto.RSASSAVerifier(
            rsaKeyPair.public as java.security.interfaces.RSAPublicKey,
        )

        assertTrue(jwt.verify(verifier))
    }

    @Test
    fun `interceptor sets Authorization Bearer header`() {
        val signer = JwtSigner.builder()
            .consumerId("test-app")
            .privateKey(rsaKeyPair.private)
            .build()

        val headers = HttpHeaders()
        val request = mockk<HttpRequest> {
            every { getHeaders() } returns headers
        }
        val execution = mockk<ClientHttpRequestExecution> {
            every { execute(any(), any()) } returns mockk<ClientHttpResponse>()
        }

        signer.interceptor().intercept(request, ByteArray(0), execution)

        val authHeader = headers.getFirst(HttpHeaders.AUTHORIZATION)
        assertNotNull(authHeader)
        assertTrue(authHeader.startsWith("Bearer "))

        // Verify it's a valid JWT
        val jwt = SignedJWT.parse(authHeader.removePrefix("Bearer "))
        assertEquals("test-app", jwt.jwtClaimsSet.issuer)
    }

    @Test
    fun `builder rejects blank consumerId`() {
        assertFailsWith<IllegalArgumentException> {
            JwtSigner.builder().consumerId("")
        }
    }

    @Test
    fun `builder rejects zero token lifetime`() {
        assertFailsWith<IllegalArgumentException> {
            JwtSigner.builder().tokenLifetime(Duration.ZERO)
        }
    }

    @Test
    fun `builder rejects negative token lifetime`() {
        assertFailsWith<IllegalArgumentException> {
            JwtSigner.builder().tokenLifetime(Duration.ofSeconds(-1))
        }
    }

    @Test
    fun `builder requires consumerId`() {
        assertFailsWith<IllegalArgumentException> {
            JwtSigner.builder()
                .privateKey(rsaKeyPair.private)
                .build()
        }
    }

    @Test
    fun `builder requires privateKey`() {
        assertFailsWith<IllegalArgumentException> {
            JwtSigner.builder()
                .consumerId("test-app")
                .build()
        }
    }

    @Test
    fun `default token lifetime is 60 seconds`() {
        val signer = JwtSigner.builder()
            .consumerId("test-app")
            .privateKey(rsaKeyPair.private)
            .build()

        val jwt = SignedJWT.parse(signer.createToken())
        val claims = jwt.jwtClaimsSet
        val diffSeconds = (claims.expirationTime.time - claims.issueTime.time) / 1000

        assertEquals(60, diffSeconds)
    }
}
