// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.protocol

import app.epistola.template.model.BlockStylePreset
import app.epistola.template.model.PageSettings
import app.epistola.template.model.TemplateDocument
import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * Portable JSON envelope for one resource declared by [CatalogManifest].
 *
 * @property schemaVersion wire version, which must match the owning manifest.
 * @property resource discriminated resource payload.
 */
data class ResourceDetail(
    val schemaVersion: Int,
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
    @JsonSubTypes(
        JsonSubTypes.Type(value = TemplateResource::class, name = "template"),
        JsonSubTypes.Type(value = ThemeResource::class, name = "theme"),
        JsonSubTypes.Type(value = StencilResource::class, name = "stencil"),
        JsonSubTypes.Type(value = AttributeResource::class, name = "attribute"),
        JsonSubTypes.Type(value = ImageResource::class, name = "image"),
        JsonSubTypes.Type(value = CodeListResource::class, name = "codeList"),
        JsonSubTypes.Type(value = FontResource::class, name = "font"),
    )
    val resource: CatalogResource,
)

/**
 * Base type for all portable catalog resources.
 *
 * Jackson discriminates concrete payloads using the existing `type` property.
 * Every resource has a catalog-local [key] and human-readable [name].
 *
 * [key] is the resource's public address within its catalog. Until wire v8 the member was called
 * `slug`; every resource binds either name, so that details stored under earlier versions still
 * bind.
 */
sealed interface CatalogResource {
    val type: String
    val key: String
    val name: String

    /**
     * Labels for finding the resource (wire v8). Every type carries them; a type that did not before
     * leaves them out of its JSON when there are none, so its fingerprints stay put. A stencil,
     * which always had them, writes them even when empty, as it always did.
     */
    val tags: List<String>
}

/**
 * Exported template, including its data contract and optional variants.
 *
 * [templateModel] is always validated. Every non-null
 * [VariantEntry.templateModel] is independently validated using the same
 * catalog-scoped resource context.
 *
 * [pdfaEnabled] records whether the template renders PDF/A-compliant output.
 * It is optional and additive: absent on the wire means `true`, which
 * preserves the behaviour of catalogs from exporters that predate this
 * field. A consumer that does not understand it ignores it. Adding it
 * therefore needs no `schemaVersion` bump (see "Future wire evolution" in
 * `contracts/catalog/docs/catalog-compatibility.md`).
 *
 * [themeKey] names the template's default theme; until wire v8 the member was called `themeId`, and
 * binding reads either name. [themeCatalogKey] names the alias of the catalog holding it when that is
 * another catalog the template's catalog depends on ([CatalogAliases], contract ADR 0003); null, or the
 * catalog's own key, means the template's own catalog. An earlier draft of wire v8 also named the
 * theme's catalog by `themeNamespace` and `themeRegistry`; they are ignored when binding, so that
 * details stored with them still bind, and the migrator's wire check refuses them in a v8 archive.
 *
 * [description] (wire v8) is plain text for the template's authors and readers -- what it is for,
 * design decisions, known gaps -- at most [MAX_DESCRIPTION_LENGTH] characters, and is not rendered.
 * It is the source of the manifest resource entry's `description`: when both are present they must
 * be equal. Omitted when null, so a template without one canonicalizes as before.
 */
@JsonIgnoreProperties("themeNamespace", "themeRegistry")
data class TemplateResource(
    @JsonAlias("slug")
    override val key: String,
    override val name: String,
    @JsonAlias("themeId")
    val themeKey: String? = null,
    val themeCatalogKey: String? = null,
    val pdfaEnabled: Boolean = true,
    val dataModel: Map<String, Any?>? = null,
    val dataExamples: List<DataExampleEntry>? = null,
    val templateModel: TemplateDocument,
    val variants: List<VariantEntry>,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val description: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    override val tags: List<String> = emptyList(),
) : CatalogResource {
    override val type: String get() = "template"

    companion object {
        /** Longest template description, in characters (Unicode code points). */
        const val MAX_DESCRIPTION_LENGTH: Int = 2000
    }
}

