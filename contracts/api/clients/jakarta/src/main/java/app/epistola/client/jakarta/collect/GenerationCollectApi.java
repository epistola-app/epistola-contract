// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.collect;

import app.epistola.client.jakarta.api.ApiExceptionMapper;
import app.epistola.client.jakarta.model.CollectRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * The {@code /generation/collect} endpoint, typed to return the raw {@link Response} instead of a
 * bound entity.
 *
 * <p>Hand-written alongside the generated {@code GenerationApi}, whose
 * {@code collectGenerationResults} returns a {@code String} — that is, the whole batch in memory.
 * Collection is the production path for asynchronous generation and a batch is unbounded, so
 * {@link ResultCollector} reads the NDJSON response one line at a time off the response stream and
 * needs the {@code Content-Encoding} header to pick a decompressor. Both require the
 * {@link Response} itself.
 *
 * <p>Same annotations as the generated interfaces, so it behaves identically under CDI:
 * {@code @Inject @RestClient GenerationCollectApi} with
 * {@code app.epistola.client.jakarta.collect.GenerationCollectApi/mp-rest/url} configured.
 */
@RegisterRestClient
@RegisterProvider(ApiExceptionMapper.class)
@Path("/tenants/{tenantId}")
public interface GenerationCollectApi {

    /**
     * Collects completed and failed generation results as a (possibly compressed) NDJSON stream.
     *
     * @param tenantId       the tenant to collect for
     * @param acceptEncoding the compression the caller can decode, e.g. {@code "lz4, zstd, gzip"}
     * @param collectRequest the acknowledgement cursor and batch limit
     */
    @POST
    @Path("/generation/collect")
    @Consumes({"application/vnd.epistola.v1+json"})
    @Produces({"application/vnd.epistola.v1+ndjson", "application/problem+json"})
    Response collectGenerationResults(
            @PathParam("tenantId") String tenantId,
            @HeaderParam("Accept-Encoding") String acceptEncoding,
            CollectRequest collectRequest);
}
