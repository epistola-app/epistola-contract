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
import com.fasterxml.jackson.annotation.JsonPropertyOrder
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
 * @property schemaVersion portable catalog wire version. Content: the fingerprint (V6) covers it.
 * @property catalog stable catalog identity and display metadata ([CatalogInfo] says which members
 *   are the name, which content and which presentation).
 * @property publisher descriptive publisher metadata, not publisher authority. Presentation: not
 *   covered by the fingerprint.
 * @property release version and optional canonical content fingerprint: the archive's binding of
 *   the catalog's name and number to a content hash. Not covered by the fingerprint.
 * @property compatibility optional consumer compatibility declaration. Content: the fingerprint
 *   covers it.
 * @property includes optional linked catalog documents retained by the format. Presentation: not
 *   covered by the fingerprint.
 * @property resources complete resource-detail index.
 * @property dependencies the index of the resources in other catalogs that the content uses, each by
 *   alias and key ([DependencyRef]), derived from the content by the producer.
 * @property catalogDependencies the catalogs this catalog depends on, each declared once under the
 *   alias its content names it by, with its name and the release it pins (wire v8,
 *   [CatalogDependency]). Every alias that [dependencies] or the content names must be declared here.
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
 * A catalog this catalog depends on, declared once for the whole catalog (wire v8): an [alias] that
 * content names it by, its name, and the release it pins (contract ADR 0003).
 *
 * - **The alias** is what the catalog's content uses. A reference into this dependency names the
 *   alias and a resource key (its `catalogKey` member holds the alias), never a registry, a
 *   namespace or a version. The rules are [CatalogAliases]: the catalog key rule, unique within the
 *   list, never the catalog's own key.
 * - **The name** ([registry], [namespace], [catalogKey]) says which catalog the alias stands for,
 *   so that an installation can find newer releases of it. Renaming or mirroring the dependency
 *   changes the name here and nothing in the content.
 * - **The pin** is [releaseFingerprint]: the content hash of the release the catalog renders with
 *   and was cut with. [releaseVersion] is the number that release had when it was pinned, a hint for
 *   people and the path a carried copy is found at; when the number and the hash disagree, the hash
 *   is the release. A declaration names an exact release, not a range; ranges may come in a later
 *   wire version, as an addition.
 *
 * An earlier draft of wire v8 also carried a version selector, `version`. It is ignored when
 * binding, so that manifests stored with it still bind.
 *
 * @property registry the dependency's registry, written in full ([CatalogNamespaces.REGISTRY_PATTERN]).
 * @property namespace the dependency's namespace within [registry] ([CatalogNamespaces.PATTERN]).
 * @property catalogKey the dependency's key within [namespace].
 * @property releaseVersion the number of the release [releaseFingerprint] pins, as it was pinned, a
 *   release number ([CatalogReleaseVersions]). Null only for a catalog migrated from wire v7 or
 *   earlier, which never recorded one: the author pins one before the catalog is released again.
 * @property releaseFingerprint the pin: the fingerprint of the release this catalog renders with,
 *   copied from that release's own `release.fingerprint`, in the form that names its algorithm
 *   ([QualifiedCatalogFingerprint]; `v6:<hex>` for a release made under V6, which covers content
 *   only, so the same release has the same pin under any name). An installation checks a copy of
 *   that release, from any registry, mirror or file, with exactly the algorithm it names, after the
 *   current algorithm has moved on. Null exactly when [releaseVersion] is null. The wire requires
 *   the pairing; the migrator's wire check and the validator enforce it, not this model, so that
 *   manifests stored without it still bind.
 * @property alias how this catalog's content names the dependency ([CatalogAliases]). Defaults to
 *   [catalogKey], so that a manifest stored before aliases existed binds with the alias its content
 *   already used; the wire requires it from wire v8.
 */
