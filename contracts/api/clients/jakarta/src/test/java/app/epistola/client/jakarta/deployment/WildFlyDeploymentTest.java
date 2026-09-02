// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.epistola.client.jakarta.StubServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Deploys the client into a real WildFly and calls it — because a jar that resolves is not a jar
 * that deploys.
 *
 * <p>The WAR holds nothing but this client and the smoke application from {@code src/smokeApp}: a
 * CDI bean that injects a generated {@code @RestClient} interface, configured exactly as
 * {@code EpistolaConfig} documents. If the client shipped a JAX-RS implementation, declared a
 * {@code javax.*} dependency, or registered a provider the container rejects, the deployment fails
 * — and this is the only place that question gets a real answer.
 *
 * <p><strong>Opt-in.</strong> It needs Docker and pulls a WildFly image, so it is excluded from
 * the default {@code test} task and from CI:
 *
 * <pre>./gradlew deploymentTest -PdeploymentTest</pre>
 */
@Tag("deployment")
class WildFlyDeploymentTest {

    private static final String WILDFLY_IMAGE = "quay.io/wildfly/wildfly:37.0.1.Final-jdk21";
    private static final int WILDFLY_HTTP_PORT = 8080;
    private static final String WAR_NAME = "epistola-smoke";

    private static final String PONG =
            "{\"status\":\"UP\",\"timestamp\":\"2026-09-02T10:00:00Z\"}";

    @Test
    void the_client_deploys_into_wildfly_and_its_injected_rest_client_reaches_the_api() throws Exception {
        Path war = buildSmokeWar();

        try (StubServer epistola = StubServer.start(request ->
                StubServer.StubResponse.of(200, "application/vnd.epistola.v1+json", PONG))) {

            int stubPort = epistola.baseUri().getPort();
            Testcontainers.exposeHostPorts(stubPort);

            try (GenericContainer<?> wildfly = new GenericContainer<>(WILDFLY_IMAGE)
                    .withExposedPorts(WILDFLY_HTTP_PORT)
                    // The default standalone.xml carries no MicroProfile subsystems; the
                    // microprofile profile is what a MicroProfile Rest Client consumer runs on.
                    .withCommand(
                            "/opt/jboss/wildfly/bin/standalone.sh",
                            "-c", "standalone-microprofile.xml",
                            "-b", "0.0.0.0")
                    .withEnv("EPISTOLA_URL", "http://host.testcontainers.internal:" + stubPort + "/api")
                    .withCopyFileToContainer(
                            // 0644 explicitly: the WAR is a temp file, so it arrives 0600 owned by
                            // root, and WildFly runs as jboss — the deployment scanner would only
                            // report the file as "incomplete", never as unreadable.
                            MountableFile.forHostPath(war, 0644),
                            "/opt/jboss/wildfly/standalone/deployments/" + WAR_NAME + ".war")
                    .waitingFor(Wait.forLogMessage(".*Deployed \"" + WAR_NAME + "\\.war\".*", 1)
                            .withStartupTimeout(Duration.ofMinutes(5)))) {

                wildfly.start();

                // Reaching here means the WAR deployed. Now prove the injected client works.
                String base = "http://" + wildfly.getHost() + ":" + wildfly.getMappedPort(WILDFLY_HTTP_PORT);
                HttpResponse<String> response = HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(URI.create(base + "/" + WAR_NAME + "/api/smoke/ping"))
                                        .timeout(Duration.ofSeconds(30))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());

                assertEquals(200, response.statusCode(), response.body());
                assertEquals("UP", response.body().trim());

                StubServer.RecordedRequest seen = epistola.onlyRequest();
                assertEquals("/api/ping", seen.path());
                assertEquals(
                        "application/vnd.epistola.v1+json",
                        seen.header("Content-Type"),
                        "the deployed client must still send the versioned media type");
                assertNotNull(seen.header("User-Agent"));
                assertTrue(
                        seen.header("User-Agent").startsWith("epistola-contract/"),
                        "User-Agent should start with the contract token, was: " + seen.header("User-Agent"));
                assertEquals(
                        "smoke-node-1",
                        seen.header("X-EP-Node-Id"),
                        "the RestClientListener should have applied the configured node id");
                assertEquals(
                        "ApiKey epk_smoke_test",
                        seen.header("Authorization"),
                        "the RestClientListener should have applied the configured API key");
            }
        }
    }

    /**
     * Assembles the WAR by hand — the client jar, the compiled smoke application and an empty
     * {@code beans.xml} — so that a deployment failure can only mean one of those three, and never
     * something a packaging library added on our behalf.
     */
    private static Path buildSmokeWar() throws IOException {
        Path clientJar = requiredPath("epistola.client.jar");
        Path smokeClasses = requiredPath("epistola.smokeApp.classes");
        Path smokeResources = requiredPath("epistola.smokeApp.resources");

        Path war = Files.createTempFile(WAR_NAME, ".war");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(war))) {
            // An empty beans.xml is what makes the WAR a CDI bean archive.
            write(zip, "WEB-INF/beans.xml", BEANS_XML.getBytes(StandardCharsets.UTF_8));
            write(zip, "WEB-INF/lib/client-jakarta.jar", Files.readAllBytes(clientJar));
            for (Path jar : runtimeJars()) {
                write(zip, "WEB-INF/lib/" + jar.getFileName(), Files.readAllBytes(jar));
            }
            copyTree(zip, smokeClasses, "WEB-INF/classes/");
            copyTree(zip, smokeResources, "WEB-INF/classes/");
        }
        return war;
    }

    /**
     * Everything the client ships alongside itself. Taken from the resolved runtime classpath
     * rather than named here, so a dependency added to the client cannot be left out of the WAR
     * and turn into a NoClassDefFoundError at deployment.
     */
    private static List<Path> runtimeJars() {
        String value = System.getProperty("epistola.client.runtimeJars", "");
        if (value.isBlank()) {
            return List.of();
        }
        return Stream.of(value.split(java.io.File.pathSeparator))
                .filter(entry -> !entry.isBlank())
                .map(Path::of)
                .filter(Files::isRegularFile)
                .toList();
    }

    private static Path requiredPath(String systemProperty) {
        String value = System.getProperty(systemProperty);
        if (value == null) {
            throw new IllegalStateException(
                    systemProperty + " is not set — run this through the deploymentTest Gradle task,"
                            + " which builds the client jar and the smoke application first");
        }
        Path path = Path.of(value);
        if (!Files.exists(path)) {
            throw new IllegalStateException(systemProperty + " points at " + path + ", which does not exist");
        }
        return path;
    }

    private static void copyTree(ZipOutputStream zip, Path root, String prefix) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> regular = files.filter(Files::isRegularFile).sorted().toList();
            for (Path file : regular) {
                write(zip, prefix + root.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
            }
        }
    }

    private static void write(ZipOutputStream zip, String name, byte[] content) {
        try {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content);
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to add " + name + " to the smoke WAR", e);
        }
    }

    private static final String BEANS_XML =
            "<beans xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"4.0\" bean-discovery-mode=\"all\"/>";
}
