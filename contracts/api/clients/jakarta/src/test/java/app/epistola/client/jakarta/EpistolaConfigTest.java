// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.epistola.client.jakarta.identity.ClientIdentity;
import io.smallrye.config.SmallRyeConfigBuilder;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The MicroProfile Config route is how an application server consumer configures this client, so
 * the property names and the failure modes are part of the contract with them.
 */
class EpistolaConfigTest {

    @Test
    void the_identity_defaults_when_nothing_is_configured() {
        ClientIdentity identity = EpistolaConfig.identity(config(Map.of()));

        assertEquals("epistola-contract/" + ClientIdentity.contractVersion(), identity.getUserAgent());
        assertTrue(identity.getNodeId() != null && !identity.getNodeId().isBlank());
    }

    @Test
    void the_node_id_and_product_tokens_are_read_from_config() {
        ClientIdentity identity = EpistolaConfig.identity(config(Map.of(
                EpistolaConfig.NODE_ID, "pod-42",
                EpistolaConfig.USER_AGENT_PRODUCTS, "zaakafhandelcomponent/3.4.0 gzac/5.0.0")));

        assertEquals("pod-42", identity.getNodeId());
        assertEquals(
                "epistola-contract/" + ClientIdentity.contractVersion() + " zaakafhandelcomponent/3.4.0 gzac/5.0.0",
                identity.getUserAgent());
    }

    @Test
    void a_product_token_without_a_version_is_rejected_by_name() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> EpistolaConfig.identity(config(Map.of(EpistolaConfig.USER_AGENT_PRODUCTS, "zac"))));

        assertTrue(e.getMessage().contains(EpistolaConfig.USER_AGENT_PRODUCTS), e.getMessage());
    }

    @Test
    void no_authentication_configured_means_no_filter() {
        assertEquals(Optional.empty(), EpistolaConfig.authFilter(config(Map.of())));
    }

    @Test
    void an_api_key_produces_an_api_key_authorization_header() {
        ClientRequestFilter filter = EpistolaConfig.authFilter(
                        config(Map.of(EpistolaConfig.API_KEY, "epk_live_abc")))
                .orElseThrow();

        assertEquals("ApiKey epk_live_abc", headerAfter(filter, "Authorization"));
    }

    @Test
    void a_jwt_identity_produces_a_bearer_header(@TempDir Path tempDir) throws Exception {
        Path pem = tempDir.resolve("private.pem");
        Files.writeString(pem, toPem(rsaKey()));

        ClientRequestFilter filter = EpistolaConfig.authFilter(config(Map.of(
                        EpistolaConfig.JWT_CONSUMER_ID, "invoice-service",
                        EpistolaConfig.JWT_PRIVATE_KEY_PATH, pem.toString())))
                .orElseThrow();

        assertTrue(headerAfter(filter, "Authorization").startsWith("Bearer "));
    }

    @Test
    void an_inline_pem_key_works_too() throws Exception {
        ClientRequestFilter filter = EpistolaConfig.authFilter(config(Map.of(
                        EpistolaConfig.JWT_CONSUMER_ID, "invoice-service",
                        EpistolaConfig.JWT_PRIVATE_KEY, toPem(rsaKey()))))
                .orElseThrow();

        assertTrue(headerAfter(filter, "Authorization").startsWith("Bearer "));
    }

    @Test
    void configuring_both_authentication_schemes_fails_loudly() {
        IllegalStateException e = assertThrows(
                IllegalStateException.class,
                () -> EpistolaConfig.authFilter(config(Map.of(
                        EpistolaConfig.API_KEY, "epk_live_abc",
                        EpistolaConfig.JWT_CONSUMER_ID, "invoice-service"))));

        assertTrue(e.getMessage().contains("choose one"), e.getMessage());
    }

    @Test
    void a_jwt_identity_with_no_key_or_two_keys_fails_loudly() throws Exception {
        assertThrows(
                IllegalStateException.class,
                () -> EpistolaConfig.authFilter(
                        config(Map.of(EpistolaConfig.JWT_CONSUMER_ID, "invoice-service"))));

        assertThrows(
                IllegalStateException.class,
                () -> EpistolaConfig.authFilter(config(Map.of(
                        EpistolaConfig.JWT_CONSUMER_ID, "invoice-service",
                        EpistolaConfig.JWT_PRIVATE_KEY, toPem(rsaKey()),
                        EpistolaConfig.JWT_PRIVATE_KEY_PATH, "/tmp/does-not-matter.pem"))));
    }

    private static Config config(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withSources(new io.smallrye.config.PropertiesConfigSource(
                        new HashMap<>(properties), "test", 500))
                .build();
    }

    /** Runs the filter over an empty header map and reports what it set. */
    private static String headerAfter(ClientRequestFilter filter, String header) {
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        ClientRequestContext context = (ClientRequestContext) Proxy.newProxyInstance(
                ClientRequestContext.class.getClassLoader(),
                new Class<?>[] {ClientRequestContext.class},
                (proxy, method, args) -> {
                    if ("getHeaders".equals(method.getName())) {
                        return headers;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        try {
            filter.filter(context);
        } catch (Exception e) {
            throw new AssertionError("the filter should not fail", e);
        }
        Object value = headers.getFirst(header);
        return value == null ? null : value.toString();
    }

    private static PrivateKey rsaKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair().getPrivate();
    }

    private static String toPem(PrivateKey key) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(key.getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }
}
