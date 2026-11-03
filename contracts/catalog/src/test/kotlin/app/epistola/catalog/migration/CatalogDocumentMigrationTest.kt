// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.migration

import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.PublisherInfo
import app.epistola.catalog.protocol.ReleaseInfo
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A producer's stored document migrates to exactly what the archive migration makes of the same
 * document inside a resource, so a store and an archive cannot drift apart.
 */
class CatalogDocumentMigrationTest {
    private val mapper = JsonMapper.builder().build()

    /** A v7 template document: theme reference, stencil and image nodes, fonts, a 2-character key. */
    private val v7Document: JsonNode = mapper.readTree(
        """
        {
          "modelVersion": 1,
          "root": "root",
          "themeRef": {"type": "override", "themeId": "co"},
          "documentStylesOverride": {"fontFamily": {"slug": "inter"}},
          "nodes": {
            "root": {"id": "root", "type": "root", "slots": ["root-children"]},
            "inst": {"id": "inst", "type": "stencil", "slots": ["inst-children"],
                     "props": {"stencilId": "letterhead", "catalogKey": "shared", "version": 3, "draftVersion": 4},
                     "styles": {"fontFamily": {"slug": "serif", "catalogKey": "system"}}},
            "logo": {"id": "logo", "type": "image", "props": {"assetId": "lg", "alt": "Logo"}}
          },
          "slots": {
            "root-children": {"id": "root-children", "nodeId": "root", "name": "children", "children": ["inst", "logo"]},
            "inst-children": {"id": "inst-children", "nodeId": "inst", "name": "children", "children": []}
          }
        }
        """.trimIndent(),
    )

    @Test
    fun `renames, follows the key rule and drops stencil versions`() {
        val migrated = CatalogDocumentMigration.templateDocumentV7ToV8(v7Document)
        val doc = migrated.document

        assertEquals("co0", doc["themeRef"]["themeKey"].asString())
        assertEquals("inter", doc["documentStylesOverride"]["fontFamily"]["key"].asString())
        assertEquals(mapOf("stencilKey" to "letterhead", "catalogKey" to "shared"), props(doc, "inst"))
        assertEquals("serif", doc["nodes"]["inst"]["styles"]["fontFamily"]["key"].asString())
        assertEquals(mapOf("imageKey" to "lg0", "alt" to "Logo"), props(doc, "logo"))
        assertEquals(
            listOf("document.nodes.inst.props.draftVersion", "document.nodes.inst.props.version"),
            migrated.notices.map { it.path }.sorted(),
        )
        assertTrue(migrated.notices.all { it.code == CatalogMigrationCodes.STENCIL_VERSION_REMOVED })
    }

    @Test
    fun `leaves its input alone and is idempotent`() {
        val before = v7Document.deepCopy()
        val once = CatalogDocumentMigration.templateDocumentV7ToV8(v7Document)
        val twice = CatalogDocumentMigration.templateDocumentV7ToV8(once.document)

        assertEquals(before, v7Document)
        assertEquals(once.document, twice.document)
        assertEquals(emptyList(), twice.notices)
    }

    @Test
    fun `matches what the archive migration makes of the same document inside a stencil`() {
        val detail = mapper.createObjectNode().apply {
            put("schemaVersion", 7)
            set(
                "resource",
                mapper.createObjectNode().apply {
                    put("type", "stencil")
                    put("slug", "letterhead")
                    put("name", "Letterhead")
                    put("version", 1)
                    set("content", v7Document.deepCopy())
                },
            )
        }

        CatalogV7ToV8Migration().migrateResource(detail, "resources/stencil/letterhead.json", CatalogMigrationContext(7, manifest()))

        assertEquals(detail["resource"]["content"], CatalogDocumentMigration.templateDocumentV7ToV8(v7Document).document)
    }

    @Test
    fun `migrates a style map's font reference`() {
        val styles = mapper.readTree("""{"fontFamily": {"slug": "in", "catalogKey": "system"}, "fontSize": "12pt"}""")

        val migrated = CatalogDocumentMigration.stylesV7ToV8(styles).document

        assertEquals(mapper.readTree("""{"fontFamily": {"key": "in0", "catalogKey": "system"}, "fontSize": "12pt"}"""), migrated)
    }

    private fun props(document: JsonNode, id: String): Map<String, Any?> = mapper.convertValue((document["nodes"][id]["props"] as ObjectNode), Map::class.java).mapKeys { it.key as String }

    private fun manifest() = CatalogManifest(
        schemaVersion = 7,
        catalog = CatalogInfo("letters", "Letters"),
        publisher = PublisherInfo("Example"),
        release = ReleaseInfo("1.0.0"),
        resources = emptyList(),
    )
}
