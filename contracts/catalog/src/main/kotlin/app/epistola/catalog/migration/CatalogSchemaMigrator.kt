// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.migration

import app.epistola.catalog.archive.ArchiveContentProvider
import app.epistola.catalog.protocol.CatalogAliases
import app.epistola.catalog.protocol.CatalogArchivePaths
import app.epistola.catalog.protocol.CatalogDependency
import app.epistola.catalog.protocol.CatalogKeywords
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.CatalogNamespaces
import app.epistola.catalog.protocol.CatalogPresetDeclaration
import app.epistola.catalog.protocol.CatalogPresets
import app.epistola.catalog.protocol.CatalogReleaseIds
import app.epistola.catalog.protocol.CatalogReleaseVersions
import app.epistola.catalog.protocol.KeywordRule
import app.epistola.catalog.protocol.PresetRule
import app.epistola.catalog.protocol.ReleaseBase
import app.epistola.catalog.protocol.ResourceDetail
import tools.jackson.core.JacksonException
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.io.IOException
import java.io.InputStream

/**
 * Supported portable catalog wire-version interval.
 *
 * A version is accepted only when an explicit migration path exists from
 * [BASELINE_VERSION] through [CURRENT_VERSION].
 */
object CatalogWireSchema {
    const val CURRENT_VERSION: Int = 8
    const val BASELINE_VERSION: Int = 4
}

/**
 * What the caller knows about a catalog that its archive does not say.
 *
 * A catalog migrated from wire v7 or earlier names neither its registry nor its namespace, and both
 * are identity, the catalog's name, which must not change when its releases move between
 * installations: the importer passes the registry and namespace chosen when the catalog was created
 * (ADR 0003, amendment of 2026-10-21), never values derived from a tenant key or an installation's
 * host on the way. Both are ignored for an archive at wire v8 or later, which names its own. Without
 * either, migrating such an archive fails with [CatalogMigrationCodes.REGISTRY_MISSING] or
 * [CatalogMigrationCodes.NAMESPACE_MISSING]; the migrator never invents one.
 *
 * [namespace] comes first so that call sites written before the registry existed still compile.
 *
 * @property namespace the namespace to give such a catalog
 *   ([app.epistola.catalog.protocol.CatalogNamespaces.PATTERN]).
 * @property registry the registry to give such a catalog, written in full
 *   ([app.epistola.catalog.protocol.CatalogNamespaces.REGISTRY_PATTERN]). An archive has no default
 *   registry, so the caller names its default by its real name.
 */
data class CatalogMigrationOptions
@JvmOverloads
constructor(
    val namespace: String? = null,
    val registry: String? = null,
) {
    companion object {
        /** No caller knowledge: archives from before wire v8 cannot be migrated with these. */
        @JvmField
        val DEFAULT = CatalogMigrationOptions()
    }
}

/** Stable diagnostic explaining why a wire document could not be migrated. */
data class CatalogMigrationFinding(
    val code: String,
    val path: String,
    val message: String,
)

/** Non-fatal diagnostic describing a successful migration conversion. */
data class CatalogMigrationNotice(
    val code: String,
    val path: String,
    val message: String,
)

/**
 * Outcome of parsing, version-gating, migrating, and binding one JSON document.
 *
 * Ordinary malformed or unsupported input produces findings and a null
 * [value]. Unrecoverable I/O remains exceptional.
 */
data class CatalogMigrationResult<T>(
    val value: T?,
    val sourceVersion: Int?,
    val findings: List<CatalogMigrationFinding>,
    val notices: List<CatalogMigrationNotice> = emptyList(),
) {
    /** True when a value was bound without migration findings. */
    val valid: Boolean get() = value != null && findings.isEmpty()
}

/**
 * Manifest state required to migrate and bind a resource detail consistently.
 */
data class CatalogMigrationContext(
    val sourceVersion: Int,
    val manifest: CatalogManifest,
    /**
     * The archive's files, when the caller has them.
     *
     * A migration that has to reason about a resource's *bytes* -- hashing a binary, or resolving
     * what another resource points at -- cannot do it from [manifest] alone, which carries resource
     * entries but not their contents. Null when a resource detail is migrated on its own, outside
     * an archive; a migration that needs content reports a finding rather than guessing.
     */
    val content: ArchiveContentProvider? = null,
)

/** Stable finding codes emitted by [CatalogSchemaMigrator]. */
object CatalogMigrationCodes {
    const val SCHEMA_UNKNOWN = "CATALOG_SCHEMA_UNKNOWN"
    const val SCHEMA_TOO_NEW = "CATALOG_SCHEMA_TOO_NEW"
    const val SCHEMA_TOO_OLD = "CATALOG_SCHEMA_TOO_OLD"
    const val SCHEMA_VERSION_MISMATCH = "CATALOG_SCHEMA_VERSION_MISMATCH"
    const val RESOURCE_TYPE_MISMATCH = "CATALOG_RESOURCE_TYPE_MISMATCH"
    const val DRAFT_MARKER_INVALID = "CATALOG_DRAFT_MARKER_INVALID"
    const val STALE_DRAFT_MARKER_REMOVED = "CATALOG_STALE_DRAFT_MARKER_REMOVED"
    const val KEYWORD_INVALID = "CATALOG_KEYWORD_INVALID"
    const val KEYWORD_DUPLICATE = "CATALOG_KEYWORD_DUPLICATE"
    const val KEYWORD_TOO_LONG = "CATALOG_KEYWORD_TOO_LONG"
    const val KEYWORD_LIMIT_EXCEEDED = "CATALOG_KEYWORD_LIMIT_EXCEEDED"

