// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.protocol

import tools.jackson.module.kotlin.jsonMapper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CatalogReleaseIdsTest {
    @Test
    fun `a release id is lowercase letters and digits joined by single hyphens, 4 to 36 characters`() {
        listOf("k7m2x9qa", "abcd", "2026", "f47ac10b-58cc-4372-a567-0e02b2c3d479", "a".repeat(36)).forEach { assertTrue(CatalogReleaseIds.isValid(it), it) }
        listOf("", "abc", "K7M2X9QA", "k7m2_x9", "k7m2.x9", "-k7m2", "k7m2-", "k7--m2", "1.4.0", "a".repeat(37))
            .forEach { assertFalse(CatalogReleaseIds.isValid(it), it) }
    }

    @Test
    fun `the rule matches the v8 manifest schema`() {
        val schema = requireNotNull(
            javaClass.getResourceAsStream("/META-INF/epistola-catalog/schemas/catalog-manifest-v8.schema.json"),
        ).use(jsonMapper()::readTree)
        val property = schema["\$defs"]["ReleaseInfo"]["properties"]["releaseId"]

        assertEquals(CatalogReleaseIds.PATTERN, property["pattern"].asString())
        assertEquals(CatalogReleaseIds.MIN_LENGTH, property["minLength"].asInt())
        assertEquals(CatalogReleaseIds.MAX_LENGTH, property["maxLength"].asInt())
    }

    /**
     * The id an archive carries is the id the API addresses the release by, so the archive's rule is
     * the API's `CatalogReleaseId`. The spec is read as text: this module has no YAML parser, and the
     * rule is a flat block.
     */
    @Test
    fun `the rule is the API's CatalogReleaseId`() {
        val spec = File("../api/components/schemas/identifiers.yaml")
        assertTrue(spec.isFile, "the API spec must be next to the catalog module: ${spec.absolutePath}")
        val block = assertNotNull(
            Regex("(?m)^CatalogReleaseId:\\n((?:[ ]{2}.*\\n|\\n)+?)(?=^\\S)").find(spec.readText()),
            "CatalogReleaseId is a top-level schema of the API's identifiers.yaml",
        ).groupValues[1]
        val pattern = assertNotNull(Regex("(?m)^ {2}pattern: '([^']+)'$").find(block)).groupValues[1]
        val minLength = assertNotNull(Regex("(?m)^ {2}minLength: (\\d+)$").find(block)).groupValues[1].toInt()
        val maxLength = assertNotNull(Regex("(?m)^ {2}maxLength: (\\d+)$").find(block)).groupValues[1].toInt()

        assertEquals(CatalogReleaseIds.PATTERN, pattern)
        assertEquals(CatalogReleaseIds.MIN_LENGTH, minLength)
        assertEquals(CatalogReleaseIds.MAX_LENGTH, maxLength)
    }
}
