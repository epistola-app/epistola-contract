// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.model

import app.epistola.client.infrastructure.Serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the any-setter/any-getter catch-all actually works through the mapper this client uses
 * ([Serializer.jacksonObjectMapper], jackson-module-kotlin's creator-based deserialization) —
 * verified rather than assumed, since a Kotlin data class primary constructor as a Jackson creator
 * is exactly where an any-setter is most likely to silently not bind.
 */
class ProblemDetailExtensionsTest {

    private val mapper = Serializer.jacksonObjectMapper

    @Test
    fun `an unmodelled member lands in extensions, not on the floor`() {
        val json = """
            {"type":"https://epistola.app/errors/catalog-schema-too-old","title":"Catalog schema too old",
             "status":409,"version":3,"baselineVersion":5}
        """.trimIndent()

        val problem = mapper.readValue(json, ProblemDetail::class.java)

        assertEquals("Catalog schema too old", problem.title)
        assertEquals(409, problem.status)
        assertEquals(3, problem.extensions["version"])
        assertEquals(5, problem.extensions["baselineVersion"])
    }

    @Test
    fun `the five named fields do not also land in extensions`() {
        val json = """{"type":"about:blank","title":"X","status":400,"detail":"d","instance":"i"}"""

        val problem = mapper.readValue(json, ProblemDetail::class.java)

        assertTrue(problem.extensions.isEmpty(), "${problem.extensions}")
    }

    @Test
    fun `no extension members means an empty map, not null or an exception`() {
        val problem = mapper.readValue("""{"title":"X","status":400}""", ProblemDetail::class.java)

        assertTrue(problem.extensions.isEmpty())
    }

    @Test
    fun `extensions round-trip through serialization as top-level members`() {
        val original = ProblemDetail(title = "X", status = 400, extensions = mapOf("version" to 3))

        val written = mapper.writeValueAsString(original)
        val reread = mapper.readValue(written, ProblemDetail::class.java)

        assertTrue(written.contains("\"version\":3"), written)
        assertEquals(3, reread.extensions["version"])
    }
}
