// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.api.error

import org.yaml.snakeyaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Guards the problem-type registry on the server side.
 *
 * [KnownProblemSlugs] and [GENERATED_PROBLEM_TYPE_BASE] are generated from the spec's
 * `x-problem-types` extension, so they cannot drift from the contract — the first test is a
 * belt-and-braces check that the generator ran against the spec that actually ships on the
 * classpath (`openapi/epistola-contract.yaml`), not a stale bundle.
 *
 * What can still drift is the hand-written [ProblemDetails.KnownSlugs], which exists for
 * compatibility and delegates to the generated constants. Its *values* are safe by construction;
 * what a new problem type would leave behind is a missing constant, which is what the second test
 * catches.
 */
class ProblemRegistryTest {

    @Suppress("UNCHECKED_CAST")
    private fun specRegistry(): Map<String, Any> {
        val resource = javaClass.getResourceAsStream("/openapi/epistola-contract.yaml")
        assertNotNull(resource, "bundled spec missing from classpath")
        val spec = resource.use { Yaml().load<Map<String, Any>>(it) }
        return assertNotNull(spec["x-problem-types"] as? Map<String, Any>, "spec has no x-problem-types")
    }

    private fun constantsOf(type: Class<*>): Set<String> = type.declaredFields
        .filter { it.type == String::class.java }
        .map { it.also { field -> field.isAccessible = true }.get(null) as String }
        .toSet()

    @Test
    fun `the generated slugs match the spec that ships on the classpath`() {
        @Suppress("UNCHECKED_CAST")
        val specSlugs = (specRegistry()["types"] as List<Map<String, Any>>)
            .map { it["slug"] as String }
            .toSet()

        assertEquals(
            specSlugs,
            constantsOf(KnownProblemSlugs::class.java),
            "KnownProblemSlugs disagrees with the bundled spec — the generator ran against a " +
                "different spec than the one packaged into the jar",
        )
    }

    @Test
    fun `the compatibility KnownSlugs object still covers the whole registry`() {
        assertEquals(
            constantsOf(KnownProblemSlugs::class.java),
            constantsOf(ProblemDetails.KnownSlugs::class.java),
            "ProblemDetails.KnownSlugs no longer covers every generated slug — add the missing " +
                "constant(s), delegating to KnownProblemSlugs",
        )
    }

    @Test
    fun `the generated type base is what ProblemDetails exposes`() {
        assertEquals(specRegistry()["base"], GENERATED_PROBLEM_TYPE_BASE)
        assertEquals(GENERATED_PROBLEM_TYPE_BASE, ProblemDetails.TYPE_BASE)
    }

    @Test
    fun `every registered slug round-trips through typeFor`() {
        for (slug in constantsOf(KnownProblemSlugs::class.java)) {
            assertEquals(
                ProblemDetails.TYPE_BASE + slug,
                ProblemDetails.typeFor(slug).toString(),
                "typeFor should build the registry's type URI for '$slug'",
            )
        }
    }
}