    /** A declared preset's key is not a string or breaks [CatalogPresets.KEY_PATTERN] (wire v8, ADR 0007). */
    const val PRESET_KEY_INVALID = "CATALOG_PRESET_KEY_INVALID"

    /** The catalog declares the same preset key more than once (wire v8, ADR 0007). */
    const val PRESET_DUPLICATE = "CATALOG_PRESET_DUPLICATE"

    /** A declared preset's name is not a nonblank string within [CatalogPresets.NAME_MAX_LENGTH] (wire v8). */
    const val PRESET_NAME_INVALID = "CATALOG_PRESET_NAME_INVALID"

    /** A declared preset's description is longer than [CatalogPresets.DESCRIPTION_MAX_LENGTH] (wire v8). */
    const val PRESET_DESCRIPTION_INVALID = "CATALOG_PRESET_DESCRIPTION_INVALID"

    /**
     * `release.version` is not a wire-v8 release number ([CatalogReleaseVersions]:
     * `MAJOR.MINOR.PATCH`, or `MAJOR.MINOR.PATCH-<namespace>.<N>` for a patch release). Raised for a
     * catalog-v8 manifest, and by the v7 to v8 migration for a release numbered with any pre-release
     * or build part (`1.0.0-beta.1`), which earlier wire versions accepted as a pre-release: the
     * migrator never strips that part, because the number would then name another release, and
     * never reads it as a patch release.
     */
    const val RELEASE_VERSION_INVALID = "CATALOG_RELEASE_VERSION_INVALID"

    /**
     * A catalog-v8 manifest's `release.releaseId` is not a release id ([CatalogReleaseIds]:
     * lowercase letters, digits and single hyphens, 4 to 36 characters).
     */
    const val RELEASE_ID_INVALID = "CATALOG_RELEASE_ID_INVALID"

    /**
     * A catalog-v8 manifest's `release.base` is not an object with a release number `version`
     * ([CatalogReleaseVersions]) and a `fingerprint` naming a known algorithm
     * ([ReleaseBase.isValidFingerprint]).
     */
    const val RELEASE_BASE_INVALID = "CATALOG_RELEASE_BASE_INVALID"

    /**
     * A catalog-v8 catalog dependency's `releaseVersion` is not a release number
     * ([CatalogReleaseVersions]: `MAJOR.MINOR.PATCH`, or `MAJOR.MINOR.PATCH-<namespace>.<N>` for a patch release).
     */
    const val DEPENDENCY_CATALOG_VERSION_INVALID = "CATALOG_DEPENDENCY_CATALOG_VERSION_INVALID"

    /**
     * Notice: an early wire-v8 template's variant metadata moved to the template's own metadata,
     * under each namespace's `variants.<variant key>` ([VariantMetadataFold]).
     */
    const val VARIANT_METADATA_FOLDED = "CATALOG_VARIANT_METADATA_FOLDED"

    /**
     * An early wire-v8 template's variant metadata cannot move to the template's metadata without
     * overwriting a value there, or it is not an object on a keyed variant ([VariantMetadataFold]).
     */
    const val VARIANT_METADATA_UNFOLDABLE = "CATALOG_VARIANT_METADATA_UNFOLDABLE"

    /**
     * A catalog-v6 asset dependency names no catalog, and catalog-v7 requires one.
     *
     * Not repairable: the archive says which asset is depended on but not whose, and guessing
     * would bind the consumer to whichever catalog happened to match. The publisher re-exports,
     * which qualifies it from their own installed state.
     */
    const val DEPENDENCY_UNQUALIFIED = "CATALOG_DEPENDENCY_UNQUALIFIED"

    /**
     * A catalog-v6 binary could not be identified by its content while migrating to catalog-v7.
     *
     * Raised when an asset declares no `contentUrl`, when a font face names an asset the archive
     * does not hold, or when a resource detail is migrated outside an archive so there are no
     * bytes to hash. Not repairable: catalog-v7 identifies a binary by what it is, and that cannot
     * be invented.
     */
    const val ASSET_CONTENT_UNRESOLVED = "CATALOG_ASSET_CONTENT_UNRESOLVED"

    /**
     * A catalog-v7 (or earlier) archive names no registry, and the caller supplied none in
     * [CatalogMigrationOptions.registry]. Not repairable without the caller: a registry is part of
     * the catalog's identity, so the migrator never invents one.
     */
    const val REGISTRY_MISSING = "CATALOG_REGISTRY_MISSING"

    /**
     * A registry is not a DNS name written in full, whether the archive's own or the one the caller
     * supplied for a catalog-v7 archive. Also raised when a catalog-v8 manifest's catalog or catalog
     * dependency names none.
     */
    const val REGISTRY_INVALID = "CATALOG_REGISTRY_INVALID"

