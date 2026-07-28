// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.api.error

import org.yaml.snakeyaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Guards the hand-written [ProblemDetails.KnownSlugs] against the machine-readable
 * problem-type registry (`x-problem-types`) in the bundled spec, which ships on the
 * classpath as `openapi/epistola-contract.yaml`. When the registry changes, this test
 * fails until the constants are updated in the same change.
 */
class ProblemRegistryTest {

    @Suppress("UNCHECKED_CAST")
    private fun specRegistry(): Map<String, Any> {
        val resource = javaClass.getResourceAsStream("/openapi/epistola-contract.yaml")
        assertNotNull(resource, "bundled spec missing from classpath")
        val spec = resource.use { Yaml().load<Map<String, Any>>(it) }
        return assertNotNull(spec["x-problem-types"] as? Map<String, Any>, "spec has no x-problem-types")
    }

    @Test
    fun `hand-written slug constants match the spec registry`() {
        @Suppress("UNCHECKED_CAST")
        val specSlugs = (specRegistry()["types"] as List<Map<String, Any>>)
            .map { it["slug"] as String }
            .toSet()

        val constantSlugs = ProblemDetails.KnownSlugs::class.java.declaredFields
            .filter { it.type == String::class.java }
            .map { it.also { f -> f.isAccessible = true }.get(null) as String }
            .toSet()

        assertEquals(
            specSlugs,
            constantSlugs,
            "ProblemDetails.KnownSlugs drifted from the spec's x-problem-types registry — " +
                "update the constants (and docs/error-types.md) in the same change",
        )
    }

    @Test
    fun `hand-written TYPE_BASE matches the spec registry base`() {
        assertEquals(specRegistry()["base"], ProblemDetails.TYPE_BASE)
    }
}
