// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.protocol

import app.epistola.catalog.canonical.QualifiedCatalogFingerprint
import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
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
 * @property catalogDependencies the catalogs this catalog depends on, each declared once with the
 *   exact release it pins (wire v8). Every catalog named by [dependencies] must be declared here.
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
 * [catalogKey]) but never a version. The catalog renders with exactly the release
 * [releaseVersion] pins. A declaration names an exact release, not a range; ranges may come in a
 * later wire version, as an addition.
 *
 * An earlier draft of wire v8 also carried a version selector, `version`. It is ignored when
 * binding, so that manifests stored with it still bind.
 *
 * @property registry the dependency's registry, written in full ([CatalogNamespaces.REGISTRY_PATTERN]).
 * @property namespace the dependency's namespace within [registry] ([CatalogNamespaces.PATTERN]).
 * @property catalogKey the dependency's key within [namespace].
 * @property releaseVersion the exact release of the dependency that this catalog renders with, and
 *   that this archive's release was cut with, a release number ([CatalogReleaseVersions]). Null only for a catalog migrated from wire v7 or
 *   earlier, which never recorded one: the author pins one before the catalog is released again.
 * @property releaseFingerprint the fingerprint of the [releaseVersion] release, copied from that
 *   release's own `release.fingerprint`, in the form that names its algorithm (`v5:<hex>`,
 *   [QualifiedCatalogFingerprint]). It lets an installation check a copy of that release fetched
 *   from any registry or mirror, with exactly the algorithm it names, after the current algorithm
 *   has moved on. Null exactly when [releaseVersion] is null. The wire requires the pairing; the
 *   migrator's wire check and the validator enforce it, not this model, so that manifests stored
 *   without it still bind.
 */