    /**
     * A catalog-v7 (or earlier) archive names no namespace, and the caller supplied none in
     * [CatalogMigrationOptions.namespace]. Not repairable without the caller: a namespace is part of
     * the catalog's identity, so the migrator never invents one.
     */
    const val NAMESPACE_MISSING = "CATALOG_NAMESPACE_MISSING"

    /**
     * A namespace is not a valid slug, whether the archive's own or the one the caller supplied for
     * a catalog-v7 archive. Also raised when a catalog-v8 manifest's catalog or catalog dependency
     * names none.
     */
    const val NAMESPACE_INVALID = "CATALOG_NAMESPACE_INVALID"

    /**
     * A catalog-v8 catalog dependency names no `alias`, or one that is not a string following the
     * alias rule ([app.epistola.catalog.protocol.CatalogAliases]: the catalog key rule). Also raised by
     * the v7-to-v8 archive migration for a catalog its content names that cannot be declared, because
     * its key cannot be an alias.
     */
    const val DEPENDENCY_ALIAS_INVALID = "CATALOG_DEPENDENCY_ALIAS_INVALID"

    /**
     * A reference migrated from before wire v8 names a catalog by registry and namespace that the
     * catalog does not declare as a dependency, so it cannot be mapped to an alias (contract ADR 0003). Not
     * repaired: the migration does not invent a dependency in another namespace.
     */
    const val REFERENCE_ALIAS_UNRESOLVED = "CATALOG_REFERENCE_ALIAS_UNRESOLVED"

    /**
     * A catalog-v8 catalog dependency's `releaseFingerprint` does not pair with its `releaseVersion`:
     * it is missing or null although a release is recorded, set although none is, or not in the form
     * that names a known algorithm (`v6:<lowercase SHA-256 hex>`).
     */
    const val DEPENDENCY_CATALOG_FINGERPRINT_INVALID = "CATALOG_DEPENDENCY_CATALOG_FINGERPRINT_INVALID"

    /** Notice: a catalog-v7 catalog was given the registry the caller supplied. */
    const val REGISTRY_ASSIGNED = "CATALOG_REGISTRY_ASSIGNED"

    /** Notice: a catalog-v7 catalog was given the namespace the caller supplied. */
    const val NAMESPACE_ASSIGNED = "CATALOG_NAMESPACE_ASSIGNED"

    /**
     * Notice: a catalog-v7 catalog named by a resource dependency, or by the content, was declared as
     * a catalog dependency under its key as the alias, in the catalog's own registry and namespace,
     * pinning no release, for the author to pin one.
     */
    const val DEPENDENCY_CATALOG_DECLARED = "CATALOG_DEPENDENCY_CATALOG_DECLARED"

    /**
     * Notice: a reference migrated from before wire v8 that named a catalog by registry and
     * namespace now names it by alias, or names no catalog when it named the catalog itself (contract ADR
     * 0003).
     */
    const val REFERENCE_ALIASED = "CATALOG_REFERENCE_ALIASED"

    /**
     * Notice: a catalog-v7 stencil version was removed -- a stencil resource's own `version`, or a
     * stencil reference's `version` or `draftVersion`. Catalog v8 versions catalogs as a whole.
     */
    const val STENCIL_VERSION_REMOVED = "CATALOG_STENCIL_VERSION_REMOVED"

    /**
     * Notice: a catalog-v7 data example was given a key derived from its name, unique within its
     * template. The path names the new `key`, and the message the key chosen.
     */
    const val DATA_EXAMPLE_KEY_ASSIGNED = "CATALOG_DATA_EXAMPLE_KEY_ASSIGNED"

    /**
     * Notice: a catalog-v7 key shorter than 3 characters was padded on the right with `0` to 3
     * ([app.epistola.catalog.protocol.CatalogKeys.migrate]): `a` became `a00`, `nl` `nl0`. One per key
     * the catalog defines (a resource's, a variant's, a data example's), at the key's path; the
     * references to it are rewritten the same way without a notice of their own.
     */
    const val RESOURCE_KEY_PADDED = "CATALOG_RESOURCE_KEY_PADDED"

    /**
     * Notice: a catalog-v7 key longer than 50 characters was cut to 50, without a trailing hyphen
     * ([app.epistola.catalog.protocol.CatalogKeys.migrate]). One per key the catalog defines, like
     * [RESOURCE_KEY_PADDED]; the references to it are rewritten the same way.
     */
    const val RESOURCE_KEY_TRUNCATED = "CATALOG_RESOURCE_KEY_TRUNCATED"

    /**
     * A catalog-v7 key would be padded to a key that another key of the same scope already has, or
     * is padded to as well: another resource of the same type in the catalog, another variant or data
     * example of the same template, another attribute of the same variant. Not repaired: choosing a
     * different key would make the rewrite depend on the catalog, and references from other catalogs,
     * which are rewritten without seeing it, would then point elsewhere. The archive is refused.
     */
    const val RESOURCE_KEY_PADDING_COLLISION = "CATALOG_RESOURCE_KEY_PADDING_COLLISION"

