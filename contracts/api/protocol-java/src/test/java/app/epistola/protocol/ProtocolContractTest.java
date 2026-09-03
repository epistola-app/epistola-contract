// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.protocol;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Guards the two properties this module promises its consumers, both of which are invisible in
 * ordinary use and easy to lose by accident.
 */
class ProtocolContractTest {

    @Test
    void the_module_has_no_runtime_dependencies() {
        // Every consumer ships this jar — the Kotlin client into a Spring application, the Jakarta
        // client into a WAR that must stay thin. Anything added here is added to all of them, so
        // "none" is the number to hold, not "few".
        List<String> runtime = runtimeDependencies();

        assertTrue(
                runtime.isEmpty(),
                "protocol-java must ship nothing to its consumers. Found: " + runtime);
    }

    @Test
    void the_package_is_null_marked_so_kotlin_sees_real_types() {
        // Without this the Kotlin client and server stubs see platform types (String!) and lose
        // null-safety at exactly the points where null carries meaning: an assignment the server
        // has not sent yet, a problem type with no Epistola slug.
        assertNotNull(
                PartitionRouting.class.getPackage().getAnnotation(NullMarked.class),
                "app.epistola.protocol must be @NullMarked (see package-info.java)");
    }

    @Test
    void everything_that_can_return_null_says_so() {
        assertNullable(PartitionRouting.class, "of");
        assertNullable(PartitionRouting.class, "routingKeyToMe");
        assertNullable(ProblemTypeUris.class, "slugFor");
        assertNullable(UserAgent.class, "versionOf");
    }

    private static void assertNullable(Class<?> type, String methodName) {
        Method method = null;
        for (Method candidate : type.getDeclaredMethods()) {
            if (candidate.getName().equals(methodName)) {
                method = candidate;
                break;
            }
        }
        assertNotNull(method, type.getSimpleName() + " has no method " + methodName);
        assertNotNull(
                method.getAnnotatedReturnType().getAnnotation(Nullable.class),
                type.getSimpleName() + "." + methodName + " can return null but is not @Nullable —"
                        + " Kotlin callers would not be told");
    }

    private static List<String> runtimeDependencies() {
        List<String> found = new ArrayList<>();
        try (InputStream stream = ProtocolContractTest.class.getResourceAsStream("/runtime-dependencies.txt")) {
            assertNotNull(stream, "runtime-dependencies.txt is missing — run generateDependencyReport");
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("#") && !line.isBlank()) {
                    found.add(line);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }
}
