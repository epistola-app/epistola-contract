// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.protocol

import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRefOverride

/**
 * Every place a catalog's content names another catalog, by the alias its dependency list declares
 * ([CatalogAliases], contract ADR 0003), listed once for the validator and the migration.
 *
 * | Where | Names |
 * | --- | --- |
 * | a template | `themeCatalogKey` (with `themeKey`) |
 * | a template document's theme reference | `themeRef.catalogKey` (with `themeKey`) |
 * | a stencil node's props | `catalogKey` (with `stencilKey`) |
 * | an image node's props | `catalogKey` (with `imageKey`) |
 * | a `fontFamily` font reference in theme, preset, document or node styles | `catalogKey` (with `key`) |
 * | an attribute's code-list binding | `catalogKey` (with `key`) |
 * | a variant's attribute key | `<alias>.<key>` (`system.locale`) |
 *
 * The manifest's own references (`catalog.defaultTheme`, the `dependencies` index) are checked where
 * the manifest is. A reference whose alias is the catalog's own key is to the catalog itself; the
 * callers tell those apart.
 */
internal object CatalogReferences {
    /**
     * One reference into a catalog named by alias.
     *
     * @property type the resource type referred to (`theme`, `stencil`, `image`, `font`, `codeList`,
     *   `attribute`).
     * @property key the resource's key in that catalog, or null when the content names none.
     * @property alias the alias the reference names.
     * @property path where the alias is named.
     */
    data class Reference(
        val type: String,
        val key: String?,
        val alias: String,
        val path: String,
    )

    /** The references [resource] makes by alias, its payload at [path] (`resources/theme/house.json.resource`). */
    fun inResource(
        resource: CatalogResource,
        path: String,
    ): List<Reference> {
        val references = mutableListOf<Reference>()
        when (resource) {
            is TemplateResource -> {
                resource.themeCatalogKey?.let { references += Reference("theme", resource.themeKey, it, "$path.themeCatalogKey") }
                inDocument(resource.templateModel, "$path.templateModel", references)
                resource.variants.forEachIndexed { index, variant ->
                    variant.templateModel?.let { inDocument(it, "$path.variants[$index].templateModel", references) }
                    variant.attributes?.keys?.sorted()?.forEach { attribute ->
                        val separator = attribute.indexOf(CatalogKeys.ATTRIBUTE_QUALIFIER_SEPARATOR)
                        if (separator > 0) {
                            references += Reference(
                                "attribute",
                                attribute.substring(separator + 1),
                                attribute.substring(0, separator),
                                "$path.variants[$index].attributes.$attribute",
                            )
                        }
                    }
                }
            }
            is StencilResource -> inDocument(resource.content, "$path.content", references)
            is ThemeResource -> {
                inStyles(resource.documentStyles, "$path.documentStyles", references)
                resource.blockStylePresets.orEmpty().toSortedMap().forEach { (name, preset) ->
                    inStyles(preset.styles, "$path.blockStylePresets.$name.styles", references)
                }
            }
            is AttributeResource -> resource.codeListBinding?.let { binding ->
                binding.catalogKey?.let { references += Reference("codeList", binding.key, it, "$path.codeListBinding.catalogKey") }
            }
            is CodeListResource, is ImageResource, is FontResource -> Unit
        }
        return references
    }

    private fun inDocument(
        document: TemplateDocument,
        path: String,
        references: MutableList<Reference>,
    ) {
        (document.themeRef as? ThemeRefOverride)?.let { theme ->
            theme.catalogKey?.let { references += Reference("theme", theme.themeKey, it, "$path.themeRef.catalogKey") }
        }
        inStyles(document.documentStylesOverride, "$path.documentStylesOverride", references)
        document.nodes.toSortedMap().forEach { (id, node) ->
            inStyles(node.styles, "$path.nodes.$id.styles", references)
            val props = node.props ?: return@forEach
            val alias = props["catalogKey"] as? String ?: return@forEach
            when (node.type) {
                "stencil" -> references += Reference("stencil", props["stencilKey"] as? String, alias, "$path.nodes.$id.props.catalogKey")
                "image" -> references += Reference("image", props["imageKey"] as? String, alias, "$path.nodes.$id.props.catalogKey")
            }
        }
    }

    private fun inStyles(
        styles: Map<String, Any?>?,
        path: String,
        references: MutableList<Reference>,
    ) {
        val font = styles?.get("fontFamily") as? Map<*, *> ?: return
        val alias = font["catalogKey"] as? String ?: return
        references += Reference("font", font["key"] as? String, alias, "$path.fontFamily.catalogKey")
    }
}