    /** Like [RESOURCE_KEY_PADDING_COLLISION], for a key cut to 50 characters. */
    const val RESOURCE_KEY_TRUNCATION_COLLISION = "CATALOG_RESOURCE_KEY_TRUNCATION_COLLISION"

    /**
     * A catalog-v8 manifest's `detailUrl` is not a relative path below the manifest's directory
     * ([app.epistola.catalog.protocol.CatalogArchivePaths]). Not raised for an earlier wire version,
     * which did not constrain it.
     */
    const val DETAIL_URL_INVALID = "CATALOG_MANIFEST_DETAIL_URL_INVALID"

    /**
     * A catalog-v8 image's or font face's `contentUrl` is not a relative path below the manifest's
     * directory ([app.epistola.catalog.protocol.CatalogArchivePaths]). Not raised for an earlier wire
     * version, which did not constrain it.
     */
    const val ASSET_PATH_INVALID = "CATALOG_ASSET_PATH_INVALID"

    /** Notice: a catalog-v6 keyword was rewritten to its catalog-v7 form. */
    const val KEYWORD_NORMALIZED = "CATALOG_KEYWORD_NORMALIZED"

    /** Notice: a catalog-v6 keyword was rewritten and shortened to fit the catalog-v7 length limit. */
    const val KEYWORD_TRUNCATED = "CATALOG_KEYWORD_TRUNCATED"

    /** Notice: a catalog-v6 keyword had no letters or digits, or exceeded the catalog-v7 keyword limit. */
    const val KEYWORD_REMOVED = "CATALOG_KEYWORD_REMOVED"
}

/**
 * Portable catalog-wide wire-version gate.
 *
 * Catalog v4 is the migration baseline and v8 is the only emitted shape.
 */
object CatalogSchemaMigrator {
    private val mapper = jsonMapper { addModule(kotlinModule()) }
    private val migrations: List<CatalogSchemaMigration> = listOf(
        CatalogV4ToV5Migration(),
        CatalogV5ToV6Migration(),
        CatalogV6ToV7Migration(),
        CatalogV7ToV8Migration(),
    )

    /**
     * Parses and migrates `catalog.json` to the current [CatalogManifest].
     *
     * [input] is consumed but not closed by this method. [options] supplies what an archive from
     * before wire v8 cannot say for itself, notably its registry and namespace.
     *
     * @throws IOException when the stream cannot be read.
     */
    @JvmOverloads
    fun migrateManifest(
        input: InputStream,
        options: CatalogMigrationOptions = CatalogMigrationOptions.DEFAULT,
    ): CatalogMigrationResult<CatalogManifest> {
        val tree = parse(input) ?: return failure(CatalogMigrationCodes.SCHEMA_UNKNOWN, "catalog.json", "manifest is not a JSON object")
        val source = schemaVersion(tree) ?: return failure(
            CatalogMigrationCodes.SCHEMA_UNKNOWN,
            "catalog.json.schemaVersion",
            "manifest schemaVersion is missing or is not an integer",
        )
        versionFinding(source, "catalog.json.schemaVersion")?.let { return CatalogMigrationResult(null, source, listOf(it)) }
        val migration = migrate(source) { step -> step.migrateManifest(tree, options) }
        if (migration.findings.isNotEmpty()) return CatalogMigrationResult(null, source, migration.findings, migration.notices)
        val wireFindings = validateManifestWireValues(tree, source)
        if (wireFindings.isNotEmpty()) return CatalogMigrationResult(null, source, wireFindings, migration.notices)
        return bind(tree, CatalogManifest::class.java, source, "catalog.json", migration.notices)
    }

