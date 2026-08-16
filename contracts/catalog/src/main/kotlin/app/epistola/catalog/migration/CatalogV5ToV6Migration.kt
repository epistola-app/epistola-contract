// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.migration

import tools.jackson.databind.node.ObjectNode

/** Adds the optional authored catalog-discovery metadata introduced by wire v6. */
internal class CatalogV5ToV6Migration : CatalogSchemaMigration {
    override val fromVersion: Int = 5
    override val toVersion: Int = 6

    override fun migrateManifest(tree: ObjectNode): CatalogMigrationStepResult {
        tree.put("schemaVersion", toVersion)
        val catalog = tree["catalog"] as? ObjectNode ?: return CatalogMigrationStepResult()
        val notices = mutableListOf<CatalogMigrationNotice>()
        if (catalog["defaultLanguage"] == null || catalog["defaultLanguage"].isNull) {
            catalog.put("defaultLanguage", DEFAULT_LANGUAGE)
            notices += CatalogMigrationNotice(
                CatalogMigrationCodes.DEFAULT_LANGUAGE_ADDED,
                "catalog.json.catalog.defaultLanguage",
                "defaulted catalog-v5 metadata to $DEFAULT_LANGUAGE",
            )
        }
        if (catalog["keywords"] == null || catalog["keywords"].isNull) {
            catalog.putArray("keywords")
        }
        return CatalogMigrationStepResult(notices = notices)
    }

    override fun migrateResource(
        tree: ObjectNode,
        path: String,
    ): CatalogMigrationStepResult {
        tree.put("schemaVersion", toVersion)
        return CatalogMigrationStepResult()
    }

    private companion object {
        const val DEFAULT_LANGUAGE = "nl-NL"
    }
}
