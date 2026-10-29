// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.canonical

import app.epistola.catalog.archive.CatalogArchive
import app.epistola.catalog.migration.CatalogKeyRenames
import app.epistola.catalog.migration.CatalogV8Additions
import app.epistola.catalog.protocol.CatalogDependency
import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.CatalogKeys
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.DependencyRef
import app.epistola.catalog.protocol.ImageResource
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
 * A fingerprint that names the algorithm it was computed with: `v6:<lowercase SHA-256 hex>`.
 *
 * Wire v8 writes every fingerprint in this form, so a fingerprint copied into another archive, such
 * as a catalog dependency's `releaseFingerprint`, can still be verified after the current algorithm
 * moves on. A verifier computes exactly the named algorithm and never tries the others: an older
 * algorithm covers less (V4 covers no identity or dependencies), so accepting whichever matches
 * would let a copy pass that differs in what the newer one covers.
 */
data class QualifiedCatalogFingerprint(
    val version: CatalogFingerprintVersion,
    val fingerprint: CatalogFingerprint,
) {
    /** The wire form, `v6:<hex>`. */
    override fun toString(): String = "${version.label}:${fingerprint.value}"

    companion object {
        /** The shape of a qualified fingerprint: an algorithm label, a colon and lowercase SHA-256 hex. */
        const val PATTERN: String = "^v[1-9][0-9]*:[0-9a-f]{64}$"

        private val REGEX = Regex(PATTERN)

        /**
         * Parses the wire form, or returns null when [value] does not have the shape of [PATTERN] or
         * names an algorithm this library does not know.
         */
        @JvmStatic
        fun parse(value: String): QualifiedCatalogFingerprint? {
            if (!REGEX.matches(value)) return null
            val (label, hex) = value.split(':', limit = 2)
            val version = CatalogFingerprintVersion.entries.firstOrNull { it.label == label } ?: return null
            return QualifiedCatalogFingerprint(version, CatalogFingerprint(hex))
        }
    }
}

/**
 * Versioned canonicalization algorithms accepted by the contract.
 *
 * V1 through V5 are retained for fingerprints already stored by older producers, and verify exactly
 * as they did. V4, introduced with catalog v6, includes authored discovery metadata. V5, introduced
 * with catalog v8, also covers the catalog's identity and declared dependencies: its registry and
 * namespace, the registry and namespace of every resource dependency, and every catalog dependency
 * with its registry, namespace, pinned release and that release's fingerprint. It covers the
 * catalog's default theme and declared presets (ADR 0007) too, each only when present, so a catalog
 * that has neither keeps the V5 fingerprint it had before they existed.
 * Registry and namespace are separate members, never joined into one string.
 *
 * V6 is the current algorithm (contract ADR 0003, amendment of 2026-10-22): a release's identity is
 * its **content**, so V6 covers what the release renders with and nothing that names or presents it.
 * It covers every resource as V5 does (references to other catalogs name an alias), the resource
 * index, the catalog's content settings (`defaultTheme`, `presets`, `license`), the resource
 * dependencies by alias, the catalog dependencies as alias to pinned release fingerprint, and
 * `compatibility` (the Epistola versions the content needs). It does not cover the catalog's own
 * name (registry, namespace, key), the release block, the catalog's presentation (display `name`,
 * `description`, `keywords`, `presentation`, `attributes`, `publisher`, `includes`), or the wire
 * version (`schemaVersion`): two catalogs that differ only there have the same V6 fingerprint, so
 * the same release under another name, from any registry, mirror or file, verifies as itself. The
 * algorithm itself, its domain prefix and canonical form, fixes how content is encoded, so a later
 * wire version that only re-encodes the same content keeps every release's V6 value.
 *
 * V5 and V6 name a resource's address `key`, as wire v8 does. V1 to V4
 * canonicalize the members catalog v8 renamed ([CatalogKeyRenames]) under their old names (`slug`,
 * `themeId`, `stencilId`, `assetId`, `iconAssetSlug`, `imageAssetSlugs`, an attribute's `catalog`,
 * a font reference's `slug`), so they reproduce exactly the fingerprints producers computed before
 * the rename, whichever wire version the archive is read at. Likewise V5 is the only algorithm that
 * covers the members wire v8 added to a template -- a data example's `key`, a `description`
 * ([CatalogV8Additions]) -- so a key the v7-to-v8 migration derives moves no legacy fingerprint.
 *
 * No algorithm covers producer metadata, a resource's `metadata` or the release's: it is what a
 * producer says about the content, not the content ([app.epistola.catalog.protocol.CatalogMetadata]).
 */
enum class CatalogFingerprintVersion {
    V1,
    V2,
    V3,
    V4,
    V5,
    V6,
    ;

