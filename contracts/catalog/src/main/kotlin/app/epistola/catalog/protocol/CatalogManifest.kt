// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.protocol

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * Portable `catalog.json` manifest and resource index.
 *
 * The manifest describes catalog identity and release metadata, then binds
 * each declared resource to a separate [ResourceDetail] document. It contains
 * portable contract state only; Exchange publication state and Suite install
 * state are not represented.
 *
 * @property schemaVersion portable catalog wire version.
 * @property catalog stable catalog identity and display metadata.
 * @property publisher descriptive publisher metadata, not publisher authority.
 * @property release version and optional canonical content fingerprint.
 * @property compatibility optional consumer compatibility declaration.
 * @property includes optional linked catalog documents retained by the format.
 * @property resources complete resource-detail index.
 * @property dependencies explicit references to resources outside this catalog.
 */
data class CatalogManifest(
    val schemaVersion: Int,
    val catalog: CatalogInfo,
    val publisher: PublisherInfo,
    val release: ReleaseInfo,
    val compatibility: CompatibilityInfo? = null,
    val includes: List<IncludeEntry>? = null,
    val resources: List<ResourceEntry>,
    val dependencies: List<DependencyRef>? = null,
)

/**
 * A reference to a resource that this catalog depends on.
 * Sealed hierarchy ensures type-safe construction:
 * - Themes, stencils, code lists, and fonts are catalog-scoped (require catalogKey)
 * - Assets are tenant-global (just the UUID)
 *
 * Reserved for a future release (catalog versioning Phase 3): the catalog-scoped
 * subtypes (Theme, Stencil, CodeList, Font) will gain an optional
 * `versionRange: String? = null` carrying a SemVer range (e.g. ">=1.2.0 <2.0.0")
 * validated against the dependency catalog's installed/released version at
 * import/upgrade time. Not added yet — declaring it before there is a producer
 * and consumer risks it being mis-set. The free-form manifest snapshot stored
 * by consumers round-trips an unknown field, so adding it later needs no
 * migration.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = DependencyRef.Theme::class, name = "theme"),
    JsonSubTypes.Type(value = DependencyRef.Stencil::class, name = "stencil"),
    JsonSubTypes.Type(value = DependencyRef.Asset::class, name = "asset"),
    JsonSubTypes.Type(value = DependencyRef.CodeList::class, name = "codeList"),
    JsonSubTypes.Type(value = DependencyRef.Font::class, name = "font"),
)
sealed class DependencyRef {
    /** Resource slug within the dependency's namespace. */
    abstract val slug: String

    /** Theme in another catalog. */
    data class Theme(val catalogKey: String, override val slug: String) : DependencyRef()

    /** Stencil in another catalog. */
    data class Stencil(val catalogKey: String, override val slug: String) : DependencyRef()

    /** Asset in the consumer's asset namespace. */
    data class Asset(override val slug: String) : DependencyRef()

    /** Code list in another catalog. */
    data class CodeList(val catalogKey: String, override val slug: String) : DependencyRef()

    /** Font family in another catalog. */
    data class Font(val catalogKey: String, override val slug: String) : DependencyRef()
}

/** Stable catalog identity and human-readable metadata. */
data class CatalogInfo(
    val slug: String,
    val name: String,
    val description: String? = null,
)

/**
 * Descriptive publisher metadata carried by an exported catalog.
 *
 * This does not grant Exchange publishing authority or encode organization
 * ownership.
 */
data class PublisherInfo(
    val name: String,
    val url: String? = null,
)

/**
 * Release metadata for a catalog.
 *
 * @param version author-controlled SemVer (`MAJOR.MINOR.PATCH`) for AUTHORED
 *   catalogs, or the installed release label for bundled/subscribed ones.
 * @param releasedAt ISO-8601 timestamp the version was cut, if known.
 * @param fingerprint lowercase hex SHA-256 of the catalog's canonical content
 *   (deterministic, order-independent, excludes volatile fields). Lets
 *   consumers detect that content actually changed independently of the
 *   `version` label. Nullable for catalogs produced before fingerprinting.
 */
data class ReleaseInfo(
    val version: String,
    val releasedAt: String? = null,
    val fingerprint: String? = null,
)

/**
 * Optional declaration of Epistola versions understood by the producer.
 *
 * Interpretation and enforcement are consumer policy; the portable validator
 * only preserves the value.
 */
data class CompatibilityInfo(
    val epistolaVersions: String? = null,
)

/** Additional catalog document linked from the manifest. */
data class IncludeEntry(
    val url: String,
    val description: String? = null,
)

/**
 * Manifest entry binding a resource identity to its detail document.
 *
 * [detailUrl] is archive-relative and whole-catalog validation requires it to
 * resolve to `resources/{type}/{slug}.json`.
 */
data class ResourceEntry(
    val type: String,
    val slug: String,
    val name: String,
    val description: String? = null,
    val updatedAt: String? = null,
    val detailUrl: String,
    val compatibility: CompatibilityInfo? = null,
)
