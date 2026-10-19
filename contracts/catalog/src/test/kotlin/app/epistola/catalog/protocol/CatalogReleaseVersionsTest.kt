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

class CatalogReleaseVersionsTest {
    @Test
    fun `a release number is MAJOR_MINOR_PATCH without leading zeros, pre-release or build part`() {
        listOf("0.0.0", "1.4.0", "10.20.30", "1.0.${"1".repeat(46)}").forEach { assertTrue(CatalogReleaseVersions.isValid(it), it) }
        listOf("", "1.0", "1.0.0.0", "01.0.0", "1.01.0", "v1.0.0", "1.0.0-beta.1", "1.0.0+5", "^1.0.0", "1.x", "1.0.${"1".repeat(47)}")
            .forEach { assertFalse(CatalogReleaseVersions.isValid(it), it) }
    }

    @Test
    fun `the rule matches every place the v8 manifest schema names a release`() {
        val schema = requireNotNull(
            javaClass.getResourceAsStream("/META-INF/epistola-catalog/schemas/catalog-manifest-v8.schema.json"),
        ).use(jsonMapper()::readTree)
        listOf(
            schema["\$defs"]["ReleaseInfo"]["properties"]["version"],
            schema["\$defs"]["CatalogDependency"]["properties"]["releaseVersion"],
        ).forEach { property ->
            assertEquals(CatalogReleaseVersions.PATTERN, property["pattern"].asString(), property.toString())
            assertEquals(CatalogReleaseVersions.MAX_LENGTH, property["maxLength"].asInt(), property.toString())
        }
    }

    @Test
    fun `wire v7 keeps full SemVer`() {
        val schema = requireNotNull(
            javaClass.getResourceAsStream("/META-INF/epistola-catalog/schemas/catalog-manifest-v7.schema.json"),
        ).use(jsonMapper()::readTree)
        val version = schema["\$defs"]["ReleaseInfo"]["properties"]["version"]

        assertFalse(version.has("pattern"), version.toString())
    }

    /**
     * The API reports every release an archive carries, so the archive's rule is the API's
     * `ReleaseVersion`. The spec is read as text: this module has no YAML parser, and the schema is
     * a flat block.
     */
    @Test
    fun `the rule is the API's ReleaseVersion`() {
        val spec = File("../api/components/schemas/catalogs.yaml")
        assertTrue(spec.isFile, "the API spec must be next to the catalog module: ${spec.absolutePath}")
        val block = assertNotNull(
            Regex("(?m)^ReleaseVersion:\\n((?:[ ]{2}.*\\n|\\n)+)").find(spec.readText()),
            "ReleaseVersion is a top-level schema of the API's catalogs.yaml",
        ).groupValues[1]
        val pattern = assertNotNull(Regex("(?m)^ {2}pattern: '([^']+)'$").find(block)).groupValues[1]
        val maxLength = assertNotNull(Regex("(?m)^ {2}maxLength: (\\d+)$").find(block)).groupValues[1].toInt()

        assertEquals(CatalogReleaseVersions.PATTERN, pattern)
        assertEquals(CatalogReleaseVersions.MAX_LENGTH, maxLength)
    }
}
