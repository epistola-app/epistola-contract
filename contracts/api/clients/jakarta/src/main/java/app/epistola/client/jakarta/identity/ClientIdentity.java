// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.identity;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the client identity headers ({@code User-Agent} and {@code X-EP-Node-Id}) the contract
 * requires on every Epistola API request.
 *
 * <p>The {@code User-Agent} always starts with {@code epistola-contract/{contractVersion}}.
 * Additional product tokens can be added to describe the full software stack.
 *
 * <pre>{@code
 * ClientIdentity identity = ClientIdentity.builder()
 *     .nodeId("my-pod-123")
 *     .product("zaakafhandelcomponent", "3.4.0")
 *     .build();
 *
 * GenerationApi api = RestClientBuilder.newBuilder()
 *     .baseUri(URI.create("https://epistola.example.com/api"))
 *     .register(identity.filter())
 *     .build(GenerationApi.class);
 * }</pre>
 *
 * Produces headers:
 * <pre>
 * User-Agent: epistola-contract/1.1.0 zaakafhandelcomponent/3.4.0
 * X-EP-Node-Id: my-pod-123
 * </pre>
 */
public final class ClientIdentity {

    /**
     * Header carrying the node identifier the contract requires, from the spec's
     * {@code x-client-identity} registry.
     */
    public static final String HEADER_NODE_ID = ContractIdentity.NODE_ID_HEADER;

    static final String CONTRACT_PRODUCT = ContractIdentity.CONTRACT_PRODUCT;

    private static final String CONTRACT_VERSION_RESOURCE = "/epistola-contract-version.txt";

    private static final class VersionHolder {
        private static final String VALUE = readContractVersion();

        private static String readContractVersion() {
            try (var stream = ClientIdentity.class.getResourceAsStream(CONTRACT_VERSION_RESOURCE)) {
                if (stream == null) {
                    return "unknown";
                }
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                return "unknown";
            }
        }
    }

    private final String userAgent;
    private final String nodeId;

    private ClientIdentity(String userAgent, String nodeId) {
        this.userAgent = userAgent;
        this.nodeId = nodeId;
    }

    /**
     * The contract version this client library was built against, read from the bundled
     * {@code epistola-contract-version.txt} resource the build writes from the spec.
     */
    public static String contractVersion() {
        return VersionHolder.VALUE;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getNodeId() {
        return nodeId;
    }

    /**
     * A JAX-RS request filter that sets {@code User-Agent} and {@code X-EP-Node-Id} on every
     * outgoing request. Register it on a {@code RestClientBuilder}, or let
     * {@code EpistolaRestClientListener} register it for you.
     */
    public ClientRequestFilter filter() {
        return new ClientIdentityFilter(this);
    }

    /** Builds a {@link ClientIdentity}; every product token is validated as it is added. */
    public static final class Builder {

        private final List<String> products = new ArrayList<>();
        private String nodeId;

        /**
         * Sets the node identifier (Kubernetes pod name, container ID, hostname). Defaults to the
         * local hostname.
         */
        public Builder nodeId(String nodeId) {
            // A blank node id would send the mandatory header empty, which is worse than the
            // hostname default it would be replacing.
            if (nodeId != null && nodeId.isBlank()) {
                throw new IllegalArgumentException("nodeId must not be blank");
            }
            this.nodeId = nodeId;
            return this;
        }

        /**
         * Adds a product/version pair to the {@code User-Agent}. Products appear in the order they
         * are added, after the {@code epistola-contract/{version}} token.
         */
        public Builder product(String name, String version) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Product name must not be blank");
            }
            if (version == null || version.isBlank()) {
                throw new IllegalArgumentException("Product version must not be blank");
            }
            if (name.indexOf('/') >= 0 || name.indexOf(' ') >= 0) {
                throw new IllegalArgumentException("Product name must not contain '/' or spaces");
            }
            products.add(name + ContractIdentity.VERSION_SEPARATOR + version);
            return this;
        }

        public ClientIdentity build() {
            StringBuilder userAgent = new StringBuilder(CONTRACT_PRODUCT)
                    .append(ContractIdentity.VERSION_SEPARATOR)
                    .append(contractVersion());
            for (String product : products) {
                userAgent.append(ContractIdentity.PRODUCT_SEPARATOR).append(product);
            }
            return new ClientIdentity(userAgent.toString(), nodeId != null ? nodeId : localHostname());
        }

        private static String localHostname() {
            try {
                return InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e) {
                // A container with no resolvable hostname is normal; the header is still required.
                return "unknown-host";
            }
        }
    }

    private static final class ClientIdentityFilter implements ClientRequestFilter {

        private final ClientIdentity identity;

        private ClientIdentityFilter(ClientIdentity identity) {
            this.identity = identity;
        }

        @Override
        public void filter(ClientRequestContext requestContext) {
            requestContext.getHeaders().putSingle(HttpHeaders.USER_AGENT, identity.userAgent);
            requestContext.getHeaders().putSingle(HEADER_NODE_ID, identity.nodeId);
        }
    }
}
