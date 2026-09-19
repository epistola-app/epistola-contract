// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.smoke;

import app.epistola.client.jakarta.api.ImagesApi;
import app.epistola.client.jakarta.api.SystemApi;
import app.epistola.client.jakarta.model.ImageDto;
import app.epistola.client.jakarta.model.PingRequest;
import app.epistola.client.jakarta.model.PongResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * The smallest application that proves the client works inside an application server: a CDI bean
 * that injects a generated {@code @RestClient} interface and calls it.
 *
 * <p>This is the shape a consumer writes. If the client cannot be injected — a missing CDI
 * qualifier, a provider the container rejects, a duplicated JAX-RS API in the WAR — WildFly fails
 * the deployment, and {@code WildFlyDeploymentTest} fails with it.
 */
@ApplicationScoped
@Path("/smoke")
public class SmokeResource {

    @Inject
    @RestClient
    SystemApi systemApi;

    @Inject
    @RestClient
    ImagesApi imagesApi;

    @GET
    @Path("/ping")
    @Produces(MediaType.TEXT_PLAIN)
    public String ping() {
        PongResponse pong = systemApi.ping(new PingRequest().name("epistola-smoke").description("deployment smoke test"));
        return String.valueOf(pong.getStatus());
    }

    /**
     * Uploads an image, which is the one operation that needs something the server supplies beyond
     * the REST client itself: the {@code EntityPart} implementation the multipart body is built
     * from. Nothing in the WAR provides it, so this fails if the server does not.
     */
    @GET
    @Path("/upload")
    @Produces(MediaType.TEXT_PLAIN)
    public String upload() {
        try {
            java.io.File file = Files.createTempDirectory("smoke").resolve("logo.png").toFile();
            Files.write(file.toPath(), "not really a png".getBytes(java.nio.charset.StandardCharsets.UTF_8));

            ImageDto image = imagesApi.uploadImage("acme-corp", "main", file, "Logo", null, null);
            return String.valueOf(image.getSlug());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
