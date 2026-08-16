// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.protocol

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.util.Collections
import java.util.TreeSet

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

/**
 * Stable catalog identity, human-readable metadata, and authored discovery metadata.
 *
 * The legacy constructor, destructuring functions, and three-argument [copy] method
 * intentionally retain the JVM surface published in 1.0.1. Use [create] or
 * [copyWithMetadata] when setting catalog-v6 metadata.
 */
class CatalogInfo private constructor(
    val slug: String,
    val name: String,
    val description: String?,
    val defaultLanguage: String?,
    keywords: Set<String>,
    val presentation: CatalogPresentation?,
) {
    /** Deterministically ordered, immutable authored catalog keywords. */
    val keywords: Set<String> = immutableSortedSet(keywords)

    /** Source- and binary-compatible constructor retained from catalog 1.0.1. */
    constructor(slug: String, name: String, description: String? = null) :
        this(slug, name, description, null, emptySet(), null)

    operator fun component1(): String = slug

    operator fun component2(): String = name

    operator fun component3(): String? = description

    operator fun component4(): String? = defaultLanguage

    operator fun component5(): Set<String> = keywords

    operator fun component6(): CatalogPresentation? = presentation

    /** Legacy copy shape; v6 metadata is retained when identity fields change. */
    fun copy(
        slug: String = this.slug,
        name: String = this.name,
        description: String? = this.description,
    ): CatalogInfo = CatalogInfo(slug, name, description, defaultLanguage, keywords, presentation)

    /** Copies the catalog while replacing any catalog-v6 metadata. */
    fun copyWithMetadata(
        defaultLanguage: String? = this.defaultLanguage,
        keywords: Set<String> = this.keywords,
        presentation: CatalogPresentation? = this.presentation,
    ): CatalogInfo = CatalogInfo(slug, name, description, defaultLanguage, keywords, presentation)

    override fun equals(other: Any?): Boolean = this === other ||
        other is CatalogInfo &&
        slug == other.slug &&
        name == other.name &&
        description == other.description &&
        defaultLanguage == other.defaultLanguage &&
        keywords == other.keywords &&
        presentation == other.presentation

    override fun hashCode(): Int {
        var result = slug.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + (description?.hashCode() ?: 0)
        result = 31 * result + (defaultLanguage?.hashCode() ?: 0)
        result = 31 * result + keywords.hashCode()
        result = 31 * result + (presentation?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "CatalogInfo(slug=$slug, name=$name, description=$description, " +
        "defaultLanguage=$defaultLanguage, keywords=$keywords, presentation=$presentation)"

    companion object {
        /** Creates a catalog identity with optional catalog-v6 metadata. */
        @JvmStatic
        @JsonCreator
        fun create(
            @JsonProperty("slug") slug: String,
            @JsonProperty("name") name: String,
            @JsonProperty("description") description: String? = null,
            @JsonProperty("defaultLanguage") defaultLanguage: String? = null,
            @JsonProperty("keywords") keywords: Set<String>? = null,
            @JsonProperty("presentation") presentation: CatalogPresentation? = null,
        ): CatalogInfo = CatalogInfo(slug, name, description, defaultLanguage, keywords.orEmpty(), presentation)

        private fun immutableSortedSet(values: Set<String>): Set<String> = when {
            values.isEmpty() -> emptySet()
            else -> Collections.unmodifiableSet(TreeSet(values))
        }
    }
}

/** Optional authored icon and ordered gallery references for a catalog. */
data class CatalogPresentation(
    val iconAssetSlug: String? = null,
    val imageAssetSlugs: List<String> = emptyList(),
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
