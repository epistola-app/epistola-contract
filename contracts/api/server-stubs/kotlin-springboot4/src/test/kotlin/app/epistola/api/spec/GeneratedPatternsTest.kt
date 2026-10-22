// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.api.spec

import org.yaml.snakeyaml.Yaml
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A `pattern` in the spec must survive code generation. The Kotlin generator writes each pattern
 * into a comment and an annotation, and rewrites a star directly followed by a slash (which would
 * end the comment) to a star, an underscore and a slash. That changes the regular expression
 * itself: the merge-sides `entry` pattern became one no value matches, and every request with it
 * was refused. Write such a repetition bounded (`{0,63}`) instead.
 */
class GeneratedPatternsTest {
    @Test
    fun `no pattern in the spec contains a star followed by a slash`() {
        val resource = javaClass.getResourceAsStream("/openapi/epistola-contract.yaml")
        assertNotNull(resource, "bundled spec missing from classpath")
        val spec = resource.use { Yaml().load<Any>(it) }
        val offending = mutableListOf<String>()
        fun walk(node: Any?, path: String) {
            when (node) {
                is Map<*, *> -> node.forEach { (key, value) ->
                    if (key == "pattern" && value is String && "*/" in value) offending += "$path: $value"
                    walk(value, "$path/$key")
                }
                is List<*> -> node.forEachIndexed { index, value -> walk(value, "$path[$index]") }
            }
        }
        walk(spec, "")
        assertTrue(offending.isEmpty(), "Patterns the generator would change (write the repetition bounded):\n" + offending.joinToString("\n"))
    }
}