    /** The label a [QualifiedCatalogFingerprint] names this algorithm by: `v1` to `v6`. */
    val label: String get() = name.lowercase()
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

    /** Produces the current V6 fingerprint. */
    fun currentFingerprint(catalog: CatalogArchive): CatalogFingerprint = fingerprint(catalog, CatalogFingerprintVersion.V6)

    /** The algorithm [currentFingerprint] computes, and the one wire v8 requires of a release. */
    @JvmField
    val CURRENT_FINGERPRINT_VERSION: CatalogFingerprintVersion = CatalogFingerprintVersion.V6

    /** Produces the current V6 fingerprint in the form wire v8 writes, `v6:<hex>`. */
    fun currentQualifiedFingerprint(catalog: CatalogArchive): QualifiedCatalogFingerprint = qualifiedFingerprint(catalog, CURRENT_FINGERPRINT_VERSION)

    /**
     * Produces a fingerprint with an explicitly selected algorithm, in the form that names it.
     *
     * @throws java.io.IOException when referenced archive content cannot be read.
     */
    fun qualifiedFingerprint(
        catalog: CatalogArchive,
        version: CatalogFingerprintVersion,
    ): QualifiedCatalogFingerprint = QualifiedCatalogFingerprint(version, fingerprint(catalog, version))

    /**
     * Whether [expected], a fingerprint in the form `v6:<hex>`, identifies the catalog under exactly
     * the algorithm it names. False when [expected] does not parse or names an unknown algorithm.
     */
    fun matchesQualifiedFingerprint(
        catalog: CatalogArchive,
        expected: String,
    ): Boolean {
        val parsed = QualifiedCatalogFingerprint.parse(expected) ?: return false
        return fingerprint(catalog, parsed.version) == parsed.fingerprint
    }

    /**
     * Produces a catalog fingerprint using an explicitly selected algorithm.
     *
     * @throws java.io.IOException when referenced archive content cannot be read.
     */
    fun fingerprint(
        catalog: CatalogArchive,
        version: CatalogFingerprintVersion,
    ): CatalogFingerprint = computeFingerprint(if (coversBoundModel(version)) catalog else sourceKeyed(catalog), version)

    /**
     * Whether [version] canonicalizes the catalog as bound (V5 and later, which cover the keys as
     * wire v8 has them) rather than under the keys its source archive used (V1 to V4, [sourceKeyed]).
     */
    private fun coversBoundModel(version: CatalogFingerprintVersion): Boolean = version >= CatalogFingerprintVersion.V5

