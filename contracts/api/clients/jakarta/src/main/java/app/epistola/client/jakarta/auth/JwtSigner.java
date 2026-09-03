// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.auth;

import app.epistola.protocol.ProtocolJwtSigner;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.time.Duration;

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
 * <p>The signing itself is {@link ProtocolJwtSigner}, shared with the Kotlin client: the JDK's own
 * {@code java.security} primitives, no JOSE library. This class is the JAX-RS face of it.
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

    private final ProtocolJwtSigner signer;

    private JwtSigner(ProtocolJwtSigner signer) {
        this.signer = signer;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Loads a private key from a PEM file. Supports RSA and EC (P-256) keys in PKCS#8 format
     * ({@code BEGIN PRIVATE KEY}).
     */
    public static PrivateKey loadPrivateKey(Path path) {
        return ProtocolJwtSigner.loadPrivateKey(path);
    }

    /**
     * Parses a PEM-encoded private key string. Supports RSA and EC (P-256) keys in PKCS#8 format
     * ({@code BEGIN PRIVATE KEY}).
     */
    public static PrivateKey parsePrivateKeyPem(String pem) {
        return ProtocolJwtSigner.parsePrivateKeyPem(pem);
    }

    /** Creates a signed JWT with a fresh {@code iat}, {@code exp} and {@code jti}. */
    public String createToken() {
        return signer.createToken();
    }

    /**
     * A JAX-RS request filter that sets {@code Authorization: Bearer <jwt>} on every outgoing
     * request. A fresh token is created per request, so an expired one is never sent.
     */
    public ClientRequestFilter filter() {
        return new JwtSignerFilter(this);
    }

    public static final class Builder {

        private final ProtocolJwtSigner.Builder delegate = ProtocolJwtSigner.builder();

        /** Sets the consumer ID used as the JWT {@code iss} claim. */
        public Builder consumerId(String consumerId) {
            delegate.consumerId(consumerId);
            return this;
        }

        /** Sets the private key used to sign tokens. */
        public Builder privateKey(PrivateKey privateKey) {
            delegate.privateKey(privateKey);
            return this;
        }

        /** Sets the token lifetime (default: 60 seconds). */
        public Builder tokenLifetime(Duration tokenLifetime) {
            delegate.tokenLifetime(tokenLifetime);
            return this;
        }

        public JwtSigner build() {
            return new JwtSigner(delegate.build());
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
