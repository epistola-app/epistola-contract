// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What this client drags onto a consumer's classpath is part of its contract, so it is asserted
 * rather than reviewed.
 *
 * It previously declared `spring-boot-starter-web` and resolved 33 artifacts, embedded Tomcat and
 * Spring MVC among them — a library for *calling* HTTP shipping a server for *serving* it. Nothing
 * caught that, because nothing was looking.
 */
class DependencyHygieneTest {

    /**
     * Things a client has no business bringing. Servlet containers and Spring MVC serve HTTP;
     * Boot starters are opinionated bundles that belong in an application, not a library; a JOSE
     * library is no longer needed now that JWT signing is plain `java.security`.
     */
    private val forbidden = listOf(
        "org.apache.tomcat",
        "org.eclipse.jetty",
        "io.undertow",
        "org.springframework:spring-webmvc",
        "org.springframework.boot:spring-boot-starter",
        "com.nimbusds",
        "jakarta.servlet",
    )

    private fun runtimeDependencies(): List<String> = checkNotNull(javaClass.getResourceAsStream("/runtime-dependencies.txt")) {
        "runtime-dependencies.txt is missing — run the generateDependencyReport task"
    }.bufferedReader().readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }

    @Test
    fun `no servlet container, Spring MVC, Boot starter or JOSE library reaches a consumer`() {
        val offenders = runtimeDependencies().filter { dependency ->
            forbidden.any { dependency.startsWith(it) }
        }

        if (offenders.isNotEmpty()) {
            fail(
                "This client must not put these on a consumer's classpath: $offenders. " +
                    "It calls HTTP; it does not serve it, and it signs JWTs with java.security.",
            )
        }
    }

    @Test
    fun `it still brings what it genuinely needs`() {
        val dependencies = runtimeDependencies()

        assertTrue("org.springframework:spring-web" in dependencies, dependencies.toString())
        assertTrue("com.fasterxml.jackson.core:jackson-databind" in dependencies, dependencies.toString())
        assertTrue("org.jetbrains.kotlin:kotlin-stdlib" in dependencies, dependencies.toString())
    }

    @Test
    fun `the dependency count stays in the range a client library belongs in`() {
        // Not a precise number — transitives shift with Spring and Jackson releases. A ceiling is
        // enough to catch a starter or a server being pulled back in, which is what took it to 33.
        val count = runtimeDependencies().size

        assertTrue(count in 5..20, "expected a small runtime classpath for a client library, found $count: " + runtimeDependencies())
    }
}