/**
 * Theme styles and page defaults portable between catalog consumers.
 *
 * Style keys and preset usage are checked against the registries shipped in
 * this artifact.
 */
data class ThemeResource(
    @JsonAlias("slug")
    override val key: String,
    override val name: String,
    val description: String? = null,
    val documentStyles: Map<String, Any?>? = null,
    val pageSettings: PageSettings? = null,
    val blockStylePresets: Map<String, BlockStylePreset>? = null,
    val spacingUnit: Float? = null,
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    override val tags: List<String> = emptyList(),
) : CatalogResource {
    override val type: String get() = "theme"
}

/**
 * Wire format for a stencil resource.
 *
 * A stencil carries no version of its own from wire v8: a catalog is versioned as a whole, so the
 * stencil a template embeds is the one in the release its catalog dependency pins (or the
 * stencil in the same catalog). Until wire v7 a `version` here recorded the published stencil
 * version, and stencil nodes pinned it; the v7-to-v8 migration removes both. The property is
 * ignored when binding, so a manifest a consumer stored under v7 still loads.
 *
 * `parameterSchema` carries the stencil's typed input parameters as a JSON Schema object
 * (`{type:"object", properties, required}`). It is optional and additive: a ZIP from a stencil
 * without declared parameters (or from an exporter predating this field) simply omits it, and a
 * consumer that does not understand it ignores it. When present it round-trips so that templates
 * binding to those parameters stay bound after an import.
 *
 * [content] may embed nodes that reference other stencils by `stencilKey` (`stencilId` until wire
 * v8), optionally with the `catalogKey` alias of another catalog ([CatalogAliases]). These references form a portable composition graph: validation
 * requires them to resolve, rejects direct and transitive recursion, and limits the complete chain
 * to five stencil levels.
 */
@JsonIgnoreProperties("version")
data class StencilResource(
    @JsonAlias("slug")
    override val key: String,
    override val name: String,
    val description: String? = null,
    override val tags: List<String> = emptyList(),
    val content: TemplateDocument,
    val parameterSchema: Map<String, Any?>? = null,
    /**
     * The stencil's content also sees the using template's data and loop variables, not only its
     * parameters (ADR 0006, part 5). Wire v8; left out of the JSON when false, so V5 fingerprints of
     * stencils that do not use it stay put.
     */
    @get:JsonInclude(JsonInclude.Include.NON_DEFAULT)
    val readsTemplateData: Boolean = false,
) : CatalogResource {
    override val type: String get() = "stencil"
}

/**
 * Variant attribute definition.
 *
 * An attribute either declares [allowedValues] directly or binds a
 * [CodeListResource] through [codeListBinding]; using both is invalid.
 */
data class AttributeResource(
    @JsonAlias("slug")
    override val key: String,
    override val name: String,
    val allowedValues: List<String> = emptyList(),
    val codeListBinding: CodeListBindingRef? = null,
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    override val tags: List<String> = emptyList(),
) : CatalogResource {
    override val type: String get() = "attribute"
}

/**
 * Points a variant-attribute definition at a code list that constrains its
 * allowed values. `catalogKey = null` means "the same catalog as the
 * attribute" (the typical case for catalogs that author both an attribute
 * and the list it binds to); otherwise it is the alias of another catalog the
 * attribute's catalog depends on ([CatalogAliases], contract ADR 0003), so content means
 * the same thing wherever it is installed and whatever the catalog it names
 * is called there. An earlier draft of wire v8 also named that catalog's
 * `namespace` and `registry`; they are ignored when binding.
 *
 * [key] names the code list; until wire v8 the member was called `slug`, and binding reads either
 * name.
 *
 * Mutually exclusive with `AttributeResource.allowedValues` on the
 * consumer side. The wire format doesn't enforce that — it relies on the
 * importer (`CreateAttributeDefinition`) to reject mixed input, where the
 * error message is friendlier than a `oneOf` schema rejection.
 */
@JsonIgnoreProperties("namespace", "registry")
data class CodeListBindingRef(
    val catalogKey: String? = null,
    @JsonAlias("slug")
    val key: String,
)

