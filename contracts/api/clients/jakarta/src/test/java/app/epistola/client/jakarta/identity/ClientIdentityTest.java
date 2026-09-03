// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.epistola.client.jakarta.EpistolaRestClients;
import app.epistola.client.jakarta.StubServer;
import app.epistola.client.jakarta.api.SystemApi;
import app.epistola.client.jakarta.model.PingRequest;
import org.junit.jupiter.api.Test;

class ClientIdentityTest {

    @Test
    void the_header_name_and_product_token_come_from_the_contract_registry() {
        // Generated from the spec's x-client-identity extension, as are the Kotlin client's and
        // the server module's. Pinning the literals means a registry change shows up as a
        // deliberate test edit rather than a silent change to what every deployed client sends.
        assertEquals("X-EP-Node-Id", ClientIdentity.HEADER_NODE_ID);
        assertEquals("epistola-contract", ContractIdentity.CONTRACT_PRODUCT);
        assertEquals(" ", ContractIdentity.PRODUCT_SEPARATOR);
        assertEquals("/", ContractIdentity.VERSION_SEPARATOR);
    }

    @Test
    void the_user_agent_starts_with_the_contract_token() {
        ClientIdentity identity = ClientIdentity.builder().nodeId("node-1").build();

        assertEquals("epistola-contract/" + ClientIdentity.contractVersion(), identity.getUserAgent());
        assertEquals("node-1", identity.getNodeId());
    }

    @Test
    void the_contract_version_comes_from_the_spec_not_a_placeholder() {
        String version = ClientIdentity.contractVersion();

        assertNotNull(version);
        assertFalse("unknown".equals(version), "the build should have written epistola-contract-version.txt");
        assertTrue(
                version.matches("\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?"),
                "expected a spec version, got: " + version);
    }

    @Test
    void products_are_appended_in_the_order_they_were_added() {
        ClientIdentity identity = ClientIdentity.builder()
                .nodeId("pod-7")
                .product("zaakafhandelcomponent", "3.4.0")
                .product("gzac", "5.0.0")
                .build();

        assertEquals(
                "epistola-contract/" + ClientIdentity.contractVersion() + " zaakafhandelcomponent/3.4.0 gzac/5.0.0",
                identity.getUserAgent());
    }

    @Test
    void the_node_id_defaults_to_the_hostname() {
        ClientIdentity identity = ClientIdentity.builder().build();

        assertNotNull(identity.getNodeId());
        assertFalse(identity.getNodeId().isBlank(), "X-EP-Node-Id is required, so it must never be blank");
    }

    @Test
    void malformed_product_tokens_are_rejected_where_they_are_added() {
        ClientIdentity.Builder builder = ClientIdentity.builder();

        assertThrows(IllegalArgumentException.class, () -> builder.product("", "1.0"));
        assertThrows(IllegalArgumentException.class, () -> builder.product("app", ""));
        assertThrows(IllegalArgumentException.class, () -> builder.product("my app", "1.0"));
        assertThrows(IllegalArgumentException.class, () -> builder.product("my/app", "1.0"));
    }

    @Test
    void both_identity_headers_reach_the_server() {
        try (StubServer stub = StubServer.start(request ->
                StubServer.StubResponse.of(
                        200, "application/vnd.epistola.v1+json", "{\"status\":\"UP\",\"timestamp\":\"2026-09-02T10:00:00Z\"}"))) {

            SystemApi system = EpistolaRestClients.builder()
                    .baseUri(stub.baseUri())
                    .identity(ClientIdentity.builder().nodeId("pod-7").product("my-app", "1.0.0").build())
                    .build()
                    .api(SystemApi.class);

            system.ping(new PingRequest().name("my-app"));

            StubServer.RecordedRequest request = stub.onlyRequest();
            assertEquals(
                    "epistola-contract/" + ClientIdentity.contractVersion() + " my-app/1.0.0",
                    request.header("User-Agent"));
            assertEquals("pod-7", request.header(ClientIdentity.HEADER_NODE_ID));
        }
    }
}
