// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * What a consumer's WAR ends up carrying is the whole reason this client exists as a separate
 * artifact, so it is asserted rather than reviewed.
 *
 * <p>The {@code generateDependencyReport} Gradle task resolves the compile and runtime classpaths
 * and writes them to a test resource; these tests read that. A dependency added to
 * {@code build.gradle.kts} in the wrong configuration fails here, not in someone's deployment.
 */
class DependencyHygieneTest {

    private static final String REPORT = "/dependency-report.txt";

    /**
     * Groups that must never appear anywhere near this client, on any classpath.
     *
     * <p>Spring because the whole point is a consumer who does not have it. {@code javax.*} because
     * a Jakarta EE 9+ container will not load it, and mixing namespaces produces failures that look
     * like anything but a dependency problem.
     */
    private static final List<String> FORBIDDEN_ANYWHERE = List.of(
            "org.springframework",
            "javax.ws.rs",
            "javax.json",
            "javax.annotation",
            "javax.enterprise",
            "javax.inject",
            "javax.servlet",
            "javax.validation",
            "javax.xml.bind");

    /**
     * JAX-RS, JSON-B and JSON-P implementations. Fine on the test classpath — that is how the
     * generated interfaces get exercised — but shipping one puts a second REST stack in a WAR
     * whose server already has one, which is the scar every consumer of such a client carries.
     */
    private static final List<String> FORBIDDEN_AT_RUNTIME = List.of(
            "org.jboss.resteasy",
            "org.glassfish.jersey",
            "org.apache.cxf",
            "io.smallrye",
            "org.eclipse:yasson",
            "org.eclipse.parsson",
            "org.eclipse.jetty",
            "io.undertow",
            "org.apache.tomcat");

    @Test
    void the_published_artifact_has_no_runtime_dependencies_at_all() {
        List<String> runtime = coordinates("runtime");

        assertTrue(
                runtime.isEmpty(),
                "client-jakarta must ship nothing into a consumer's WAR — every API it uses is supplied"
                        + " by the application server. Found: " + runtime);
    }

    @Test
    void no_rest_json_or_servlet_implementation_can_reach_the_runtime_classpath() {
        assertNoneMatch(coordinates("runtime"), FORBIDDEN_AT_RUNTIME, "runtime");
    }

    @Test
    void neither_classpath_carries_spring_or_a_javax_namespace_artifact() {
        assertNoneMatch(coordinates("runtime"), FORBIDDEN_ANYWHERE, "runtime");
        assertNoneMatch(coordinates("compile"), FORBIDDEN_ANYWHERE, "compile");
    }

    @Test
    void the_compile_classpath_is_the_jakarta_and_microprofile_apis_it_is_meant_to_be() {
        List<String> compile = coordinates("compile");

        assertTrue(compile.stream().anyMatch(it -> it.startsWith("jakarta.ws.rs:")), compile.toString());
        assertTrue(compile.stream().anyMatch(it -> it.startsWith("jakarta.json:")), compile.toString());
        assertTrue(compile.stream().anyMatch(it -> it.startsWith("jakarta.json.bind:")), compile.toString());
        assertTrue(
                compile.stream().anyMatch(it -> it.startsWith("org.eclipse.microprofile.rest.client:")),
                compile.toString());
        assertTrue(
                compile.stream().anyMatch(it -> it.startsWith("org.eclipse.microprofile.config:")),
                compile.toString());
    }

    @Test
    void the_report_was_actually_produced() {
        // Guards against these assertions passing because the report is missing or empty.
        assertTrue(coordinates("compile").size() >= 6, "the compile classpath looks implausibly small");
    }

    private static void assertNoneMatch(List<String> coordinates, List<String> forbidden, String classpath) {
        List<String> offenders = coordinates.stream()
                .filter(coordinate -> forbidden.stream()
                        .anyMatch(prefix -> coordinate.toLowerCase(Locale.ROOT).startsWith(prefix)))
                .collect(Collectors.toList());

        if (!offenders.isEmpty()) {
            fail("Forbidden on the " + classpath + " classpath: " + offenders);
        }
    }

    private static List<String> coordinates(String classpath) {
        List<String> found = new ArrayList<>();
        try (InputStream stream = DependencyHygieneTest.class.getResourceAsStream(REPORT)) {
            assertNotNull(stream, REPORT + " is missing — run the generateDependencyReport Gradle task");
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#") || line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\t", 2);
                if (parts.length == 2 && parts[0].equals(classpath)) {
                    found.add(parts[1]);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }
}
