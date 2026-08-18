// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.migration

import app.epistola.catalog.protocol.CatalogManifest
import tools.jackson.databind.node.ObjectNode
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
        assertEquals(6, assertNotNull(result.value).schemaVersion)
        assertEquals("fixture", result.value.catalog.slug)
        assertEquals(emptyList(), result.value.catalog.attributes)
        assertEquals(emptySet(), result.value.catalog.keywords)
    }

    @Test
    fun `current golden wire version binds without migration`() {
        val result = CatalogSchemaMigrator.migrateManifest(resource("wire-v6/catalog.json"))

        assertTrue(result.valid)
        assertEquals(6, result.sourceVersion)
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
        val tooNew = minimalManifest(7)
        val malformed = CatalogSchemaMigrator.migrateManifest(ByteArrayInputStream("{".toByteArray()))

        assertEquals(CatalogMigrationCodes.SCHEMA_TOO_NEW, tooNew.findings.single().code)
        assertEquals(CatalogMigrationCodes.SCHEMA_UNKNOWN, malformed.findings.single().code)
    }

    @Test
    fun `v5 migration supplies empty optional discovery metadata without inventing attributes`() {
        val result = CatalogSchemaMigrator.migrateManifest(resource("wire-v5/catalog.json"))
        val catalog = assertNotNull(result.value).catalog
        val tree = resource("wire-v5/catalog.json").use(mapper::readTree) as ObjectNode
        val step = CatalogV5ToV6Migration().migrateManifest(tree)

        assertTrue(result.valid)
        assertEquals(5, result.sourceVersion)
        assertEquals(emptyList(), catalog.attributes)
        assertEquals(emptySet(), catalog.keywords)
        assertTrue(result.notices.isEmpty())
        assertEquals(resource("migrations/v5-to-v6/manifest-expected.json").use(mapper::readTree), tree)
        assertEquals(resource("migrations/v5-to-v6/notices.json").use(mapper::readTree), mapper.valueToTree(step.notices))
    }

    @Test
    fun `keyword wire validation preserves exact text and rejects malformed arrays before binding`() {
        val valid = v6ManifestWithKeywords("Government", "government")
        val duplicate = v6ManifestWithKeywords("documents", "documents")
        val untrimmed = v6ManifestWithKeywords(" documents ")

        assertEquals(setOf("Government", "government"), assertNotNull(valid.value).catalog.keywords)
        assertEquals(CatalogMigrationCodes.KEYWORD_DUPLICATE, duplicate.findings.single().code)
        assertEquals("catalog.json.catalog.keywords[1]", duplicate.findings.single().path)
        assertEquals(CatalogMigrationCodes.KEYWORD_INVALID, untrimmed.findings.single().code)
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
    fun `v4 migration preserves object-valued type properties in JSON Schema`() {
        val tree = mapper.readTree(
            """
            {
              "schemaVersion": 4,
              "resource": {
                "parameterSchema": {
                  "type": "object",
                  "properties": {
                    "type": {
                      "type": "string",
                      "description": "Type activiteit"
                    }
                  }
                }
              }
            }
            """.trimIndent(),
        ) as ObjectNode
        val parameterSchema = tree["resource"]["parameterSchema"].toString()

        val result = CatalogV4ToV5Migration().migrateResource(tree, "resources/stencil/letter.json")

        assertTrue(result.findings.isEmpty())
        assertTrue(result.notices.isEmpty())
        assertEquals(parameterSchema, tree["resource"]["parameterSchema"].toString())
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

    private fun v6ManifestWithKeywords(vararg keywords: String): CatalogMigrationResult<CatalogManifest> {
        val encoded = keywords.joinToString(",") { mapper.writeValueAsString(it) }
        val json = """
            {"schemaVersion":6,"catalog":{"slug":"x","name":"X","keywords":[$encoded]},
            "publisher":{"name":"X"},"release":{"version":"1.0.0"},"resources":[]}
        """.trimIndent()
        return CatalogSchemaMigrator.migrateManifest(ByteArrayInputStream(json.toByteArray()))
    }

    private fun resource(path: String) = requireNotNull(javaClass.getResourceAsStream("/META-INF/epistola-catalog/fixtures/v1/$path"))
}
