// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.epistola.client.jakarta.EpistolaConfig;
import app.epistola.client.jakarta.EpistolaRestClients;
import app.epistola.client.jakarta.StubServer;
import app.epistola.client.jakarta.api.SystemApi;
import app.epistola.client.jakarta.identity.ClientIdentity;
import app.epistola.client.jakarta.model.PingRequest;
import com.example.someservice.SomeOtherServiceApi;
import java.net.URI;
import java.util.Map;
import java.util.function.Consumer;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.junit.jupiter.api.Test;

/**
 * The listener is what makes {@code @Inject @RestClient} work with configuration alone, and it runs
 * for <em>every</em> rest client in the deployment — so what it does and, more importantly, what it
 * leaves alone are both worth pinning down.
 *
 * <p>Configuration reaches it through {@code ConfigProvider}, which reads system properties, so
 * these tests set and clear them around each call rather than building a {@code Config} by hand.
 */
class EpistolaRestClientListenerTest {

    private static final String PONG = "{\"status\":\"UP\",\"timestamp\":\"2026-09-02T10:00:00Z\"}";

    @Test
    void configured_identity_and_authentication_reach_an_injected_style_client() {
        withConfig(
                Map.of(
                        EpistolaConfig.NODE_ID, "pod-42",
                        EpistolaConfig.USER_AGENT_PRODUCTS, "zaakafhandelcomponent/3.4.0",
                        EpistolaConfig.API_KEY, "epk_from_config"),
                request -> {
                    assertEquals("pod-42", request.header(ClientIdentity.HEADER_NODE_ID));
                    assertEquals("ApiKey epk_from_config", request.header("Authorization"));
                    assertTrue(
                            request.header("User-Agent").endsWith("zaakafhandelcomponent/3.4.0"),
                            request.header("User-Agent"));
                });
    }

    @Test
    void with_nothing_configured_the_mandatory_identity_headers_are_still_sent() {
        withConfig(Map.of(), request -> {
            assertNotNull(request.header("User-Agent"));
            assertTrue(request.header("User-Agent").startsWith("epistola-contract/"));
            assertNotNull(request.header(ClientIdentity.HEADER_NODE_ID));
            assertNull(request.header("Authorization"), "no credentials configured means no Authorization header");
        });
    }

    @Test
    void a_programmatically_configured_builder_is_left_alone() {
        // Both routes registering an identity filter would leave which one wins to same-priority
        // provider ordering, which JAX-RS does not define. The explicit one must win.
        withConfig(
                Map.of(
                        EpistolaConfig.NODE_ID, "pod-from-config",
                        EpistolaConfig.API_KEY, "epk_from_config"),
                stub -> {},
                (baseUri, call) -> {
                    SystemApi api = EpistolaRestClients.builder()
                            .baseUri(baseUri)
                            .identity(ClientIdentity.builder().nodeId("pod-from-code").build())
                            .apiKey("epk_from_code")
                            .build()
                            .api(SystemApi.class);
                    call.accept(api);
                },
                request -> {
                    assertEquals("pod-from-code", request.header(ClientIdentity.HEADER_NODE_ID));
                    assertEquals("ApiKey epk_from_code", request.header("Authorization"));
                });
    }

    @Test
    void a_rest_client_that_is_not_an_epistola_interface_is_untouched() {
        // Stamping Epistola credentials onto an application's other outbound calls would be a
        // security bug, not a convenience.
        System.setProperty(EpistolaConfig.API_KEY, "epk_secret");
        try (StubServer stub = StubServer.start(request -> StubServer.StubResponse.of(200, "text/plain", "ok"))) {
            SomeOtherServiceApi other = RestClientBuilder.newBuilder()
                    .baseUri(stub.baseUri())
                    .build(SomeOtherServiceApi.class);

            other.hello();

            StubServer.RecordedRequest request = stub.onlyRequest();
            assertNull(request.header("Authorization"), "the Epistola API key must not leak to other services");
            assertNull(request.header(ClientIdentity.HEADER_NODE_ID));
        } finally {
            System.clearProperty(EpistolaConfig.API_KEY);
        }
    }

    private void withConfig(Map<String, String> properties, Consumer<StubServer.RecordedRequest> assertions) {
        withConfig(
                properties,
                stub -> {},
                (baseUri, call) -> call.accept(
                        RestClientBuilder.newBuilder().baseUri(baseUri).build(SystemApi.class)),
                assertions);
    }

    private void withConfig(
            Map<String, String> properties,
            Consumer<StubServer> setUp,
            java.util.function.BiConsumer<URI, Consumer<SystemApi>> buildClient,
            Consumer<StubServer.RecordedRequest> assertions) {

        properties.forEach(System::setProperty);
        try (StubServer stub = StubServer.start(
                request -> StubServer.StubResponse.of(200, "application/vnd.epistola.v1+json", PONG))) {
            setUp.accept(stub);
            buildClient.accept(stub.baseUri(), api -> api.ping(new PingRequest()));
            assertions.accept(stub.onlyRequest());
        } finally {
            properties.keySet().forEach(System::clearProperty);
        }
    }
}
