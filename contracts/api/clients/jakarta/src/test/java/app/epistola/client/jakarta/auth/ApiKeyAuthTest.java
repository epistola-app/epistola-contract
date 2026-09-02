// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.epistola.client.jakarta.EpistolaRestClients;
import app.epistola.client.jakarta.StubServer;
import app.epistola.client.jakarta.api.SystemApi;
import app.epistola.client.jakarta.model.PingRequest;
import org.junit.jupiter.api.Test;

class ApiKeyAuthTest {

    private static final String PONG =
            "{\"status\":\"UP\",\"timestamp\":\"2026-09-02T10:00:00Z\"}";

    @Test
    void the_key_is_sent_as_an_authorization_scheme_not_the_legacy_header() {
        try (StubServer stub = StubServer.start(
                request -> StubServer.StubResponse.of(200, "application/vnd.epistola.v1+json", PONG))) {

            EpistolaRestClients.builder()
                    .baseUri(stub.baseUri())
                    .apiKey("epk_live_abc123")
                    .build()
                    .api(SystemApi.class)
                    .ping(new PingRequest());

            StubServer.RecordedRequest request = stub.onlyRequest();
            assertEquals("ApiKey epk_live_abc123", request.header("Authorization"));
            assertEquals(null, request.header("X-API-Key"), "the legacy header is deprecated and not sent");
        }
    }

    @Test
    void surrounding_whitespace_is_trimmed_so_a_pasted_secret_still_works() {
        try (StubServer stub = StubServer.start(
                request -> StubServer.StubResponse.of(200, "application/vnd.epistola.v1+json", PONG))) {

            EpistolaRestClients.builder()
                    .baseUri(stub.baseUri())
                    .apiKey("  epk_live_abc123\n")
                    .build()
                    .api(SystemApi.class)
                    .ping(new PingRequest());

            assertEquals("ApiKey epk_live_abc123", stub.onlyRequest().header("Authorization"));
        }
    }

    @Test
    void a_blank_key_is_rejected_at_construction_rather_than_at_the_first_401() {
        assertThrows(IllegalArgumentException.class, () -> ApiKeyAuth.of(null));
        assertThrows(IllegalArgumentException.class, () -> ApiKeyAuth.of(""));
        assertThrows(IllegalArgumentException.class, () -> ApiKeyAuth.of("   "));
    }
}
