package app.epistola.catalog.protocol

import app.epistola.template.model.PageSettings
import app.epistola.template.model.TemplateDocument
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * Wire format for a single resource detail JSON fetched from a catalog's detailUrl.
 * Matches the schema defined in docs/exchange.md.
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
 * Base type for all catalog resources. Discriminated by the `type` field.
 */
sealed interface CatalogResource {
    val type: String
    val slug: String
    val name: String
}

data class TemplateResource(
    override val slug: String,
    override val name: String,
    val themeId: String? = null,
    val themeCatalogKey: String? = null,
    val dataModel: Map<String, Any?>? = null,
    val dataExamples: List<DataExampleEntry>? = null,
    val templateModel: TemplateDocument,
    val variants: List<VariantEntry>,
) : CatalogResource {
    override val type: String get() = "template"
}

data class ThemeResource(
    override val slug: String,
    override val name: String,
    val description: String? = null,
    val documentStyles: Map<String, Any?>? = null,
    val pageSettings: PageSettings? = null,
    val blockStylePresets: Map<String, Any?>? = null,
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
 */
data class StencilResource(
    override val slug: String,
    override val name: String,
    val version: Int,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val content: TemplateDocument,
) : CatalogResource {
    override val type: String get() = "stencil"
}

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

data class CodeListEntryEntry(
    val code: String,
    val label: String,
    val sortOrder: Int = 0,
    val hidden: Boolean = false,
)

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

data class DataExampleEntry(
    val name: String,
    val data: Map<String, Any?>,
)

data class VariantEntry(
    val id: String,
    val title: String? = null,
    val attributes: Map<String, String>? = null,
    val templateModel: TemplateDocument? = null,
    val isDefault: Boolean = false,
)
