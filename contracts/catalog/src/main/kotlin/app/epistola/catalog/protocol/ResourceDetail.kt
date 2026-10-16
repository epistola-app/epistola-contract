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
 * binding reads either name. [themeCatalogKey], [themeNamespace] and [themeRegistry] qualify it when
 * the theme lives in another catalog; each null one means the template's own catalog's (wire v8). A
 * [themeRegistry] without a [themeNamespace] is invalid.
 */
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
    val themeNamespace: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val themeRegistry: String? = null,
) : CatalogResource {
    override val type: String get() = "template"
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
) : CatalogResource {
    override val type: String get() = "theme"
}

/**
 * Wire format for a stencil resource.
 *
 * A stencil carries no version of its own from wire v8: a catalog is versioned as a whole, so the
 * stencil a template embeds is the one in the release its catalog dependency resolves to (or the
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
 * v8), optionally qualified with `catalogKey`, `namespace` and `registry`. These references form a portable composition graph: validation
 * requires them to resolve, rejects direct and transitive recursion, and limits the complete chain
 * to five stencil levels.
 */
@JsonIgnoreProperties("version")
data class StencilResource(
    @JsonAlias("slug")
    override val key: String,
    override val name: String,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val content: TemplateDocument,
    val parameterSchema: Map<String, Any?>? = null,
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
) : CatalogResource {
    override val type: String get() = "attribute"
}

/**
 * Points a variant-attribute definition at a code list that constrains its
 * allowed values. `catalogKey = null` means "the same catalog as the
 * attribute" (the typical case for catalogs that author both an attribute
 * and the list it binds to). `namespace = null` and `registry = null` mean
 * the owning catalog's namespace and registry (wire v8), never an
 * installation default: content means the same thing wherever it is
 * installed. A `registry` without a `namespace` is invalid.
 *
 * [key] names the code list; until wire v8 the member was called `slug`, and binding reads either
 * name.
 *
 * Mutually exclusive with `AttributeResource.allowedValues` on the
 * consumer side. The wire format doesn't enforce that — it relies on the
 * importer (`CreateAttributeDefinition`) to reject mixed input, where the
 * error message is friendlier than a `oneOf` schema rejection.
 */
data class CodeListBindingRef(
    val catalogKey: String? = null,
    @JsonAlias("slug")
    val key: String,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val namespace: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val registry: String? = null,
)

/**
 * Points a style's `fontFamily` value at a font family. `catalogKey = null`
 * means "the same catalog as the referencing theme/template" (the typical
 * case); a non-null `catalogKey` references another catalog in the same
 * tenant — most notably the bundled `system` catalog. Mirrors
 * [CodeListBindingRef]; this is the shape stored in `documentStyles`,
 * block-style presets, and inline node styles under the `fontFamily` key.
 * `namespace = null` and `registry = null` mean the referencing catalog's
 * namespace and registry (wire v8); a `registry` without a `namespace` is
 * invalid. [key] names the font family; until wire v8 the member was called
 * `slug`, and binding reads either name.
 */
data class FontRef(
    val catalogKey: String? = null,
    @JsonAlias("slug")
    val key: String,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val namespace: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val registry: String? = null,
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
) : CatalogResource,
    BinaryRef {
    override val type: String get() = "image"
}

/**
 * Inline catalog representation of a font family. A font family is a thin
 * grouping over its face binaries, each carried by a [FontVariantEntry]. The
 * `FontResource` itself carries no binary. Bundled system fonts are
 * classpath-backed locally and are never exported, so the wire format only
 * ever describes catalog-authored fonts.
 */
data class FontResource(
    @JsonAlias("slug")
    override val key: String,
    override val name: String,
    val kind: String,
    val variants: List<FontVariantEntry>,
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

/** Named example payload checked against [TemplateResource.dataModel]. */
data class DataExampleEntry(
    val name: String,
    val data: Map<String, Any?>,
)

/**
 * One selectable template variant.
 *
 * A null [templateModel] inherits the template's base model. At most one
 * variant may set [isDefault].
 *
 * [key] is the variant's public address within its template. Until wire v8 the member was called
 * `slug` (and `id` until wire v7); binding reads `slug` too.
 */
data class VariantEntry(
    @JsonAlias("slug")
    val key: String,
    val title: String? = null,
    val attributes: Map<String, String>? = null,
    val templateModel: TemplateDocument? = null,
    val isDefault: Boolean = false,
)
