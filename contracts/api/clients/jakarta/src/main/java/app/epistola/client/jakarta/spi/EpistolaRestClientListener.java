// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.spi;

import app.epistola.client.jakarta.EpistolaConfig;
import jakarta.ws.rs.Priorities;
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
 *   <li>It touches <em>only</em> interfaces in {@code app.epistola.client.jakarta}. This listener
 *       runs for every rest client in the deployment, and stamping Epistola credentials onto an
 *       application's other outbound calls would be a security bug, not a convenience.</li>
 *   <li>It registers an authentication filter only when one is configured. An application that
 *       authenticates through a filter of its own configures nothing here and keeps control.</li>
 * </ul>
 *
 * <p>Registering the same filters again through {@code EpistolaRestClients} is harmless — each one
 * sets its header with {@code putSingle} — but it is redundant; pick one route.
 */
public class EpistolaRestClientListener implements RestClientListener {

    private static final String EPISTOLA_CLIENT_PACKAGE = "app.epistola.client.jakarta.";

    @Override
    public void onNewClient(Class<?> serviceInterface, RestClientBuilder builder) {
        if (serviceInterface == null || !serviceInterface.getName().startsWith(EPISTOLA_CLIENT_PACKAGE)) {
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
