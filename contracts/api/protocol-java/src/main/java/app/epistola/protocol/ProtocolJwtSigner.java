// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.protocol;

import java.io.IOException;
import java.io.UncheckedIOException;
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
 * Mints the short-lived self-signed JWTs Epistola accepts for consumer authentication.
 *
 * <p>Each token carries:
 * <ul>
 *   <li>{@code iss} — the consumer ID</li>
 *   <li>{@code iat} — issued-at</li>
 *   <li>{@code exp} — {@code iat} plus the token lifetime</li>
 *   <li>{@code jti} — a fresh UUID per token, for replay protection</li>
 * </ul>
 *
 * <p>Signed with the JDK's own {@code java.security} primitives — RS256 for RSA keys, ES256 for
 * EC P-256 — and with the four fixed claims written out directly, so this needs no JOSE library
 * and no JSON library. That is what lets it be shared: the Jakarta client must not put weight in a
 * WAR, and the Spring client no longer publishes a JOSE dependency for four claims and one
 * signature.
 *
 * <p>Each client wraps this in whatever its HTTP stack wants — a Spring
 * {@code ClientHttpRequestInterceptor}, a JAX-RS {@code ClientRequestFilter} — and calls
 * {@link #createToken()} per request so an expired token is never sent.
 */
public final class ProtocolJwtSigner {

    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private final String consumerId;
    private final PrivateKey privateKey;
    private final Algorithm algorithm;
    private final Duration tokenLifetime;

    private ProtocolJwtSigner(String consumerId, PrivateKey privateKey, Algorithm algorithm, Duration tokenLifetime) {
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
     * Parses a PEM-encoded private key. Supports RSA and EC (P-256) keys in PKCS#8 format
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

    /** The JOSE algorithm this signer uses, decided by the key type. */
    public String algorithm() {
        return algorithm.joseName;
    }

    /** Creates a signed JWT with a fresh {@code iat}, {@code exp} and {@code jti}. */
    public String createToken() {
        Instant now = Instant.now();
        String header = "{\"alg\":\"" + algorithm.joseName + "\",\"typ\":\"JWT\"}";
        String claims = "{\"iss\":\"" + escapeJson(consumerId) + "\""
                + ",\"iat\":" + now.getEpochSecond()
                + ",\"exp\":" + now.plus(tokenLifetime).getEpochSecond()
                + ",\"jti\":\"" + UUID.randomUUID() + "\"}";

        String signingInput = encode(header) + "." + encode(claims);
        return signingInput + "." + BASE64_URL.encodeToString(sign(signingInput));
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

    private static String encode(String value) {
        return BASE64_URL.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Escapes a JSON string body. Only {@code iss} needs it — the other claims are numbers, a UUID,
     * and fixed algorithm names — but a consumer ID is configuration, so it gets escaped properly
     * rather than assumed safe.
     */
    static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    /** The JOSE algorithms supported, and the JCA signature that produces each. */
    private enum Algorithm {
        // SHA256withECDSAinP1363Format emits the raw R||S pair JOSE requires; plain SHA256withECDSA
        // would emit a DER SEQUENCE, which every compliant verifier rejects.
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

        /** The consumer ID used as the JWT {@code iss} claim. */
        public Builder consumerId(String consumerId) {
            if (consumerId == null || consumerId.isBlank()) {
                throw new IllegalArgumentException("consumerId must not be blank");
            }
            this.consumerId = consumerId;
            return this;
        }

        /** The private key used to sign tokens. */
        public Builder privateKey(PrivateKey privateKey) {
            this.privateKey = privateKey;
            return this;
        }

        /** Token lifetime (default: 60 seconds). */
        public Builder tokenLifetime(Duration tokenLifetime) {
            if (tokenLifetime == null || tokenLifetime.isNegative() || tokenLifetime.isZero()) {
                throw new IllegalArgumentException("tokenLifetime must be positive");
            }
            this.tokenLifetime = tokenLifetime;
            return this;
        }

        public ProtocolJwtSigner build() {
            // IllegalArgumentException, not IllegalState: this is the behaviour the released Spring
            // client has, and the rest of protocol-java validates the same way. The Jakarta client
            // threw IllegalState here, which is the copy nobody has depended on yet.
            if (consumerId == null) {
                throw new IllegalArgumentException("consumerId is required");
            }
            if (privateKey == null) {
                throw new IllegalArgumentException("privateKey is required");
            }
            return new ProtocolJwtSigner(consumerId, privateKey, detectAlgorithm(privateKey), tokenLifetime);
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
}
