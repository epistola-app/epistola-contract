// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.migration

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * The members catalog wire v8 renamed so that a resource's public address is called its `key`.
 *
 * A `key` is a resource's public address within its parent; an `id` is a generated identifier that
 * is not an address. Until wire v7 the catalog called the address `slug`, and references to it
 * `themeId`, `stencilId`, `assetId`, `iconAssetSlug` and `imageAssetSlugs`, and an attribute
 * assignment named its attribute's catalog `catalog`:
 *
 * | Where | Until wire v7 | From wire v8 |
 * | --- | --- | --- |
 * | `catalog.json` catalog, resource entries, resource dependencies | `slug` | `key` |
 * | `catalog.json` catalog presentation | `iconAssetSlug`, `imageAssetSlugs` | `iconImageKey`, `imageKeys` |
 * | `catalog.json` catalog attribute assignments | `catalog` | `catalogKey` |
 * | every resource, a template's variants, an attribute's code-list binding | `slug` | `key` |
 * | a template's default theme | `themeId` | `themeKey` |
 * | a template document's theme reference | `themeId` | `themeKey` |
 * | a stencil node's props | `stencilId` | `stencilKey` |
 * | an image node's props | `assetId` | `imageKey` |
 * | a `fontFamily` font reference in theme, preset, document or node styles | `slug` | `key` |
 *
 * The renames are listed once here and applied three ways: forward by [CatalogV7ToV8Migration],
 * detected by the migrator's wire check (a v8 document that still uses an old name is not wire v8),
 * and backward by the canonicalizer, whose legacy algorithms (V1 to V4) must keep reproducing the
 * fingerprints producers computed over the old names.
 *
 * Only these positions are renamed. A template's data model or a node's other props may use the
 * same words for something else, and are left alone.
 */
internal object CatalogKeyRenames {
    /** One renamed member at one position in a document. */
    data class Site(
        val owner: ObjectNode,
        val oldName: String,
        val newName: String,
        val path: String,
    )

    /** The renamed positions in a `catalog.json` tree. */
    fun manifestSites(tree: ObjectNode): List<Site> {
        val sites = mutableListOf<Site>()
        val catalog = tree["catalog"] as? ObjectNode
        if (catalog != null) {
            sites += Site(catalog, "slug", "key", "catalog.json.catalog")
            (catalog["presentation"] as? ObjectNode)?.let {
                sites += Site(it, "iconAssetSlug", "iconImageKey", "catalog.json.catalog.presentation")
                sites += Site(it, "imageAssetSlugs", "imageKeys", "catalog.json.catalog.presentation")
            }
            (catalog["attributes"] as? ArrayNode)?.forEachIndexed { index, attribute ->
                if (attribute is ObjectNode) sites += Site(attribute, "catalog", "catalogKey", "catalog.json.catalog.attributes[$index]")
            }
        }
        for (field in listOf("resources", "dependencies")) {
            (tree[field] as? ArrayNode)?.forEachIndexed { index, entry ->
                if (entry is ObjectNode) sites += Site(entry, "slug", "key", "catalog.json.$field[$index]")
            }
        }
        return sites
    }

    /**
     * The renamed positions in a resource payload: the `resource` object of a resource detail, at
     * [path] (such as `resources/theme/default.json.resource`).
     */
    fun resourceSites(
        resource: ObjectNode,
        path: String,
    ): List<Site> {
        val sites = mutableListOf(Site(resource, "slug", "key", path))
        when (resource["type"]?.takeIf { it.isString }?.asString()) {
            "template" -> {
                sites += Site(resource, "themeId", "themeKey", path)
                documentSites(resource["templateModel"], "$path.templateModel", sites)
                (resource["variants"] as? ArrayNode)?.forEachIndexed { index, variant ->
                    if (variant !is ObjectNode) return@forEachIndexed
                    sites += Site(variant, "slug", "key", "$path.variants[$index]")
                    documentSites(variant["templateModel"], "$path.variants[$index].templateModel", sites)
                }
            }
            "stencil" -> documentSites(resource["content"], "$path.content", sites)
            "theme" -> {
                fontSite(resource["documentStyles"], "$path.documentStyles", sites)
                (resource["blockStylePresets"] as? ObjectNode)?.let { presets ->
                    presets.propertyNames().toList().sorted().forEach { name ->
                        fontSite(presets[name]?.get("styles"), "$path.blockStylePresets.$name.styles", sites)
                    }
                }
            }
            "attribute" -> (resource["codeListBinding"] as? ObjectNode)?.let {
                sites += Site(it, "slug", "key", "$path.codeListBinding")
            }
        }
        return sites
    }

    /**
     * A template document's theme reference, the props of its stencil and image nodes, and the font
     * references in its document and node styles.
     */
    private fun documentSites(
        document: JsonNode?,
        path: String,
        sites: MutableList<Site>,
    ) {
        if (document !is ObjectNode) return
        (document["themeRef"] as? ObjectNode)?.let { sites += Site(it, "themeId", "themeKey", "$path.themeRef") }
        fontSite(document["documentStylesOverride"], "$path.documentStylesOverride", sites)
        val nodes = document["nodes"] as? ObjectNode ?: return
        nodes.propertyNames().toList().sorted().forEach { id ->
            val node = nodes[id] as? ObjectNode ?: return@forEach
            fontSite(node["styles"], "$path.nodes.$id.styles", sites)
            val props = node["props"] as? ObjectNode ?: return@forEach
            val propsPath = "$path.nodes.$id.props"
            when (node["type"]?.takeIf { it.isString }?.asString()) {
                "stencil" -> sites += Site(props, "stencilId", "stencilKey", propsPath)
                "image" -> sites += Site(props, "assetId", "imageKey", propsPath)
            }
        }
    }

    /** The font reference a style map names under `fontFamily`, when it is an object rather than a plain family name. */
    private fun fontSite(
        styles: JsonNode?,
        path: String,
        sites: MutableList<Site>,
    ) {
        val font = (styles as? ObjectNode)?.get("fontFamily") as? ObjectNode ?: return
        sites += Site(font, "slug", "key", "$path.fontFamily")
    }

    /**
     * Renames each old member to its new name, in place and keeping its position. A site that
     * already carries the new name is left alone: the old member then stays, and the wire check
     * reports it rather than one value silently replacing the other.
     */
    fun toCurrent(sites: List<Site>) = sites.forEach { rename(it.owner, it.oldName, it.newName) }

    /** Renames each new member back to its old name, in place: the shape a legacy fingerprint covers. */
    fun toLegacy(sites: List<Site>) = sites.forEach { rename(it.owner, it.newName, it.oldName) }

    /** The paths of the old members still present at [sites], each with the name that replaced it. */
    fun remainingOldMembers(sites: List<Site>): List<Pair<String, Site>> = sites
        .filter { it.owner.has(it.oldName) }
        .map { "${it.path}.${it.oldName}" to it }

    /**
     * A copy of [resource], a resource payload, with every renamed member carrying its old name: the
     * resource as a catalog-v7 producer serialized it.
     */
    fun legacyResource(resource: JsonNode): JsonNode {
        if (resource !is ObjectNode) return resource
        val copy = resource.deepCopy()
        toLegacy(resourceSites(copy, "resource"))
        return copy
    }

    private fun rename(
        owner: ObjectNode,
        from: String,
        to: String,
    ) {
        if (!owner.has(from) || owner.has(to)) return
        val members = owner.properties().map { (name, value) -> (if (name == from) to else name) to value }
        owner.removeAll()
        members.forEach { (name, value) -> owner.set(name, value) }
    }
}