@JsonIgnoreProperties("version")
data class CatalogDependency(
    val registry: String,
    val namespace: String,
    val catalogKey: String,
    val releaseVersion: String? = null,
    val releaseFingerprint: String? = null,
) {
    companion object {
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
    /**
     * Resource key within the dependency catalog: its public address there. Read as `slug` too,
     * the name wire v7 and earlier gave it, so that manifests stored under those versions still bind.
     */
    abstract val key: String

    /** Key of the catalog holding the resource. */
    abstract val catalogKey: String

    /** Namespace of the catalog holding the resource, within [registry] (wire v8). */
    abstract val namespace: String?

    /** Registry of the catalog holding the resource, written in full (wire v8). */
    abstract val registry: String?

    /** Theme in another catalog. */
    data class Theme(
        override val catalogKey: String,
        @JsonAlias("slug")
        override val key: String,
        override val namespace: String? = null,
        override val registry: String? = null,
    ) : DependencyRef()

    /** Stencil in another catalog. */
    data class Stencil(
        override val catalogKey: String,
        @JsonAlias("slug")
        override val key: String,
        override val namespace: String? = null,
        override val registry: String? = null,
    ) : DependencyRef()

    /**
     * Image in another catalog.
     *
     * Qualified like every other kind since wire v7. It was unqualified while an image was an
     * asset whose key was a generated UUID, unique by construction across every catalog a tenant
     * holds; a readable key is not, so two catalogs may each hold a `logo` and an unqualified
     * reference to one of them means nothing.
     */
    data class Image(
        override val catalogKey: String,
        @JsonAlias("slug")
        override val key: String,
        override val namespace: String? = null,
        override val registry: String? = null,
    ) : DependencyRef()

    /** Code list in another catalog. */
    data class CodeList(
        override val catalogKey: String,
        @JsonAlias("slug")
        override val key: String,
        override val namespace: String? = null,
        override val registry: String? = null,
    ) : DependencyRef()

    /** Font family in another catalog. */
    data class Font(
        override val catalogKey: String,
        @JsonAlias("slug")
        override val key: String,
        override val namespace: String? = null,
        override val registry: String? = null,
    ) : DependencyRef()
}

/**
 * Stable catalog identity, human-readable metadata, and authored discovery metadata.
 *
 * A catalog is identified by [registry], [namespace] and [key] together. The
 * three-argument constructor preserves source compatibility for existing producers. Use [create],
 * [copyWithMetadata] or [withNamespace] when setting discovery metadata, the default theme and
 * declared presets, or the registry and namespace.
 */
class CatalogInfo private constructor(
    /**
     * The catalog's key: its public address within [namespace]. Until wire v8 the member was called
     * `slug`; binding reads either name, so that manifests stored under earlier versions still bind.
     */
    val key: String,
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
    /**
     * The theme a template that names none renders with, after the variant's own theme and the
     * template's (wire v8, ADR 0007); null means none, and such a template renders with the
     * rendering defaults alone. Omitted from the JSON when null.
     */
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val defaultTheme: CatalogThemeRef?,
    presets: List<CatalogPresetDeclaration>,
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

    /**
     * The block style presets the catalog declares (wire v8, ADR 0007), immutable, in authored order;
     * empty when it declares none, and then omitted from the JSON. Keys must be unique
     * ([CatalogPresets]); the migrator and validator enforce that, not this model.
     */
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val presets: List<CatalogPresetDeclaration> = Collections.unmodifiableList(presets.toList())

    /** Source-compatible constructor retained from catalog 1.0.1. */
    constructor(key: String, name: String, description: String? = null) :
        this(key, name, description, emptyList(), emptySet(), null, null, null, null, null, emptyList())

    /** Copies the catalog while replacing any discovery metadata, its default theme or its declared presets. */
    fun copyWithMetadata(
        attributes: List<AttributeAssignment> = this.attributes,
        keywords: Set<String> = this.keywords,
        presentation: CatalogPresentation? = this.presentation,
        license: CatalogLicense? = this.license,
        defaultTheme: CatalogThemeRef? = this.defaultTheme,
        presets: List<CatalogPresetDeclaration> = this.presets,
    ): CatalogInfo = CatalogInfo(key, name, description, attributes, keywords, presentation, license, namespace, registry, defaultTheme, presets)

    /** Copies the catalog with [registry] and [namespace] in place of its current ones. */
    fun withNamespace(
        registry: String?,
        namespace: String?,
    ): CatalogInfo = CatalogInfo(key, name, description, attributes, keywords, presentation, license, namespace, registry, defaultTheme, presets)

    override fun equals(other: Any?): Boolean = this === other ||
        other is CatalogInfo &&
        registry == other.registry &&
        namespace == other.namespace &&
        key == other.key &&
        name == other.name &&
        description == other.description &&
        attributes == other.attributes &&
        keywords == other.keywords &&
        presentation == other.presentation &&
        license == other.license &&
        defaultTheme == other.defaultTheme &&
        presets == other.presets

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + (registry?.hashCode() ?: 0)
        result = 31 * result + (namespace?.hashCode() ?: 0)
        result = 31 * result + name.hashCode()
        result = 31 * result + (description?.hashCode() ?: 0)
        result = 31 * result + attributes.hashCode()
        result = 31 * result + keywords.hashCode()
        result = 31 * result + (presentation?.hashCode() ?: 0)
        result = 31 * result + (license?.hashCode() ?: 0)
        result = 31 * result + (defaultTheme?.hashCode() ?: 0)
        result = 31 * result + presets.hashCode()
        return result
    }

    override fun toString(): String = "CatalogInfo(registry=$registry, namespace=$namespace, key=$key, name=$name, description=$description, " +
        "attributes=$attributes, keywords=$keywords, presentation=$presentation, license=$license, defaultTheme=$defaultTheme, presets=$presets)"

    companion object {
        /** Creates a catalog identity with optional discovery metadata. */
        @JvmStatic
        @JsonCreator
        fun create(
            @JsonProperty("key") @JsonAlias("slug") key: String,
            @JsonProperty("name") name: String,
            @JsonProperty("description") description: String? = null,
            @JsonProperty("attributes") attributes: List<AttributeAssignment>? = null,
            @JsonProperty("keywords") keywords: Set<String>? = null,
            @JsonProperty("presentation") presentation: CatalogPresentation? = null,
            @JsonProperty("license") license: CatalogLicense? = null,
            @JsonProperty("namespace") namespace: String? = null,
            @JsonProperty("registry") registry: String? = null,
            @JsonProperty("defaultTheme") defaultTheme: CatalogThemeRef? = null,
            @JsonProperty("presets") presets: List<CatalogPresetDeclaration>? = null,
        ): CatalogInfo = CatalogInfo(
            key,
            name,
            description,
            attributes.orEmpty(),
            keywords.orEmpty(),
            presentation,
            license,
            namespace,
            registry,
            defaultTheme,
            presets.orEmpty(),
        )

        private fun immutableSortedSet(values: Set<String>): Set<String> = when {
            values.isEmpty() -> emptySet()
            else -> Collections.unmodifiableSet(TreeSet(values))
        }
    }
}

/**
 * A reference to a theme resource from the catalog manifest: a catalog's [CatalogInfo.defaultTheme]
 * (wire v8, ADR 0007).
 *
 * Qualified like every other cross-catalog reference: an omitted [catalogKey], [namespace] or
 * [registry] means the owning catalog's own. A theme in another catalog lives in a catalog the
 * owning catalog depends on. Absent qualifiers are omitted from the JSON.
 *
 * @property key key of the theme resource ([CatalogKeys.RESOURCE]).
 * @property catalogKey key of the catalog holding the theme; null means the owning catalog.
 * @property namespace namespace of that catalog ([CatalogNamespaces.PATTERN]); null means the owning
 *   catalog's.
 * @property registry registry of that catalog, written in full ([CatalogNamespaces.REGISTRY_PATTERN]);
 *   null means the owning catalog's, never an installation default. Naming one requires [namespace].
 */
