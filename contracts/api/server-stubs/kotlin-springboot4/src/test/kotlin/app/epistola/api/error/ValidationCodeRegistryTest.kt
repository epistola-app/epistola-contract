// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.api.error

import app.epistola.catalog.validation.TemplateValidationCodes
import org.yaml.snakeyaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The validation codes of `x-validation-codes` in the spec that ships on the classpath. Its
 * `template` codes are the portable template validator's: every error code the catalog contract's
 * [TemplateValidationCodes] publishes, in kebab-case, so the API registry cannot drift from the
 * validator that produces them. The generated [KnownValidationCodes] must list exactly the
 * registry's codes.
 */
class ValidationCodeRegistryTest {

    @Suppress("UNCHECKED_CAST")
    private fun codes(): List<Map<String, Any>> {
        val resource = javaClass.getResourceAsStream("/openapi/epistola-contract.yaml")
        assertNotNull(resource, "bundled spec missing from classpath")
        val spec = resource.use { Yaml().load<Map<String, Any>>(it) }
        val registry = assertNotNull(spec["x-validation-codes"] as? Map<String, Any>, "spec has no x-validation-codes")
        return registry["codes"] as List<Map<String, Any>>
    }

    private fun kebab(code: String): String = code.lowercase().replace('_', '-')

    @Test
    fun `the template codes are the template validator's error codes, in kebab-case`() {
        val registered = codes().filter { it["kind"] == "template" }.map { it["code"] as String }.toSet()
        val errors = (TemplateValidationCodes.ALL - TemplateValidationCodes.WARNINGS).map(::kebab).toSet()
        assertEquals(errors, registered)
    }

    @Test
    fun `the generated KnownValidationCodes match the spec that ships on the classpath`() {
        val specCodes = codes().map { it["code"] as String }
        val constants = KnownValidationCodes::class.java.declaredFields
            .filter { it.type == String::class.java }
            .map { it.also { field -> field.isAccessible = true }.get(null) as String }
            .toSet()

        assertEquals(
            specCodes.toSet(),
            constants,
            "KnownValidationCodes disagrees with the bundled spec — the generator ran against a " +
                "different spec than the one packaged into the jar",
        )
        assertEquals(specCodes, KnownValidationCodes.ALL, "ALL should list every code, in registry order")
        assertEquals("pattern", KnownValidationCodes.PATTERN)
        assertEquals("stencil-recursion", KnownValidationCodes.STENCIL_RECURSION)
        assertEquals("catalog-resources-not-ready", KnownValidationCodes.CATALOG_RESOURCES_NOT_READY)
    }

    @Test
    fun `every code is kebab-case, registered once, with a known kind and a description`() {
        val all = codes()
        assertEquals(all.size, all.map { it["code"] }.toSet().size, "a code is registered twice")
        for (entry in all) {
            val code = entry["code"] as String
            assertTrue(Regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$").matches(code), "$code is not kebab-case")
            assertTrue(entry["kind"] in setOf("constraint", "template", "domain"), "$code: kind ${entry["kind"]}")
            assertTrue((entry["description"] as? String).orEmpty().isNotBlank(), "$code has no description")
        }
    }
}
