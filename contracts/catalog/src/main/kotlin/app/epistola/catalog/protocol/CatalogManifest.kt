// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.protocol

import app.epistola.catalog.canonical.QualifiedCatalogFingerprint
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
 * @property catalogDependencies the catalogs this catalog depends on, each declared once with a
 *   version selector (wire v8). Every catalog named by [dependencies] must be declared here.
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
    val catalogDependencies: List<CatalogDependency>? = null,
)

/**
 * A catalog this catalog depends on, declared once for the whole catalog (wire v8).
 *
 * Catalogs are versioned as a whole, so the version lives here and nowhere else: a reference from
 * this catalog's resources into the dependency names the catalog ([registry], [namespace] and
 * [catalogKey]) but never a version. The catalog renders with the release [version] resolves to.
 *
 * @property registry the dependency's registry, written in full ([CatalogNamespaces.REGISTRY_PATTERN]).
 * @property namespace the dependency's namespace within [registry] ([CatalogNamespaces.PATTERN]).
 * @property catalogKey the dependency's key within [namespace].
 * @property version the declared version selector, in the npm SemVer range grammar (`^1.4.0`,
 *   `>=1.0.0 <2.0.0`, `1.4.0`, `*`, ...), at most [MAX_VERSION_LENGTH] characters.
 * @property releaseVersion the exact release of the dependency that this archive's release was cut
 *   with. Null only for a catalog migrated from wire v7 or earlier, which never recorded one.
 * @property releaseFingerprint the fingerprint of the [releaseVersion] release, copied from that
 *   release's own `release.fingerprint`, in the form that names its algorithm (`v5:<hex>`,
 *   [QualifiedCatalogFingerprint]). It lets an installation check a copy of that release fetched
 *   from any registry or mirror, with exactly the algorithm it names, after the current algorithm
 *   has moved on. Null exactly when [releaseVersion] is null. The wire requires the pairing; the
 *   migrator's wire check and the validator enforce it, not this model, so that manifests stored
 *   without it still bind.
 */
data class CatalogDependency(
    val registry: String,
    val namespace: String,
    val catalogKey: String,
    val version: String,
    val releaseVersion: String? = null,
    val releaseFingerprint: String? = null,
) {
    companion object {
        /** Longest version selector a declaration may carry. */
        const val MAX_VERSION_LENGTH: Int = 100

        /** The shape of a [releaseFingerprint]: [QualifiedCatalogFingerprint.PATTERN], `v5:<hex>`. */
        const val RELEASE_FINGERPRINT_PATTERN: String = QualifiedCatalogFingerprint.PATTERN

        /**
         * Whether [fingerprint] is a valid [releaseFingerprint]: it has the shape of
         * [RELEASE_FINGERPRINT_PATTERN] and names an algorithm this library knows.
         */
        @JvmStatic
        fun isValidReleaseFingerprint(fingerprint: String): Boolean = QualifiedCatalogFingerprint.parse(fingerprint) != null
    }
}

