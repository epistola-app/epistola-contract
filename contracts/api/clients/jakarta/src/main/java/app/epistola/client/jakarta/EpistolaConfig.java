// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta;

import app.epistola.client.jakarta.auth.ApiKeyAuth;
import app.epistola.client.jakarta.auth.JwtSigner;
import app.epistola.client.jakarta.identity.ClientIdentity;
import jakarta.ws.rs.client.ClientRequestFilter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;

/**
 * The MicroProfile Config properties that configure an injected Epistola client, and the helpers
 * that turn them into filters.
 *
 * <p>The base URL comes from MicroProfile Rest Client itself
 * ({@code <fully.qualified.InterfaceName>/mp-rest/url}). Everything Epistola-specific is read from
 * the properties below, so an application on WildFly, Open Liberty, Payara or Quarkus configures
 * this client the way it configures everything else — environment variables, a
 * {@code microprofile-config.properties}, or a config source of its own.
 *
 * <pre>
 * # Where the API lives (MicroProfile Rest Client's own property)
 * app.epistola.client.jakarta.api.GenerationApi/mp-rest/url=https://epistola.example.com/api
 *
 * # Identity — required on every request; node-id defaults to the hostname
 * epistola.client.node-id=${HOSTNAME}
 * epistola.client.user-agent.products=zaakafhandelcomponent/3.4.0
 *
 * # Authentication — an API key, or a self-signed JWT; not both
 * epistola.client.api-key=epk_...
 * epistola.client.jwt.consumer-id=invoice-service
 * epistola.client.jwt.private-key-path=/run/secrets/epistola-key.pem
 * epistola.client.jwt.token-lifetime=PT60S
 * </pre>
 *
 * <p>{@code EpistolaRestClientListener} applies these automatically to every generated Epistola
 * interface. Nothing is applied to any other rest client in the deployment.
 */
public final class EpistolaConfig {

    /** Node identifier for the {@code X-EP-Node-Id} header. Defaults to the local hostname. */
    public static final String NODE_ID = "epistola.client.node-id";

    /**
     * Extra {@code User-Agent} product tokens, space-separated, each {@code name/version}, e.g.
     * {@code "zaakafhandelcomponent/3.4.0 gzac/5.0.0"}. Appended after
     * {@code epistola-contract/{version}}.
     */
    public static final String USER_AGENT_PRODUCTS = "epistola.client.user-agent.products";

    /** Static tenant API key, sent as {@code Authorization: ApiKey <key>}. */
    public static final String API_KEY = "epistola.client.api-key";

    /** Consumer ID used as the {@code iss} claim of self-signed JWTs. */
    public static final String JWT_CONSUMER_ID = "epistola.client.jwt.consumer-id";

    /** PKCS#8 PEM private key, inline. Mutually exclusive with {@link #JWT_PRIVATE_KEY_PATH}. */
    public static final String JWT_PRIVATE_KEY = "epistola.client.jwt.private-key";

    /** Path to a PKCS#8 PEM private key file — the usual choice with a mounted secret. */
    public static final String JWT_PRIVATE_KEY_PATH = "epistola.client.jwt.private-key-path";

    /** JWT lifetime as an ISO-8601 duration (default {@code PT60S}). */
    public static final String JWT_TOKEN_LIFETIME = "epistola.client.jwt.token-lifetime";

    /**
     * Builds the identity from {@link #NODE_ID} and {@link #USER_AGENT_PRODUCTS}. Always succeeds:
     * the headers are required on every request, so there is nothing to opt out of.
     */
    public static ClientIdentity identity(Config config) {
        ClientIdentity.Builder builder = ClientIdentity.builder();
        config.getOptionalValue(NODE_ID, String.class)
                .filter(nodeId -> !nodeId.isBlank())
                .ifPresent(builder::nodeId);
        config.getOptionalValue(USER_AGENT_PRODUCTS, String.class)
                .filter(products -> !products.isBlank())
                .ifPresent(products -> {
                    for (String token : products.trim().split("\\s+")) {
                        int slash = token.lastIndexOf('/');
                        if (slash <= 0 || slash == token.length() - 1) {
                            throw new IllegalArgumentException(
                                    USER_AGENT_PRODUCTS + " entries must be 'name/version', got: " + token);
                        }
                        builder.product(token.substring(0, slash), token.substring(slash + 1));
                    }
                });
        return builder.build();
    }

    /**
     * The authentication filter the configuration asks for, or empty when none is configured (an
     * application may authenticate through a filter of its own).
     *
     * @throws IllegalStateException when both an API key and a JWT identity are configured — the
     *                               two cannot both own the {@code Authorization} header, and
     *                               silently preferring one would hide a deployment mistake
     */
    public static Optional<ClientRequestFilter> authFilter(Config config) {
        Optional<String> apiKey = config.getOptionalValue(API_KEY, String.class).filter(key -> !key.isBlank());
        Optional<String> consumerId =
                config.getOptionalValue(JWT_CONSUMER_ID, String.class).filter(id -> !id.isBlank());

        if (apiKey.isPresent() && consumerId.isPresent()) {
            throw new IllegalStateException(
                    "Both " + API_KEY + " and " + JWT_CONSUMER_ID + " are configured; choose one");
        }
        if (apiKey.isPresent()) {
            return Optional.of(ApiKeyAuth.of(apiKey.get()).filter());
        }
        if (consumerId.isPresent()) {
            return Optional.of(jwtSigner(config, consumerId.get()).filter());
        }
        return Optional.empty();
    }

    private static JwtSigner jwtSigner(Config config, String consumerId) {
        Optional<String> inlineKey =
                config.getOptionalValue(JWT_PRIVATE_KEY, String.class).filter(pem -> !pem.isBlank());
        Optional<String> keyPath =
                config.getOptionalValue(JWT_PRIVATE_KEY_PATH, String.class).filter(path -> !path.isBlank());

        if (inlineKey.isPresent() == keyPath.isPresent()) {
            throw new IllegalStateException(
                    "Exactly one of " + JWT_PRIVATE_KEY + " or " + JWT_PRIVATE_KEY_PATH
                            + " must be set when " + JWT_CONSUMER_ID + " is configured");
        }

        JwtSigner.Builder builder = JwtSigner.builder()
                .consumerId(consumerId)
                .privateKey(inlineKey
                        .map(JwtSigner::parsePrivateKeyPem)
                        .orElseGet(() -> JwtSigner.loadPrivateKey(Path.of(keyPath.get()))));
        config.getOptionalValue(JWT_TOKEN_LIFETIME, String.class)
                .filter(value -> !value.isBlank())
                .ifPresent(value -> builder.tokenLifetime(Duration.parse(value)));
        return builder.build();
    }

    private EpistolaConfig() {
    }
}