/**
 * Points a style's `fontFamily` value at a font family. `catalogKey = null`
 * means "the same catalog as the referencing theme/template" (the typical
 * case); a non-null `catalogKey` is the alias of another catalog the
 * referencing catalog depends on ([CatalogAliases], contract ADR 0003) — most notably
 * the bundled `system` catalog. Mirrors [CodeListBindingRef]; this is the
 * shape stored in `documentStyles`, block-style presets, and inline node
 * styles under the `fontFamily` key. An earlier draft of wire v8 also named
 * that catalog's `namespace` and `registry`; they are ignored when binding.
 * [key] names the font family; until wire v8 the member was called `slug`,
 * and binding reads either name.
 */
@JsonIgnoreProperties("namespace", "registry")
data class FontRef(
    val catalogKey: String? = null,
    @JsonAlias("slug")
    val key: String,
)

/**
 * Inline catalog representation of a code list. Wire format only carries the
 * entries — runtime `source_type` / `source_url` / auth metadata is local to
 * each importing instance and reconstructed from the catalog source (a
 * bundled catalog's code lists become `CLASSPATH`-sourced; a remote
 * catalog's become `URL`-sourced via the refresh path).
 */
data class CodeListResource(
    @JsonAlias("slug")
    override val key: String,
    override val name: String,
    val description: String? = null,
    val entries: List<CodeListEntryEntry>,
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    override val tags: List<String> = emptyList(),
) : CatalogResource {
    override val type: String get() = "codeList"
}

/** One stable value in a [CodeListResource]. */
data class CodeListEntryEntry(
    val code: String,
    val label: String,
    val sortOrder: Int = 0,
    val hidden: Boolean = false,
)

/**
 * Metadata for a binary file carried in the same archive.
 *
 * [contentUrl] identifies the normalized archive path. Binary bytes remain
 * available through [app.epistola.catalog.archive.ArchiveContentProvider] and
 * participate in per-resource and catalog fingerprints.
 */
/**
 * Something that names a binary in the archive.
 *
 * [contentHash] is the identity: what the bytes are. [contentUrl] is only a location, and only
 * when the archive does not put the binary where its hash says. A catalog written from wire v7
 * onwards omits it and files every binary at [canonicalPath], so identical bytes are one file and
 * there is no naming convention to disagree about. An archive migrated from an earlier version
 * keeps the path it already had, because a migration rewrites documents and cannot move files.
 */
interface BinaryRef {
    /**
     * Where the bytes are, when the archive does not put them where the hash says.
     *
     * Read, never written: a catalog written at wire v7 omits it, and only an archive migrated
     * from an earlier version still carries a path. Removed with the next wire version, by which
     * time no archive in circulation should set it. Resolve through [contentPath] rather than
     * reading this directly.
     */
    @Deprecated("A binary is placed by its hash; read contentPath() instead.")
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val contentUrl: String?
    val contentHash: String

    /**
     * Where the bytes are: the declared path, or the one the hash implies.
     *
     * Derived, never serialised. It is a reading convenience, and putting it on the wire would
     * add a field that says nothing new and would move every fingerprint that carries a binary.
     */
    @Suppress("DEPRECATION") // The one place that reads it: resolving it away is its whole job.
    fun contentPath(): String = contentUrl?.removePrefix("./") ?: canonicalPath(contentHash)

    companion object {
        /** The archive path a binary takes when nothing says otherwise. */
        fun canonicalPath(contentHash: String): String = "bin/$contentHash"
    }
}

data class ImageResource(
    @JsonAlias("slug")
    override val key: String,
    override val name: String,
    val mediaType: String,
    val width: Int? = null,
    val height: Int? = null,
    override val contentHash: String,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    override val contentUrl: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    override val tags: List<String> = emptyList(),
) : CatalogResource,
    BinaryRef {
    override val type: String get() = "image"
}

