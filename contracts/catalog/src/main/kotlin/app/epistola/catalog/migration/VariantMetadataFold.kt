// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.migration

import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * An early wire-v8 template carried producer metadata per variant (`variants[].metadata`). Wire v8
 * now carries it per resource ([app.epistola.catalog.protocol.CatalogResource.metadata]), so a
 * template read with variant metadata has it folded into its own, keyed by variant: a variant's
 * `{"<namespace>": V}` becomes the template's `{"<namespace>": {"variants": {"<variant key>": V}}}`.
 *
 * The fold is a pure function of the document, so independently read archives agree, and it
 * invents nothing: every value the producer wrote is kept, member for member, under the namespace
 * it was written in. A value that cannot be placed without overwriting one already there, because
 * the template's own metadata holds that namespace's `variants` for that variant already or holds
 * a namespace that is not an object, is a finding, never a choice. Metadata counts in no fingerprint,
 * so the fold moves none.
 */
internal object VariantMetadataFold {
    fun fold(
        resource: ObjectNode,
        path: String,
        findings: MutableList<CatalogMigrationFinding>,
        notices: MutableList<CatalogMigrationNotice>,
    ) {
        if (resource["type"]?.takeIf { it.isString }?.asString() != "template") return
        val variants = resource["variants"] as? ArrayNode ?: return
        variants.forEachIndexed { index, node ->
            val variant = node as? ObjectNode ?: return@forEachIndexed
            if (!variant.has("metadata")) return@forEachIndexed
            val variantPath = "$path.variants[$index].metadata"
            val metadata = variant["metadata"] as? ObjectNode
            val key = variant["key"]?.takeIf { it.isString }?.asString() ?: variant["slug"]?.takeIf { it.isString }?.asString()
            if (metadata == null || key == null) {
                findings += CatalogMigrationFinding(CatalogMigrationCodes.VARIANT_METADATA_UNFOLDABLE, variantPath, "a variant's metadata must be an object on a variant with a key")
                return@forEachIndexed
            }
            val target = (resource["metadata"] as? ObjectNode) ?: resource.putObject("metadata")
            for (namespace in metadata.propertyNames().sorted()) {
                val existing = target[namespace]
                val owned = when {
                    existing == null -> target.putObject(namespace)
                    existing is ObjectNode -> existing
                    else -> null
                }
                val byVariant = owned?.let { (it["variants"] as? ObjectNode) ?: if (it.has("variants")) null else it.putObject("variants") }
                if (byVariant == null || byVariant.has(key)) {
                    findings += CatalogMigrationFinding(
                        CatalogMigrationCodes.VARIANT_METADATA_UNFOLDABLE,
                        "$variantPath.$namespace",
                        "variant '$key' metadata in namespace '$namespace' cannot move to the template's metadata: " +
                            "it already holds metadata.$namespace.variants.$key, or that namespace is not an object",
                    )
                    continue
                }
                byVariant.set(key, metadata[namespace].deepCopy())
            }
            variant.remove("metadata")
            notices += CatalogMigrationNotice(
                CatalogMigrationCodes.VARIANT_METADATA_FOLDED,
                variantPath,
                "variant '$key' metadata moved to the template's metadata, under each namespace's variants.$key",
            )
        }
        (resource["metadata"] as? ObjectNode)?.let { if (it.isEmpty) resource.remove("metadata") }
    }
}
