package app.epistola.catalog.protocol

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * Wire format for the catalog manifest JSON served at a catalog URL.
 * Matches the schema defined in docs/exchange.md.
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
    abstract val slug: String

    data class Theme(val catalogKey: String, override val slug: String) : DependencyRef()
    data class Stencil(val catalogKey: String, override val slug: String) : DependencyRef()
    data class Asset(override val slug: String) : DependencyRef()
    data class CodeList(val catalogKey: String, override val slug: String) : DependencyRef()
    data class Font(val catalogKey: String, override val slug: String) : DependencyRef()
}

data class CatalogInfo(
    val slug: String,
    val name: String,
    val description: String? = null,
)

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

data class CompatibilityInfo(
    val epistolaVersions: String? = null,
)

data class IncludeEntry(
    val url: String,
    val description: String? = null,
)

data class ResourceEntry(
    val type: String,
    val slug: String,
    val name: String,
    val description: String? = null,
    val updatedAt: String? = null,
    val detailUrl: String,
    val compatibility: CompatibilityInfo? = null,
)