    /**
     * Parses and migrates one resource detail using its owning manifest.
     *
     * The document's schema version must match [context], and its resource
     * discriminator must match [declaredType]. [path] is copied into findings
     * so callers retain deterministic archive locations.
     *
     * @throws IllegalArgumentException when [declaredType] is blank.
     * @throws IOException when the stream cannot be read.
     */
    fun migrateResourceDetail(
        declaredType: String,
        input: InputStream,
        context: CatalogMigrationContext,
        path: String,
    ): CatalogMigrationResult<ResourceDetail> {
        require(declaredType.isNotBlank())
        val tree = parse(input) ?: return failure(CatalogMigrationCodes.SCHEMA_UNKNOWN, path, "resource detail is not a JSON object")
        val source = schemaVersion(tree) ?: return failure(
            CatalogMigrationCodes.SCHEMA_UNKNOWN,
            "$path.schemaVersion",
            "resource detail schemaVersion is missing or is not an integer",
        )
        if (source != context.sourceVersion) {
            return failure(
                CatalogMigrationCodes.SCHEMA_VERSION_MISMATCH,
                "$path.schemaVersion",
                "resource detail is at schemaVersion $source but the catalog manifest is at ${context.sourceVersion}",
                source,
            )
        }
        // Compared through the same rename the manifest went through. A catalog-v6 archive says
        // `asset` in both places; its manifest entry has already migrated to `image` by the time
        // this runs, so a literal comparison would report every image as a type mismatch.
        val actualType = tree["resource"]?.get("type")?.takeIf { it.isString }?.asString()
            ?.let { if (it == "asset") "image" else it }
        if (actualType != null && actualType != declaredType) {
            return failure(
                CatalogMigrationCodes.RESOURCE_TYPE_MISMATCH,
                "$path.resource.type",
                "resource detail declares type '$actualType' but the manifest entry is '$declaredType'",
                source,
            )
        }
        versionFinding(source, "$path.schemaVersion")?.let { return CatalogMigrationResult(null, source, listOf(it)) }
        val migration = migrate(source) { step -> step.migrateResource(tree, path, context) }
        if (migration.findings.isNotEmpty()) return CatalogMigrationResult(null, source, migration.findings, migration.notices)
        val wireFindings = (tree["resource"] as? ObjectNode)
            ?.let {
                renamedMemberFindings(CatalogKeyRenames.resourceSites(it, "$path.resource")) +
                    CatalogReferenceQualifiers.findings(CatalogReferenceQualifiers.resourceSites(it, "$path.resource")) +
                    contentUrlFindings(it, "$path.resource", source)
            }
            .orEmpty()
        if (wireFindings.isNotEmpty()) return CatalogMigrationResult(null, source, wireFindings, migration.notices)
        val foldFindings = mutableListOf<CatalogMigrationFinding>()
        val notices = migration.notices.toMutableList()
        (tree["resource"] as? ObjectNode)?.let { VariantMetadataFold.fold(it, "$path.resource", foldFindings, notices) }
        if (foldFindings.isNotEmpty()) return CatalogMigrationResult(null, source, foldFindings, notices)
        return bind(tree, ResourceDetail::class.java, source, path, notices)
    }

    private fun parse(input: InputStream): ObjectNode? = try {
        mapper.readTree(input) as? ObjectNode
    } catch (exception: JacksonException) {
        null
    } catch (exception: IOException) {
        throw exception
    }

    private fun schemaVersion(tree: ObjectNode): Int? = tree["schemaVersion"]?.takeIf { it.isIntegralNumber }?.asInt()

    private fun validateManifestWireValues(
        tree: ObjectNode,
        source: Int,
    ): List<CatalogMigrationFinding> = renamedMemberFindings(CatalogKeyRenames.manifestSites(tree)) +
        CatalogReferenceQualifiers.findings(CatalogReferenceQualifiers.manifestSites(tree)) +
        namespaceFindings(tree) +
        aliasFindings(tree) +
        releaseVersionFindings(tree) +
        releaseIdFindings(tree) +
        releaseBaseFindings(tree) +
        releaseFingerprintFindings(tree) +
        keywordFindings(tree) +
        presetFindings(tree) +
        detailUrlFindings(tree, source)

    /**
     * Wire v8 requires every path into the catalog to be relative and below the manifest's directory
     * ([CatalogArchivePaths]). An archive written at an earlier version is not held to it, so that it
     * stays readable; the archive reader never resolves a path outside the archive whatever it says.
     */
    private fun detailUrlFindings(
        tree: ObjectNode,
        source: Int,
    ): List<CatalogMigrationFinding> {
        if (source < ARCHIVE_PATHS_SINCE_WIRE_VERSION) return emptyList()
        val resources = tree["resources"] as? ArrayNode ?: return emptyList()
        return resources.mapIndexedNotNull { index, entry ->
            val detailUrl = (entry as? ObjectNode)?.get("detailUrl")?.takeIf { it.isString }?.asString() ?: return@mapIndexedNotNull null
            if (CatalogArchivePaths.isValid(detailUrl)) return@mapIndexedNotNull null
            CatalogMigrationFinding(
                CatalogMigrationCodes.DETAIL_URL_INVALID,
                "catalog.json.resources[$index].detailUrl",
                "detailUrl '$detailUrl' must be a relative path below the manifest's directory",
            )
        }
    }

    /** The `contentUrl` of an image or font face, held to the same rule as a manifest `detailUrl`. */
    private fun contentUrlFindings(
        resource: ObjectNode,
        path: String,
        source: Int,
    ): List<CatalogMigrationFinding> {
        if (source < ARCHIVE_PATHS_SINCE_WIRE_VERSION) return emptyList()
        val sites = when (resource["type"]?.takeIf { it.isString }?.asString()) {
            "image" -> listOf(resource to path)
            "font" -> (resource["variants"] as? ArrayNode)?.mapIndexedNotNull { index, face -> (face as? ObjectNode)?.let { it to "$path.variants[$index]" } }.orEmpty()
            else -> emptyList()
        }
        return sites.mapNotNull { (owner, ownerPath) ->
            val contentUrl = owner["contentUrl"]?.takeIf { it.isString }?.asString() ?: return@mapNotNull null
            if (CatalogArchivePaths.isValid(contentUrl)) return@mapNotNull null
            CatalogMigrationFinding(
                CatalogMigrationCodes.ASSET_PATH_INVALID,
                "$ownerPath.contentUrl",
                "contentUrl '$contentUrl' must be a relative path below the manifest's directory",
            )
        }
    }

