// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.epistola.client.jakarta.EpistolaRestClients;
import app.epistola.client.jakarta.StubServer;
import app.epistola.client.jakarta.api.SystemApi;
import app.epistola.client.jakarta.model.PingRequest;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The signing is done with plain {@code java.security} rather than a JOSE library, so these tests
 * verify the produced tokens the way a server would: decode the parts, then check the signature
 * against the public key with the algorithm the header names.
 */
class JwtSignerTest {

    private static KeyPair rsaKeyPair;
    private static KeyPair ecKeyPair;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        rsaKeyPair = rsa.generateKeyPair();

        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(new ECGenParameterSpec("secp256r1"));
        ecKeyPair = ec.generateKeyPair();
    }

    @Test
    void an_rsa_key_produces_a_verifiable_rs256_token() throws Exception {
        JwtSigner signer = JwtSigner.builder()
                .consumerId("invoice-service")
                .privateKey(rsaKeyPair.getPrivate())
                .build();

        String token = signer.createToken();

        assertEquals("RS256", header(token).getString("alg"));
        assertEquals("JWT", header(token).getString("typ"));
        assertTrue(verify(token, "SHA256withRSA", rsaKeyPair.getPublic()), "RS256 signature should verify");
    }

    @Test
    void an_ec_key_produces_a_verifiable_es256_token_in_jose_format() throws Exception {
        JwtSigner signer = JwtSigner.builder()
                .consumerId("invoice-service")
                .privateKey(ecKeyPair.getPrivate())
                .build();

        String token = signer.createToken();

        assertEquals("ES256", header(token).getString("alg"));
        // The raw R||S encoding is what JOSE requires; a DER SEQUENCE would be 70-72 bytes and
        // would fail every compliant verifier.
        assertEquals(64, decode(token.split("\\.")[2]).length, "ES256 signatures are a raw 64-byte R||S pair");
        assertTrue(
                verify(token, "SHA256withECDSAinP1363Format", ecKeyPair.getPublic()),
                "ES256 signature should verify");
    }

    @Test
    void the_claims_carry_the_issuer_lifetime_and_a_replay_nonce() {
        JwtSigner signer = JwtSigner.builder()
                .consumerId("invoice-service")
                .privateKey(rsaKeyPair.getPrivate())
                .tokenLifetime(Duration.ofSeconds(120))
                .build();

        JsonObject claims = claims(signer.createToken());

        assertEquals("invoice-service", claims.getString("iss"));
        long issuedAt = claims.getJsonNumber("iat").longValue();
        long expiresAt = claims.getJsonNumber("exp").longValue();
        assertEquals(120, expiresAt - issuedAt);
        assertTrue(Math.abs(Instant.now().getEpochSecond() - issuedAt) < 60, "iat should be now");
        assertTrue(claims.getString("jti").length() >= 36, "jti should be a UUID");
    }

    @Test
    void the_default_lifetime_is_one_minute() {
        JwtSigner signer = JwtSigner.builder()
                .consumerId("invoice-service")
                .privateKey(rsaKeyPair.getPrivate())
                .build();

        JsonObject claims = claims(signer.createToken());

        assertEquals(60, claims.getJsonNumber("exp").longValue() - claims.getJsonNumber("iat").longValue());
    }

    @Test
    void every_token_gets_a_fresh_nonce_so_replay_protection_works() {
        JwtSigner signer = JwtSigner.builder()
                .consumerId("invoice-service")
                .privateKey(rsaKeyPair.getPrivate())
                .build();

        assertNotEquals(claims(signer.createToken()).getString("jti"), claims(signer.createToken()).getString("jti"));
    }

    @Test
    void a_pem_key_round_trips_through_the_loader(@TempDir Path tempDir) throws Exception {
        Path pemFile = tempDir.resolve("private.pem");
        Files.writeString(pemFile, toPem(rsaKeyPair.getPrivate()));

        PrivateKey loaded = JwtSigner.loadPrivateKey(pemFile);

        assertEquals(rsaKeyPair.getPrivate(), loaded);
    }

    @Test
    void an_ec_pem_key_is_recognised_too() {
        assertEquals(ecKeyPair.getPrivate(), JwtSigner.parsePrivateKeyPem(toPem(ecKeyPair.getPrivate())));
    }

    @Test
    void an_unparseable_pem_says_which_formats_are_supported() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> JwtSigner.parsePrivateKeyPem("-----BEGIN PRIVATE KEY-----\nbm90IGEga2V5\n-----END PRIVATE KEY-----"));

        assertTrue(e.getMessage().contains("PKCS#8"), "the message should name the expected format");
    }

    @Test
    void the_builder_rejects_an_incomplete_configuration() {
        // IllegalArgumentException throughout, matching the Kotlin client and the rest of the
        // shared protocol code — the two clients used to disagree on this.
        assertThrows(
                IllegalArgumentException.class,
                () -> JwtSigner.builder().privateKey(rsaKeyPair.getPrivate()).build());
        assertThrows(IllegalArgumentException.class, () -> JwtSigner.builder().consumerId("a").build());
        assertThrows(IllegalArgumentException.class, () -> JwtSigner.builder().consumerId(" "));
        assertThrows(
                IllegalArgumentException.class,
                () -> JwtSigner.builder().tokenLifetime(Duration.ZERO));
    }

    @Test
    void the_bearer_token_reaches_the_server_and_a_second_request_carries_a_new_one() {
        try (StubServer stub = StubServer.start(request -> StubServer.StubResponse.of(
                200, "application/vnd.epistola.v1+json",
                "{\"status\":\"UP\",\"timestamp\":\"2026-09-02T10:00:00Z\"}"))) {

            SystemApi system = EpistolaRestClients.builder()
                    .baseUri(stub.baseUri())
                    .jwtSigner(JwtSigner.builder()
                            .consumerId("invoice-service")
                            .privateKey(rsaKeyPair.getPrivate())
                            .build())
                    .build()
                    .api(SystemApi.class);

            system.ping(new PingRequest());
            system.ping(new PingRequest());

            String first = stub.requests().get(0).header("Authorization");
            String second = stub.requests().get(1).header("Authorization");
            assertTrue(first.startsWith("Bearer "), "expected a bearer token, got: " + first);
            assertNotEquals(first, second, "each request must get a freshly minted token");
        }
    }

    private static JsonObject header(String token) {
        return parse(token.split("\\.")[0]);
    }

    private static JsonObject claims(String token) {
        return parse(token.split("\\.")[1]);
    }

    private static JsonObject parse(String encodedPart) {
        String json = new String(decode(encodedPart), StandardCharsets.UTF_8);
        try (var reader = Json.createReader(new StringReader(json))) {
            return reader.readObject();
        }
    }

    private static byte[] decode(String encodedPart) {
        return Base64.getUrlDecoder().decode(encodedPart);
    }

    private static boolean verify(String token, String jcaAlgorithm, PublicKey publicKey) throws Exception {
        String[] parts = token.split("\\.");
        Signature signature = Signature.getInstance(jcaAlgorithm);
        signature.initVerify(publicKey);
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        return signature.verify(decode(parts[2]));
    }

    private static String toPem(PrivateKey key) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(key.getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }
}
