// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.migration

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * The v7-to-v8 migration of one document a producer stores outside an archive: a template document
 * (a variant's template model, a stencil's content) or a style map (a theme's document styles, a
 * block style preset's styles).
 *
 * A producer that keeps catalog content in its own store, as Suite does, migrates it with these so
 * that its store and an archive migrate to the same result: the renamed members ([CatalogKeyRenames])
 * get their wire-v8 names, the keys they reference follow the wire-v8 key rule
 * ([CatalogKeys.migrate]), and stencil references lose their versions. It is what
 * [CatalogV7ToV8Migration] does to the same document inside a resource, and nothing more: keys a
 * resource defines, data examples and the catalog identity are the producer's, not the document's.
 *
 * Both functions leave their input unchanged, and are idempotent: a document already at wire v8
 * comes back equal, with no notices.
 */
object CatalogDocumentMigration {

    /** The migrated document, and what changed in it beyond renaming. */
    data class Migrated(
        val document: JsonNode,
        val notices: List<CatalogMigrationNotice>,
    )

    /** One template document from wire v7 to v8. [path] prefixes the notices' paths. */
    @JvmStatic
    @JvmOverloads
    fun templateDocumentV7ToV8(
        document: JsonNode,
        path: String = "document",
    ): Migrated {
        val copy = document.deepCopy()
        if (copy !is ObjectNode) return Migrated(copy, emptyList())
        val sites = CatalogKeyRenames.documentSites(copy, path)
        CatalogKeyRenames.toCurrent(sites)
        sites.forEach { CatalogKeyRewrites.rewriteReference(it.owner, it.newName) }
        val notices = mutableListOf<CatalogMigrationNotice>()
        StencilReferenceVersions.strip(copy, path, notices)
        return Migrated(copy, notices)
    }

    /** One style map (with a possible `fontFamily` reference) from wire v7 to v8. */
    @JvmStatic
    @JvmOverloads
    fun stylesV7ToV8(
        styles: JsonNode,
        path: String = "styles",
    ): Migrated {
        val copy = styles.deepCopy()
        val sites = CatalogKeyRenames.styleSites(copy, path)
        CatalogKeyRenames.toCurrent(sites)
        sites.forEach { CatalogKeyRewrites.rewriteReference(it.owner, it.newName) }
        return Migrated(copy, emptyList())
    }
}