@JsonIgnoreProperties("version")
@JsonPropertyOrder("alias", "registry", "namespace", "catalogKey", "releaseVersion", "releaseFingerprint")
data class CatalogDependency(
    val registry: String,
    val namespace: String,
    val catalogKey: String,
    val releaseVersion: String? = null,
    val releaseFingerprint: String? = null,
    val alias: String = catalogKey,
) {
    companion object {
        /** The shape of a [releaseFingerprint]: [QualifiedCatalogFingerprint.PATTERN], `v6:<hex>`. */
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
 * A resource in another catalog that this catalog's content uses: the manifest's index of the
 * references its content makes into other catalogs (`dependencies`), one entry per resource.
 *
 * Producers derive it from the content, so that a consumer can tell what a catalog needs from other
 * catalogs without reading every resource; the validator holds every entry to the catalog's
 * dependency list. Sealed hierarchy ensures type-safe construction:
 * - Every kind names the catalog holding the resource (assets too, from wire v7) by the **alias**
 *   the catalog's dependency list declares for it ([catalogKey], contract ADR 0003), never by registry or
 *   namespace: those are the declaration's ([CatalogDependency]).
 *
 * A reference carries no version: catalogs are versioned as a whole, so the release is pinned once
 * per catalog in [CatalogManifest.catalogDependencies], which must declare every alias named here.
 *
 * An earlier draft of wire v8 named the catalog's `registry` and `namespace` here. They are ignored
 * when binding, so that manifests stored with them still bind; the migrator's wire check refuses
 * them in a v8 archive.
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

    /**
     * The alias of the catalog holding the resource, as [CatalogManifest.catalogDependencies]
     * declares it ([CatalogAliases]). The member keeps its name: until wire v8 it held the catalog's
     * key, which is the alias a catalog is declared under unless the producer chose another.
     */
    abstract val catalogKey: String

    /** Theme in another catalog. */
    @JsonIgnoreProperties("namespace", "registry")
    data class Theme(
        override val catalogKey: String,
        @JsonAlias("slug")
        override val key: String,
    ) : DependencyRef()

    /** Stencil in another catalog. */
    @JsonIgnoreProperties("namespace", "registry")
    data class Stencil(
        override val catalogKey: String,
        @JsonAlias("slug")
        override val key: String,
    ) : DependencyRef()

    /**
     * Image in another catalog.
     *
     * Qualified like every other kind since wire v7. It was unqualified while an image was an
     * asset whose key was a generated UUID, unique by construction across every catalog a tenant
     * holds; a readable key is not, so two catalogs may each hold a `logo` and an unqualified
     * reference to one of them means nothing.
     */
    @JsonIgnoreProperties("namespace", "registry")
    data class Image(
        override val catalogKey: String,
        @JsonAlias("slug")
        override val key: String,
    ) : DependencyRef()

    /** Code list in another catalog. */
    @JsonIgnoreProperties("namespace", "registry")
    data class CodeList(
        override val catalogKey: String,
        @JsonAlias("slug")
        override val key: String,
    ) : DependencyRef()

    /** Font family in another catalog. */
    @JsonIgnoreProperties("namespace", "registry")
    data class Font(
        override val catalogKey: String,
        @JsonAlias("slug")
        override val key: String,
    ) : DependencyRef()
}

/**
 * Stable catalog identity, human-readable metadata, and authored discovery metadata.
 *
 * A catalog is identified by [registry], [namespace] and [key] together. The
 * three-argument constructor preserves source compatibility for existing producers. Use [create],
 * [copyWithMetadata] or [withNamespace] when setting discovery metadata, the default theme and
 * declared presets, or the registry and namespace.
 *
 * Its members are of three kinds (contract ADR 0003, amendment of 2026-10-22):
 * - **the name**: [registry] (the authority, a domain), [namespace] and [key];
 * - **content**, which the fingerprint (V6) covers and which a release fixes: [defaultTheme],
 *   [presets] and [license];
 * - **presentation**, information about the name rather than the release: [name], [description],
 *   [keywords], [presentation] (which images to show; the image resources themselves are content)
 *   and [attributes] (discovery labels). The fingerprint does not cover it, so it can be updated
 *   without a release; when an installation receives several releases of a catalog, the
 *   presentation most recently received wins. The manifest's [CatalogManifest.publisher] and
 *   [CatalogManifest.includes] are presentation too.
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
 * Like every reference into another catalog, it names the catalog by the alias the dependency list
 * declares ([CatalogAliases], contract ADR 0003): an omitted [catalogKey], or the catalog's own key, means the
 * owning catalog. A theme in another catalog lives in a catalog the owning catalog depends on. An
 * absent [catalogKey] is omitted from the JSON.
 *
 * An earlier draft of wire v8 also named the catalog's `namespace` and `registry` here. They are
 * ignored when binding, so that manifests stored with them still bind; the migrator's wire check
 * refuses them in a v8 archive.
 *
 * @property key key of the theme resource ([CatalogKeys.RESOURCE]).
 * @property catalogKey the alias of the catalog holding the theme; null means the owning catalog.
 */
@JsonIgnoreProperties("namespace", "registry")
data class CatalogThemeRef(
    val key: String,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val catalogKey: String? = null,
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
 * @param version the release's number. From wire v8 it is `MAJOR.MINOR.PATCH`, or, for a patch
 *   release by someone who does not hold the catalog's namespace, `MAJOR.MINOR.PATCH-<namespace>.<N>`
 *   with a namespace they hold ([CatalogReleaseVersions]); earlier wire versions accepted full
 *   SemVer. The migrator's wire check and the validator enforce it, not this model, so that
 *   manifests stored under earlier versions still bind.
 * @param releasedAt ISO-8601 timestamp the version was cut, if known.
 * @param fingerprint the canonical fingerprint of the catalog's content
 *   (deterministic, order-independent, excludes volatile fields): the
 *   release's identity. From wire v8 it names its algorithm, which must be the
 *   current one (`v6:<hex>`, [QualifiedCatalogFingerprint]). V6 covers the
 *   release's content only, never the catalog's name, this release block or
 *   the catalog's presentation, so two releases with the same fingerprint are
 *   the same release wherever they come from and whatever they are called
 *   (contract ADR 0003). Before wire v8 it is bare lowercase SHA-256 hex whose
 *   algorithm the wire version implies. Nullable for catalogs produced before
 *   fingerprinting.
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
 * @param base the release this one was made from, its draft's base (wire v8, [ReleaseBase]), by
 *   content hash, with its number for people, so that a receiver can follow the lineage to the
 *   closest release this one shares with a draft of its own and merge against it (contract ADR
 *   0005). Null for a first release, and when the producer does not know it. Release metadata: the
 *   fingerprint does not cover it. Omitted when null.
 *
 * The catalog's name ([CatalogInfo.registry], [CatalogInfo.namespace], [CatalogInfo.key]),
 * [version] and [fingerprint] together are the archive's **binding** (contract ADR 0003): "this
 * name at this number is the release with this content hash", issued by the holder of the
 * namespace. Someone who does not hold the namespace, patching another's catalog, numbers a release
 * with a label of a namespace they hold (`1.3.1-gemeente-x.1`, [CatalogReleaseVersions]). The format
 * cannot know who holds a namespace; the installation and the registry enforce it.
 */
data class ReleaseInfo(
    val version: String,
    val releasedAt: String? = null,
    val fingerprint: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val releaseId: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val metadata: Map<String, Map<String, Any?>>? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val base: ReleaseBase? = null,
)

/**
 * The release a release was made from: the base of the draft it was released from (wire v8,
 * `release.base`), by identity only: its content hash ([fingerprint]) and, for people, its number.
 *
 * Merging a release into a draft is a three-way comparison per resource against the merge base,
 * the closest release both the draft and the merged release descend from (contract ADR 0005). Each
 * release names the release it was made from, so a receiver can follow the lineage to that common
 * release. The merge then needs that release installed: it compares against the base's resources
 * in that release's own snapshot, and finds it by [fingerprint]: lineage is by hash (contract ADR
 * 0003), so it survives renames, mirrors and forks. A receiver keeps the base as it was received,
 * member for member, when it installs, migrates or re-exports the release. Merge parents (the
 * releases a draft merged) are the producer's to record, not the format's.
 *
 * The rules are enforced by the migrator's wire check and the validator, not by this model.
 *
 * @param version the base release's number, a release number ([CatalogReleaseVersions]).
 * @param fingerprint the base release's content hash: its catalog fingerprint, in the form that
 *   names its algorithm ([FINGERPRINT_PATTERN], any algorithm this library knows; `v6:<hex>` for a
 *   base made under V6, which covers content only), as a catalog dependency's
 *   [CatalogDependency.releaseFingerprint] pins a release: a receiver finds the base by it, whatever
 *   number or name the base has there.
 */
data class ReleaseBase(
    val version: String,
    val fingerprint: String,
) {
    companion object {
        /** The shape of a [fingerprint]: [QualifiedCatalogFingerprint.PATTERN], `v6:<hex>`. */
        const val FINGERPRINT_PATTERN: String = QualifiedCatalogFingerprint.PATTERN

        /**
         * Whether [fingerprint] is a valid base [ReleaseBase.fingerprint]: it has the shape of
         * [FINGERPRINT_PATTERN] and names an algorithm this library knows.
         */
        @JvmStatic
        fun isValidFingerprint(fingerprint: String): Boolean = QualifiedCatalogFingerprint.parse(fingerprint) != null
    }
}

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
