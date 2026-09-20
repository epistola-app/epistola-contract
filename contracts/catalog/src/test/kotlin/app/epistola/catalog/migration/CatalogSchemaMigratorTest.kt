// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.migration

import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.PublisherInfo
import app.epistola.catalog.protocol.ReleaseInfo
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
        assertEquals(CatalogWireSchema.CURRENT_VERSION, assertNotNull(result.value).schemaVersion)
        assertEquals("fixture", result.value.catalog.slug)
        assertEquals(emptyList(), result.value.catalog.attributes)
        assertEquals(emptySet(), result.value.catalog.keywords)
        assertEquals(null, result.value.catalog.license)
    }

    @Test
    fun `current golden wire version binds without migration`() {
        val result = CatalogSchemaMigrator.migrateManifest(resource("wire-v7/catalog.json"))

        assertTrue(result.valid)
        assertEquals(CatalogWireSchema.CURRENT_VERSION, result.sourceVersion)
        assertTrue(result.notices.isEmpty())
        assertEquals("fixture", assertNotNull(result.value).catalog.slug)
    }

    @Test
    fun `v6 golden wire version with conforming keywords migrates without notices`() {
        val result = CatalogSchemaMigrator.migrateManifest(resource("wire-v6/catalog.json"))

        assertTrue(result.valid, result.findings.toString())
        assertEquals(6, result.sourceVersion)
        assertEquals(CatalogWireSchema.CURRENT_VERSION, assertNotNull(result.value).schemaVersion)
        assertEquals(setOf("documents", "government"), result.value.catalog.keywords)
        assertTrue(result.notices.isEmpty())
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
        val tooNew = minimalManifest(CatalogWireSchema.CURRENT_VERSION + 1)
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
        assertEquals(null, catalog.license)
        assertTrue(result.notices.isEmpty())
        assertEquals(resource("migrations/v5-to-v6/manifest-expected.json").use(mapper::readTree), tree)
        assertEquals(resource("migrations/v5-to-v6/notices.json").use(mapper::readTree), mapper.valueToTree(step.notices))
    }

    @Test
    fun `v6 keywords normalize with notices while keywords that were invalid v6 remain findings`() {
        val merged = manifestWithKeywords(6, "Government", "government")
        val duplicate = manifestWithKeywords(6, "documents", "documents")
        val untrimmed = manifestWithKeywords(6, " documents ")
        val nonString = manifestWithKeywords(6, 1)

        assertEquals(setOf("government"), assertNotNull(merged.value).catalog.keywords)
        assertEquals(
            listOf(
                CatalogMigrationNotice(
                    CatalogMigrationCodes.KEYWORD_NORMALIZED,
                    "catalog.json.catalog.keywords[0]",
                    "keyword 'Government' was normalized to 'government' and merged with an identical keyword",
                ),
            ),
            merged.notices,
        )
        assertEquals(CatalogMigrationCodes.KEYWORD_DUPLICATE, duplicate.findings.single().code)
        assertEquals("catalog.json.catalog.keywords[1]", duplicate.findings.single().path)
        assertEquals(CatalogMigrationCodes.KEYWORD_INVALID, untrimmed.findings.single().code)
        assertEquals(CatalogMigrationCodes.KEYWORD_INVALID, nonString.findings.single().code)
    }

    @Test
    fun `a v6 asset dependency naming no catalog is reported, not guessed`() {
        val input = mapper.readTree(
            """{"schemaVersion":6,"catalog":{"slug":"invoices","name":"Invoices"},
               "dependencies":[{"type":"asset","slug":"logo"},
                               {"type":"theme","catalogKey":"shared","slug":"base"}]}""",
        ) as ObjectNode
        val step = CatalogV6ToV7Migration().migrateManifest(input)

        val finding = step.findings.single()
        assertEquals(CatalogMigrationCodes.DEPENDENCY_UNQUALIFIED, finding.code)
        assertEquals("catalog.json.dependencies[0]", finding.path)
    }

    @Test
    fun `a v6 manifest whose dependencies are all qualified migrates cleanly`() {
        val input = mapper.readTree(
            """{"schemaVersion":6,"catalog":{"slug":"invoices","name":"Invoices"},
               "dependencies":[{"type":"asset","catalogKey":"shared","slug":"logo"}]}""",
        ) as ObjectNode

        assertTrue(CatalogV6ToV7Migration().migrateManifest(input).findings.isEmpty())
    }

    @Test
    fun `v6 variant ids migrate to v7 slugs, values unchanged`() {
        val input = resource("migrations/v6-to-v7/template-variants-input.json").use(mapper::readTree) as ObjectNode
        val step = CatalogV6ToV7Migration().migrateResource(input, "resources/template/invoice.json", CatalogMigrationContext(6, emptyManifest()))
        val expected = resource("migrations/v6-to-v7/template-variants-expected.json").use(mapper::readTree)

        assertTrue(step.findings.isEmpty(), step.findings.toString())
        // A rename with no repair: nothing references a variant across catalogs, so no stored
        // reference elsewhere names it and none is left dangling. Hence no notices either.
        assertTrue(step.notices.isEmpty(), step.notices.toString())
        assertEquals(expected, input)
    }

    @Test
    fun `a v6 variant already carrying a slug keeps it`() {
        val input = mapper.readTree(
            """{"schemaVersion":6,"type":"template","slug":"invoice","name":"Invoice",
               "variants":[{"slug":"keep","id":"discard"}]}""",
        ) as ObjectNode
        CatalogV6ToV7Migration().migrateResource(input, "resources/template/invoice.json", CatalogMigrationContext(6, emptyManifest()))

        assertEquals("keep", input["variants"][0]["slug"].asString())
    }

    @Test
    fun `v6 golden keywords migrate to their v7 form with notices`() {
        val input = resource("migrations/v6-to-v7/manifest-input.json").use(mapper::readTree) as ObjectNode
        val step = CatalogV6ToV7Migration().migrateManifest(input)
        val result = CatalogSchemaMigrator.migrateManifest(resource("migrations/v6-to-v7/manifest-input.json"))
        val expected = resource("migrations/v6-to-v7/manifest-expected.json").use(mapper::readTree)

        assertTrue(step.findings.isEmpty(), step.findings.toString())
        assertEquals(expected, input)
        assertEquals(resource("migrations/v6-to-v7/notices.json").use(mapper::readTree), mapper.valueToTree(step.notices))
        assertTrue(result.valid, result.findings.toString())
        assertEquals(step.notices, result.notices)
        assertEquals(expected["catalog"]["keywords"].mapTo(mutableListOf()) { it.asString() }, assertNotNull(result.value).catalog.keywords.toList())
    }

    @Test
    fun `native v7 keywords are checked against every wire rule before binding`() {
        fun codes(vararg keywords: Any) = manifestWithKeywords(CatalogWireSchema.CURRENT_VERSION, *keywords).findings
            .map { it.code to it.path }

        assertEquals(listOf(CatalogMigrationCodes.KEYWORD_INVALID to "catalog.json.catalog.keywords[0]"), codes("Government"))
        assertEquals(listOf(CatalogMigrationCodes.KEYWORD_INVALID to "catalog.json.catalog.keywords[0]"), codes("getting started"))
        assertEquals(listOf(CatalogMigrationCodes.KEYWORD_INVALID to "catalog.json.catalog.keywords[0]"), codes(1))
        assertEquals(listOf(CatalogMigrationCodes.KEYWORD_TOO_LONG to "catalog.json.catalog.keywords[0]"), codes("a".repeat(31)))
        assertEquals(listOf(CatalogMigrationCodes.KEYWORD_DUPLICATE to "catalog.json.catalog.keywords[1]"), codes("a", "a"))
        assertEquals(
            listOf(CatalogMigrationCodes.KEYWORD_LIMIT_EXCEEDED to "catalog.json.catalog.keywords"),
            codes(*Array(21) { "keyword-$it" }),
        )
        assertTrue(manifestWithKeywords(CatalogWireSchema.CURRENT_VERSION, "1-loket", "a".repeat(30)).valid)
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

        val result = CatalogV4ToV5Migration().migrateResource(tree, "resources/stencil/letter.json", CatalogMigrationContext(6, emptyManifest()))

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

    private fun manifestWithKeywords(
        version: Int,
        vararg keywords: Any,
    ): CatalogMigrationResult<CatalogManifest> {
        val encoded = keywords.joinToString(",") { mapper.writeValueAsString(it) }
        val json = """
            {"schemaVersion":$version,"catalog":{"slug":"x","name":"X","keywords":[$encoded]},
            "publisher":{"name":"X"},"release":{"version":"1.0.0"},"resources":[]}
        """.trimIndent()
        return CatalogSchemaMigrator.migrateManifest(ByteArrayInputStream(json.toByteArray()))
    }

    private fun resource(path: String) = requireNotNull(javaClass.getResourceAsStream("/META-INF/epistola-catalog/fixtures/v1/$path"))

    /** A manifest with no resources: enough for a migration that does not look anything up. */
    private fun emptyManifest() = CatalogManifest(
        schemaVersion = 6,
        catalog = CatalogInfo("invoices", "Invoices"),
        publisher = PublisherInfo("Example"),
        release = ReleaseInfo("1.0.0"),
        resources = emptyList(),
    )
}
