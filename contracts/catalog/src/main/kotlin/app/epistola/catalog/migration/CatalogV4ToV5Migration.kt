// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.migration

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/** One explicit, composable catalog wire-schema migration step. */
internal interface CatalogSchemaMigration {
    val fromVersion: Int
    val toVersion: Int

    fun migrateManifest(tree: ObjectNode): CatalogMigrationStepResult

    fun migrateResource(
        tree: ObjectNode,
        path: String,
    ): CatalogMigrationStepResult
}

internal data class CatalogMigrationStepResult(
    val findings: List<CatalogMigrationFinding> = emptyList(),
    val notices: List<CatalogMigrationNotice> = emptyList(),
)

/**
 * Replaces catalog-v4's boolean stencil draft marker with catalog-v5's exact
 * version provenance. Portable v4 cannot resolve a mutable Suite draft, so a
 * stale true marker is removed with a notice while its published base and
 * embedded content remain untouched.
 */
internal class CatalogV4ToV5Migration : CatalogSchemaMigration {
    override val fromVersion: Int = 4
    override val toVersion: Int = 5

    override fun migrateManifest(tree: ObjectNode): CatalogMigrationStepResult {
        tree.put("schemaVersion", toVersion)
        return CatalogMigrationStepResult()
    }

    override fun migrateResource(
        tree: ObjectNode,
        path: String,
    ): CatalogMigrationStepResult {
        val findings = mutableListOf<CatalogMigrationFinding>()
        val notices = mutableListOf<CatalogMigrationNotice>()
        tree.put("schemaVersion", toVersion)
        tree["resource"]?.let { visit(it, "$path.resource", findings, notices) }
        return CatalogMigrationStepResult(findings, notices)
    }

    private fun visit(
        node: JsonNode,
        path: String,
        findings: MutableList<CatalogMigrationFinding>,
        notices: MutableList<CatalogMigrationNotice>,
    ) {
        when {
            node.isObject -> {
                val objectNode = node as ObjectNode
                if (objectNode["type"]?.asString() == "stencil" && objectNode["props"]?.isObject == true) {
                    val props = objectNode["props"] as ObjectNode
                    val marker = props["isDraft"]
                    if (marker != null && !marker.isBoolean) {
                        findings += CatalogMigrationFinding(
                            CatalogMigrationCodes.DRAFT_MARKER_INVALID,
                            "$path.props.isDraft",
                            "catalog-v4 stencil reference isDraft must be a boolean",
                        )
                    } else if (marker?.asBoolean() == true) {
                        notices += CatalogMigrationNotice(
                            CatalogMigrationCodes.STALE_DRAFT_MARKER_REMOVED,
                            "$path.props.isDraft",
                            "removed stale catalog-v4 draft marker; the published version and embedded content were preserved",
                        )
                    }
                    props.remove("isDraft")
                }
                objectNode.propertyNames().toList().sorted().forEach { name ->
                    visit(objectNode[name], "$path.$name", findings, notices)
                }
            }
            node.isArray -> node.forEachIndexed { index, child -> visit(child, "$path[$index]", findings, notices) }
        }
    }
}