    /** [version] over [catalog] as given: a legacy algorithm expects the source keys ([sourceKeyed]). */
    private fun computeFingerprint(
        catalog: CatalogArchive,
        version: CatalogFingerprintVersion,
    ): CatalogFingerprint {
        val entries = when (version) {
            CatalogFingerprintVersion.V1, CatalogFingerprintVersion.V2 -> entries(catalog)
            CatalogFingerprintVersion.V3 -> semanticEntries(catalog)
            CatalogFingerprintVersion.V4 -> currentEntries(catalog, currentNames = false)
            CatalogFingerprintVersion.V5 -> currentEntries(catalog, currentNames = true, variantTitle = true)
            CatalogFingerprintVersion.V6 -> currentEntries(catalog, currentNames = true)
        }
        val canonical = when (version) {
            CatalogFingerprintVersion.V1 -> canonicalV1(catalog, entries)
            CatalogFingerprintVersion.V2 -> canonicalV2(catalog, entries, includeV6Metadata = false)
            CatalogFingerprintVersion.V3 -> canonicalV2(catalog, entries, includeV6Metadata = false)
            CatalogFingerprintVersion.V4 -> canonicalV4(catalog, entries)
            CatalogFingerprintVersion.V5 -> canonicalV5(catalog, entries)
            CatalogFingerprintVersion.V6 -> canonicalV6(catalog, entries)
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
        val source = sourceKeyed(catalog)
        val exact = CatalogFingerprintVersion.entries.mapTo(mutableSetOf()) {
            computeFingerprint(if (coversBoundModel(it)) catalog else source, it).value
        }
        val projected = legacyV4Entries(source)
        exact += sha256(canonicalV1(source, projected).byteInputStream())
        exact += sha256(canonicalV2(source, projected, includeV6Metadata = false).byteInputStream())
        return expected in exact
    }

    /** Whether [expected] matches one explicitly selected canonical algorithm. */
    fun matchesFingerprint(
        catalog: CatalogArchive,
        expected: String,
        version: CatalogFingerprintVersion,
    ): Boolean = fingerprint(catalog, version).value == expected

    /**
     * Whether [expected] is the V4 fingerprint a catalog-v6 or catalog-v7 producer computed for
     * [catalog], as migrated or as its source archive carried it.
     *
     * Migration rewrites content V4 covers, so the migrated catalog alone no longer reproduces it:
     * - catalog v7 may rewrite catalog-v6 keywords, so the keywords in the raw `catalog.json` are
     *   tried as well;
     * - catalog v8 removes stencil versions, which V4 covered as part of each resource, so they are
     *   restored from the raw resource documents.
     *
     * - catalog v8 pads or cuts keys outside its bounds, and every reference to them, so the source
     *   keys are restored from the raw documents ([sourceKeyed]).
     *
     * V4 never covered a registry, a namespace, an alias or a catalog dependency, so what catalog v8
     * adds to the manifest needs no undoing. The members catalog v8 renamed need none either: V4
     * canonicalizes them under their old names whatever the archive calls them.
     */
    internal fun matchesSourceV4Fingerprint(
        migrated: CatalogArchive,
        expected: String,
    ): Boolean {
        val catalog = sourceKeyed(migrated)
        val infos = listOfNotNull(
            catalog.manifest.catalog,
            sourceKeywords(catalog)?.let { catalog.manifest.catalog.copyWithMetadata(keywords = it) },
        )
        val entrySets = listOf(currentEntries(catalog, currentNames = false), sourceStencilVersionEntries(catalog))
        return entrySets.any { entries ->
            infos.any { info -> sha256(canonicalV4(catalog, entries, info).byteInputStream()) == expected }
        }
    }

    /**
     * [catalog] under the keys its source archive used, for the legacy algorithms (V1 to V4).
     *
     * The v7-to-v8 migration pads a key shorter than 3 characters and cuts one longer than 50
     * ([CatalogKeys.migrate]), and rewrites every reference to it alike. The fingerprints a producer
     * computed before wire v8 covered the source keys, and the rewrite cannot be undone from the
     * migrated key alone (`ab0` may have been `ab` or `ab0`), so each key is restored from the raw
     * `catalog.json` and resource documents the archive still holds: a value is restored where the
     * migrated document has exactly the rewrite of the raw one's (the property names of a variant's
     * attributes likewise). Returns [catalog] itself when it was read at wire v8 or later, or does not
     * hold its raw documents, and so was never rewritten.
     */
    private fun sourceKeyed(catalog: CatalogArchive): CatalogArchive {
        if (catalog.sourceSchemaVersion >= KEY_BOUNDS_SINCE_WIRE_VERSION || "catalog.json" !in catalog.paths) return catalog
        val rawManifest = catalog.content.open("catalog.json").use { input -> decimalReader.readTree(input) } as? ObjectNode ?: return catalog
        CatalogKeyRenames.toCurrent(CatalogKeyRenames.manifestSites(rawManifest))
        val manifestTree: JsonNode = mapper.valueToTree(catalog.manifest)
        val ownKey = catalog.manifest.catalog.key
        val manifest = restoreKeys(manifestTree, rawManifest, ownKey)
            .takeIf { it != manifestTree }
            ?.let { mapper.treeToValue(it, CatalogManifest::class.java) }
            ?: catalog.manifest
        var changed = manifest !== catalog.manifest
        val detailPaths = catalog.manifest.resources.associate { "${it.type}/${it.key}" to it.detailUrl.removePrefix("./") }
        val details = catalog.resourceDetails.entries.associate { (key, detail) ->
            val rawPath = detailPaths[key]?.takeIf { it in catalog.paths } ?: "resources/$key.json".takeIf { it in catalog.paths }
            val rawResource = rawPath?.let { path ->
                catalog.content.open(path).use { input -> decimalReader.readTree(input) }.get("resource") as? ObjectNode
            } ?: return@associate key to detail
            CatalogKeyRenames.toCurrent(CatalogKeyRenames.resourceSites(rawResource, "resource"))
            val resourceTree: JsonNode = mapper.valueToTree(detail.resource)
            val restoredTree = restoreKeys(resourceTree, rawResource, ownKey)
            if (restoredTree == resourceTree) return@associate key to detail
            changed = true
            val restored = mapper.treeToValue(
                mapper.createObjectNode().put("schemaVersion", detail.schemaVersion).set("resource", restoredTree),
                ResourceDetail::class.java,
            )
            "${restored.resource.type}/${restored.resource.key}" to restored
        }
        if (!changed) return catalog
        return CatalogArchive(manifest, details, catalog.paths, catalog.content).also { it.sourceSchemaVersion = catalog.sourceSchemaVersion }
    }

    /**
     * A copy of [model] with every string that is exactly [CatalogKeys.migrate] of a different raw
     * string at the same position put back to the raw one, and every property name likewise.
     *
     * The catalog's own key, [ownKey], which the v7-to-v8 migration removes where content named the
     * catalog itself by it ([app.epistola.catalog.migration.CatalogReferenceQualifiers.dropOwnKey]),
     * is put back where the raw document had it: as a reference's `catalogKey` or
     * `themeCatalogKey`, and as the qualifier of a variant's attribute key (`letters.locale`).
     */
    private fun restoreKeys(
        model: JsonNode,
        raw: JsonNode?,
        ownKey: String,
    ): JsonNode = when {
        model is ObjectNode && raw is ObjectNode -> mapper.createObjectNode().also { restored ->
            model.propertyNames().forEach { name ->
                val rawName = when {
                    raw.has(name) -> name
                    raw.has("$ownKey.$name") && !model.has("$ownKey.$name") -> "$ownKey.$name"
                    else -> raw.propertyNames().firstOrNull { it != name && !model.has(it) && CatalogKeys.migrate(it) == name } ?: name
                }
                restored.set(rawName, restoreKeys(model[name], raw[rawName], ownKey))
            }
            OWN_KEY_MEMBERS
                .filter { member -> !model.has(member) && raw[member]?.takeIf { it.isString }?.asString() == ownKey }
                .forEach { member -> restored.put(member, ownKey) }
        }
        model is ArrayNode && raw is ArrayNode -> mapper.createArrayNode().also { restored ->
            model.forEachIndexed { index, child -> restored.add(restoreKeys(child, raw[index], ownKey)) }
        }
        model.isString && raw != null && raw.isString && model.asString() != raw.asString() && CatalogKeys.migrate(raw.asString()) == model.asString() -> raw
        else -> model
    }

    /** The members that name the catalog a reference is to, which may have named the catalog itself. */
    private val OWN_KEY_MEMBERS = listOf("catalogKey", "themeCatalogKey")

    /** The wire version from which keys are bounded, so that a catalog read at it was never rewritten. */
    private const val KEY_BOUNDS_SINCE_WIRE_VERSION = 8

    /**
     * Current resource entries with the stencil versions catalog v8 removed put back from the raw
     * resource documents: a stencil's own `version`, and a stencil reference's `version` and
     * `draftVersion`. Reproduces the resources a catalog-v7 producer fingerprinted.
     */
    private fun sourceStencilVersionEntries(catalog: CatalogArchive): List<Entry> = catalog.resourceDetails.map { (key, detail) ->
        val model: JsonNode = mapper.valueToTree(detail.resource)
        val detailPath = "resources/$key.json"
        if (detailPath in catalog.paths) {
            val raw = catalog.content.open(detailPath).use { input -> decimalReader.readTree(input).get("resource") }
            restoreStencilVersions(model, raw, resourceRoot = true)
        }
        Entry(
            key = key,
            canonicalJson = mapper.writeValueAsString(sortKeys(withoutMetadata(normalizeStencilProvenance(legacyResource(model), legacyV4 = false)))),
            assetHash = assetHash(catalog, detail),
        )
    }

    private fun restoreStencilVersions(
        model: JsonNode,
        raw: JsonNode?,
        resourceRoot: Boolean,
    ) {
        when {
            model is ObjectNode && raw is ObjectNode -> {
                val isStencil = model["type"]?.takeIf { it.isString }?.asString() == "stencil"
                if (isStencil && resourceRoot && !model.has("version")) raw["version"]?.let { model.set("version", it) }
                val props = model["props"] as? ObjectNode
                val rawProps = raw["props"] as? ObjectNode
                if (isStencil && props != null && rawProps != null) {
                    for (name in listOf("version", "draftVersion")) {
                        if (!props.has(name)) rawProps[name]?.let { props.set(name, it) }
                    }
                }
                model.propertyNames().toList().forEach { name -> restoreStencilVersions(model[name], raw[name], resourceRoot = false) }
            }
            model is ArrayNode && raw is ArrayNode -> model.forEachIndexed { index, child ->
                restoreStencilVersions(child, raw[index], resourceRoot = false)
            }
        }
    }

    /** Keywords exactly as the archive's raw `catalog.json` lists them, or null when unavailable. */
    private fun sourceKeywords(catalog: CatalogArchive): Set<String>? {
        if ("catalog.json" !in catalog.paths) return null
        val keywords = catalog.content.open("catalog.json").use { input -> mapper.readTree(input) }
            .get("catalog")?.get("keywords")
            ?.takeIf { it.isArray && it.all { keyword -> keyword.isString } }
            ?: return null
        return keywords.mapTo(linkedSetOf()) { it.asString() }
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
        includeV6Metadata: Boolean,
        info: CatalogInfo = catalog.manifest.catalog,
        includeResourceCompatibility: Boolean = true,
        includeV8Identity: Boolean = false,
    ): String = buildString {
        append("manifest ")
            .append(canonicalManifestJson(catalog, includeV6Metadata, info, includeResourceCompatibility, includeV8Identity, currentNames = includeV8Identity))
            .append('\n')
        appendEntries(entries)
    }

    /**
     * V5: V4 plus the catalog's identity and declared dependencies, introduced with catalog v8.
     *
     * A registry and namespace are identity, and a catalog dependency's pinned release decides what
     * the catalog renders with, so two releases that differ only there are different content. A
     * stencil carries no version from catalog v8, so V5 covers none; the resource entries are
     * otherwise V4's. V5 names every member as catalog v8 does: a resource's address is its `key`.
     */
    private fun canonicalV5(
        catalog: CatalogArchive,
        entries: List<Entry>,
    ): String = buildString {
        append("algorithm sha256-v5\n")
        append(
            canonicalV2(
                catalog,
                entries,
                includeV6Metadata = true,
                includeResourceCompatibility = false,
                includeV8Identity = true,
            ),
        )
    }

    /**
     * V6: the release's content and nothing that names or presents it (contract ADR 0003, amendment
     * of 2026-10-22). The resource entries are V5's, so a resource is covered exactly as V5 covers
     * it; its references to other catalogs name an alias. The manifest contributes, under its own
     * domain prefix:
     *
     * - `compatibility`: the Epistola versions the content needs;
     * - the catalog's content settings: `defaultTheme` (key and alias), `presets` (sorted by key) and
     *   `license`;
     * - the resource index (`resources`: type, key, name, description, as V5);
     * - the resource dependencies by alias and key (`dependencies`);
     * - the catalog dependencies as alias to the pinned release's fingerprint
     *   (`catalogDependencies`: `alias`, `releaseFingerprint`), not the dependency's name or number,
     *   so a dependency renamed or mirrored under another name, pinned to the same release, is the
     *   same content.
     *
     * The catalog's own registry, namespace and key, the release block, and the catalog's
     * presentation -- `name`, `description`, `keywords`, `presentation`, `attributes`, `publisher`,
     * `includes` -- are left out: they are information about the name, which may change without a
     * release. So is `schemaVersion`: the hash identifies the release across wire versions, and this
     * canonical form, under its domain prefix, already fixes how the content is encoded.
     */
    private fun canonicalV6(
        catalog: CatalogArchive,
        entries: List<Entry>,
    ): String = buildString {
        append("algorithm sha256-v6\n")
        append("content ").append(canonicalContentJson(catalog)).append('\n')
        appendEntries(entries)
    }

    private fun canonicalContentJson(catalog: CatalogArchive): String {
        val manifest = catalog.manifest
        val info = manifest.catalog
        val canonical = linkedMapOf<String, Any?>(
            "compatibility" to manifest.compatibility?.let { linkedMapOf("epistolaVersions" to it.epistolaVersions) },
            "catalog" to linkedMapOf(
                "defaultTheme" to info.defaultTheme?.let { linkedMapOf("key" to it.key, "catalogKey" to it.catalogKey) },
                "presets" to info.presets
                    .sortedWith(compareBy({ it.key }, { it.name }, { it.description }))
                    .map { preset -> linkedMapOf("key" to preset.key, "name" to preset.name, "description" to preset.description) },
                "license" to info.license?.let { license ->
                    linkedMapOf(
                        "name" to license.name,
                        "spdxExpression" to license.spdxExpression,
                        "url" to license.url,
                        "copyrightText" to license.copyrightText,
                    )
                },
            ),
            "resources" to manifest.resources
                .sortedWith(compareBy({ it.type }, { it.key }))
                .map { resource ->
                    linkedMapOf("type" to resource.type, "key" to resource.key, "name" to resource.name, "description" to resource.description)
                },
            "dependencies" to manifest.dependencies.orEmpty()
                .map { dependency ->
                    val (type, alias) = dependency.identity()
                    linkedMapOf("type" to type, "catalogKey" to alias, "key" to dependency.key)
                }
                .sortedWith(compareBy({ it["type"] }, { it["catalogKey"] }, { it["key"] })),
            "catalogDependencies" to manifest.catalogDependencies.orEmpty()
                .sortedWith(compareBy({ it.alias }, { it.releaseFingerprint }))
                .map { dependency -> linkedMapOf("alias" to dependency.alias, "releaseFingerprint" to dependency.releaseFingerprint) },
        )
        return mapper.writeValueAsString(sortKeys(mapper.valueToTree(canonical)))
    }

    private fun canonicalV4(
        catalog: CatalogArchive,
        entries: List<Entry>,
        info: CatalogInfo = catalog.manifest.catalog,
    ): String = buildString {
        append("algorithm sha256-v4\n")
        // Wire v7 dropped the per-resource `compatibility`; nothing ever set it, and nothing read
        // it. The legacy forms above still emit the null it always was, because their whole job is
        // reproducing bytes a published catalog was fingerprinted with.
        append(canonicalV2(catalog, entries, includeV6Metadata = true, info, includeResourceCompatibility = false))
    }

    private fun StringBuilder.appendEntries(entries: List<Entry>) {
        entries.sortedBy(Entry::key).forEach { entry ->
            append(entry.key).append(' ')
                .append(entry.canonicalJson).append(' ')
                .append(entry.assetHash).append('\n')
        }
    }

    private fun canonicalManifestJson(
        catalog: CatalogArchive,
        includeV6Metadata: Boolean,
        info: CatalogInfo,
        includeResourceCompatibility: Boolean,
        includeV8Identity: Boolean,
        currentNames: Boolean,
    ): String {
        val manifest = catalog.manifest
        val keyName = if (currentNames) "key" else "slug"
        val canonical = linkedMapOf<String, Any?>(
            "catalog" to canonicalCatalogInfo(info, includeV6Metadata, currentNames).also {
                if (includeV8Identity) {
                    it["registry"] = info.registry
                    it["namespace"] = info.namespace
                    putThemeSettings(it, info)
                }
            },
            "publisher" to manifest.publisher,
            "compatibility" to manifest.compatibility,
            "includes" to manifest.includes.orEmpty().sortedWith(compareBy({ it.url }, { it.description })),
            "resources" to manifest.resources
                .sortedWith(compareBy({ it.type }, { it.key }))
                .map { resource ->
                    linkedMapOf<String, Any?>(
                        "type" to resource.type,
                        keyName to resource.key,
                        "name" to resource.name,
                        "description" to resource.description,
                    ).also { if (includeResourceCompatibility) it["compatibility"] = null }
                },
            "dependencies" to canonicalDependenciesV2(
                manifest.dependencies,
                includeNamespace = includeV8Identity,
                currentNames = currentNames,
                declarations = manifest.catalogDependencies.orEmpty(),
            ),
        )
        if (includeV8Identity) {
            canonical["catalogDependencies"] = manifest.catalogDependencies.orEmpty()
                .sortedWith(compareBy({ it.registry }, { it.namespace }, { it.catalogKey }, { it.releaseVersion }, { it.releaseFingerprint }))
                .map { dependency ->
                    linkedMapOf(
                        "registry" to dependency.registry,
                        "namespace" to dependency.namespace,
                        "catalogKey" to dependency.catalogKey,
                        "releaseVersion" to dependency.releaseVersion,
                        "releaseFingerprint" to dependency.releaseFingerprint,
                    )
                }
        }
        return mapper.writeValueAsString(sortKeys(mapper.valueToTree(canonical)))
    }

    /**
     * V5 covers the catalog's default theme and declared presets (wire v8, ADR 0007): which theme an
     * inheriting template renders with, and which presets content may name, are content. Each is
     * written only when present, so a catalog without them keeps the V5 bytes it had before they
     * existed. Presets are sorted by key: their order carries no meaning.
     *
     * V5 wrote a default theme's `namespace` and `registry`, null when absent. A reference names an
     * alias and neither since contract ADR 0003, so they are written as the null they were for a default theme
     * that left them out, which keeps its V5 value.
     */
    private fun putThemeSettings(
        catalog: MutableMap<String, Any?>,
        info: CatalogInfo,
    ) {
        info.defaultTheme?.let { theme ->
            catalog["defaultTheme"] = linkedMapOf(
                "key" to theme.key,
                "catalogKey" to theme.catalogKey,
                "namespace" to null,
                "registry" to null,
            )
        }
        if (info.presets.isNotEmpty()) {
            catalog["presets"] = info.presets
                .sortedWith(compareBy({ it.key }, { it.name }, { it.description }))
                .map { preset -> linkedMapOf("key" to preset.key, "name" to preset.name, "description" to preset.description) }
        }
    }

    private fun canonicalCatalogInfo(
        catalog: CatalogInfo,
        includeV6Metadata: Boolean,
        currentNames: Boolean,
    ): MutableMap<String, Any?> = linkedMapOf<String, Any?>(
        (if (currentNames) "key" else "slug") to catalog.key,
        "name" to catalog.name,
        "description" to catalog.description,
    ).apply {
        if (includeV6Metadata) {
            put(
                "attributes",
                catalog.attributes.sortedWith(compareBy({ it.catalogKey }, { it.key })).map { attribute ->
                    linkedMapOf(
                        (if (currentNames) "catalogKey" else "catalog") to attribute.catalogKey,
                        "key" to attribute.key,
                        "value" to attribute.value,
                    )
                },
            )
            put("keywords", catalog.keywords.sorted())
            put(
                "presentation",
                catalog.presentation
                    ?.takeUnless { it.iconImageKey == null && it.imageKeys.isEmpty() }
                    ?.let { presentation ->
                        linkedMapOf(
                            (if (currentNames) "iconImageKey" else "iconAssetSlug") to presentation.iconImageKey,
                            (if (currentNames) "imageKeys" else "imageAssetSlugs") to presentation.imageKeys,
                        )
                    },
            )
            put(
                "license",
                catalog.license?.let { license ->
                    linkedMapOf(
                        "name" to license.name,
                        "spdxExpression" to license.spdxExpression,
                        "url" to license.url,
                        "copyrightText" to license.copyrightText,
                    )
                },
            )
        }
    }

    /**
     * The resource dependencies as V2 to V5 cover them. V5 ([includeNamespace]) also covers each
     * one's catalog by registry and namespace. A resource dependency names its catalog by alias since
     * contract ADR 0003, so V5 takes the catalog's name from the declaration the alias names in
     * [declarations]: a manifest whose dependencies named their catalog by key, registry and
     * namespace, as wire v8 did before aliases, keeps its V5 value, since each such catalog had to be
     * declared and is declared under its key.
     */
    private fun canonicalDependenciesV2(
        dependencies: List<DependencyRef>?,
        includeNamespace: Boolean = false,
        currentNames: Boolean = false,
        declarations: List<CatalogDependency> = emptyList(),
    ): List<Map<String, Any?>> = dependencies.orEmpty()
        .map { dependency ->
            val (type, catalogKey) = dependency.identity()
            val declared = declarations.firstOrNull { it.alias == catalogKey }.takeIf { includeNamespace }
            linkedMapOf<String, Any?>(
                "type" to type,
                "catalogKey" to (declared?.catalogKey ?: catalogKey).takeIf(String::isNotEmpty),
                "key" to dependency.key,
            ).also {
                if (includeNamespace) {
                    it["registry"] = declared?.registry
                    it["namespace"] = declared?.namespace
                }
            }
        }
        .sortedWith(
            compareBy(
                { it["type"].toString() },
                { it["registry"].toString() },
                { it["namespace"].toString() },
                { it["catalogKey"].toString() },
                { it["key"].toString() },
            ),
        )
        .map { dependency ->
            if (currentNames) dependency else dependency.mapKeys { (name, _) -> if (name == "key") "slug" else name }
        }

    private fun canonicalDependenciesV1(dependencies: List<DependencyRef>?): String = dependencies.orEmpty()
        .map { dependency ->
            val (type, catalogKey) = dependency.identity()
            "$type|$catalogKey|${dependency.key}"
        }
        .sorted()
        .joinToString(";")

    private fun DependencyRef.identity(): Pair<String, String> = when (this) {
        is DependencyRef.Theme -> "theme" to catalogKey
        is DependencyRef.Stencil -> "stencil" to catalogKey
        is DependencyRef.CodeList -> "codeList" to catalogKey
        is DependencyRef.Font -> "font" to catalogKey
        is DependencyRef.Image -> "image" to catalogKey
    }

    /**
     * Returns a stable SHA-256 for every `type/key` resource.
     *
     * Like the V1 algorithm it belongs to, it covers the members catalog v8 renamed under their old
     * names, so a resource's value does not move with the rename.
     *
     * Asset resource fingerprints include the referenced binary bytes.
     */
    fun perResourceFingerprints(catalog: CatalogArchive): Map<String, String> = entries(catalog).sortedBy(Entry::key).associate { entry ->
        entry.key to sha256((entry.canonicalJson + "\u0000" + entry.assetHash).byteInputStream())
    }

    /**
     * Returns semantic hashes for every `type/key` resource, as the V3 algorithm covers it: the
     * members catalog v8 renamed keep their old names, so a resource's value does not move with the
     * rename.
     */
    fun currentPerResourceFingerprints(catalog: CatalogArchive): Map<String, String> = semanticEntries(catalog).sortedBy(Entry::key).associate { entry ->
        entry.key to sha256((entry.canonicalJson + "\u0000" + entry.assetHash).byteInputStream())
    }

    /**
     * Returns a hash for every `type/key` resource over its complete content, as the V5 and V6
     * algorithms cover it: the per-resource counterpart of [currentFingerprint]. V6 covers each
     * resource exactly as V5 does (it differs from V5 only in what it covers of the manifest), so
     * this one form serves both.
     *
     * Use this to tell resources apart when merging or diffing releases. Unlike
     * [perResourceFingerprints] (V1) and [currentPerResourceFingerprints] (V3), which canonicalize a
     * resource as a catalog-v7 producer serialized it, it covers what catalog v8 added to a resource
     * (a template's `description`, a data example's `key`) and names every
     * member as catalog v8 does, so a change to only those members changes the resource's value. Two
     * resources have the same value exactly when V5 covers them alike: object keys sorted, numbers
     * as JSON binding normalizes them, and an image's bytes included.
     *
     * The values are the lowercase hex SHA-256 under the domain prefix `resource sha256-v5`, so they
     * never equal a V1 or V3 per-resource value. They are not interchangeable with the older forms:
     * compare values computed by this function only. It keeps the V5 form; resource content a later
     * wire version adds gets a form of its own.
     *
     * @throws java.io.IOException when referenced archive content cannot be read.
     */
    fun completePerResourceFingerprints(catalog: CatalogArchive): Map<String, String> = currentEntries(catalog, currentNames = true, variantTitle = true).sortedBy(Entry::key).associate { entry ->
        entry.key to sha256(("resource sha256-v5\n" + entry.canonicalJson + "\u0000" + entry.assetHash).byteInputStream())
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
        }.let(::legacyResource)
        val assetHash = (detail.resource as? ImageResource)?.let { asset ->
            val path = asset.contentPath()
            if (path in catalog.paths) {
                catalog.content.open(path).use(::sha256)
            } else {
                "MISSING"
            }
        }.orEmpty()
        Entry(
            key = key,
            canonicalJson = mapper.writeValueAsString(sortKeys(withoutMetadata(resourceNode))),
            assetHash = assetHash,
        )
    }

