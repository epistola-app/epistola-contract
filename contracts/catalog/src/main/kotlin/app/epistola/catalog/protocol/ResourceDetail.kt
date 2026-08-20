// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.protocol

import app.epistola.template.model.BlockStylePreset
import app.epistola.template.model.PageSettings
import app.epistola.template.model.TemplateDocument
import com.fasterxml.jackson.annotation.JsonProperty
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
        JsonSubTypes.Type(value = AssetResource::class, name = "asset"),
        JsonSubTypes.Type(value = CodeListResource::class, name = "codeList"),
        JsonSubTypes.Type(value = FontResource::class, name = "font"),
    )
    val resource: CatalogResource,
)

/**
 * Base type for all portable catalog resources.
 *
 * Jackson discriminates concrete payloads using the existing `type` property.
 * Every resource has a catalog-local [slug] and human-readable [name].
 */
sealed interface CatalogResource {
    val type: String
    val slug: String
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
 */
data class TemplateResource(
    override val slug: String,
    override val name: String,
    val themeId: String? = null,
    val themeCatalogKey: String? = null,
    val pdfaEnabled: Boolean = true,
    val dataModel: Map<String, Any?>? = null,
    val dataExamples: List<DataExampleEntry>? = null,
    val templateModel: TemplateDocument,
    val variants: List<VariantEntry>,
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
    override val slug: String,
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
 * Wire format for a stencil resource. [version] is the published version
 * number of the stencil whose [content] is carried here — preserved across
 * export/import so that templates pinning a specific version still resolve
 * after a round-trip. No default value: a ZIP without `version` is from a
 * pre-`0.6.0` exporter and must be re-exported before it can be imported.
 *
 * Only published stencil versions are exported; draft and archived versions
 * are filtered out at export time. The wire format therefore carries no
 * status field — every `StencilResource` in a catalog ZIP is published by
 * construction.
 *
 * `version` is annotated `required = true` so that consumers reject pre-0.6.0
 * ZIPs at deserialisation time regardless of their `ObjectMapper` config,
 * rather than silently coercing a missing field to `0`.
 *
 * `parameterSchema` carries the stencil version's typed input parameters as a
 * JSON Schema object (`{type:"object", properties, required}`) — the same value
 * stored on `StencilVersion.parameter_schema`. It is optional and additive: a ZIP
 * from a stencil without declared parameters (or from an exporter predating this
 * field) simply omits it, and a consumer that does not understand it ignores it.
 * Adding it therefore needs no `schemaVersion` bump (see
 * `docs/adr/0007-catalog-wire-format-migrations.md`). When present it round-trips
 * so that templates binding to those parameters stay bound after an import.
 *
 * [content] may embed nodes that reference exact published versions of other
 * stencils. These references form a portable composition graph: validation
 * requires their version to resolve, rejects direct and transitive recursion,
 * and limits the complete chain to five stencil levels.
 */
data class StencilResource(
    override val slug: String,
    override val name: String,
    @JsonProperty(required = true) val version: Int,
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
    override val slug: String,
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
 * and the list it binds to).
 *
 * Mutually exclusive with `AttributeResource.allowedValues` on the
 * consumer side. The wire format doesn't enforce that — it relies on the
 * importer (`CreateAttributeDefinition`) to reject mixed input, where the
 * error message is friendlier than a `oneOf` schema rejection.
 */
data class CodeListBindingRef(
    val catalogKey: String? = null,
    val slug: String,
)

/**
 * Points a style's `fontFamily` value at a font family. `catalogKey = null`
 * means "the same catalog as the referencing theme/template" (the typical
 * case); a non-null `catalogKey` references another catalog in the same
 * tenant — most notably the bundled `system` catalog. Mirrors
 * [CodeListBindingRef]; this is the shape stored in `documentStyles`,
 * block-style presets, and inline node styles under the `fontFamily` key.
 */
data class FontRef(
    val catalogKey: String? = null,
    val slug: String,
)

/**
 * Inline catalog representation of a code list. Wire format only carries the
 * entries — runtime `source_type` / `source_url` / auth metadata is local to
 * each importing instance and reconstructed from the catalog source (a
 * bundled catalog's code lists become `CLASSPATH`-sourced; a remote
 * catalog's become `URL`-sourced via the refresh path).
 */
data class CodeListResource(
    override val slug: String,
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
data class AssetResource(
    override val slug: String,
    override val name: String,
    val mediaType: String,
    val width: Int? = null,
    val height: Int? = null,
    val contentUrl: String,
) : CatalogResource {
    override val type: String get() = "asset"
}

/**
 * Inline catalog representation of a font family. A font family is a thin
 * grouping over up to four font-face binaries; each binary rides the catalog
 * as an ordinary [AssetResource], referenced here by its asset slug. The
 * `FontResource` itself carries no binary. Bundled system fonts are
 * classpath-backed locally and are never exported, so the wire format only
 * ever describes catalog-authored (asset-backed) fonts.
 */
data class FontResource(
    override val slug: String,
    override val name: String,
    val kind: String,
    val variants: List<FontVariantEntry>,
) : CatalogResource {
    override val type: String get() = "font"
}

/**
 * One face of a [FontResource], identified by CSS-style numeric `weight`
 * (1–1000; 400 = regular, 700 = bold) and `italic`. `assetSlug` points at an
 * [AssetResource] in the same catalog holding that face's binary. A family
 * carries as many faces as it ships (Light/Medium/SemiBold/…), not a fixed
 * four. Every face is a static binary — variable fonts are instanced into
 * static faces at upload, never represented here.
 */
data class FontVariantEntry(
    val weight: Int,
    val italic: Boolean,
    val assetSlug: String,
)

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
 */
data class VariantEntry(
    val id: String,
    val title: String? = null,
    val attributes: Map<String, String>? = null,
    val templateModel: TemplateDocument? = null,
    val isDefault: Boolean = false,
)