    /**
     * Wire v8 calls a resource's address its `key` ([CatalogKeyRenames]). The model still binds the
     * old names, so that documents a consumer stored under earlier versions still load; the wire
     * check is where v8 rejects them. A migrated document never reaches here with one, because the
     * migration renames every one of them; a document written at v8 with an old name is not wire v8.
     */
    private fun renamedMemberFindings(sites: List<CatalogKeyRenames.Site>): List<CatalogMigrationFinding> = CatalogKeyRenames.remainingOldMembers(sites).map { (path, site) ->
        CatalogMigrationFinding(
            CatalogMigrationCodes.SCHEMA_UNKNOWN,
            path,
            "'${site.oldName}' is not a catalog-v8 member; wire v8 calls it '${site.newName}'",
        )
    }

    /**
     * Wire v8 numbers every release it names `MAJOR.MINOR.PATCH`, or with a patch label
     * ([CatalogReleaseVersions]): the
     * catalog's own `release.version` and each catalog dependency's `releaseVersion`. The model binds
     * any string, so that manifests stored under earlier versions still load; the wire check is where
     * v8 enforces the rule.
     */
    private fun releaseVersionFindings(tree: ObjectNode): List<CatalogMigrationFinding> {
        val findings = mutableListOf<CatalogMigrationFinding>()
        tree["release"]?.get("version")?.takeIf { it.isString }?.asString()?.let { version ->
            if (!CatalogReleaseVersions.isValid(version)) {
                findings += CatalogMigrationFinding(
                    CatalogMigrationCodes.RELEASE_VERSION_INVALID,
                    "catalog.json.release.version",
                    "release version '$version' must be MAJOR.MINOR.PATCH, or MAJOR.MINOR.PATCH-<namespace>.<N> for a patch release, and nothing else",
                )
            }
        }
        (tree["catalogDependencies"] as? ArrayNode)?.forEachIndexed { index, entry ->
            val release = (entry as? ObjectNode)?.get("releaseVersion")?.takeUnless { it.isNull } ?: return@forEachIndexed
            if (!release.isString || !CatalogReleaseVersions.isValid(release.asString())) {
                findings += CatalogMigrationFinding(
                    CatalogMigrationCodes.DEPENDENCY_CATALOG_VERSION_INVALID,
                    "catalog.json.catalogDependencies[$index].releaseVersion",
                    "releaseVersion must be MAJOR.MINOR.PATCH, or MAJOR.MINOR.PATCH-<namespace>.<N> for a patch release, and nothing else",
                )
            }
        }
        return findings
    }

    /**
     * Wire v8 names a release's id, when it carries one, by the API's rule ([CatalogReleaseIds]).
     * The model binds any string, so that stored manifests still load; the wire check enforces it.
     */
    private fun releaseIdFindings(tree: ObjectNode): List<CatalogMigrationFinding> {
        val releaseId = tree["release"]?.get("releaseId")?.takeUnless { it.isNull } ?: return emptyList()
        if (releaseId.isString && CatalogReleaseIds.isValid(releaseId.asString())) return emptyList()
        return listOf(
            CatalogMigrationFinding(
                CatalogMigrationCodes.RELEASE_ID_INVALID,
                "catalog.json.release.releaseId",
                "releaseId must be lowercase letters, digits and single hyphens, " +
                    "${CatalogReleaseIds.MIN_LENGTH} to ${CatalogReleaseIds.MAX_LENGTH} characters",
            ),
        )
    }

    /**
     * Wire v8 holds a release's base, when it carries one, to a release number and a known qualified
     * fingerprint. The model requires both members, so the wire check reports a missing or mistyped
     * one as a finding before binding, rather than as a document that does not bind.
     */
    private fun releaseBaseFindings(tree: ObjectNode): List<CatalogMigrationFinding> {
        val base = tree["release"]?.get("base")?.takeUnless { it.isNull } ?: return emptyList()
        val path = "catalog.json.release.base"
        if (base !is ObjectNode) return listOf(CatalogMigrationFinding(CatalogMigrationCodes.RELEASE_BASE_INVALID, path, "base must be an object"))
        val findings = mutableListOf<CatalogMigrationFinding>()
        val version = base["version"]
        if (version == null || !version.isString || !CatalogReleaseVersions.isValid(version.asString())) {
            findings += CatalogMigrationFinding(CatalogMigrationCodes.RELEASE_BASE_INVALID, "$path.version", "base version must be MAJOR.MINOR.PATCH, or MAJOR.MINOR.PATCH-<namespace>.<N> for a patch release, and nothing else")
        }
        val fingerprint = base["fingerprint"]
        if (fingerprint == null || !fingerprint.isString || !ReleaseBase.isValidFingerprint(fingerprint.asString())) {
            findings += CatalogMigrationFinding(CatalogMigrationCodes.RELEASE_BASE_INVALID, "$path.fingerprint", "base fingerprint must name a known algorithm: v6:<lowercase SHA-256 hex>")
        }
        return findings
    }