    private fun semanticEntries(catalog: CatalogArchive): List<Entry> = catalog.resourceDetails.map { (key, detail) ->
        val resourceNode = normalizeStencilProvenance(legacyResource(resourceNode(catalog, key, detail)), legacyV4 = false)
        Entry(
            key = key,
            canonicalJson = mapper.writeValueAsString(sortKeys(withoutMetadata(resourceNode))),
            assetHash = assetHash(catalog, detail),
        )
    }

    /**
     * Resource entries from the bound model, under the names catalog v8 writes when [currentNames].
     * [variantTitle] keeps a variant's name under `title`, the name it had when V5 was defined, so V5
     * values stay what producers published before wire v8 renamed it to `name`.
     */
    private fun currentEntries(
        catalog: CatalogArchive,
        currentNames: Boolean,
        variantTitle: Boolean = false,
    ): List<Entry> = catalog.resourceDetails.map { (key, detail) ->
        val model: JsonNode = mapper.valueToTree(detail.resource)
        if (variantTitle) withVariantTitles(model)
        val resourceNode = normalizeStencilProvenance(if (currentNames) model else legacyResource(model), legacyV4 = false)
        Entry(
            key = key,
            canonicalJson = mapper.writeValueAsString(sortKeys(withoutMetadata(resourceNode))),
            assetHash = assetHash(catalog, detail),
        )
    }