/**
 * A reference to a resource that this catalog depends on.
 * Sealed hierarchy ensures type-safe construction:
 * - Every kind is catalog-scoped and requires catalogKey (assets too, from wire v7)
 * - Every kind names the catalog's registry and namespace from wire v8, the registry in full
 *
 * A reference carries no version: catalogs are versioned as a whole, so the version is declared
 * once per catalog in [CatalogManifest.catalogDependencies], which must declare every
 * (registry, namespace, catalogKey) named here.
 *
 * `registry` and `namespace` are nullable in the model only so that manifests stored under wire v7
 * or earlier, which name neither, still bind. The wire requires both from v8; the migrator's wire
 * check and the validator enforce that.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = DependencyRef.Theme::class, name = "theme"),
    JsonSubTypes.Type(value = DependencyRef.Stencil::class, name = "stencil"),
    JsonSubTypes.Type(value = DependencyRef.Image::class, name = "image"),
    JsonSubTypes.Type(value = DependencyRef.CodeList::class, name = "codeList"),
    JsonSubTypes.Type(value = DependencyRef.Font::class, name = "font"),
)
sealed class DependencyRef {
    /** Resource slug within the dependency catalog. */
    abstract val slug: String

    /** Key of the catalog holding the resource. */
    abstract val catalogKey: String

    /** Namespace of the catalog holding the resource, within [registry] (wire v8). */
    abstract val namespace: String?

    /** Registry of the catalog holding the resource, written in full (wire v8). */
    abstract val registry: String?

    /** Theme in another catalog. */
    data class Theme(
        override val catalogKey: String,
        override val slug: String,
        override val namespace: String? = null,
        override val registry: String? = null,
    ) : DependencyRef()

    /** Stencil in another catalog. */
    data class Stencil(
        override val catalogKey: String,
        override val slug: String,
        override val namespace: String? = null,
        override val registry: String? = null,
    ) : DependencyRef()

    /**
     * Image in another catalog.
     *
     * Qualified like every other kind since wire v7. It was unqualified while an image was an
     * asset whose slug was a generated UUID, unique by construction across every catalog a tenant
     * holds; a readable slug is not, so two catalogs may each hold a `logo` and an unqualified
     * reference to one of them means nothing.
     */
    data class Image(
        override val catalogKey: String,
        override val slug: String,
        override val namespace: String? = null,
        override val registry: String? = null,
    ) : DependencyRef()

    /** Code list in another catalog. */
    data class CodeList(
        override val catalogKey: String,
        override val slug: String,
        override val namespace: String? = null,
        override val registry: String? = null,
    ) : DependencyRef()

    /** Font family in another catalog. */
    data class Font(
        override val catalogKey: String,
        override val slug: String,
        override val namespace: String? = null,
        override val registry: String? = null,
    ) : DependencyRef()
}

/**
 * Stable catalog identity, human-readable metadata, and authored discovery metadata.
 *
 * A catalog is identified by [registry], [namespace] and [slug] (its key) together. The
 * three-argument constructor preserves source compatibility for existing producers. Use [create],
 * [copyWithMetadata] or [withNamespace] when setting discovery metadata or the registry and
 * namespace.
 */
