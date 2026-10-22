// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.protocol

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CatalogAliasesTest {
    @Test
    fun `an alias is a catalog key`() {
        listOf("system", "shared", "house-style", "a2b").forEach { assertTrue(CatalogAliases.isValid(it), it) }
        listOf("", "ab", "System", "2shared", "house_style", "house--style", "a".repeat(51)).forEach { assertFalse(CatalogAliases.isValid(it), it) }
    }

    /**
     * The API's references name another catalog by the alias its content names it by, so the
     * archive's rule is the API's `CatalogAlias`. The spec is read as text: this module has no YAML
     * parser, and the rule is a flat block.
     */
    @Test
    fun `the rule is the API's CatalogAlias`() {
        val spec = File("../api/components/schemas/identifiers.yaml")
        assertTrue(spec.isFile, "the API spec must be next to the catalog module: ${spec.absolutePath}")
        val block = assertNotNull(
            Regex("(?m)^CatalogAlias:\\n((?:[ ]{2}.*\\n)+)").find(spec.readText()),
            "CatalogAlias is a top-level schema of the API's identifiers.yaml",
        ).groupValues[1]
        val pattern = assertNotNull(Regex("(?m)^ {2}pattern: '([^']+)'$").find(block)).groupValues[1]
        val minLength = assertNotNull(Regex("(?m)^ {2}minLength: (\\d+)$").find(block)).groupValues[1].toInt()
        val maxLength = assertNotNull(Regex("(?m)^ {2}maxLength: (\\d+)$").find(block)).groupValues[1].toInt()

        assertEquals(CatalogAliases.PATTERN, pattern)
        assertEquals(CatalogAliases.MIN_LENGTH, minLength)
        assertEquals(CatalogAliases.MAX_LENGTH, maxLength)
    }
}
