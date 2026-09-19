// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.migration

import app.epistola.catalog.protocol.CatalogKeywords
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * Normalizes catalog-v6 keywords to catalog-v7's bounded lowercase form.
 *
 * Keywords are discovery metadata that nothing references, so a keyword that cannot be carried
 * over unchanged is repaired with a notice rather than failing the catalog: it is rewritten to
 * lowercase ASCII with hyphens, shortened when longer than [CatalogKeywords.MAX_LENGTH], merged
 * when it collides with another, and removed when nothing is left of it or it sorts beyond
 * [CatalogKeywords.MAX_COUNT]. Keywords that were already invalid catalog-v6 remain findings.
 */
internal class CatalogV6ToV7Migration : CatalogSchemaMigration {
    override val fromVersion: Int = 6
    override val toVersion: Int = 7

    override fun migrateManifest(tree: ObjectNode): CatalogMigrationStepResult {
        tree.put("schemaVersion", toVersion)
        val catalog = tree["catalog"] as? ObjectNode ?: return CatalogMigrationStepResult()
        val keywords = catalog["keywords"] as? ArrayNode ?: return CatalogMigrationStepResult()
        // Non-string entries are reported by the wire check that follows migration.
        if (keywords.any { !it.isString }) return CatalogMigrationStepResult()

        val sources = keywords.mapTo(mutableListOf()) { it.asString() }
        val findings = v6Findings(sources)
        if (findings.isNotEmpty()) return CatalogMigrationStepResult(findings)

        val hyphenated = sources.map(CatalogKeywords::hyphenate)
        val normalized = hyphenated.map(CatalogKeywords::truncate)
        val sourcesByKeyword = normalized.withIndex()
            .filter { it.value.isNotEmpty() }
            .groupBy({ it.value }, { it.index })
        val kept = sourcesByKeyword.keys.sorted().take(CatalogKeywords.MAX_COUNT)
        val keptSet = kept.toSet()

        val notices = sources.indices.mapNotNull { index ->
            val source = sources[index]
            val keyword = normalized[index]
            val path = "catalog.json.catalog.keywords[$index]"
            val merged = if (sourcesByKeyword[keyword].orEmpty().size > 1) " and merged with an identical keyword" else ""
            when {
                keyword.isEmpty() -> CatalogMigrationNotice(
                    CatalogMigrationCodes.KEYWORD_REMOVED,
                    path,
                    "keyword '$source' was removed because it contains no letters or digits",
                )
                keyword !in keptSet -> CatalogMigrationNotice(
                    CatalogMigrationCodes.KEYWORD_REMOVED,
                    path,
                    "keyword '$source' was removed because a catalog may list at most ${CatalogKeywords.MAX_COUNT} keywords",
                )
                hyphenated[index] != keyword -> CatalogMigrationNotice(
                    CatalogMigrationCodes.KEYWORD_TRUNCATED,
                    path,
                    "keyword '$source' was shortened to '$keyword' to fit ${CatalogKeywords.MAX_LENGTH} characters$merged",
                )
                source != keyword -> CatalogMigrationNotice(
                    CatalogMigrationCodes.KEYWORD_NORMALIZED,
                    path,
                    "keyword '$source' was normalized to '$keyword'$merged",
                )
                else -> null
            }
        }
        catalog.putArray("keywords").also { array -> kept.forEach(array::add) }
        return CatalogMigrationStepResult(notices = notices)
    }

    override fun migrateResource(
        tree: ObjectNode,
        path: String,
    ): CatalogMigrationStepResult {
        tree.put("schemaVersion", toVersion)
        renameVariantIdToSlug(tree)
        return CatalogMigrationStepResult()
    }

    /**
     * Carries a template's variant addresses from catalog-v6's `id` to catalog-v7's `slug`.
     *
     * The value is unchanged -- a variant was always addressed by a name someone chose, like
     * `english` or `default`, and every other resource type already called that a slug. Renaming
     * the field needs no notice: nothing references a variant across catalogs, so no stored
     * reference elsewhere names it and none can be left dangling.
     *
     * A v6 variant that already carries `slug` is left alone; it cannot have come from a v6
     * producer, and overwriting it would discard the more specific value.
     */
    private fun renameVariantIdToSlug(tree: ObjectNode) {
        val variants = tree["variants"] as? ArrayNode ?: return
        for (variant in variants) {
            val entry = variant as? ObjectNode ?: continue
            if (entry.has("slug")) continue
            val id = entry.remove("id") ?: continue
            entry.set("slug", id)
        }
    }

    /** Catalog-v6's own keyword rules; input that broke them was never a valid v6 catalog. */
    private fun v6Findings(sources: List<String>): List<CatalogMigrationFinding> {
        val findings = mutableListOf<CatalogMigrationFinding>()
        val seen = mutableSetOf<String>()
        sources.forEachIndexed { index, keyword ->
            val path = "catalog.json.catalog.keywords[$index]"
            if (keyword.isBlank() || keyword != keyword.trim()) {
                findings += CatalogMigrationFinding(
                    CatalogMigrationCodes.KEYWORD_INVALID,
                    path,
                    "keyword must be nonblank and must not contain leading or trailing whitespace",
                )
            }
            if (!seen.add(keyword)) {
                findings += CatalogMigrationFinding(
                    CatalogMigrationCodes.KEYWORD_DUPLICATE,
                    path,
                    "keyword '$keyword' is duplicated",
                )
            }
        }
        return findings
    }
}