class CatalogInfo private constructor(
    val slug: String,
    val name: String,
    val description: String?,
    attributes: List<AttributeAssignment>,
    keywords: Set<String>,
    val presentation: CatalogPresentation?,
    val license: CatalogLicense?,
    /**
     * The catalog's namespace within [registry] ([CatalogNamespaces.PATTERN]); wire v8.
     *
     * Nullable in the model only so that manifests stored under wire v7 or earlier, which name
     * none, still bind. The wire requires it from v8; the migrator's wire check and the validator
     * enforce that.
     */
    val namespace: String?,
    /**
     * The catalog's registry, written in full ([CatalogNamespaces.REGISTRY_PATTERN]); wire v8. An
     * archive has no default registry.
     *
     * Nullable in the model for the same reason as [namespace].
     */
    val registry: String?,
) {
    /** Immutable attributes applied to this catalog, preserving authored order. */
    val attributes: List<AttributeAssignment> = Collections.unmodifiableList(attributes.toList())

    /**
     * Deterministically ordered, immutable authored catalog keywords.
     *
     * Wire v7 restricts keywords to [CatalogKeywords] rules. The model does not enforce them, so
     * manifests stored under an earlier wire version still bind; the migrator and validator do.
     */
    val keywords: Set<String> = immutableSortedSet(keywords)

    /** Source-compatible constructor retained from catalog 1.0.1. */
    constructor(slug: String, name: String, description: String? = null) :
        this(slug, name, description, emptyList(), emptySet(), null, null, null, null)

    /** Copies the catalog while replacing any discovery metadata. */
    fun copyWithMetadata(
        attributes: List<AttributeAssignment> = this.attributes,
        keywords: Set<String> = this.keywords,
        presentation: CatalogPresentation? = this.presentation,
        license: CatalogLicense? = this.license,
    ): CatalogInfo = CatalogInfo(slug, name, description, attributes, keywords, presentation, license, namespace, registry)

    /** Copies the catalog with [registry] and [namespace] in place of its current ones. */
    fun withNamespace(
        registry: String?,
        namespace: String?,
    ): CatalogInfo = CatalogInfo(slug, name, description, attributes, keywords, presentation, license, namespace, registry)

    override fun equals(other: Any?): Boolean = this === other ||
        other is CatalogInfo &&
        registry == other.registry &&
        namespace == other.namespace &&
        slug == other.slug &&
        name == other.name &&
        description == other.description &&
        attributes == other.attributes &&
        keywords == other.keywords &&
        presentation == other.presentation &&
        license == other.license

    override fun hashCode(): Int {
        var result = slug.hashCode()
        result = 31 * result + (registry?.hashCode() ?: 0)
        result = 31 * result + (namespace?.hashCode() ?: 0)
        result = 31 * result + name.hashCode()
        result = 31 * result + (description?.hashCode() ?: 0)
        result = 31 * result + attributes.hashCode()
        result = 31 * result + keywords.hashCode()
        result = 31 * result + (presentation?.hashCode() ?: 0)
        result = 31 * result + (license?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "CatalogInfo(registry=$registry, namespace=$namespace, slug=$slug, name=$name, description=$description, " +
        "attributes=$attributes, keywords=$keywords, presentation=$presentation, license=$license)"

    companion object {
        /** Creates a catalog identity with optional discovery metadata. */
        @JvmStatic
        @JsonCreator
        fun create(
            @JsonProperty("slug") slug: String,
            @JsonProperty("name") name: String,
            @JsonProperty("description") description: String? = null,
            @JsonProperty("attributes") attributes: List<AttributeAssignment>? = null,
            @JsonProperty("keywords") keywords: Set<String>? = null,
            @JsonProperty("presentation") presentation: CatalogPresentation? = null,
            @JsonProperty("license") license: CatalogLicense? = null,
            @JsonProperty("namespace") namespace: String? = null,
            @JsonProperty("registry") registry: String? = null,
        ): CatalogInfo = CatalogInfo(
            slug,
            name,
            description,
            attributes.orEmpty(),
            keywords.orEmpty(),
            presentation,
            license,
            namespace,
            registry,
        )

        private fun immutableSortedSet(values: Set<String>): Set<String> = when {
            values.isEmpty() -> emptySet()
            else -> Collections.unmodifiableSet(TreeSet(values))
        }
    }
}

/** A qualified attribute value applied to a catalog. */
data class AttributeAssignment(
    val catalog: String,
    val key: String,
    val value: String,
)

/** Optional authored icon and ordered gallery references for a catalog. */
data class CatalogPresentation(
    val iconAssetSlug: String? = null,
    val imageAssetSlugs: List<String> = emptyList(),
)

/** Catalog-wide license terms declared by the publisher. */
data class CatalogLicense(
    val name: String,
    val spdxExpression: String? = null,
    val url: String? = null,
    val copyrightText: String? = null,
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
 * @param fingerprint the canonical fingerprint of the catalog's content
 *   (deterministic, order-independent, excludes volatile fields). Lets
 *   consumers detect that content actually changed independently of the
 *   `version` label. From wire v8 it names its algorithm, which must be the
 *   current one (`v5:<hex>`, [QualifiedCatalogFingerprint]); before wire v8 it
 *   is bare lowercase SHA-256 hex whose algorithm the wire version implies.
 *   Nullable for catalogs produced before fingerprinting.
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
 *
 * Wire v7 dropped a per-resource `compatibility`. It was never written and never read, and a
 * version range per template -- separate from the catalog's own -- answered no question anyone
 * asked. [CatalogManifest.compatibility] still declares one for the catalog as a whole.
 */
data class ResourceEntry(
    val type: String,
    val slug: String,
    val name: String,
    val description: String? = null,
    val updatedAt: String? = null,
    val detailUrl: String,
)
