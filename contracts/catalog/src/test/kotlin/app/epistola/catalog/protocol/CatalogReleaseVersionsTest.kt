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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogReleaseVersionsTest {
    @Test
    fun `a release number is MAJOR_MINOR_PATCH without leading zeros, pre-release or build part`() {
        listOf("0.0.0", "1.4.0", "10.20.30", "1.0.${"1".repeat(124)}").forEach {
            assertTrue(CatalogReleaseVersions.isValid(it), it)
            assertTrue(CatalogReleaseVersions.isCore(it), it)
            assertNull(CatalogReleaseVersions.patchNamespace(it), it)
        }
        listOf("", "1.0", "1.0.0.0", "01.0.0", "1.01.0", "v1.0.0", "1.0.0-rc.1", "1.0.0+5", "^1.0.0", "1.x", "1.0.${"1".repeat(125)}")
            .forEach {
                assertFalse(CatalogReleaseVersions.isValid(it), it)
                assertFalse(CatalogReleaseVersions.isCore(it), it)
            }
    }

    @Test
    fun `a release number is at most 128 characters, so every namespace can label a patch release`() {
        assertEquals(128, CatalogReleaseVersions.MAX_LENGTH)
        assertTrue(CatalogReleaseVersions.MAX_LENGTH >= "999999.999999.999999-".length + CatalogNamespaces.MAX_LENGTH + ".99999".length)
        val longest = "1.0.${"1".repeat(58)}-${"a".repeat(63)}.1"
        assertEquals(128, longest.length)
        assertTrue(CatalogReleaseVersions.isValid(longest))
        assertFalse(CatalogReleaseVersions.isValid("1.0.${"1".repeat(59)}-${"a".repeat(63)}.1"), "129 characters")
        assertTrue(CatalogReleaseVersions.isCore("1.0.${"1".repeat(124)}"))
        assertFalse(CatalogReleaseVersions.isCore("1.0.${"1".repeat(125)}"), "129 characters")
    }

    @Test
    fun `a patch release is labelled with a namespace and a sequence number, and sorts below the next plain number`() {
        mapOf(
            "1.3.1-gemeente-x.1" to "gemeente-x",
            "1.3.1-acme.12" to "acme",
            // Every valid namespace, the longest (63 characters) included, can label a patch release.
            "0.0.1-${"a".repeat(63)}.1" to "a".repeat(63),
            "1.0.${"1".repeat(58)}-${"a".repeat(63)}.1" to "a".repeat(63),
        ).forEach { (version, namespace) ->
            assertTrue(CatalogReleaseVersions.isValid(version), version)
            assertFalse(CatalogReleaseVersions.isCore(version), version)
            assertEquals(namespace, CatalogReleaseVersions.patchNamespace(version), version)
            assertTrue(CatalogNamespaces.isValid(namespace), namespace)
        }
        // The label names a namespace (3 to 63 characters, a leading letter) and a number from 1, and nothing more.
        listOf(
            "1.3.1-ab.1",
            "1.3.1-gemeente-x",
            "1.3.1-gemeente-x.0",
            "1.3.1-gemeente-x.01",
            "1.3.1-Gemeente.1",
            "1.3.1-1gemeente.1",
            "1.3.1-gemeente-.1",
            "1.3.1-gemeente-x.1.2",
            "1.3.1-gemeente-x.1+5",
            "1.3.1-gemeente.x.1",
            "0.0.1-${"a".repeat(64)}.1",
            // 129 characters in all.
            "1.0.${"1".repeat(59)}-${"a".repeat(63)}.1",
        ).forEach {
            assertFalse(CatalogReleaseVersions.isValid(it), it)
            assertNull(CatalogReleaseVersions.patchNamespace(it), it)
        }
    }

    @Test
    fun `the rule matches every place the v8 manifest schema names a release`() {
        val schema = requireNotNull(
            javaClass.getResourceAsStream("/META-INF/epistola-catalog/schemas/catalog-manifest-v8.schema.json"),
        ).use(jsonMapper()::readTree)
        listOf(
            schema["\$defs"]["ReleaseInfo"]["properties"]["version"],
            schema["\$defs"]["CatalogDependency"]["properties"]["releaseVersion"],
            schema["\$defs"]["ReleaseBase"]["properties"]["version"],
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
     * The API reports a release by its plain number, so the core of the archive's rule is the API's
     * `ReleaseVersion`; a patch release's label is the archive's alone until the API follows. The
     * spec is read as text: this module has no YAML parser, and the schema is a flat block.
     */
    @Test
    fun `the rule's core is the API's ReleaseVersion`() {
        val spec = File("../api/components/schemas/identifiers.yaml")
        assertTrue(spec.isFile, "the API spec must be next to the catalog module: ${spec.absolutePath}")
        val block = assertNotNull(
            Regex("(?m)^ReleaseVersion:\\n((?:[ ]{2}.*\\n)+)").find(spec.readText()),
            "ReleaseVersion is a top-level schema of the API's identifiers.yaml",
        ).groupValues[1]
        val pattern = assertNotNull(Regex("(?m)^ {2}pattern: '([^']+)'$").find(block)).groupValues[1]
        val maxLength = assertNotNull(Regex("(?m)^ {2}maxLength: (\\d+)$").find(block)).groupValues[1].toInt()

        assertEquals(CatalogReleaseVersions.CORE_PATTERN, pattern)
        assertEquals(CatalogReleaseVersions.MAX_LENGTH, maxLength)
        assertTrue(CatalogReleaseVersions.PATTERN.startsWith(CatalogReleaseVersions.CORE_PATTERN.removeSuffix("$")))
    }
}
