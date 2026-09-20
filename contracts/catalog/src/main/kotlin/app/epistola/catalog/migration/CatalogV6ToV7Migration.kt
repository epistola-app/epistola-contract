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
    private val mapper = tools.jackson.databind.json.JsonMapper.builder().build()

    override val fromVersion: Int = 6
    override val toVersion: Int = 7

    override fun migrateManifest(tree: ObjectNode): CatalogMigrationStepResult {
        tree.put("schemaVersion", toVersion)
        unqualifiedAssetDependencies(tree).let { if (it.isNotEmpty()) return CatalogMigrationStepResult(it) }
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
        context: CatalogMigrationContext,
    ): CatalogMigrationStepResult {
        tree.put("schemaVersion", toVersion)
        renameVariantIdToSlug(tree)
        return when (tree["type"]?.asString()) {
            "asset" -> assetToImage(tree, path, context)
            "font" -> fontFacesTakeTheirBinary(tree, path, context)
            else -> CatalogMigrationStepResult()
        }
    }

    /**
     * Turns a catalog-v6 asset into a catalog-v7 image, identified by what its bytes are.
     *
     * The slug is carried over unchanged. It was a generated UUID that named nothing, but template
     * content references it as `props.assetId`, so preserving it is what keeps every stored
     * document resolving without a content migration.
     */
    private fun assetToImage(
        tree: ObjectNode,
        path: String,
        context: CatalogMigrationContext,
    ): CatalogMigrationStepResult {
        tree.put("type", "image")
        val contentUrl = tree["contentUrl"]?.asString()
            ?: return finding(path, "asset has no contentUrl, so its content cannot be identified")
        val hash = hashOf(contentUrl, context)
            ?: return finding("$path.contentUrl", "content '$contentUrl' is not in the archive, so its hash cannot be computed")
        tree.put("contentHash", hash)
        return CatalogMigrationStepResult()
    }

    /**
     * Gives each font face its binary directly, in place of a slug pointing at an asset resource.
     *
     * The face's asset is read from the archive to recover where its bytes are and what they are.
     * This is why a migration is given the archive's content: the manifest lists an asset's entry
     * but not its `contentUrl`, so neither the path nor the hash can be recovered without it.
     */
    private fun fontFacesTakeTheirBinary(
        tree: ObjectNode,
        path: String,
        context: CatalogMigrationContext,
    ): CatalogMigrationStepResult {
        val variants = tree["variants"] as? ArrayNode ?: return CatalogMigrationStepResult()
        val findings = mutableListOf<CatalogMigrationFinding>()
        variants.forEachIndexed { index, variant ->
            val face = variant as? ObjectNode ?: return@forEachIndexed
            if (face.has("contentUrl")) return@forEachIndexed
            val assetSlug = face.remove("assetSlug")?.asString()
                ?: return@forEachIndexed findings.plusAssign(
                    listOf(finding("$path.variants[$index]", "font face names no asset").findings.single()),
                )
            val contentUrl = assetContentUrl(assetSlug, context)
            if (contentUrl == null) {
                findings += finding("$path.variants[$index].assetSlug", "asset '$assetSlug' is not in the archive").findings
                return@forEachIndexed
            }
            val hash = hashOf(contentUrl, context)
            if (hash == null) {
                findings += finding("$path.variants[$index].assetSlug", "content '$contentUrl' is not in the archive").findings
                return@forEachIndexed
            }
            face.put("contentUrl", contentUrl)
            face.put("contentHash", hash)
        }
        return CatalogMigrationStepResult(findings)
    }

    /** The `contentUrl` of the v6 asset with this slug, read from its own document in the archive. */
    private fun assetContentUrl(
        assetSlug: String,
        context: CatalogMigrationContext,
    ): String? {
        val entry = context.manifest.resources.firstOrNull { it.type == "asset" && it.slug == assetSlug } ?: return null
        val detailPath = entry.detailUrl.removePrefix("./")
        val content = context.content ?: return null
        return runCatching {
            content.open(detailPath).use { input ->
                (mapper.readTree(input) as? ObjectNode)?.get("resource")?.get("contentUrl")?.asString()
            }
        }.getOrNull()
    }

    /** The sha-256 of an archive-relative content path, or null when the archive does not hold it. */
    private fun hashOf(
        contentUrl: String,
        context: CatalogMigrationContext,
    ): String? {
        val content = context.content ?: return null
        val contentPath = contentUrl.removePrefix("./")
        return runCatching {
            content.open(contentPath).use { input ->
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        }.getOrNull()
    }

    private fun finding(
        path: String,
        message: String,
    ) = CatalogMigrationStepResult(
        listOf(CatalogMigrationFinding(CatalogMigrationCodes.ASSET_CONTENT_UNRESOLVED, path, message)),
    )

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

    /**
     * Catalog-v7 requires every dependency to name a catalog; catalog-v6 asset dependencies did not.
     *
     * Reported rather than repaired. The archive records which asset is depended on but not whose,
     * and inferring one would bind the consumer to whichever catalog happened to match — the exact
     * ambiguity the qualification exists to remove. A publisher re-exports instead, which qualifies
     * it from their own installed state.
     */
    private fun unqualifiedAssetDependencies(tree: ObjectNode): List<CatalogMigrationFinding> {
        val dependencies = tree["dependencies"] as? ArrayNode ?: return emptyList()
        return dependencies.mapIndexedNotNull { index, dependency ->
            val entry = dependency as? ObjectNode ?: return@mapIndexedNotNull null
            if (entry["type"]?.asString() != "asset" || entry.has("catalogKey")) return@mapIndexedNotNull null
            CatalogMigrationFinding(
                CatalogMigrationCodes.DEPENDENCY_UNQUALIFIED,
                "catalog.json.dependencies[$index]",
                "asset dependency '${entry["slug"]?.asString()}' names no catalog; re-export the catalog to qualify it",
            )
        }
    }
}
