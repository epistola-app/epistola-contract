// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta;

import app.epistola.client.jakarta.auth.ApiKeyAuth;
import app.epistola.client.jakarta.auth.JwtSigner;
import app.epistola.client.jakarta.identity.ClientIdentity;
import jakarta.ws.rs.Priorities;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

/**
 * Builds Epistola API clients programmatically, for code that is not using CDI injection — a
 * batch job, a test, a {@code @Startup} singleton that assembles its own collaborators.
 *
 * <pre>{@code
 * EpistolaRestClients clients = EpistolaRestClients.builder()
 *     .baseUri("https://epistola.example.com/api")
 *     .identity(ClientIdentity.builder().product("my-app", "1.0.0").build())
 *     .jwtSigner(signer)                       // or .apiKey("epk_...")
 *     .build();
 *
 * TemplatesApi templates = clients.api(TemplatesApi.class);
 * }</pre>
 *
 * <p>Under CDI, prefer {@code @Inject @RestClient TemplatesApi} and configure through
 * {@link EpistolaConfig}; {@code EpistolaRestClientListener} applies the same conventions there.
 * Everything this class does is available on a plain {@link RestClientBuilder} — it exists to make
 * the conventions the default rather than something each consumer re-derives.
 *
 * <p>A client built here ignores the {@link EpistolaConfig} properties entirely, even when they are
 * set: what this builder was told is what it uses.
 */
public final class EpistolaRestClients {

    /**
     * Marks a {@link RestClientBuilder} this class configured, so
     * {@code EpistolaRestClientListener} does not configure it a second time from
     * MicroProfile Config. Programmatic configuration is the more specific of the two, so it wins.
     */
    public static final String CONFIGURED_PROGRAMMATICALLY = "app.epistola.client.jakarta.configured-programmatically";

    private final URI baseUri;
    private final ClientIdentity identity;
    private final ApiKeyAuth apiKeyAuth;
    private final JwtSigner jwtSigner;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    private EpistolaRestClients(Builder builder) {
        this.baseUri = builder.baseUri;
        this.identity = builder.identity;
        this.apiKeyAuth = builder.apiKeyAuth;
        this.jwtSigner = builder.jwtSigner;
        this.connectTimeout = builder.connectTimeout;
        this.readTimeout = builder.readTimeout;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builds a client for one of the generated API interfaces (or
     * {@code GenerationCollectApi}), with the Epistola conventions applied.
     */
    public <T> T api(Class<T> apiInterface) {
        return restClientBuilder().build(apiInterface);
    }

    /**
     * The configured {@link RestClientBuilder}, for the cases this class does not cover — a custom
     * provider, a proxy, an SSL context. Call {@code build(SomeApi.class)} on the result.
     */
    public RestClientBuilder restClientBuilder() {
        RestClientBuilder builder = RestClientBuilder.newBuilder()
                .baseUri(baseUri)
                // Tells EpistolaRestClientListener to leave this builder alone. Without it both
                // would register an identity filter and which one won would come down to
                // same-priority provider ordering, which JAX-RS does not define.
                .property(CONFIGURED_PROGRAMMATICALLY, Boolean.TRUE);
        if (identity != null) {
            builder.register(identity.filter(), Priorities.HEADER_DECORATOR);
        }
        if (jwtSigner != null) {
            builder.register(jwtSigner.filter(), Priorities.AUTHENTICATION);
        } else if (apiKeyAuth != null) {
            builder.register(apiKeyAuth.filter(), Priorities.AUTHENTICATION);
        }
        if (connectTimeout != null) {
            builder.connectTimeout(connectTimeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        if (readTimeout != null) {
            builder.readTimeout(readTimeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        return builder;
    }

    public static final class Builder {

        private URI baseUri;
        private ClientIdentity identity = ClientIdentity.builder().build();
        private ApiKeyAuth apiKeyAuth;
        private JwtSigner jwtSigner;
        private Duration connectTimeout;
        private Duration readTimeout;

        /** The API base URL, including the {@code /api} path segment. Required. */
        public Builder baseUri(URI baseUri) {
            this.baseUri = baseUri;
            return this;
        }

        /** The API base URL, including the {@code /api} path segment. Required. */
        public Builder baseUri(String baseUri) {
            return baseUri(URI.create(baseUri));
        }

        /**
         * The {@code User-Agent} / {@code X-EP-Node-Id} identity. Defaults to
         * {@code epistola-contract/{version}} with the local hostname as the node ID — the headers
         * are mandatory, so there is no "no identity" option.
         */
        public Builder identity(ClientIdentity identity) {
            if (identity == null) {
                throw new IllegalArgumentException("identity must not be null");
            }
            this.identity = identity;
            return this;
        }

        /** Authenticate with a static tenant API key. Mutually exclusive with {@link #jwtSigner}. */
        public Builder apiKey(String apiKey) {
            this.apiKeyAuth = ApiKeyAuth.of(apiKey);
            return this;
        }

        /** Authenticate with self-signed JWTs. Mutually exclusive with {@link #apiKey}. */
        public Builder jwtSigner(JwtSigner jwtSigner) {
            this.jwtSigner = jwtSigner;
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder readTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
            return this;
        }

        public EpistolaRestClients build() {
            if (baseUri == null) {
                throw new IllegalStateException("baseUri is required");
            }
            if (apiKeyAuth != null && jwtSigner != null) {
                throw new IllegalStateException("Configure either apiKey or jwtSigner, not both");
            }
            return new EpistolaRestClients(this);
        }
    }
}
