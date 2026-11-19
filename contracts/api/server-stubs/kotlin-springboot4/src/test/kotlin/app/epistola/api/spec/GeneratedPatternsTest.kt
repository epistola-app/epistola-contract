// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.api.spec

import app.epistola.api.model.InteractiveGenerationLink
import app.epistola.api.model.InteractiveGenerationRequest
import app.epistola.api.model.InteractiveGenerationTrigger
import jakarta.validation.Validation
import org.yaml.snakeyaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A `pattern` in the spec must survive code generation. The Kotlin generator writes each pattern
 * into a comment and an annotation, and rewrites a star directly followed by a slash (which would
 * end the comment) to a star, an underscore and a slash. That changes the regular expression
 * itself: the merge-sides `entry` pattern became one no value matches, and every request with it
 * was refused. Write such a repetition bounded (`{0,63}`) instead.
 *
 * A pattern also means something different to its two readers unless it is anchored at both ends.
 * JSON Schema searches the value for a match, so `^https://` means "starts with"; Bean Validation's
 * `@Pattern` must match the whole value, so the same pattern accepts only the bare scheme. 2.1.0
 * shipped `returnUrl` that way, and every server built on these stubs refused every real URL.
 */
class GeneratedPatternsTest {
    private fun patterns(): List<Pair<String, String>> {
        val resource = javaClass.getResourceAsStream("/openapi/epistola-contract.yaml")
        assertNotNull(resource, "bundled spec missing from classpath")
        val spec = resource.use { Yaml().load<Any>(it) }
        val found = mutableListOf<Pair<String, String>>()
        fun walk(node: Any?, path: String) {
            when (node) {
                is Map<*, *> -> node.forEach { (key, value) ->
                    if (key == "pattern" && value is String) found += path to value
                    walk(value, "$path/$key")
                }
                is List<*> -> node.forEachIndexed { index, value -> walk(value, "$path[$index]") }
            }
        }
        walk(spec, "")
        return found
    }

    @Test
    fun `no pattern in the spec contains a star followed by a slash`() {
        val offending = patterns().filter { (_, pattern) -> "*/" in pattern }
        assertTrue(offending.isEmpty(), "Patterns the generator would change (write the repetition bounded):\n" + offending.joinToString("\n"))
    }

    @Test
    fun `every pattern in the spec is anchored at both ends`() {
        val unanchored = patterns().filterNot { (_, pattern) -> pattern.startsWith("^") && pattern.endsWith("$") }
        assertTrue(unanchored.isEmpty(), "Patterns JSON Schema and Bean Validation read differently (anchor them with ^…$):\n" + unanchored.joinToString("\n"))
    }

    @Test
    fun `bean validation accepts a real return URL and link, and refuses another scheme`() {
        val validator = Validation.buildDefaultValidatorFactory().use { it.validator }
        val accepted = InteractiveGenerationRequest(
            trigger = InteractiveGenerationTrigger.INCOMPLETE,
            returnUrl = "https://cases.example.org/zaken/Z-2026-0412?tab=letters#top",
            links = listOf(InteractiveGenerationLink(label = "Open case", url = "http://intranet.example.org/guide")),
        )
        assertEquals(emptySet(), validator.validate(accepted).map { it.propertyPath.toString() }.toSet())

        val refused = accepted.copy(returnUrl = "javascript:alert(1)")
        assertEquals(setOf("returnUrl"), validator.validate(refused).map { it.propertyPath.toString() }.toSet())
    }
}
