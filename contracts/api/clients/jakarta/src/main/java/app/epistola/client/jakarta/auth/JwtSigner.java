// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.auth;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Creates and signs short-lived JWTs for self-signed JWT authentication with Epistola.
 *
 * <p>Each token contains:
 * <ul>
 *   <li>{@code iss} — the consumer ID</li>
 *   <li>{@code iat} — issued-at timestamp</li>
 *   <li>{@code exp} — expiry ({@code iat} + token lifetime)</li>
 *   <li>{@code jti} — unique nonce (UUID) for replay protection</li>
 * </ul>
 *
 * <p>Signing uses the JDK's own {@code java.security} primitives — RS256 for RSA keys, ES256 for
 * EC P-256 keys. No JOSE library is pulled in: a client destined for a WAR should add as little to
 * that WAR as it can, and the JDK already signs both algorithms (ES256 through
 * {@code SHA256withECDSAinP1363Format}, whose output is the raw {@code R || S} JOSE expects).
 *
 * <pre>{@code
 * JwtSigner signer = JwtSigner.builder()
 *     .consumerId("invoice-service")
 *     .privateKey(JwtSigner.loadPrivateKey(Path.of("private.pem")))
 *     .build();
 *
 * GenerationApi api = RestClientBuilder.newBuilder()
 *     .baseUri(URI.create("https://epistola.example.com/api"))
 *     .register(identity.filter())   // User-Agent + X-EP-Node-Id
 *     .register(signer.filter())     // Authorization: Bearer <jwt>
 *     .build(GenerationApi.class);
 * }</pre>
 */
public final class JwtSigner {

    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private final String consumerId;
    private final PrivateKey privateKey;
    private final Algorithm algorithm;
    private final Duration tokenLifetime;

    private JwtSigner(String consumerId, PrivateKey privateKey, Algorithm algorithm, Duration tokenLifetime) {
        this.consumerId = consumerId;
        this.privateKey = privateKey;
        this.algorithm = algorithm;
        this.tokenLifetime = tokenLifetime;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Loads a private key from a PEM file. Supports RSA and EC (P-256) keys in PKCS#8 format
     * ({@code BEGIN PRIVATE KEY}).
     */
    public static PrivateKey loadPrivateKey(Path path) {
        try {
            return parsePrivateKeyPem(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read private key from " + path, e);
        }
    }

    /**
     * Parses a PEM-encoded private key string. Supports RSA and EC (P-256) keys in PKCS#8 format
     * ({@code BEGIN PRIVATE KEY}).
     */
    public static PrivateKey parsePrivateKeyPem(String pem) {
        StringBuilder base64 = new StringBuilder();
        for (String line : pem.split("\\R")) {
            if (!line.startsWith("-----")) {
                base64.append(line.trim());
            }
        }
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64.toString()));
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(keySpec);
        } catch (GeneralSecurityException | IllegalArgumentException rsaFailure) {
            try {
                return KeyFactory.getInstance("EC").generatePrivate(keySpec);
            } catch (GeneralSecurityException | IllegalArgumentException ecFailure) {
                throw new IllegalArgumentException(
                        "Failed to parse private key. Supported formats: RSA, EC (P-256) in PKCS#8 PEM format.",
                        ecFailure);
            }
        }
    }

    /** Creates a signed JWT with a fresh {@code iat}, {@code exp} and {@code jti}. */
    public String createToken() {
        Instant now = Instant.now();
        JsonObject header = Json.createObjectBuilder()
                .add("alg", algorithm.joseName)
                .add("typ", "JWT")
                .build();
        JsonObject claims = Json.createObjectBuilder()
                .add("iss", consumerId)
                .add("iat", now.getEpochSecond())
                .add("exp", now.plus(tokenLifetime).getEpochSecond())
                .add("jti", UUID.randomUUID().toString())
                .build();

        String signingInput = encode(toJson(header)) + "." + encode(toJson(claims));
        return signingInput + "." + BASE64_URL.encodeToString(sign(signingInput));
    }

    /**
     * A JAX-RS request filter that sets {@code Authorization: Bearer <jwt>} on every outgoing
     * request. A fresh token is created per request, so an expired one is never sent.
     */
    public ClientRequestFilter filter() {
        return new JwtSignerFilter(this);
    }

    private byte[] sign(String signingInput) {
        try {
            Signature signature = Signature.getInstance(algorithm.jcaName);
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to sign JWT with " + algorithm.joseName, e);
        }
    }

    private static String toJson(JsonObject object) {
        StringWriter out = new StringWriter();
        try (var writer = Json.createWriter(out)) {
            writer.writeObject(object);
        }
        return out.toString();
    }

    private static String encode(String value) {
        return BASE64_URL.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** The JOSE algorithms this signer supports, and the JCA signature that produces each. */
    private enum Algorithm {
        // SHA256withECDSAinP1363Format emits the raw R||S pair JOSE requires; plain
        // SHA256withECDSA would emit a DER SEQUENCE, which verifiers reject.
        RS256("RS256", "SHA256withRSA"),
        ES256("ES256", "SHA256withECDSAinP1363Format");

        private final String joseName;
        private final String jcaName;

        Algorithm(String joseName, String jcaName) {
            this.joseName = joseName;
            this.jcaName = jcaName;
        }
    }

    public static final class Builder {

        private String consumerId;
        private PrivateKey privateKey;
        private Duration tokenLifetime = Duration.ofSeconds(60);

        /** Sets the consumer ID used as the JWT {@code iss} claim. */
        public Builder consumerId(String consumerId) {
            if (consumerId == null || consumerId.isBlank()) {
                throw new IllegalArgumentException("consumerId must not be blank");
            }
            this.consumerId = consumerId;
            return this;
        }

        /** Sets the private key used to sign tokens. */
        public Builder privateKey(PrivateKey privateKey) {
            this.privateKey = privateKey;
            return this;
        }

        /** Sets the token lifetime (default: 60 seconds). */
        public Builder tokenLifetime(Duration tokenLifetime) {
            if (tokenLifetime == null || tokenLifetime.isNegative() || tokenLifetime.isZero()) {
                throw new IllegalArgumentException("tokenLifetime must be positive");
            }
            this.tokenLifetime = tokenLifetime;
            return this;
        }

        public JwtSigner build() {
            if (consumerId == null) {
                throw new IllegalStateException("consumerId is required");
            }
            if (privateKey == null) {
                throw new IllegalStateException("privateKey is required");
            }
            return new JwtSigner(consumerId, privateKey, detectAlgorithm(privateKey), tokenLifetime);
        }

        private static Algorithm detectAlgorithm(PrivateKey key) {
            if (key instanceof RSAPrivateKey) {
                return Algorithm.RS256;
            }
            if (key instanceof ECPrivateKey) {
                return Algorithm.ES256;
            }
            throw new IllegalArgumentException(
                    "Unsupported key type: " + key.getClass().getSimpleName()
                            + " (algorithm: " + key.getAlgorithm() + "). Supported: RSA (2048+), EC (P-256)");
        }
    }

    private static final class JwtSignerFilter implements ClientRequestFilter {

        private final JwtSigner signer;

        private JwtSignerFilter(JwtSigner signer) {
            this.signer = signer;
        }

        @Override
        public void filter(ClientRequestContext requestContext) {
            requestContext.getHeaders().putSingle(HttpHeaders.AUTHORIZATION, "Bearer " + signer.createToken());
        }
    }
}