    /** Renames each of a template's variants' `name` back to `title`, in place. */
    private fun withVariantTitles(resource: JsonNode) {
        (resource["variants"] as? tools.jackson.databind.node.ArrayNode)?.forEach { variant ->
            if (variant !is tools.jackson.databind.node.ObjectNode || !variant.has("name") || variant.has("title")) return@forEach
            val members = variant.properties().map { (name, value) -> (if (name == "name") "title" else name) to value }
            variant.removeAll()
            members.forEach { (name, value) -> variant.set(name, value) }
        }
    }

    private fun legacyV4Entries(catalog: CatalogArchive): List<Entry> = catalog.resourceDetails.map { (key, detail) ->
        val resourceNode = normalizeStencilProvenance(legacyResource(resourceNode(catalog, key, detail)), legacyV4 = true)
        Entry(
            key = key,
            canonicalJson = mapper.writeValueAsString(sortKeys(withoutMetadata(resourceNode))),
            assetHash = assetHash(catalog, detail),
        )
    }

    private fun assetHash(
        catalog: CatalogArchive,
        detail: ResourceDetail,
    ): String = (detail.resource as? ImageResource)?.let { asset ->
        val path = asset.contentPath()
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
            val type = normalized["type"]
            if (type?.isString == true && type.asString() == "stencil" && normalized["props"] is ObjectNode) {
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

    /**
     * A resource as a catalog-v7 producer serialized it, which is what V1 to V4 cover: the members
     * catalog v8 renamed under their old names ([CatalogKeyRenames]), and without the members it
     * added ([CatalogV8Additions]).
     */
    private fun legacyResource(resource: JsonNode): JsonNode = CatalogV8Additions.withoutAdditions(CatalogKeyRenames.legacyResource(resource))

    /**
     * [resource] without its producer `metadata` (wire v8, [app.epistola.catalog.protocol.CatalogMetadata]):
     * metadata never counts in a hash, at release or resource level, under any algorithm. It is what
     * a producer says about the content, not the content.
     */
    private fun withoutMetadata(resource: JsonNode): JsonNode = if (resource is ObjectNode && resource.has("metadata")) resource.deepCopy().apply { remove("metadata") } else resource

    private fun CatalogInfo.identityLine(): String = "$key $name ${description.orEmpty()}\n"

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