data class CatalogThemeRef(
    val key: String,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val catalogKey: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val namespace: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val registry: String? = null,
)

/**
 * A block style preset a catalog declares (wire v8, ADR 0007): what a block's `stylePreset` names.
 * A theme's `blockStylePresets` supplies the styles under the same [key].
 *
 * The rules ([CatalogPresets]) are enforced by the migrator's wire check and the validator, not by
 * this model.
 *
 * @property key the preset's key, unique within the catalog ([CatalogPresets.KEY_PATTERN]).
 * @property name display name shown to authors.
 * @property description optional description shown to authors; omitted from the JSON when null.
 */
data class CatalogPresetDeclaration(
    val key: String,
    val name: String,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val description: String? = null,
)

/**
 * A qualified attribute value applied to a catalog.
 *
 * @property catalogKey key of the catalog defining the attribute. Until wire v8 the member was
 *   called `catalog`; binding reads either name.
 * @property key key of the attribute within that catalog.
 */
data class AttributeAssignment(
    @JsonAlias("catalog")
    val catalogKey: String,
    val key: String,
    val value: String,
)

/**
 * Optional authored icon and ordered gallery references for a catalog.
 *
 * @property iconImageKey key of the image resource that serves as the catalog's icon. Until wire v8
 *   the member was called `iconAssetSlug`; binding reads either name.
 * @property imageKeys keys of the image resources shown in the catalog's gallery, in order. Until
 *   wire v8 the member was called `imageAssetSlugs`; binding reads either name.
 */
data class CatalogPresentation(
    @JsonAlias("iconAssetSlug")
    val iconImageKey: String? = null,
    @JsonAlias("imageAssetSlugs")
    val imageKeys: List<String> = emptyList(),
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
 * @param version the release's number. From wire v8 it is `MAJOR.MINOR.PATCH`, without a
 *   pre-release or build part ([CatalogReleaseVersions]); earlier wire versions accepted full
 *   SemVer. The migrator's wire check and the validator enforce it, not this model, so that
 *   manifests stored under earlier versions still bind.
 * @param releasedAt ISO-8601 timestamp the version was cut, if known.
 * @param fingerprint the canonical fingerprint of the catalog's content
 *   (deterministic, order-independent, excludes volatile fields). Lets
 *   consumers detect that content actually changed independently of the
 *   `version` label. From wire v8 it names its algorithm, which must be the
 *   current one (`v5:<hex>`, [QualifiedCatalogFingerprint]); before wire v8 it
 *   is bare lowercase SHA-256 hex whose algorithm the wire version implies.
 *   Nullable for catalogs produced before fingerprinting.
 * @param releaseId the release's id (wire v8, [CatalogReleaseIds]): unique among the releases of
 *   the catalog's name (registry, namespace and key), and kept when the release moves to another
 *   installation, through an exchange, a registry or a file, so that the release is the same
 *   everywhere. Null when the producer recorded none; the receiver then assigns one. Release
 *   metadata like [version]: the fingerprint does not cover it. Omitted when null.
 * @param metadata producer-specific facts about the release (wire v8) that the contract carries
 *   but does not define, keyed by a reverse-DNS namespace the producer owns, each value a JSON
 *   object, under the same rules as a variant's metadata ([CatalogVariantMetadata]): Epistola Suite
 *   records the rendering-defaults version a release was cut with as
 *   `{"app.epistola.suite": {"renderingDefaultsVersion": 6}}`. Only the owning producer writes a
 *   namespace; every other reader keeps it, an unrecognised one included, member for member. Not
 *   covered by the fingerprint. Omitted when null or empty, since the wire forbids `{}`.
 */
data class ReleaseInfo(
    val version: String,
    val releasedAt: String? = null,
    val fingerprint: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val releaseId: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val metadata: Map<String, Map<String, Any?>>? = null,
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
 * resolve to `resources/{type}/{key}.json`. From wire v8 it must be a relative
 * path below the manifest's directory ([CatalogArchivePaths]).
 *
 * [description] is the resource's own description, copied from its detail where
 * the resource has one, so that a listing can show it without opening the detail.
 * For a template (wire v8) the detail's [TemplateResource.description] is the
 * source: when both are present they must be equal.
 *
 * [key] is the resource's public address within the catalog and its type. Until wire v8 the member
 * was called `slug`; binding reads either name, so that manifests stored under earlier versions
 * still bind.
 *
 * Wire v7 dropped a per-resource `compatibility`. It was never written and never read, and a
 * version range per template -- separate from the catalog's own -- answered no question anyone
 * asked. [CatalogManifest.compatibility] still declares one for the catalog as a whole.
 */
data class ResourceEntry(
    val type: String,
    @JsonAlias("slug")
    val key: String,
    val name: String,
    val description: String? = null,
    val updatedAt: String? = null,
    val detailUrl: String,
)
