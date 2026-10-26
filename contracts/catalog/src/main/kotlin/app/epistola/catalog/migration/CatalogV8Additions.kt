// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.migration

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * The resource members catalog wire v8 added that a legacy fingerprint algorithm does not cover.
 *
 * | Where | Member |
 * | --- | --- |
 * | a template's data examples | `key` (assigned from the name by [CatalogV7ToV8Migration]) |
 * | a template | `description` |
 * | a template's variants | `metadata`, `description` |
 * | a stencil | `readsTemplateData` |
 * | every type but a stencil | `tags` |
 *
 * V1 to V4 canonicalize a resource as a catalog-v7 producer serialized it, so they leave these out:
 * otherwise a key the migration derived would move every fingerprint a v7 producer published. V5
 * covers them. Like [CatalogKeyRenames], the list lives here once.
 */
internal object CatalogV8Additions {
    /** A copy of [resource], a resource payload, without the members wire v8 added. */
    fun withoutAdditions(resource: JsonNode): JsonNode {
        if (resource !is ObjectNode) return resource
        when (resource["type"]?.takeIf { it.isString }?.asString()) {
            "template" -> Unit
            "stencil" -> return resource.deepCopy().apply { remove("readsTemplateData") }
            else -> return if (resource.has("tags")) resource.deepCopy().apply { remove("tags") } else resource
        }
        val copy = resource.deepCopy()
        copy.remove("tags")
        copy.remove("description")
        (copy["dataExamples"] as? ArrayNode)?.forEach { (it as? ObjectNode)?.remove("key") }
        (copy["variants"] as? ArrayNode)?.forEach {
            (it as? ObjectNode)?.let { variant ->
                variant.remove("metadata")
                variant.remove("description")
            }
        }
        return copy
    }
}
