// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * The signing is plain {@code java.security} rather than a JOSE library, so these tests verify the
 * tokens the way a server would: decode the parts, then check the signature against the public key
 * with the algorithm the header names.
 *
 * <p>The Kotlin client's own JwtSignerTest goes further and parses these tokens with Nimbus — an
 * independent JOSE implementation — which is the check that matters most for a hand-rolled signer.
 */
class ProtocolJwtSignerTest {

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
        ProtocolJwtSigner signer = signerFor(rsaKeyPair.getPrivate());

        assertEquals("RS256", signer.algorithm());
        String token = signer.createToken();
        assertTrue(part(token, 0).contains("\"alg\":\"RS256\""));
        assertTrue(verify(token, "SHA256withRSA", rsaKeyPair.getPublic()));
    }

    @Test
    void an_ec_key_produces_a_verifiable_es256_token_in_jose_format() throws Exception {
        ProtocolJwtSigner signer = signerFor(ecKeyPair.getPrivate());

        assertEquals("ES256", signer.algorithm());
        String token = signer.createToken();
        // The raw R||S encoding is what JOSE requires; a DER SEQUENCE would be 70-72 bytes and
        // would fail every compliant verifier.
        assertEquals(64, decode(token.split("\\.")[2]).length);
        assertTrue(verify(token, "SHA256withECDSAinP1363Format", ecKeyPair.getPublic()));
    }

    @Test
    void the_claims_carry_the_issuer_lifetime_and_a_replay_nonce() {
        String claims = part(signerFor(rsaKeyPair.getPrivate(), Duration.ofSeconds(120)).createToken(), 1);

        assertTrue(claims.contains("\"iss\":\"invoice-service\""), claims);
        long issuedAt = longClaim(claims, "iat");
        assertEquals(120, longClaim(claims, "exp") - issuedAt);
        assertTrue(Math.abs(Instant.now().getEpochSecond() - issuedAt) < 60, "iat should be now");
        assertTrue(claims.contains("\"jti\":\""), claims);
    }

    @Test
    void the_default_lifetime_is_one_minute() {
        String claims = part(signerFor(rsaKeyPair.getPrivate()).createToken(), 1);
        assertEquals(60, longClaim(claims, "exp") - longClaim(claims, "iat"));
    }

    @Test
    void every_token_gets_a_fresh_nonce_so_replay_protection_works() {
        ProtocolJwtSigner signer = signerFor(rsaKeyPair.getPrivate());
        assertNotEquals(part(signer.createToken(), 1), part(signer.createToken(), 1));
    }

    @Test
    void a_consumer_id_with_json_metacharacters_cannot_break_the_claims() {
        // iss comes from configuration, so it is escaped rather than assumed safe. Without this the
        // token body would be malformed JSON, or worse, carry an injected claim.
        String claims = part(signerFor(rsaKeyPair.getPrivate(), "acme\",\"admin\":true").createToken(), 1);

        assertTrue(claims.startsWith("{\"iss\":\"acme\\\",\\\"admin\\\":true\""), claims);
    }

    @Test
    void control_characters_are_escaped_too() {
        assertEquals("a\\nb", ProtocolJwtSigner.escapeJson("a\nb"));
        assertEquals("a\\\\b", ProtocolJwtSigner.escapeJson("a\\b"));
        assertEquals("a\\u0000b", ProtocolJwtSigner.escapeJson("a\u0000b"));
    }

    @Test
    void a_pem_key_round_trips_through_the_loader(@TempDir Path tempDir) throws Exception {
        Path pemFile = tempDir.resolve("private.pem");
        Files.writeString(pemFile, toPem(rsaKeyPair.getPrivate()));

        assertEquals(rsaKeyPair.getPrivate(), ProtocolJwtSigner.loadPrivateKey(pemFile));
        assertEquals(ecKeyPair.getPrivate(), ProtocolJwtSigner.parsePrivateKeyPem(toPem(ecKeyPair.getPrivate())));
    }

    @Test
    void an_unparseable_pem_says_which_formats_are_supported() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> ProtocolJwtSigner.parsePrivateKeyPem(
                        "-----BEGIN PRIVATE KEY-----\nbm90IGEga2V5\n-----END PRIVATE KEY-----"));

        assertTrue(e.getMessage().contains("PKCS#8"), e.getMessage());
    }

    @Test
    void the_builder_rejects_an_incomplete_or_invalid_configuration() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ProtocolJwtSigner.builder().privateKey(rsaKeyPair.getPrivate()).build());
        assertThrows(
                IllegalArgumentException.class, () -> ProtocolJwtSigner.builder().consumerId("a").build());
        assertThrows(IllegalArgumentException.class, () -> ProtocolJwtSigner.builder().consumerId(" "));
        assertThrows(
                IllegalArgumentException.class,
                () -> ProtocolJwtSigner.builder().tokenLifetime(Duration.ZERO));
    }

    private static ProtocolJwtSigner signerFor(PrivateKey key) {
        return signerFor(key, "invoice-service");
    }

    private static ProtocolJwtSigner signerFor(PrivateKey key, String consumerId) {
        return ProtocolJwtSigner.builder().consumerId(consumerId).privateKey(key).build();
    }

    private static ProtocolJwtSigner signerFor(PrivateKey key, Duration lifetime) {
        return ProtocolJwtSigner.builder()
                .consumerId("invoice-service")
                .privateKey(key)
                .tokenLifetime(lifetime)
                .build();
    }

    private static String part(String token, int index) {
        return new String(decode(token.split("\\.")[index]), StandardCharsets.UTF_8);
    }

    private static long longClaim(String claims, String name) {
        int start = claims.indexOf("\"" + name + "\":") + name.length() + 3;
        int end = start;
        while (end < claims.length() && Character.isDigit(claims.charAt(end))) {
            end++;
        }
        return Long.parseLong(claims.substring(start, end));
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
