// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.migration

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/** Wire v8 removed stencil versions: a stencil reference names its stencil, not a version of it. */
internal object StencilReferenceVersions {
    /** Removes `version` and `draftVersion` from every stencil node, wherever a document embeds it. */
    fun strip(
        node: JsonNode,
        path: String,
        notices: MutableList<CatalogMigrationNotice>,
    ) {
        when (node) {
            is ObjectNode -> {
                val props = node["props"] as? ObjectNode
                if (node["type"]?.takeIf { it.isString }?.asString() == "stencil" && props != null) {
                    for (name in listOf("draftVersion", "version")) {
                        if (props.remove(name) != null) {
                            notices += CatalogMigrationNotice(
                                CatalogMigrationCodes.STENCIL_VERSION_REMOVED,
                                "$path.props.$name",
                                "stencil reference $name was removed; the stencil resolves through its catalog's dependency",
                            )
                        }
                    }
                }
                node.propertyNames().toList().sorted().forEach { name ->
                    strip(node[name], "$path.$name", notices)
                }
            }
            is ArrayNode -> node.forEachIndexed { index, child -> strip(child, "$path[$index]", notices) }
            else -> Unit
        }
    }
}
