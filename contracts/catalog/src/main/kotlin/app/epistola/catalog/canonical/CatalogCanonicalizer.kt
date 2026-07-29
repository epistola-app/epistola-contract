// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.canonical

import app.epistola.catalog.archive.CatalogArchive
import app.epistola.catalog.protocol.AssetResource
import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.DependencyRef
import app.epistola.catalog.protocol.ResourceDetail
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.io.InputStream
import java.security.MessageDigest

/**
 * Lowercase SHA-256 identity of canonical catalog content.
 *
 * This is a content identifier, not a digest of the encoded ZIP file.
 */
@JvmInline
value class CatalogFingerprint(val value: String)

/**
 * Versioned canonicalization algorithms accepted by the contract.
 *
 * V1 and V2 are retained for fingerprints already stored by older producers.
 * V3 is the current semantic catalog-v5 algorithm.
 */
enum class CatalogFingerprintVersion {
    V1,
    V2,
    V3,
}

/**
 * Produces deterministic catalog and per-resource content representations.
 *
 * Canonicalization sorts object keys and resource/dependency collections,
 * normalizes numeric JSON parsing, hashes asset bytes while streaming, and
 * excludes volatile release metadata. ZIP entry order, timestamps, and
 * compression never affect the result.
 */
object CatalogCanonicalizer {
    private val mapper = jsonMapper { addModule(kotlinModule()) }
    private val decimalReader = mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    /**
     * Produces the legacy V1 fingerprint for source and binary compatibility.
     *
     * New fingerprints should be produced with [currentFingerprint].
     */
    fun fingerprint(catalog: CatalogArchive): CatalogFingerprint = fingerprint(catalog, CatalogFingerprintVersion.V1)

    /** Produces the current semantic V3 fingerprint. */
    fun currentFingerprint(catalog: CatalogArchive): CatalogFingerprint = fingerprint(catalog, CatalogFingerprintVersion.V3)

    /**
     * Produces a catalog fingerprint using an explicitly selected algorithm.
     *
     * @throws java.io.IOException when referenced archive content cannot be read.
     */
    fun fingerprint(
        catalog: CatalogArchive,
        version: CatalogFingerprintVersion,
    ): CatalogFingerprint {
        val entries = if (version == CatalogFingerprintVersion.V3) semanticEntries(catalog) else entries(catalog)
        val canonical = when (version) {
            CatalogFingerprintVersion.V1 -> canonicalV1(catalog, entries)
            CatalogFingerprintVersion.V2 -> canonicalV2(catalog, entries)
            CatalogFingerprintVersion.V3 -> canonicalV2(catalog, entries)
        }
        return CatalogFingerprint(sha256(canonical.byteInputStream()))
    }

    /**
     * Whether [expected] identifies the catalog under a current or legacy
     * canonical form, including catalog-v4's explicit `isDraft:false` syntax.
     */
    fun matchesFingerprint(
        catalog: CatalogArchive,
        expected: String,
    ): Boolean {
        val exact = CatalogFingerprintVersion.entries.mapTo(mutableSetOf()) { fingerprint(catalog, it).value }
        val projected = legacyV4Entries(catalog)
        exact += sha256(canonicalV1(catalog, projected).byteInputStream())
        exact += sha256(canonicalV2(catalog, projected).byteInputStream())
        return expected in exact
    }

    private fun canonicalV1(
        catalog: CatalogArchive,
        entries: List<Entry>,
    ): String = buildString {
        append(catalog.manifest.catalog.identityLine())
        appendEntries(entries)
        append("deps ").append(canonicalDependenciesV1(catalog.manifest.dependencies)).append('\n')
    }

    private fun canonicalV2(
        catalog: CatalogArchive,
        entries: List<Entry>,
    ): String = buildString {
        append("manifest ")
            .append(canonicalManifestJson(catalog))
            .append('\n')
        appendEntries(entries)
    }

    private fun StringBuilder.appendEntries(entries: List<Entry>) {
        entries.sortedBy(Entry::key).forEach { entry ->
            append(entry.key).append(' ')
                .append(entry.canonicalJson).append(' ')
                .append(entry.assetHash).append('\n')
        }
    }

    private fun canonicalManifestJson(catalog: CatalogArchive): String {
        val manifest = catalog.manifest
        val canonical = linkedMapOf<String, Any?>(
            "catalog" to manifest.catalog,
            "publisher" to manifest.publisher,
            "compatibility" to manifest.compatibility,
            "includes" to manifest.includes.orEmpty().sortedWith(compareBy({ it.url }, { it.description })),
            "resources" to manifest.resources
                .sortedWith(compareBy({ it.type }, { it.slug }))
                .map { resource ->
                    linkedMapOf(
                        "type" to resource.type,
                        "slug" to resource.slug,
                        "name" to resource.name,
                        "description" to resource.description,
                        "compatibility" to resource.compatibility,
                    )
                },
            "dependencies" to canonicalDependenciesV2(manifest.dependencies),
        )
        return mapper.writeValueAsString(sortKeys(mapper.valueToTree(canonical)))
    }

    private fun canonicalDependenciesV2(dependencies: List<DependencyRef>?): List<Map<String, Any?>> = dependencies.orEmpty()
        .map { dependency ->
            val (type, catalogKey) = dependency.identity()
            linkedMapOf(
                "type" to type,
                "catalogKey" to catalogKey.takeIf(String::isNotEmpty),
                "slug" to dependency.slug,
            )
        }
        .sortedWith(compareBy({ it["type"].toString() }, { it["catalogKey"].toString() }, { it["slug"].toString() }))

