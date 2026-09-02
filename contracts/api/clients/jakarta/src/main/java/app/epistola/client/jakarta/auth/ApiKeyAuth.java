// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.auth;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;

/**
 * Static API-key authentication.
 *
 * <p>New integrations should send API keys through {@code Authorization: ApiKey <key>}. The legacy
 * {@code X-API-Key} header is still accepted by the API but deprecated.
 *
 * <p>Some Epistola Suite deployments disable API-key authentication entirely; that surfaces as a
 * {@code ProblemDetailException} whose {@code typeSlug} is
 * {@code KnownProblemSlugs.API_KEY_AUTH_DISABLED}.
 */
public final class ApiKeyAuth {

    private final String apiKey;

    private ApiKeyAuth(String apiKey) {
        this.apiKey = apiKey;
    }

    public static ApiKeyAuth of(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        return new ApiKeyAuth(apiKey.trim());
    }

    /** A JAX-RS request filter that sets {@code Authorization: ApiKey <key>} on every request. */
    public ClientRequestFilter filter() {
        return new ApiKeyAuthFilter(apiKey);
    }

    private static final class ApiKeyAuthFilter implements ClientRequestFilter {

        private final String apiKey;

        private ApiKeyAuthFilter(String apiKey) {
            this.apiKey = apiKey;
        }

        @Override
        public void filter(ClientRequestContext requestContext) {
            requestContext.getHeaders().putSingle(HttpHeaders.AUTHORIZATION, "ApiKey " + apiKey);
        }
    }
}
