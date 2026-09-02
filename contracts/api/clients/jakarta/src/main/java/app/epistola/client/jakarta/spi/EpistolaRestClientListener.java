// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.spi;

import app.epistola.client.jakarta.EpistolaConfig;
import app.epistola.client.jakarta.EpistolaRestClients;
import jakarta.ws.rs.Priorities;
import java.util.Set;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.eclipse.microprofile.rest.client.spi.RestClientListener;

/**
 * Applies the Epistola client conventions to every Epistola rest client the application builds —
 * the {@code User-Agent} and {@code X-EP-Node-Id} headers the contract requires, and whichever
 * authentication the configuration names.
 *
 * <p>Registered through {@code META-INF/services}, so
 * {@code @Inject @RestClient GenerationApi api} yields a fully configured client with no wiring
 * code. The exception mapper does not come from here: the generated interfaces carry
 * {@code @RegisterProvider(ApiExceptionMapper.class)} themselves.
 *
 * <p>Two deliberate limits:
 * <ul>
 *   <li>It touches <em>only</em> the rest-client interfaces this library ships. This listener runs
 *       for every rest client in the deployment, and stamping Epistola credentials onto an
 *       application's other outbound calls would be a security bug, not a convenience.</li>
 *   <li>It registers an authentication filter only when one is configured. An application that
 *       authenticates through a filter of its own configures nothing here and keeps control.</li>
 * </ul>
 *
 * <p>A builder that {@code EpistolaRestClients} configured is skipped entirely: the two routes are
 * alternatives, and the explicit one wins.
 */
public class EpistolaRestClientListener implements RestClientListener {

    /**
     * The two packages that hold rest-client interfaces this library ships: the generated APIs and
     * the hand-written collect endpoint. Matched exactly rather than by prefix — a prefix would
     * also claim anything a consumer happened to put under {@code app.epistola.client.jakarta}.
     */
    private static final Set<String> EPISTOLA_CLIENT_PACKAGES =
            Set.of("app.epistola.client.jakarta.api", "app.epistola.client.jakarta.collect");

    @Override
    public void onNewClient(Class<?> serviceInterface, RestClientBuilder builder) {
        if (serviceInterface == null
                || serviceInterface.getPackage() == null
                || !EPISTOLA_CLIENT_PACKAGES.contains(serviceInterface.getPackage().getName())) {
            return;
        }
        if (Boolean.TRUE.equals(
                builder.getConfiguration().getProperty(EpistolaRestClients.CONFIGURED_PROGRAMMATICALLY))) {
            // EpistolaRestClients already configured this builder from what the caller passed it.
            // Adding a second identity or auth filter here would leave which one wins to
            // same-priority provider ordering, which JAX-RS does not define.
            return;
        }

        Config config;
        try {
            config = ConfigProvider.getConfig();
        } catch (IllegalStateException noConfigImplementation) {
            // No MicroProfile Config on this runtime — a plain Java SE process using RESTEasy's
            // client directly, for instance. There is nothing to read, so there is nothing to
            // apply; EpistolaRestClients is the route for that case. Never fail client creation.
            return;
        }

        // Identity is registered ahead of authentication so a failure to build the auth filter
        // still leaves a request that identifies itself in the server's logs.
        builder.register(EpistolaConfig.identity(config).filter(), Priorities.HEADER_DECORATOR);
        EpistolaConfig.authFilter(config).ifPresent(filter -> builder.register(filter, Priorities.AUTHENTICATION));
    }
}