    private fun canonicalDependenciesV1(dependencies: List<DependencyRef>?): String = dependencies.orEmpty()
        .map { dependency ->
            val (type, catalogKey) = dependency.identity()
            "$type|$catalogKey|${dependency.slug}"
        }
        .sorted()
        .joinToString(";")

    private fun DependencyRef.identity(): Pair<String, String> = when (this) {
        is DependencyRef.Theme -> "theme" to catalogKey
        is DependencyRef.Stencil -> "stencil" to catalogKey
        is DependencyRef.CodeList -> "codeList" to catalogKey
        is DependencyRef.Font -> "font" to catalogKey
        is DependencyRef.Asset -> "asset" to ""
    }

    /**
     * Returns a stable SHA-256 for every `type/slug` resource.
     *
     * Asset resource fingerprints include the referenced binary bytes.
     */
    fun perResourceFingerprints(catalog: CatalogArchive): Map<String, String> = entries(catalog).sortedBy(Entry::key).associate { entry ->
        entry.key to sha256((entry.canonicalJson + "\u0000" + entry.assetHash).byteInputStream())
    }

    /** Returns semantic catalog-v5 hashes for every `type/slug` resource. */
    fun currentPerResourceFingerprints(catalog: CatalogArchive): Map<String, String> = semanticEntries(catalog).sortedBy(Entry::key).associate { entry ->
        entry.key to sha256((entry.canonicalJson + "\u0000" + entry.assetHash).byteInputStream())
    }

    /**
     * Serializes only the resource payload with recursively sorted object keys.
     *
     * The surrounding [ResourceDetail.schemaVersion] is intentionally omitted.
     */
    fun canonicalResourceJson(detail: ResourceDetail): String = mapper.writeValueAsString(sortKeys(mapper.valueToTree(detail.resource)))

    private fun entries(catalog: CatalogArchive): List<Entry> = catalog.resourceDetails.map { (key, detail) ->
        val detailPath = "resources/$key.json"
        val resourceNode = if (detailPath in catalog.paths) {
            catalog.content.open(detailPath).use { input ->
                decimalReader.readTree(input).get("resource")
            }
        } else {
            mapper.valueToTree(detail.resource)
        }
        val assetHash = (detail.resource as? AssetResource)?.let { asset ->
            val path = asset.contentUrl.removePrefix("./")
            if (path in catalog.paths) {
                catalog.content.open(path).use(::sha256)
            } else {
                "MISSING"
            }
        }.orEmpty()
        Entry(
            key = key,
            canonicalJson = mapper.writeValueAsString(sortKeys(resourceNode)),
            assetHash = assetHash,
        )
    }

    private fun semanticEntries(catalog: CatalogArchive): List<Entry> = catalog.resourceDetails.map { (key, detail) ->
        val resourceNode = normalizeStencilProvenance(resourceNode(catalog, key, detail), legacyV4 = false)
        Entry(
            key = key,
            canonicalJson = mapper.writeValueAsString(sortKeys(resourceNode)),
            assetHash = assetHash(catalog, detail),
        )
    }

    private fun legacyV4Entries(catalog: CatalogArchive): List<Entry> = catalog.resourceDetails.map { (key, detail) ->
        val resourceNode = normalizeStencilProvenance(resourceNode(catalog, key, detail), legacyV4 = true)
        Entry(
            key = key,
            canonicalJson = mapper.writeValueAsString(sortKeys(resourceNode)),
            assetHash = assetHash(catalog, detail),
        )
    }

    private fun assetHash(
        catalog: CatalogArchive,
        detail: ResourceDetail,
    ): String = (detail.resource as? AssetResource)?.let { asset ->
        val path = asset.contentUrl.removePrefix("./")
        if (path in catalog.paths) catalog.content.open(path).use(::sha256) else "MISSING"
    }.orEmpty()

    private fun resourceNode(
        catalog: CatalogArchive,
        key: String,
        detail: ResourceDetail,
    ): JsonNode {
        val detailPath = "resources/$key.json"
        return if (detailPath in catalog.paths) {
            catalog.content.open(detailPath).use { input -> decimalReader.readTree(input).get("resource") }
        } else {
            mapper.valueToTree(detail.resource)
        }
    }

    private fun normalizeStencilProvenance(
        node: JsonNode,
        legacyV4: Boolean,
    ): JsonNode = when (node) {
        is ObjectNode -> mapper.createObjectNode().also { normalized ->
            node.propertyNames().forEach { name ->
                normalized.set(name, normalizeStencilProvenance(node[name], legacyV4))
            }
            if (normalized["type"]?.asString() == "stencil" && normalized["props"] is ObjectNode) {
                val props = normalized["props"] as ObjectNode
                props.remove("isDraft")
                if (legacyV4 && props["draftVersion"] == null) props.put("isDraft", false)
            }
        }
        is ArrayNode -> mapper.createArrayNode().also { normalized ->
            node.forEach { normalized.add(normalizeStencilProvenance(it, legacyV4)) }
        }
        else -> node
    }

    private fun CatalogInfo.identityLine(): String = "$slug $name ${description.orEmpty()}\n"

    private fun sortKeys(node: JsonNode): JsonNode = when (node) {
        is ObjectNode -> mapper.createObjectNode().also { sorted ->
            node.propertyNames().sorted().forEach { name -> sorted.set(name, sortKeys(node.get(name))) }
        }
        is ArrayNode -> mapper.createArrayNode().also { sorted ->
            node.forEach { sorted.add(sortKeys(it)) }
        }
        else -> node
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        input.use { source ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class Entry(
        val key: String,
        val canonicalJson: String,
        val assetHash: String,
    )
}