    /**
     * Wire v8 records a catalog dependency's release fingerprint exactly when it records the
     * release. The model binds either alone, so that manifests stored without the fingerprint still
     * load; the wire check is where v8 enforces the pairing.
     */
    private fun releaseFingerprintFindings(tree: ObjectNode): List<CatalogMigrationFinding> {
        val dependencies = tree["catalogDependencies"] as? ArrayNode ?: return emptyList()
        return dependencies.mapIndexedNotNull { index, entry ->
            if (entry !is ObjectNode) return@mapIndexedNotNull null
            val path = "catalog.json.catalogDependencies[$index].releaseFingerprint"
            val release = entry["releaseVersion"]?.takeUnless { it.isNull }
            val fingerprint = entry["releaseFingerprint"]?.takeUnless { it.isNull }
            val message = when {
                release == null && fingerprint != null -> "releaseFingerprint must be null when no releaseVersion is recorded"
                release != null && fingerprint == null -> "releaseFingerprint is required when a releaseVersion is recorded"
                fingerprint != null && !(fingerprint.isString && CatalogDependency.isValidReleaseFingerprint(fingerprint.asString())) ->
                    "releaseFingerprint must name a known algorithm: v6:<lowercase SHA-256 hex>"
                else -> null
            }
            message?.let { CatalogMigrationFinding(CatalogMigrationCodes.DEPENDENCY_CATALOG_FINGERPRINT_INVALID, path, it) }
        }
    }

    /**
     * Wire v8 requires a registry, written in full, and a namespace wherever the manifest names a
     * catalog: the catalog itself and each catalog dependency. The model binds missing ones, so that
     * manifests stored under earlier versions still load; the wire check is where v8 enforces them.
     */
    private fun namespaceFindings(tree: ObjectNode): List<CatalogMigrationFinding> {
        val findings = mutableListOf<CatalogMigrationFinding>()

        fun check(
            entry: ObjectNode,
            path: String,
            what: String,
        ) {
            val registry = entry["registry"]
            if (registry == null || !registry.isString || !CatalogNamespaces.isValidRegistry(registry.asString())) {
                findings += CatalogMigrationFinding(
                    CatalogMigrationCodes.REGISTRY_INVALID,
                    "$path.registry",
                    if (registry == null || registry.isNull) "$what names no registry" else "$what registry must be a DNS name written in full",
                )
            }
            val namespace = entry["namespace"]
            if (namespace == null || !namespace.isString || !CatalogNamespaces.isValid(namespace.asString())) {
                findings += CatalogMigrationFinding(
                    CatalogMigrationCodes.NAMESPACE_INVALID,
                    "$path.namespace",
                    if (namespace == null || namespace.isNull) "$what names no namespace" else "$what namespace must be a lowercase slug",
                )
            }
        }
        (tree["catalog"] as? ObjectNode)?.let { check(it, "catalog.json.catalog", "catalog") }
        (tree["catalogDependencies"] as? ArrayNode)?.forEachIndexed { index, entry ->
            if (entry is ObjectNode) check(entry, "catalog.json.catalogDependencies[$index]", "dependency")
        }
        return findings
    }

    /**
     * Wire v8 declares each catalog dependency under an alias (contract ADR 0003), which the model defaults to
     * the catalog's key so that manifests stored before aliases existed still bind; the wire check is
     * where v8 requires one, by [CatalogAliases]. Whether it is unique, and not the catalog's own key,
     * is the validator's to check.
     */
    private fun aliasFindings(tree: ObjectNode): List<CatalogMigrationFinding> {
        val dependencies = tree["catalogDependencies"] as? ArrayNode ?: return emptyList()
        return dependencies.mapIndexedNotNull { index, entry ->
            if (entry !is ObjectNode) return@mapIndexedNotNull null
            val alias = entry["alias"]
            if (alias != null && alias.isString && CatalogAliases.isValid(alias.asString())) return@mapIndexedNotNull null
            CatalogMigrationFinding(
                CatalogMigrationCodes.DEPENDENCY_ALIAS_INVALID,
                "catalog.json.catalogDependencies[$index].alias",
                if (alias == null || alias.isNull) {
                    "dependency names no alias"
                } else {
                    "alias must be ${CatalogAliases.MIN_LENGTH} to ${CatalogAliases.MAX_LENGTH} characters matching ${CatalogAliases.PATTERN}"
                },
            )
        }
    }

    private fun keywordFindings(tree: ObjectNode): List<CatalogMigrationFinding> {
        val keywords = tree["catalog"]?.get("keywords")?.takeIf { it.isArray } ?: return emptyList()
        // Binding would coerce a number to a string, so reject non-strings here.
        val nonStrings = keywords.mapIndexedNotNull { index, node ->
            if (node.isString) {
                null
            } else {
                CatalogMigrationFinding(CatalogMigrationCodes.KEYWORD_INVALID, "catalog.json.catalog.keywords[$index]", "keyword must be a string")
            }
        }
        if (nonStrings.isNotEmpty()) return nonStrings
        return CatalogKeywords.violations(keywords.mapTo(mutableListOf()) { it.asString() }).map { violation ->
            CatalogMigrationFinding(
                code = when (violation.rule) {
                    KeywordRule.INVALID -> CatalogMigrationCodes.KEYWORD_INVALID
                    KeywordRule.TOO_LONG -> CatalogMigrationCodes.KEYWORD_TOO_LONG
                    KeywordRule.DUPLICATE -> CatalogMigrationCodes.KEYWORD_DUPLICATE
                    KeywordRule.LIMIT_EXCEEDED -> CatalogMigrationCodes.KEYWORD_LIMIT_EXCEEDED
                },
                path = violation.index?.let { "catalog.json.catalog.keywords[$it]" } ?: "catalog.json.catalog.keywords",
                message = violation.message,
            )
        }
    }