/**
 * Inline catalog representation of a font family. A font family is a thin
 * grouping over its face binaries, each carried by a [FontVariantEntry]. The
 * `FontResource` itself carries no binary.
 *
 * A catalog carries its font faces as resources of its release, like any other resource: the
 * family here, each face's bytes in the archive at its `contentUrl`, and both inside the release's
 * fingerprint, which covers each face's bytes as it does an image's. That holds for every catalog,
 * a catalog an installation supplies itself (such as Epistola Suite's bundled `system` catalog)
 * included, so a release that pins it renders with exactly the faces it was cut with, wherever it
 * is installed. How an installation stores the bytes (on its classpath, say) is its own business.
 */
data class FontResource(
    @JsonAlias("slug")
    override val key: String,
    override val name: String,
    val kind: String,
    val variants: List<FontVariantEntry>,
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    override val tags: List<String> = emptyList(),
) : CatalogResource {
    override val type: String get() = "font"
}

/**
 * One face of a [FontResource], identified by CSS-style numeric `weight`
 * (1–1000; 400 = regular, 700 = bold) and `italic`.
 *
 * A face names its binary the way an image does: [contentUrl] is where the bytes sit in the
 * archive and [contentHash] is what they are. Until wire v7 it pointed at a separate asset
 * resource by slug, which gave a binary a name it never had — nothing chooses what a font face
 * is called, and two catalogs shipping the same face shipped it twice under different names.
 *
 * A family carries as many faces as it ships (Light/Medium/SemiBold/…), not a fixed four. Every
 * face is a static binary — variable fonts are instanced into static faces at upload, never
 * represented here.
 */
data class FontVariantEntry(
    val weight: Int,
    val italic: Boolean,
    /** What the face's binary is -- `font/ttf` or `font/otf`. Stated, never inferred from a path. */
    val mediaType: String,
    override val contentHash: String,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    override val contentUrl: String? = null,
) : BinaryRef

/**
 * Named example payload checked against [TemplateResource.dataModel].
 *
 * [key] (wire v8) is the example's address within its template, unique there
 * ([CatalogKeys.RESOURCE]); [name] is its display name, also unique there. A catalog-v7 example
 * had no key, and the v7-to-v8 migration derives one from its name
 * ([CatalogKeys.deriveDataExampleKey]). The key is nullable, and omitted when null, so that examples
 * a consumer stored before wire v8 still bind; the catalog validator reports a missing one.
 */
@JsonPropertyOrder("key", "name", "data")
data class DataExampleEntry(
    val name: String,
    val data: Map<String, Any?>,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val key: String? = null,
)

/**
 * One selectable template variant.
 *
 * A null [templateModel] inherits the template's base model. At most one
 * variant may set [isDefault].
 *
 * [key] is the variant's public address within its template. Until wire v8 the member was called
 * `slug` (and `id` until wire v7); binding reads `slug` too.
 *
 * [attributes] maps an attribute's key to the variant's value for it. An attribute another catalog
 * defines is keyed `<alias>.<key>` (`system.locale`), by the alias the dependency list declares for
 * that catalog ([CatalogAliases], contract ADR 0003), like every other reference into another catalog.
 *
 * [metadata] (wire v8) is producer-specific data the contract carries but does not define, keyed by
 * a reverse-DNS namespace the producer owns (`app.epistola.suite`), each value a JSON object
 * ([CatalogVariantMetadata]). It binds the way the other free-form JSON in the model does
 * ([TemplateResource.dataModel]): plain maps, lists and scalars, written back member for member, with
 * numbers normalized as Jackson binds them. Omitted when null or empty, since the wire forbids `{}`.
 *
 * [description] (wire v8) is plain text for the variant's authors: what it is for. Not rendered.
 * Omitted when null or empty, so a variant without one serializes, and fingerprints, as before.
 */
data class VariantEntry(
    @JsonAlias("slug")
    val key: String,
    val title: String? = null,
    val attributes: Map<String, String>? = null,
    val templateModel: TemplateDocument? = null,
    val isDefault: Boolean = false,
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val metadata: Map<String, Map<String, Any?>>? = null,
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val description: String? = null,
)
