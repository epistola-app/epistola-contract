// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.migration

import app.epistola.catalog.protocol.CatalogManifest
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CatalogSchemaMigratorTest {
    private val mapper = jsonMapper { addModule(kotlinModule()) }

    @Test
    fun `v4 golden wire version migrates to current`() {
        val result = CatalogSchemaMigrator.migrateManifest(resource("wire-v4/catalog.json"))

        assertTrue(result.valid)
        assertEquals(4, result.sourceVersion)
        assertEquals(5, assertNotNull(result.value).schemaVersion)
        assertEquals("fixture", result.value.catalog.slug)
    }

    @Test
    fun `current golden wire version binds without migration`() {
        val result = CatalogSchemaMigrator.migrateManifest(resource("wire-v5/catalog.json"))

        assertTrue(result.valid)
        assertEquals(5, result.sourceVersion)
        assertTrue(result.notices.isEmpty())
        assertEquals("fixture", assertNotNull(result.value).catalog.slug)
    }

    @Test
    fun `sub-current version is rejected even when it resembles the current shape`() {
        val bytes = resource("wire-v4/catalog.json").readAllBytes()
            .toString(Charsets.UTF_8)
            .replace("\"schemaVersion\": 4", "\"schemaVersion\": 3")
            .toByteArray()
        val result = CatalogSchemaMigrator.migrateManifest(ByteArrayInputStream(bytes))

        assertEquals(CatalogMigrationCodes.SCHEMA_TOO_OLD, result.findings.single().code)
        assertEquals(3, result.sourceVersion)
    }

    @Test
    fun `newer and malformed manifests return findings`() {
        val tooNew = minimalManifest(6)
        val malformed = CatalogSchemaMigrator.migrateManifest(ByteArrayInputStream("{".toByteArray()))

        assertEquals(CatalogMigrationCodes.SCHEMA_TOO_NEW, tooNew.findings.single().code)
        assertEquals(CatalogMigrationCodes.SCHEMA_UNKNOWN, malformed.findings.single().code)
    }

    @Test
    fun `detail must match catalog version and declared type`() {
        val manifest = assertNotNull(CatalogSchemaMigrator.migrateManifest(resource("wire-v4/catalog.json")).value)
        val context = CatalogMigrationContext(4, manifest)
        val detailBytes = resource("wire-v4/resources/theme/default.json").readAllBytes()
        val wrongVersion = detailBytes.toString(Charsets.UTF_8)
            .replace("\"schemaVersion\": 4", "\"schemaVersion\": 3")
            .toByteArray()

        val version = CatalogSchemaMigrator.migrateResourceDetail(
            "theme",
            ByteArrayInputStream(wrongVersion),
            context,
            "resources/theme/default.json",
        )
        val type = CatalogSchemaMigrator.migrateResourceDetail(
            "template",
            ByteArrayInputStream(detailBytes),
            context,
            "resources/theme/default.json",
        )

        assertEquals(CatalogMigrationCodes.SCHEMA_VERSION_MISMATCH, version.findings.single().code)
        assertEquals(CatalogMigrationCodes.RESOURCE_TYPE_MISMATCH, type.findings.single().code)
    }

    @Test
    fun `v4 stencil markers migrate recursively and stale true markers produce notices`() {
        val manifest = assertNotNull(CatalogSchemaMigrator.migrateManifest(resource("wire-v4/catalog.json")).value)
        val result = CatalogSchemaMigrator.migrateResourceDetail(
            "stencil",
            resource("migrations/v4-to-v5/stencil-input.json"),
            CatalogMigrationContext(4, manifest),
            "resources/stencil/letter.json",
        )
        val currentManifest = assertNotNull(CatalogSchemaMigrator.migrateManifest(resource("wire-v5/catalog.json")).value)
        val expected = CatalogSchemaMigrator.migrateResourceDetail(
            "stencil",
            resource("migrations/v4-to-v5/stencil-expected.json"),
            CatalogMigrationContext(5, currentManifest),
            "resources/stencil/letter.json",
        )
        val expectedNotices = resource("migrations/v4-to-v5/notices.json").use(mapper::readTree)

        assertTrue(result.valid)
        assertEquals(expected.value, result.value)
        assertEquals(expectedNotices, mapper.valueToTree(result.notices))
    }

    @Test
    fun `v4 stencil marker must be boolean`() {
        val manifest = assertNotNull(CatalogSchemaMigrator.migrateManifest(resource("wire-v4/catalog.json")).value)
        val malformed = resource("migrations/v4-to-v5/stencil-input.json").readAllBytes()
            .toString(Charsets.UTF_8)
            .replace("\"isDraft\": true", "\"isDraft\": \"true\"")
            .toByteArray()

        val result = CatalogSchemaMigrator.migrateResourceDetail(
            "stencil",
            ByteArrayInputStream(malformed),
            CatalogMigrationContext(4, manifest),
            "resources/stencil/letter.json",
        )

        assertEquals(CatalogMigrationCodes.DRAFT_MARKER_INVALID, result.findings.single().code)
        assertEquals(
            "resources/stencil/letter.json.resource.content.nodes.nested.props.isDraft",
            result.findings.single().path,
        )
    }

    private fun minimalManifest(version: Int): CatalogMigrationResult<CatalogManifest> {
        val json = """
            {"schemaVersion":$version,"catalog":{"slug":"x","name":"X"},"publisher":{"name":"X"},
            "release":{"version":"1.0.0"},"resources":[]}
        """.trimIndent()
        return CatalogSchemaMigrator.migrateManifest(ByteArrayInputStream(json.toByteArray()))
    }

    private fun resource(path: String) = requireNotNull(javaClass.getResourceAsStream("/META-INF/epistola-catalog/fixtures/v1/$path"))
}