    /**
     * The presets a catalog declares (wire v8, ADR 0007) follow [CatalogPresets]. Binding would coerce
     * a number to a string and keep a repeated key, so they are checked here. A manifest from before
     * wire v8 declares none.
     */
    private fun presetFindings(tree: ObjectNode): List<CatalogMigrationFinding> {
        val presets = tree["catalog"]?.get("presets") as? ArrayNode ?: return emptyList()
        val findings = mutableListOf<CatalogMigrationFinding>()
        val declared = mutableListOf<Pair<Int, CatalogPresetDeclaration>>()
        presets.forEachIndexed { index, node ->
            val path = "catalog.json.catalog.presets[$index]"
            val key = node["key"]
            val name = node["name"]
            when {
                key == null || !key.isString -> findings += CatalogMigrationFinding(CatalogMigrationCodes.PRESET_KEY_INVALID, "$path.key", "preset key must be a string")
                name == null || !name.isString -> findings += CatalogMigrationFinding(CatalogMigrationCodes.PRESET_NAME_INVALID, "$path.name", "preset name must be a string")
                else -> declared += index to CatalogPresetDeclaration(key.asString(), name.asString(), node["description"]?.takeIf { it.isString }?.asString())
            }
        }
        CatalogPresets.violations(declared.map { it.second }).forEach { violation ->
            findings += CatalogMigrationFinding(
                code = when (violation.rule) {
                    PresetRule.KEY_INVALID -> CatalogMigrationCodes.PRESET_KEY_INVALID
                    PresetRule.NAME_INVALID -> CatalogMigrationCodes.PRESET_NAME_INVALID
                    PresetRule.DESCRIPTION_TOO_LONG -> CatalogMigrationCodes.PRESET_DESCRIPTION_INVALID
                    PresetRule.DUPLICATE -> CatalogMigrationCodes.PRESET_DUPLICATE
                },
                path = "catalog.json.catalog.presets[${declared[violation.index].first}].${violation.member}",
                message = violation.message,
            )
        }
        return findings
    }

    /** The wire version from which every path into the catalog is held to [CatalogArchivePaths]. */
    private const val ARCHIVE_PATHS_SINCE_WIRE_VERSION = 8

    private fun versionFinding(
        source: Int,
        path: String,
    ): CatalogMigrationFinding? = when {
        source > CatalogWireSchema.CURRENT_VERSION -> CatalogMigrationFinding(
            CatalogMigrationCodes.SCHEMA_TOO_NEW,
            path,
            "catalog schemaVersion $source is newer than supported version ${CatalogWireSchema.CURRENT_VERSION}",
        )
        source < CatalogWireSchema.BASELINE_VERSION -> CatalogMigrationFinding(
            CatalogMigrationCodes.SCHEMA_TOO_OLD,
            path,
            "catalog schemaVersion $source is older than baseline ${CatalogWireSchema.BASELINE_VERSION}",
        )
        else -> null
    }

    private fun <T : Any> bind(
        tree: ObjectNode,
        type: Class<T>,
        source: Int,
        path: String,
        notices: List<CatalogMigrationNotice> = emptyList(),
    ): CatalogMigrationResult<T> = try {
        CatalogMigrationResult(mapper.treeToValue(tree, type), source, emptyList(), notices)
    } catch (exception: JacksonException) {
        failure(
            CatalogMigrationCodes.SCHEMA_UNKNOWN,
            path,
            "document has a valid schemaVersion but does not bind to the current contract: ${exception.originalMessage}",
            source,
        )
    }

    private fun <T> failure(
        code: String,
        path: String,
        message: String,
        sourceVersion: Int? = null,
    ): CatalogMigrationResult<T> = CatalogMigrationResult(
        value = null,
        sourceVersion = sourceVersion,
        findings = listOf(CatalogMigrationFinding(code, path, message)),
    )

    private fun migrate(
        sourceVersion: Int,
        apply: (CatalogSchemaMigration) -> CatalogMigrationStepResult,
    ): CatalogMigrationStepResult {
        val findings = mutableListOf<CatalogMigrationFinding>()
        val notices = mutableListOf<CatalogMigrationNotice>()
        var version = sourceVersion
        while (version < CatalogWireSchema.CURRENT_VERSION) {
            val migration = requireNotNull(migrations.singleOrNull { it.fromVersion == version }) {
                "missing catalog migration from schemaVersion $version"
            }
            val result = apply(migration)
            findings += result.findings
            notices += result.notices
            if (result.findings.isNotEmpty()) break
            version = migration.toVersion
        }
        return CatalogMigrationStepResult(findings, notices)
    }
}
