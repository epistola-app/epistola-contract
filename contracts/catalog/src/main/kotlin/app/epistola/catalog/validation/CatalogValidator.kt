// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.validation

import app.epistola.catalog.archive.ArchiveContentProvider
import app.epistola.catalog.archive.CatalogArchive
import app.epistola.catalog.archive.CatalogArchivePolicy
import app.epistola.catalog.archive.CatalogArchiveReader
import app.epistola.catalog.canonical.CatalogCanonicalizer
import app.epistola.catalog.canonical.QualifiedCatalogFingerprint
import app.epistola.catalog.migration.CatalogMigrationOptions
import app.epistola.catalog.migration.CatalogWireSchema
import app.epistola.catalog.protocol.AttributeResource
import app.epistola.catalog.protocol.BinaryRef
import app.epistola.catalog.protocol.CatalogArchivePaths
import app.epistola.catalog.protocol.CatalogDependency
import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.CatalogKeys
import app.epistola.catalog.protocol.CatalogKeywords
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.CatalogNamespaces
import app.epistola.catalog.protocol.CatalogResource
import app.epistola.catalog.protocol.CatalogVariantMetadata
import app.epistola.catalog.protocol.CodeListResource
import app.epistola.catalog.protocol.DependencyRef
import app.epistola.catalog.protocol.FontResource
import app.epistola.catalog.protocol.ImageResource
import app.epistola.catalog.protocol.KeywordRule
import app.epistola.catalog.protocol.ResourceDetail
import app.epistola.catalog.protocol.ResourceEntry
import app.epistola.catalog.protocol.StencilResource
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.catalog.protocol.ThemeResource
import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRefOverride
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.io.InputStream
import java.net.URI
import java.time.OffsetDateTime

/**
 * Stable diagnostic returned by resource or whole-catalog validation.
 *
 * Paths are rooted at `catalog.json`, `resources/...`, or `archive` and remain
 * deterministic across runs.
 */
data class CatalogValidationFinding(
    /** Machine-readable contract code. */
    val code: String,
    /** Whether the finding prevents portable acceptance. */
    val severity: ValidationSeverity,
    /** Deterministic path to the invalid value or document. */
    val path: String,
    /** Human-readable explanation for logs and user interfaces. */
    val message: String,
)

/** Deterministically ordered aggregate of catalog validation findings. */
data class CatalogValidationReport(
    val findings: List<CatalogValidationFinding>,
) {
    /** True when [findings] contains no errors. */
    val valid: Boolean get() = findings.none { it.severity == ValidationSeverity.ERROR }
}

/**
 * Consumer boundary for references whose target lives outside the catalog.
 *
 * Implementations must not perform persistence or authorization changes.
 * Returning [ResourceResolution.UNKNOWN] leaves the reference unchecked;
 * returning [ResourceResolution.MISSING] produces a stable finding.
 */
fun interface CatalogDependencyResolver {
    /** Resolves one external resource identity. */
    fun resolve(reference: CatalogResourceReference): ResourceResolution

    companion object {
        /** Resolver used when no external catalog graph is available. */
        val UNKNOWN = CatalogDependencyResolver { ResourceResolution.UNKNOWN }
    }
}

/**
 * Portable whole-catalog validation configuration.
 *
 * @property archive limits used when validating a ZIP stream.
 * @property dependencyResolver lookup boundary for external resource closure.
 * @property verifyFingerprint whether a declared release fingerprint must
 *   equal a supported canonical fingerprint.
 * @property migration what the caller knows that an archive from before the current wire version
 *   does not say, notably the registry and namespace of a catalog-v7 archive. Used when validating a ZIP stream.
 */
data class CatalogValidationPolicy(
    val archive: CatalogArchivePolicy = CatalogArchivePolicy(),
    val dependencyResolver: CatalogDependencyResolver = CatalogDependencyResolver.UNKNOWN,
    val verifyFingerprint: Boolean = true,
    val migration: CatalogMigrationOptions = CatalogMigrationOptions.DEFAULT,
)

/**
 * Stable finding codes owned by whole-catalog and resource validation.
 *
 * Template-specific findings retain their [TemplateValidationCodes] values
 * when aggregated into a catalog report.
 */
object CatalogValidationCodes {
    const val MANIFEST_SCHEMA_UNSUPPORTED = "CATALOG_MANIFEST_SCHEMA_UNSUPPORTED"
    const val MANIFEST_RESOURCE_DUPLICATE = "CATALOG_MANIFEST_RESOURCE_DUPLICATE"
    const val MANIFEST_DETAIL_PATH_INVALID = "CATALOG_MANIFEST_DETAIL_PATH_INVALID"
    const val MANIFEST_DETAIL_MISSING = "CATALOG_MANIFEST_DETAIL_MISSING"
    const val MANIFEST_DETAIL_UNDECLARED = "CATALOG_MANIFEST_DETAIL_UNDECLARED"
    const val RESOURCE_SCHEMA_MISMATCH = "CATALOG_RESOURCE_SCHEMA_MISMATCH"
    const val RESOURCE_TYPE_MISMATCH = "CATALOG_RESOURCE_TYPE_MISMATCH"
    const val RESOURCE_KEY_MISMATCH = "CATALOG_RESOURCE_KEY_MISMATCH"
    const val RESOURCE_NAME_MISMATCH = "CATALOG_RESOURCE_NAME_MISMATCH"

    /**
     * A key outside the one resource key rule ([CatalogKeys.RESOURCE]): a resource's or a variant's
     * key, a variant's attribute key, a resource dependency's key, or the key a reference in a
     * resource names (a template's theme, a font, a code-list binding).
     */
    const val RESOURCE_KEY_INVALID = "CATALOG_RESOURCE_KEY_INVALID"
    const val RESOURCE_REFERENCE_MISSING = "CATALOG_RESOURCE_REFERENCE_MISSING"
    const val DEPENDENCY_REFERENCE_MISSING = "CATALOG_DEPENDENCY_REFERENCE_MISSING"
    const val DEPENDENCY_DUPLICATE = "CATALOG_DEPENDENCY_DUPLICATE"

    /**
     * A namespace is missing or malformed: the catalog's own or a dependency's is missing or not a
     * slug, a reference inside content names one that is not a slug, or a reference inside content
     * names a registry without a namespace.
     */
    const val NAMESPACE_INVALID = "CATALOG_NAMESPACE_INVALID"

    /**
     * A registry is missing or malformed: the catalog's own or a dependency's is missing or not a
     * DNS name written in full, or a reference inside content names one that is not.
     */
    const val REGISTRY_INVALID = "CATALOG_REGISTRY_INVALID"

    /** A resource dependency names a catalog that `catalogDependencies` does not declare. */
    const val DEPENDENCY_CATALOG_UNDECLARED = "CATALOG_DEPENDENCY_CATALOG_UNDECLARED"

    /** `catalogDependencies` declares the same catalog (registry, namespace and key) more than once. */
    const val DEPENDENCY_CATALOG_DUPLICATE = "CATALOG_DEPENDENCY_CATALOG_DUPLICATE"

    /** A catalog dependency, or a resource dependency, names the catalog itself. */
    const val DEPENDENCY_CATALOG_SELF = "CATALOG_DEPENDENCY_CATALOG_SELF"

    /**
     * A catalog dependency's version selector is blank or longer than
     * [app.epistola.catalog.protocol.CatalogDependency.MAX_VERSION_LENGTH], or its recorded release
     * is not SemVer.
     */
    const val DEPENDENCY_CATALOG_VERSION_INVALID = "CATALOG_DEPENDENCY_CATALOG_VERSION_INVALID"

    /**
     * A catalog dependency's `releaseFingerprint` does not pair with its `releaseVersion`: missing
     * although a release is recorded, set although none is, or not in the form that names a known
     * algorithm (`v5:<lowercase SHA-256 hex>`).
     */
    const val DEPENDENCY_CATALOG_FINGERPRINT_INVALID = "CATALOG_DEPENDENCY_CATALOG_FINGERPRINT_INVALID"
    const val RELEASE_VERSION_INVALID = "CATALOG_RELEASE_VERSION_INVALID"
    const val RELEASE_TIMESTAMP_INVALID = "CATALOG_RELEASE_TIMESTAMP_INVALID"
    const val RELEASE_FINGERPRINT_INVALID = "CATALOG_RELEASE_FINGERPRINT_INVALID"
    const val RELEASE_FINGERPRINT_MISMATCH = "CATALOG_RELEASE_FINGERPRINT_MISMATCH"
    const val CATALOG_ATTRIBUTE_IDENTITY_INVALID = "CATALOG_ATTRIBUTE_IDENTITY_INVALID"
    const val CATALOG_ATTRIBUTE_DUPLICATE = "CATALOG_ATTRIBUTE_DUPLICATE"
    const val CATALOG_LICENSE_INVALID = "CATALOG_LICENSE_INVALID"
    const val KEYWORD_INVALID = "CATALOG_KEYWORD_INVALID"
    const val KEYWORD_DUPLICATE = "CATALOG_KEYWORD_DUPLICATE"
    const val KEYWORD_TOO_LONG = "CATALOG_KEYWORD_TOO_LONG"
    const val KEYWORD_LIMIT_EXCEEDED = "CATALOG_KEYWORD_LIMIT_EXCEEDED"
    const val PRESENTATION_ASSET_MISSING = "CATALOG_PRESENTATION_ASSET_MISSING"
    const val PRESENTATION_RESOURCE_NOT_ASSET = "CATALOG_PRESENTATION_RESOURCE_NOT_ASSET"
    const val PRESENTATION_ASSET_MEDIA_TYPE_INVALID = "CATALOG_PRESENTATION_ASSET_MEDIA_TYPE_INVALID"
    const val PRESENTATION_IMAGE_DUPLICATE = "CATALOG_PRESENTATION_IMAGE_DUPLICATE"
    const val ASSET_PATH_INVALID = "CATALOG_ASSET_PATH_INVALID"
    const val ASSET_FILE_MISSING = "CATALOG_ASSET_FILE_MISSING"
    const val ASSET_MEDIA_TYPE_INVALID = "CATALOG_ASSET_MEDIA_TYPE_INVALID"
    const val ASSET_DIMENSIONS_INVALID = "CATALOG_ASSET_DIMENSIONS_INVALID"

    /** A binary's declared contentHash is not a lowercase hex sha-256. */
    const val ASSET_CONTENT_HASH_INVALID = "CATALOG_ASSET_CONTENT_HASH_INVALID"

    /** A binary's declared contentHash is not what its bytes hash to. */
    const val ASSET_CONTENT_HASH_MISMATCH = "CATALOG_ASSET_CONTENT_HASH_MISMATCH"
    const val FONT_KIND_INVALID = "CATALOG_FONT_KIND_INVALID"
    const val FONT_VARIANT_INVALID = "CATALOG_FONT_VARIANT_INVALID"
    const val FONT_VARIANT_DUPLICATE = "CATALOG_FONT_VARIANT_DUPLICATE"
    const val CODE_LIST_CODE_DUPLICATE = "CATALOG_CODE_LIST_CODE_DUPLICATE"
    const val ATTRIBUTE_VALUES_CONFLICT = "CATALOG_ATTRIBUTE_VALUES_CONFLICT"
    const val TEMPLATE_EXAMPLE_NAME_DUPLICATE = "CATALOG_TEMPLATE_EXAMPLE_NAME_DUPLICATE"

    /** Two data examples of one template have the same key (wire v8). */
    const val TEMPLATE_EXAMPLE_KEY_DUPLICATE = "CATALOG_TEMPLATE_EXAMPLE_KEY_DUPLICATE"

    /**
     * A data example has no key, or one outside [CatalogKeys.RESOURCE] (wire v8). A catalog
     * migrated from wire v7 has one for every example, derived from its name.
     */
    const val TEMPLATE_EXAMPLE_KEY_INVALID = "CATALOG_TEMPLATE_EXAMPLE_KEY_INVALID"

    /** A template's description is longer than [TemplateResource.MAX_DESCRIPTION_LENGTH] (wire v8). */
    const val TEMPLATE_DESCRIPTION_INVALID = "CATALOG_TEMPLATE_DESCRIPTION_INVALID"

    /**
     * A template's description and its manifest resource entry's description are both present and
     * differ. The template's own is the source the entry copies (wire v8).
     */
    const val RESOURCE_DESCRIPTION_MISMATCH = "CATALOG_RESOURCE_DESCRIPTION_MISMATCH"

    /**
     * A variant's metadata is empty, names more than [CatalogVariantMetadata.MAX_NAMESPACES]
     * namespaces, or names one that is not a reverse-DNS namespace ([CatalogVariantMetadata]).
     */
    const val VARIANT_METADATA_INVALID = "CATALOG_VARIANT_METADATA_INVALID"

    /** A variant's serialized metadata exceeds [CatalogVariantMetadata.MAX_SERIALIZED_BYTES]. */
    const val VARIANT_METADATA_TOO_LARGE = "CATALOG_VARIANT_METADATA_TOO_LARGE"

    /**
     * A manifest resource entry's `detailUrl` is not a relative path below the manifest's directory
     * ([app.epistola.catalog.protocol.CatalogArchivePaths]); checked for a catalog read at wire v8.
     * [MANIFEST_DETAIL_PATH_INVALID] covers a well-formed path to the wrong place.
     */
    const val MANIFEST_DETAIL_URL_INVALID = "CATALOG_MANIFEST_DETAIL_URL_INVALID"
    const val TEMPLATE_DATA_SCHEMA_INVALID = "CATALOG_TEMPLATE_DATA_SCHEMA_INVALID"
    const val TEMPLATE_DATA_EXAMPLE_INVALID = "CATALOG_TEMPLATE_DATA_EXAMPLE_INVALID"
    const val TEMPLATE_VARIANT_KEY_DUPLICATE = "CATALOG_TEMPLATE_VARIANT_KEY_DUPLICATE"
    const val TEMPLATE_VARIANT_DEFAULT_INVALID = "CATALOG_TEMPLATE_VARIANT_DEFAULT_INVALID"
}

/**
 * In-memory catalog graph supplied to [ResourceValidator].
 *
 * @property catalogKey identity used for same-catalog references.
 * @property resources resources keyed by `type/key`.
 * @property namespace the catalog's namespace; a reference naming none, or this one, with no or this
 *   [catalogKey] and [registry], is a same-catalog reference. Null when the namespace is not known.
 * @property registry the catalog's registry; a reference that omits its registry means this one.
 *   Null when the registry is not known.
 * @property paths normalized archive paths available through the catalog.
 * @property dependencyResolver lookup boundary for other catalogs.
 */
data class ResourceValidationContext(
    val catalogKey: String,
    val resources: Map<String, CatalogResource>,
    val paths: Set<String>,
    val dependencyResolver: CatalogDependencyResolver = CatalogDependencyResolver.UNKNOWN,
    /**
     * The archive's files, when the caller has them.
     *
     * Needed to check that a binary's declared `contentHash` is what its bytes actually hash to.
     * Null when a resource is validated outside an archive, and the check is then skipped rather
     * than failed -- an absent archive is not a wrong hash.
     */
    val content: ArchiveContentProvider? = null,
    val namespace: String? = null,
    val registry: String? = null,
    /**
     * The wire version the catalog was read at, before migration. Rules a later version introduced
     * apply only from that version, so that an archive written earlier stays acceptable: from wire v8
     * a binary's `contentUrl` must be a relative path below the manifest's directory
     * ([CatalogArchivePaths]), where earlier versions required a `./` path without `..` segments.
     */
    val sourceSchemaVersion: Int = CatalogWireSchema.CURRENT_VERSION,
)

/**
 * Validates the portable semantics of one bound resource detail.
 *
 * This validator assumes archive decoding and manifest/detail binding are
 * handled by [CatalogValidator]. It validates resource-specific schemas,
 * references, templates, stencils, themes, variants, attributes, code lists,
 * fonts, assets, and examples without Suite persistence concerns.
 */
object ResourceValidator {
    /**
     * Validates [detail] at a deterministic archive [path].
     *
     * Ordinary semantic failures are returned as findings rather than thrown.
     */
    fun validate(
        detail: ResourceDetail,
        context: ResourceValidationContext,
        path: String,
    ): CatalogValidationReport {
        val findings = mutableListOf<CatalogValidationFinding>()
        val resource = detail.resource
        // One rule for every type (wire v8): the bounds fit the columns every consumer stores a
        // key in, so a key that passes here is one every consumer can actually hold.
        validateKey(resource.key, "$path.resource.key", findings)
        when (resource) {
            is TemplateResource -> validateTemplate(resource, context, "$path.resource", findings)
            is StencilResource -> {
                validateDocument(resource.content, context, "$path.resource.content", resource, findings)
                resource.parameterSchema?.let { schema ->
                    appendTemplateFindings(
                        ParameterSchemaValidator.validate(schema, "$path.resource.parameterSchema"),
                        findings,
                    )
                }
            }
            is ThemeResource -> validateTheme(resource, context, "$path.resource", findings)
            is AttributeResource -> validateAttribute(resource, context, "$path.resource", findings)
            is CodeListResource -> validateCodeList(resource, "$path.resource", findings)
            is ImageResource -> validateImage(resource, context, "$path.resource", findings)
            is FontResource -> validateFont(resource, context, "$path.resource", findings)
        }
        return CatalogValidationReport(findings.sorted())
    }

    private fun validateTemplate(
        resource: TemplateResource,
        context: ResourceValidationContext,
        path: String,
        findings: MutableList<CatalogValidationFinding>,
    ) {
        resource.themeKey?.let { themeKey ->
            validateReference(
                CatalogResourceReference("theme", themeKey, resource.themeCatalogKey, resource.themeNamespace, resource.themeRegistry),
                context,
                "$path.themeKey",
                findings,
            )
        }
        validateContentIdentity(resource.themeRegistry, "$path.themeRegistry", resource.themeNamespace, "$path.themeNamespace", findings)
        validateDocument(resource.templateModel, context, "$path.templateModel", null, findings)
        val variantKeys = mutableSetOf<String>()
        resource.variants.forEachIndexed { index, variant ->
            validateKey(variant.key, "$path.variants[$index].key", findings)
            variant.attributes?.keys?.sorted()?.forEach { attribute ->
                if (!CatalogKeys.isValidAttributeKey(attribute)) {
                    findings.error(
                        CatalogValidationCodes.RESOURCE_KEY_INVALID,
                        "$path.variants[$index].attributes.$attribute",
                        "variant attribute key must be an attribute key, or <catalogKey>.<key> for another catalog's attribute, " +
                            "each part ${CatalogKeys.MIN_LENGTH} to ${CatalogKeys.MAX_LENGTH} characters",
                    )
                }
            }
            if (!variantKeys.add(variant.key)) {
                findings.error(CatalogValidationCodes.TEMPLATE_VARIANT_KEY_DUPLICATE, "$path.variants[$index].key", "variant key '${variant.key}' is duplicated")
            }
            variant.templateModel?.let { validateDocument(it, context, "$path.variants[$index].templateModel", null, findings) }
        }
        val defaults = resource.variants.count { it.isDefault }
        if (defaults > 1) {
            findings.error(CatalogValidationCodes.TEMPLATE_VARIANT_DEFAULT_INVALID, "$path.variants", "at most one variant may be the default")
        }
        resource.variants.forEachIndexed { index, variant ->
            variant.metadata?.let { validateVariantMetadata(it, "$path.variants[$index].metadata", findings) }
        }
        resource.description?.let { description ->
            if (description.codePointCount(0, description.length) > TemplateResource.MAX_DESCRIPTION_LENGTH) {
                findings.error(
                    CatalogValidationCodes.TEMPLATE_DESCRIPTION_INVALID,
                    "$path.description",
                    "description must be at most ${TemplateResource.MAX_DESCRIPTION_LENGTH} characters",
                )
            }
        }
        val exampleNames = mutableSetOf<String>()
        val exampleKeys = mutableSetOf<String>()
        resource.dataExamples.orEmpty().forEachIndexed { index, example ->
            if (!exampleNames.add(example.name)) {
                findings.error(
                    CatalogValidationCodes.TEMPLATE_EXAMPLE_NAME_DUPLICATE,
                    "$path.dataExamples[$index].name",
                    "data example name '${example.name}' is duplicated",
                )
            }
            val key = example.key
            val rule = CatalogKeys.RESOURCE
            if (key == null || !CatalogKeys.matches(rule, key)) {
                findings.error(
                    CatalogValidationCodes.TEMPLATE_EXAMPLE_KEY_INVALID,
                    "$path.dataExamples[$index].key",
                    if (key == null) {
                        "data example '${example.name}' has no key"
                    } else {
                        "data example key must be ${rule.minLength} to ${rule.maxLength} characters matching ${rule.pattern}"
                    },
                )
            } else if (!exampleKeys.add(key)) {
                findings.error(
                    CatalogValidationCodes.TEMPLATE_EXAMPLE_KEY_DUPLICATE,
                    "$path.dataExamples[$index].key",
                    "data example key '$key' is duplicated",
                )
            }
        }
        resource.dataModel?.let { schema ->
            val schemaErrors = PortableJsonSchema.checkSchema(schema)
            schemaErrors.forEach { message ->
                findings.error(CatalogValidationCodes.TEMPLATE_DATA_SCHEMA_INVALID, "$path.dataModel", message)
            }
            if (schemaErrors.isEmpty()) {
                resource.dataExamples.orEmpty().forEachIndexed { index, example ->
                    // Rendering fills an absent property from its schema default before it validates the
                    // data, so an example is held to what rendering would accept.
                    PortableJsonSchema.validate(PortableJsonSchema.withDefaults(example.data, schema), schema).forEach { message ->
                        findings.error(CatalogValidationCodes.TEMPLATE_DATA_EXAMPLE_INVALID, "$path.dataExamples[$index].data", message)
                    }
                }
            }
        }
    }

    /**
     * Holds a variant's metadata to [CatalogVariantMetadata]: one to sixteen reverse-DNS namespaces,
     * at most 8 KiB serialized. What a namespace holds is its producer's business, not checked here.
     */
    private fun validateVariantMetadata(
        metadata: Map<String, Map<String, Any?>>,
        path: String,
        findings: MutableList<CatalogValidationFinding>,
    ) {
        if (metadata.size !in CatalogVariantMetadata.MIN_NAMESPACES..CatalogVariantMetadata.MAX_NAMESPACES) {
            findings.error(
                CatalogValidationCodes.VARIANT_METADATA_INVALID,
                path,
                "metadata must name ${CatalogVariantMetadata.MIN_NAMESPACES} to ${CatalogVariantMetadata.MAX_NAMESPACES} namespaces; omit it when empty",
            )
        }
        metadata.keys.sorted().forEach { namespace ->
            if (!CatalogVariantMetadata.isValidNamespace(namespace)) {
                findings.error(
                    CatalogValidationCodes.VARIANT_METADATA_INVALID,
                    "$path.$namespace",
                    "metadata namespace '$namespace' must be a lowercase reverse-DNS name of at most ${CatalogVariantMetadata.NAMESPACE_MAX_LENGTH} characters",
                )
            }
        }
        val size = metadataMapper.writeValueAsBytes(metadata).size
        if (size > CatalogVariantMetadata.MAX_SERIALIZED_BYTES) {
            findings.error(
                CatalogValidationCodes.VARIANT_METADATA_TOO_LARGE,
                path,
                "metadata is $size bytes serialized; at most ${CatalogVariantMetadata.MAX_SERIALIZED_BYTES} are allowed",
            )
        }
    }

    private val metadataMapper = jsonMapper { addModule(kotlinModule()) }

    private fun validateDocument(
        document: TemplateDocument,
        context: ResourceValidationContext,
        path: String,
        containingStencil: StencilResource?,
        findings: MutableList<CatalogValidationFinding>,
    ) {
        (document.themeRef as? ThemeRefOverride)?.let { theme ->
            validateContentIdentity(theme.registry, "$path.themeRef.registry", theme.namespace, "$path.themeRef.namespace", findings)
        }
        val report = TemplateValidator.validate(
            document,
            object : TemplateValidationContext {
                override val documentKind =
                    if (containingStencil == null) TemplateDocumentKind.TEMPLATE else TemplateDocumentKind.STENCIL
                override val currentCatalogKey: String = context.catalogKey
                override val currentNamespace: String? = context.namespace
                override val currentRegistry: String? = context.registry
                override val containingStencil: CatalogResourceReference? = containingStencil?.let {
                    CatalogResourceReference("stencil", it.key, context.catalogKey, context.namespace, context.registry)
                }

                override fun resolveResource(reference: CatalogResourceReference): ResourceResolution = resolveReference(reference, context)
            },
        )
        appendTemplateFindings(report, findings, path)
    }

    private fun appendTemplateFindings(
        report: TemplateValidationReport,
        findings: MutableList<CatalogValidationFinding>,
        path: String? = null,
    ) {
        findings += report.findings.map {
            val relativePath = it.path.removePrefix("$").removePrefix(".")
            CatalogValidationFinding(
                it.code,
                it.severity,
                when {
                    path == null -> relativePath
                    relativePath.isEmpty() -> path
                    else -> "$path.$relativePath"
                },
                it.message,
            )
        }
    }

    private fun validateTheme(
        resource: ThemeResource,
        context: ResourceValidationContext,
        path: String,
        findings: MutableList<CatalogValidationFinding>,
    ) {
        validateFontRefs(resource.documentStyles, context, "$path.documentStyles", findings)
        resource.blockStylePresets.orEmpty().forEach { (name, value) ->
            validateFontRefs(value.styles, context, "$path.blockStylePresets.$name.styles", findings)
        }
        appendTemplateFindings(TemplateValidator.validateThemeStyles(resource.documentStyles, resource.blockStylePresets), findings, path)
        if (resource.spacingUnit != null && (!resource.spacingUnit.isFinite() || resource.spacingUnit <= 0)) {
            findings.error(CatalogValidationCodes.TEMPLATE_DATA_SCHEMA_INVALID, "$path.spacingUnit", "spacingUnit must be a positive finite number")
        }
    }

    private fun validateFontRefs(
        styles: Map<String, Any?>?,
        context: ResourceValidationContext,
        path: String,
        findings: MutableList<CatalogValidationFinding>,
    ) {
        val font = styles?.get("fontFamily") as? Map<*, *> ?: return
        val key = font["key"] as? String ?: return
        val catalogKey = font["catalogKey"] as? String
        val namespace = font["namespace"] as? String
        val registry = font["registry"] as? String
        validateContentIdentity(registry, "$path.fontFamily.registry", namespace, "$path.fontFamily.namespace", findings)
        validateReference(CatalogResourceReference("font", key, catalogKey, namespace, registry), context, "$path.fontFamily", findings)
    }

    private fun validateAttribute(
        resource: AttributeResource,
        context: ResourceValidationContext,
        path: String,
        findings: MutableList<CatalogValidationFinding>,
    ) {
        if (resource.allowedValues.isNotEmpty() && resource.codeListBinding != null) {
            findings.error(
                CatalogValidationCodes.ATTRIBUTE_VALUES_CONFLICT,
                path,
                "allowedValues and codeListBinding are mutually exclusive",
            )
        }
        resource.codeListBinding?.let {
            validateContentIdentity(it.registry, "$path.codeListBinding.registry", it.namespace, "$path.codeListBinding.namespace", findings)
            validateReference(
                CatalogResourceReference("codeList", it.key, it.catalogKey, it.namespace, it.registry),
                context,
                "$path.codeListBinding",
                findings,
            )
        }
    }

    private fun validateCodeList(
        resource: CodeListResource,
        path: String,
        findings: MutableList<CatalogValidationFinding>,
    ) {
        val codes = mutableSetOf<String>()
        resource.entries.forEachIndexed { index, entry ->
            if (!codes.add(entry.code)) {
                findings.error(CatalogValidationCodes.CODE_LIST_CODE_DUPLICATE, "$path.entries[$index].code", "code '${entry.code}' is duplicated")
            }
        }
    }

    private fun validateImage(
        resource: ImageResource,
        context: ResourceValidationContext,
        path: String,
        findings: MutableList<CatalogValidationFinding>,
    ) {
        validateBinary(resource, context, path, findings)
        if (!MEDIA_TYPE.matches(resource.mediaType)) {
            findings.error(CatalogValidationCodes.ASSET_MEDIA_TYPE_INVALID, "$path.mediaType", "mediaType is not a valid type/subtype")
        }
        if ((resource.width != null && resource.width <= 0) || (resource.height != null && resource.height <= 0)) {
            findings.error(CatalogValidationCodes.ASSET_DIMENSIONS_INVALID, path, "image dimensions must be positive")
        }
    }

    /**
     * Checks one binary: that its path is safe and present, and that its declared hash is what the
     * bytes actually hash to.
     *
     * The hash is the binary's identity from wire v7, so a declared one that does not match its
     * content is a catalog claiming to carry something it does not. Checked here rather than
     * trusted, and skipped when the caller has no archive to read.
     */
    private val CONTENT_HASH = Regex("^[0-9a-f]{64}$")

    private fun sha256Hex(input: java.io.InputStream): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun validateBinary(
        binary: BinaryRef,
        context: ResourceValidationContext,
        path: String,
        findings: MutableList<CatalogValidationFinding>,
    ) {
        val contentHash = binary.contentHash
        val contentPath = binary.contentPath()

        // Read directly because a *declared* path is what needs checking for traversal; one the
        // hash implies cannot escape the archive.
        @Suppress("DEPRECATION")
        val contentUrl = binary.contentUrl
        val safe = when {
            contentUrl == null -> true
            // Wire v8 states the rule every path into the catalog follows.
            context.sourceSchemaVersion >= ARCHIVE_PATHS_SINCE_WIRE_VERSION -> CatalogArchivePaths.isValid(contentUrl)
            // Earlier versions are held to what they were always held to.
            else -> contentUrl.startsWith("./") && '\\' !in contentUrl && ".." !in contentUrl.split('/')
        }
        if (!safe) {
            findings.error(CatalogValidationCodes.ASSET_PATH_INVALID, "$path.contentUrl", "contentUrl must be a relative path below the manifest's directory")
            return
        }
        if (contentPath !in context.paths) {
            findings.error(CatalogValidationCodes.ASSET_FILE_MISSING, "$path.contentUrl", "content file '$contentPath' is missing")
            return
        }
        if (!CONTENT_HASH.matches(contentHash)) {
            findings.error(CatalogValidationCodes.ASSET_CONTENT_HASH_INVALID, "$path.contentHash", "contentHash must be a lowercase hex sha-256")
            return
        }
        val provider = context.content ?: return
        val actual = provider.open(contentPath).use(::sha256Hex)
        if (actual != contentHash) {
            findings.error(
                CatalogValidationCodes.ASSET_CONTENT_HASH_MISMATCH,
                "$path.contentHash",
                "content file '$contentPath' hashes to '$actual', not the declared '$contentHash'",
            )
        }
    }

    private fun validateFont(
        resource: FontResource,
        context: ResourceValidationContext,
        path: String,
        findings: MutableList<CatalogValidationFinding>,
    ) {
        if (resource.kind !in setOf("sans", "serif", "mono", "condensed", "display")) {
            findings.error(CatalogValidationCodes.FONT_KIND_INVALID, "$path.kind", "font kind '${resource.kind}' is unsupported")
        }
        val faces = mutableSetOf<Pair<Int, Boolean>>()
        resource.variants.forEachIndexed { index, face ->
            if (face.weight !in 1..1000) {
                findings.error(CatalogValidationCodes.FONT_VARIANT_INVALID, "$path.variants[$index].weight", "font weight must be between 1 and 1000")
            }
            if (!faces.add(face.weight to face.italic)) {
                findings.error(CatalogValidationCodes.FONT_VARIANT_DUPLICATE, "$path.variants[$index]", "font face weight/italic combination is duplicated")
            }
            validateBinary(face, context, "$path.variants[$index]", findings)
        }
    }

    private fun validateReference(
        reference: CatalogResourceReference,
        context: ResourceValidationContext,
        path: String,
        findings: MutableList<CatalogValidationFinding>,
    ) {
        if (!CatalogKeys.isValid(reference.key)) {
            findings.error(
                CatalogValidationCodes.RESOURCE_KEY_INVALID,
                path,
                "${reference.type} reference key must be ${CatalogKeys.MIN_LENGTH} to ${CatalogKeys.MAX_LENGTH} characters matching ${CatalogKeys.PATTERN}",
            )
            return
        }
        val resolution = resolveReference(reference, context)
        if (resolution == ResourceResolution.MISSING) {
            findings.error(CatalogValidationCodes.RESOURCE_REFERENCE_MISSING, path, "${reference.type} resource '${reference.key}' cannot be resolved")
        }
    }

    /**
     * A reference inside content may omit its registry and namespace, each meaning the owning
     * catalog's. Whatever it names must be well-formed, and a registry needs a namespace: a
     * namespace is only meaningful within a registry, so naming another registry with the owning
     * catalog's namespace would be a coincidence of names, not a reference.
     */
    private fun validateContentIdentity(
        registry: String?,
        registryPath: String,
        namespace: String?,
        namespacePath: String,
        findings: MutableList<CatalogValidationFinding>,
    ) {
        if (registry != null && !CatalogNamespaces.isValidRegistry(registry)) {
            findings.error(CatalogValidationCodes.REGISTRY_INVALID, registryPath, "registry '$registry' is not a DNS name written in full")
        }
        if (namespace != null && !CatalogNamespaces.isValid(namespace)) {
            findings.error(CatalogValidationCodes.NAMESPACE_INVALID, namespacePath, "namespace '$namespace' is not a valid catalog namespace")
        }
        if (registry != null && namespace == null) {
            findings.error(CatalogValidationCodes.NAMESPACE_INVALID, namespacePath, "a reference naming registry '$registry' must name a namespace too")
        }
    }

    /**
     * Resolves within the catalog when the reference names no other catalog, and otherwise through
     * the consumer's resolver. A reference to another catalog reaches the resolver with the owning
     * catalog's registry, namespace or key filled in wherever it omitted one.
     */
    private fun resolveReference(
        reference: CatalogResourceReference,
        context: ResourceValidationContext,
    ): ResourceResolution {
        val otherRegistry = reference.registry != null && reference.registry != context.registry
        val otherNamespace = reference.namespace != null && reference.namespace != context.namespace
        val otherCatalog = reference.catalogKey != null && reference.catalogKey != context.catalogKey
        if (otherRegistry || otherNamespace || otherCatalog) {
            return context.dependencyResolver.resolve(
                reference.copy(
                    catalogKey = reference.catalogKey ?: context.catalogKey,
                    namespace = reference.namespace ?: context.namespace,
                    registry = reference.registry ?: context.registry,
                ),
            )
        }
        context.resources["${reference.type}/${reference.key}"] ?: return ResourceResolution.MISSING
        return ResourceResolution.PRESENT
    }

    private val MEDIA_TYPE = Regex("^[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+$")
}

/** The wire version from which every path into the catalog is held to [CatalogArchivePaths]. */
private const val ARCHIVE_PATHS_SINCE_WIRE_VERSION = 8

/**
 * Entry point for complete portable catalog validation.
 *
 * The stream overload first invokes
 * [app.epistola.catalog.archive.CatalogArchiveReader], while the in-memory
 * overload validates manifest/detail consistency, release metadata,
 * dependency closure, every resource, nested template documents, stencil
 * composition, and canonical fingerprints.
 *
 * Suite-specific authorization, persistence, conflict handling, installed
 * dependency policy, rendering, and publication state intentionally remain
 * outside this validator.
 */
object CatalogValidator {
    /**
     * Safely decodes and validates a catalog ZIP stream.
     *
     * Archive and semantic findings are combined in deterministic order. The
     * input is consumed and closed by the archive reader.
     *
     * @throws java.io.IOException for unrecoverable stream or temporary-storage
     *   failures.
     */
    fun validate(
        input: InputStream,
        policy: CatalogValidationPolicy = CatalogValidationPolicy(),
    ): CatalogValidationReport {
        val read = CatalogArchiveReader.read(input, policy.archive, policy.migration)
        val archiveFindings = read.findings.map {
            CatalogValidationFinding(it.code, ValidationSeverity.ERROR, it.path, it.message)
        }
        val archive = read.archive ?: return CatalogValidationReport(archiveFindings.sorted())
        return archive.use {
            CatalogValidationReport((archiveFindings + validate(it, policy).findings).sorted())
        }
    }

    /**
     * Validates an already decoded [catalog].
     *
     * The caller retains ownership and must close [catalog] when appropriate.
     */
    fun validate(
        catalog: CatalogArchive,
        policy: CatalogValidationPolicy = CatalogValidationPolicy(),
    ): CatalogValidationReport {
        val findings = mutableListOf<CatalogValidationFinding>()
        val manifest = catalog.manifest
        if (manifest.schemaVersion != CatalogWireSchema.CURRENT_VERSION) {
            findings.error(
                CatalogValidationCodes.MANIFEST_SCHEMA_UNSUPPORTED,
                "catalog.json.schemaVersion",
                "schemaVersion ${manifest.schemaVersion} is not current version ${CatalogWireSchema.CURRENT_VERSION}",
            )
        }
        validateRelease(catalog, policy, findings)
        validateCatalogDependencies(manifest, findings)
        validateDependencies(manifest, policy, findings)

        val manifestKeys = mutableSetOf<String>()
        val entriesByKey = linkedMapOf<String, ResourceEntry>()
        manifest.resources.forEachIndexed { index, entry ->
            val key = "${entry.type}/${entry.key}"
            if (!manifestKeys.add(key)) {
                findings.error(CatalogValidationCodes.MANIFEST_RESOURCE_DUPLICATE, "catalog.json.resources[$index]", "resource '$key' is duplicated")
            }
            entriesByKey[key] = entry
            val expectedPath = "resources/$key.json"
            // An image migrated from catalog v6 keeps its file under `resources/asset/`: the
            // migration renames the type and cannot move the file. Accepted so such an archive
            // stays installable; anything written at v7 uses the type's own directory.
            val legacyPath = if (entry.type == "image") "resources/asset/${entry.key}.json" else null
            val declaredPath = entry.detailUrl.removePrefix("./")
            // Likewise a key the v7-to-v8 migration padded or cut (CatalogKeys.migrate): the file
            // keeps the name the source key gave it.
            val migratedPath = declaredPath.takeIf { catalog.sourceSchemaVersion < ARCHIVE_PATHS_SINCE_WIRE_VERSION && isMigratedDetailPath(it, entry) }
            if (catalog.sourceSchemaVersion >= ARCHIVE_PATHS_SINCE_WIRE_VERSION && !CatalogArchivePaths.isValid(entry.detailUrl)) {
                findings.error(
                    CatalogValidationCodes.MANIFEST_DETAIL_URL_INVALID,
                    "catalog.json.resources[$index].detailUrl",
                    "detailUrl must be a relative path below the manifest's directory",
                )
            } else if (declaredPath != expectedPath && declaredPath != legacyPath && declaredPath != migratedPath) {
                findings.error(
                    CatalogValidationCodes.MANIFEST_DETAIL_PATH_INVALID,
                    "catalog.json.resources[$index].detailUrl",
                    "detailUrl must be './$expectedPath' or '$expectedPath'",
                )
            }
            if ((expectedPath !in catalog.paths && legacyPath !in catalog.paths && migratedPath !in catalog.paths) || key !in catalog.resourceDetails) {
                findings.error(CatalogValidationCodes.MANIFEST_DETAIL_MISSING, expectedPath, "manifest resource '$key' has no detail document")
            }
        }
        catalog.resourceDetails.forEach { (key, detail) ->
            val entry = entriesByKey[key]
            val path = "resources/$key.json"
            if (entry == null) {
                findings.error(CatalogValidationCodes.MANIFEST_DETAIL_UNDECLARED, path, "resource detail '$key' is not declared in the manifest")
            } else {
                if (detail.schemaVersion != manifest.schemaVersion) {
                    findings.error(CatalogValidationCodes.RESOURCE_SCHEMA_MISMATCH, "$path.schemaVersion", "resource and manifest schemaVersion differ")
                }
                if (detail.resource.type != entry.type) {
                    findings.error(CatalogValidationCodes.RESOURCE_TYPE_MISMATCH, "$path.resource.type", "resource type differs from manifest")
                }
                if (detail.resource.key != entry.key) {
                    findings.error(CatalogValidationCodes.RESOURCE_KEY_MISMATCH, "$path.resource.key", "resource key differs from manifest")
                }
                if (detail.resource.name != entry.name) {
                    findings.error(CatalogValidationCodes.RESOURCE_NAME_MISMATCH, "$path.resource.name", "resource name differs from manifest")
                }
                // A template's description is the source the manifest entry copies (wire v8).
                val description = (detail.resource as? TemplateResource)?.description
                if (description != null && entry.description != null && description != entry.description) {
                    findings.error(CatalogValidationCodes.RESOURCE_DESCRIPTION_MISMATCH, "$path.resource.description", "template description differs from manifest")
                }
            }
        }
        val resources = catalog.resourceDetails.mapValues { it.value.resource }
        validateCatalogInfo(manifest.catalog, resources, findings)
        val context = ResourceValidationContext(
            catalogKey = manifest.catalog.key,
            resources = resources,
            paths = catalog.paths,
            dependencyResolver = policy.dependencyResolver,
            namespace = manifest.catalog.namespace,
            registry = manifest.catalog.registry,
            sourceSchemaVersion = catalog.sourceSchemaVersion,
        )
        catalog.resourceDetails.toSortedMap().forEach { (key, detail) ->
            findings += ResourceValidator.validate(detail, context, "resources/$key.json").findings
        }
        validateStencilComposition(resources, manifest.catalog.key, manifest.catalog.namespace, manifest.catalog.registry, findings)
        return CatalogValidationReport(findings.distinct().sorted())
    }

    /**
     * Whether [declaredPath] is where a catalog written before wire v8 filed [entry] under the key
     * the v7-to-v8 migration rewrote ([CatalogKeys.migrate]): the type's directory (or `asset/` for an
     * image from catalog v6), named by the source key.
     */
    private fun isMigratedDetailPath(
        declaredPath: String,
        entry: ResourceEntry,
    ): Boolean {
        if (!declaredPath.endsWith(".json")) return false
        val directory = declaredPath.substringBeforeLast('/', "")
        val sourceKey = declaredPath.substringAfterLast('/').removeSuffix(".json")
        val directories = setOfNotNull("resources/${entry.type}", "resources/asset".takeIf { entry.type == "image" })
        return directory in directories && sourceKey != entry.key && CatalogKeys.migrate(sourceKey) == entry.key
    }

    private fun validateStencilComposition(
        resources: Map<String, CatalogResource>,
        catalogKey: String,
        namespace: String?,
        registry: String?,
        findings: MutableList<CatalogValidationFinding>,
    ) {
        val stencils = resources.values
            .filterIsInstance<StencilResource>()
            .associateBy { StencilResourceIdentity(it.key) }
        val edges = stencils.mapValues { (_, stencil) ->
            val parentByNode = stencil.content.slots.values
                .flatMap { slot -> slot.children.map { child -> child to slot.nodeId } }
                .toMap()

            fun hasStencilAncestor(node: Node): Boolean {
                val visited = mutableSetOf<String>()
                var parentId = parentByNode[node.id]
                while (parentId != null && visited.add(parentId)) {
                    val parent = stencil.content.nodes[parentId] ?: return false
                    if (parent.type == "stencil") return true
                    parentId = parentByNode[parent.id]
                }
                return false
            }

            stencil.content.nodes.values
                .filter { it.type == "stencil" && !hasStencilAncestor(it) }
                .sortedBy(Node::id)
                .mapNotNull { node ->
                    val stencilKey = node.props?.get("stencilKey") as? String ?: return@mapNotNull null
                    val referenceCatalog = node.props["catalogKey"] as? String ?: catalogKey
                    val referenceNamespace = node.props["namespace"] as? String ?: namespace
                    val referenceRegistry = node.props["registry"] as? String ?: registry
                    if (referenceCatalog != catalogKey || referenceNamespace != namespace || referenceRegistry != registry) {
                        return@mapNotNull null
                    }
                    StencilCompositionEdge(
                        target = StencilResourceIdentity(stencilKey),
                        path = "resources/stencil/${stencil.key}.json.resource.content.nodes.${node.id}.props.stencilKey",
                    )
                }
        }
        val greatestDepth = mutableMapOf<StencilResourceIdentity, Int>()

        fun visit(
            current: StencilResourceIdentity,
            ancestors: Set<StencilResourceIdentity>,
            depth: Int,
        ) {
            edges[current].orEmpty().forEach { edge ->
                val target = edge.target
                if (target in ancestors) {
                    findings.error(
                        TemplateValidationCodes.STENCIL_RECURSION,
                        edge.path,
                        "stencil '${target.key}' would contain itself transitively",
                    )
                    return@forEach
                }
                val nextDepth = depth + 1
                if (nextDepth > TemplateValidationLimits.MAX_STENCIL_NESTING_DEPTH) {
                    findings.error(
                        TemplateValidationCodes.STENCIL_NESTING_DEPTH_EXCEEDED,
                        edge.path,
                        "stencil nesting depth $nextDepth exceeds maximum ${TemplateValidationLimits.MAX_STENCIL_NESTING_DEPTH}",
                    )
                    return@forEach
                }
                if (target in stencils) {
                    val previousDepth = greatestDepth[target] ?: 0
                    if (nextDepth > previousDepth) {
                        greatestDepth[target] = nextDepth
                        visit(target, ancestors + target, nextDepth)
                    }
                }
            }
        }

        stencils.keys.sortedBy { it.key }.forEach { stencil ->
            if ((greatestDepth[stencil] ?: 0) < 1) {
                greatestDepth[stencil] = 1
                visit(stencil, setOf(stencil), 1)
            }
        }
    }

    private fun validateRelease(
        catalog: CatalogArchive,
        policy: CatalogValidationPolicy,
        findings: MutableList<CatalogValidationFinding>,
    ) {
        val release = catalog.manifest.release
        if (!SEMVER.matches(release.version)) {
            findings.error(CatalogValidationCodes.RELEASE_VERSION_INVALID, "catalog.json.release.version", "release version must be SemVer")
        }
        release.releasedAt?.let {
            if (runCatching { OffsetDateTime.parse(it) }.isFailure) {
                findings.error(CatalogValidationCodes.RELEASE_TIMESTAMP_INVALID, "catalog.json.release.releasedAt", "releasedAt must be an ISO-8601 offset timestamp")
            }
        }
        release.fingerprint?.let {
            // From wire v8 the fingerprint names its algorithm, and a release is fingerprinted with
            // the current one: an older algorithm covers less, so accepting it would verify less.
            // Before wire v8 it is bare hex and the wire version implies the algorithm.
            val qualified = catalog.sourceSchemaVersion >= V5_FINGERPRINT_SINCE_WIRE_VERSION
            val invalid = if (qualified) {
                QualifiedCatalogFingerprint.parse(it)?.version != CatalogCanonicalizer.CURRENT_FINGERPRINT_VERSION
            } else {
                !SHA256.matches(it)
            }
            if (invalid) {
                val message = if (qualified) {
                    "fingerprint must name the current algorithm: ${CatalogCanonicalizer.CURRENT_FINGERPRINT_VERSION.label}:<lowercase SHA-256 hex>"
                } else {
                    "fingerprint must be a lowercase SHA-256 hex string"
                }
                findings.error(CatalogValidationCodes.RELEASE_FINGERPRINT_INVALID, "catalog.json.release.fingerprint", message)
            } else if (policy.verifyFingerprint) {
                val matches = when {
                    qualified -> CatalogCanonicalizer.matchesQualifiedFingerprint(catalog, it)
                    // Migration may have rewritten keywords, or removed stencil versions, that the
                    // fingerprint was computed over.
                    catalog.sourceSchemaVersion >= V4_FINGERPRINT_SINCE_WIRE_VERSION ->
                        CatalogCanonicalizer.matchesSourceV4Fingerprint(catalog, it)
                    else -> CatalogCanonicalizer.matchesFingerprint(catalog, it)
                }
                if (!matches) {
                    findings.error(
                        CatalogValidationCodes.RELEASE_FINGERPRINT_MISMATCH,
                        "catalog.json.release.fingerprint",
                        "fingerprint does not match current or legacy canonical catalog content",
                    )
                }
            }
        }
        catalog.manifest.includes.orEmpty().forEachIndexed { index, include ->
            if (runCatching { URI(include.url) }.getOrNull()?.isAbsolute != true) {
                findings.error(CatalogValidationCodes.MANIFEST_DETAIL_PATH_INVALID, "catalog.json.includes[$index].url", "include URL must be absolute")
            }
        }
    }

    private fun validateCatalogInfo(
        catalog: CatalogInfo,
        resources: Map<String, CatalogResource>,
        findings: MutableList<CatalogValidationFinding>,
    ) {
        val seenAttributes = mutableSetOf<Pair<String, String>>()
        catalog.attributes.forEachIndexed { index, attribute ->
            val path = "catalog.json.catalog.attributes[$index]"
            if (!ATTRIBUTE_IDENTITY.matches(attribute.catalogKey)) {
                findings.error(
                    CatalogValidationCodes.CATALOG_ATTRIBUTE_IDENTITY_INVALID,
                    "$path.catalogKey",
                    "attribute catalogKey must contain lowercase letters, digits, and hyphens",
                )
            }
            if (!CatalogKeys.isValid(attribute.key)) {
                findings.error(
                    CatalogValidationCodes.CATALOG_ATTRIBUTE_IDENTITY_INVALID,
                    "$path.key",
                    "attribute key must be ${CatalogKeys.MIN_LENGTH} to ${CatalogKeys.MAX_LENGTH} characters matching ${CatalogKeys.PATTERN}",
                )
            }
            if (!seenAttributes.add(attribute.catalogKey to attribute.key)) {
                findings.error(
                    CatalogValidationCodes.CATALOG_ATTRIBUTE_DUPLICATE,
                    path,
                    "attribute '${attribute.catalogKey}.${attribute.key}' is duplicated",
                )
            }
        }
        CatalogKeywords.violations(catalog.keywords.toList()).forEach { violation ->
            findings.error(
                when (violation.rule) {
                    KeywordRule.INVALID -> CatalogValidationCodes.KEYWORD_INVALID
                    KeywordRule.TOO_LONG -> CatalogValidationCodes.KEYWORD_TOO_LONG
                    KeywordRule.DUPLICATE -> CatalogValidationCodes.KEYWORD_DUPLICATE
                    KeywordRule.LIMIT_EXCEEDED -> CatalogValidationCodes.KEYWORD_LIMIT_EXCEEDED
                },
                violation.index?.let { "catalog.json.catalog.keywords[$it]" } ?: "catalog.json.catalog.keywords",
                violation.message,
            )
        }
        catalog.license?.let { license ->
            if (license.name.isBlank() || license.name != license.name.trim()) {
                findings.error(
                    CatalogValidationCodes.CATALOG_LICENSE_INVALID,
                    "catalog.json.catalog.license.name",
                    "license name must be nonblank and must not contain leading or trailing whitespace",
                )
            }
            license.spdxExpression?.let { expression ->
                if (expression.isBlank() || expression != expression.trim()) {
                    findings.error(
                        CatalogValidationCodes.CATALOG_LICENSE_INVALID,
                        "catalog.json.catalog.license.spdxExpression",
                        "SPDX expression must be nonblank and must not contain leading or trailing whitespace",
                    )
                }
            }
            license.url?.let { url ->
                val uri = runCatching { URI(url) }.getOrNull()
                if (
                    url != url.trim() ||
                    uri?.isAbsolute != true ||
                    uri.scheme?.lowercase() !in setOf("http", "https") ||
                    uri.host.isNullOrBlank()
                ) {
                    findings.error(
                        CatalogValidationCodes.CATALOG_LICENSE_INVALID,
                        "catalog.json.catalog.license.url",
                        "license URL must be an absolute HTTP or HTTPS URL without surrounding whitespace",
                    )
                }
            }
            license.copyrightText?.let { copyright ->
                if (copyright.isBlank() || copyright != copyright.trim()) {
                    findings.error(
                        CatalogValidationCodes.CATALOG_LICENSE_INVALID,
                        "catalog.json.catalog.license.copyrightText",
                        "copyright text must be nonblank and must not contain leading or trailing whitespace",
                    )
                }
            }
        }
        val presentation = catalog.presentation ?: return
        presentation.iconImageKey?.let { key ->
            validatePresentationAsset(key, "catalog.json.catalog.presentation.iconImageKey", resources, findings)
        }
        val seen = mutableSetOf<String>()
        presentation.imageKeys.forEachIndexed { index, slug ->
            val path = "catalog.json.catalog.presentation.imageKeys[$index]"
            if (!seen.add(slug)) {
                findings.error(
                    CatalogValidationCodes.PRESENTATION_IMAGE_DUPLICATE,
                    path,
                    "gallery asset '$slug' is duplicated",
                )
            }
            validatePresentationAsset(slug, path, resources, findings)
        }
    }

    private fun validatePresentationAsset(
        slug: String,
        path: String,
        resources: Map<String, CatalogResource>,
        findings: MutableList<CatalogValidationFinding>,
    ) {
        val asset = resources["image/$slug"] as? ImageResource
        if (asset == null) {
            val code = if (resources.values.any { it.key == slug }) {
                CatalogValidationCodes.PRESENTATION_RESOURCE_NOT_ASSET
            } else {
                CatalogValidationCodes.PRESENTATION_ASSET_MISSING
            }
            val message = if (code == CatalogValidationCodes.PRESENTATION_RESOURCE_NOT_ASSET) {
                "presentation reference '$slug' does not resolve to an asset resource"
            } else {
                "presentation asset '$slug' cannot be resolved"
            }
            findings.error(code, path, message)
        } else if (!asset.mediaType.substringBefore('/').equals("image", ignoreCase = true)) {
            findings.error(
                CatalogValidationCodes.PRESENTATION_ASSET_MEDIA_TYPE_INVALID,
                path,
                "presentation asset '$slug' must declare an image media type",
            )
        }
    }

    /**
     * Wire v8: the catalog names its registry in full and its namespace, and declares each catalog it
     * depends on once, never itself, with a version selector and, unless migrated, the release it
     * was cut with and that release's fingerprint.
     */
    private fun validateCatalogDependencies(
        manifest: CatalogManifest,
        findings: MutableList<CatalogValidationFinding>,
    ) {
        val own = CatalogIdentity(manifest.catalog.registry, manifest.catalog.namespace, manifest.catalog.key)
        validateTopLevelIdentity(manifest.catalog.registry, manifest.catalog.namespace, "catalog.json.catalog", "catalog", findings)
        val seen = mutableSetOf<CatalogIdentity>()
        manifest.catalogDependencies.orEmpty().forEachIndexed { index, dependency ->
            val path = "catalog.json.catalogDependencies[$index]"
            val identity = CatalogIdentity(dependency.registry, dependency.namespace, dependency.catalogKey)
            validateTopLevelIdentity(dependency.registry, dependency.namespace, path, "catalog dependency", findings)
            if (!CatalogKeys.matches(CatalogKeys.CATALOG, dependency.catalogKey)) {
                findings.error(
                    CatalogValidationCodes.RESOURCE_KEY_INVALID,
                    "$path.catalogKey",
                    "catalogKey must be ${CatalogKeys.CATALOG.minLength} to ${CatalogKeys.CATALOG.maxLength} characters matching ${CatalogKeys.CATALOG.pattern}",
                )
            }
            if (identity == own) {
                findings.error(CatalogValidationCodes.DEPENDENCY_CATALOG_SELF, path, "a catalog cannot depend on itself")
            }
            if (!seen.add(identity)) {
                findings.error(
                    CatalogValidationCodes.DEPENDENCY_CATALOG_DUPLICATE,
                    path,
                    "catalog $identity is declared more than once",
                )
            }
            if (dependency.version.isBlank() || dependency.version.length > CatalogDependency.MAX_VERSION_LENGTH) {
                findings.error(
                    CatalogValidationCodes.DEPENDENCY_CATALOG_VERSION_INVALID,
                    "$path.version",
                    "version selector must be 1 to ${CatalogDependency.MAX_VERSION_LENGTH} characters and not blank",
                )
            }
            dependency.releaseVersion?.let { release ->
                if (!SEMVER.matches(release)) {
                    findings.error(CatalogValidationCodes.DEPENDENCY_CATALOG_VERSION_INVALID, "$path.releaseVersion", "releaseVersion must be SemVer")
                }
            }
            val fingerprintFinding = when {
                dependency.releaseVersion == null && dependency.releaseFingerprint != null ->
                    "releaseFingerprint must be null when no releaseVersion is recorded"
                dependency.releaseVersion != null && dependency.releaseFingerprint == null ->
                    "releaseFingerprint is required when a releaseVersion is recorded"
                dependency.releaseFingerprint != null && !CatalogDependency.isValidReleaseFingerprint(dependency.releaseFingerprint) ->
                    "releaseFingerprint must name a known algorithm: v5:<lowercase SHA-256 hex>"
                else -> null
            }
            fingerprintFinding?.let { findings.error(CatalogValidationCodes.DEPENDENCY_CATALOG_FINGERPRINT_INVALID, "$path.releaseFingerprint", it) }
        }
    }

    /** Where an archive names a catalog at its top level, both registry and namespace are required. */
    private fun validateTopLevelIdentity(
        registry: String?,
        namespace: String?,
        path: String,
        what: String,
        findings: MutableList<CatalogValidationFinding>,
    ) {
        when {
            registry == null -> findings.error(CatalogValidationCodes.REGISTRY_INVALID, "$path.registry", "$what names no registry")
            !CatalogNamespaces.isValidRegistry(registry) -> findings.error(
                CatalogValidationCodes.REGISTRY_INVALID,
                "$path.registry",
                "$what registry '$registry' must be a DNS name written in full",
            )
        }
        when {
            namespace == null -> findings.error(CatalogValidationCodes.NAMESPACE_INVALID, "$path.namespace", "$what names no namespace")
            !CatalogNamespaces.isValid(namespace) -> findings.error(
                CatalogValidationCodes.NAMESPACE_INVALID,
                "$path.namespace",
                "$what namespace '$namespace' must be a lowercase slug",
            )
        }
    }

    /** A catalog's identity: registry, namespace and key. Parts are null only in a malformed manifest. */
    private data class CatalogIdentity(
        val registry: String?,
        val namespace: String?,
        val catalogKey: String,
    ) {
        override fun toString(): String = "'$catalogKey' in namespace '$namespace' of registry '$registry'"
    }

    private fun validateDependencies(
        manifest: CatalogManifest,
        policy: CatalogValidationPolicy,
        findings: MutableList<CatalogValidationFinding>,
    ) {
        val own = CatalogIdentity(manifest.catalog.registry, manifest.catalog.namespace, manifest.catalog.key)
        val declared = manifest.catalogDependencies.orEmpty().mapTo(mutableSetOf()) { CatalogIdentity(it.registry, it.namespace, it.catalogKey) }
        val seen = mutableSetOf<String>()
        manifest.dependencies.orEmpty().forEachIndexed { index, dependency ->
            val path = "catalog.json.dependencies[$index]"
            val type = when (dependency) {
                is DependencyRef.Theme -> "theme"
                is DependencyRef.Stencil -> "stencil"
                is DependencyRef.CodeList -> "codeList"
                is DependencyRef.Font -> "font"
                is DependencyRef.Image -> "image"
            }
            val reference = CatalogResourceReference(type, dependency.key, dependency.catalogKey, dependency.namespace, dependency.registry)
            val key = "${reference.type}|${reference.registry.orEmpty()}|${reference.namespace.orEmpty()}|${reference.catalogKey.orEmpty()}|${reference.key}"
            if (!seen.add(key)) {
                findings.error(CatalogValidationCodes.DEPENDENCY_DUPLICATE, path, "dependency '$key' is duplicated")
            }
            if (!CatalogKeys.isValid(dependency.key)) {
                findings.error(
                    CatalogValidationCodes.RESOURCE_KEY_INVALID,
                    "$path.key",
                    "dependency key must be ${CatalogKeys.MIN_LENGTH} to ${CatalogKeys.MAX_LENGTH} characters matching ${CatalogKeys.PATTERN}",
                )
            }
            validateTopLevelIdentity(dependency.registry, dependency.namespace, path, "dependency", findings)
            val catalog = CatalogIdentity(dependency.registry, dependency.namespace, dependency.catalogKey)
            when {
                dependency.registry == null || dependency.namespace == null -> Unit
                catalog == own -> findings.error(
                    CatalogValidationCodes.DEPENDENCY_CATALOG_SELF,
                    path,
                    "dependency '$key' names the catalog itself",
                )
                catalog !in declared -> findings.error(
                    CatalogValidationCodes.DEPENDENCY_CATALOG_UNDECLARED,
                    path,
                    "dependency '$key' names catalog $catalog, which catalogDependencies does not declare",
                )
            }
            if (policy.dependencyResolver.resolve(reference) == ResourceResolution.MISSING) {
                findings.error(
                    CatalogValidationCodes.DEPENDENCY_REFERENCE_MISSING,
                    path,
                    "dependency '$key' cannot be resolved",
                )
            }
        }
    }

    private val SEMVER = Regex(
        "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$",
    )
    private val SHA256 = Regex("^[0-9a-f]{64}$")

    /** Wire version from which a catalog must carry a V4 fingerprint. */
    private const val V4_FINGERPRINT_SINCE_WIRE_VERSION = 6

    /** Wire version from which a catalog must carry a V5 fingerprint. */
    private const val V5_FINGERPRINT_SINCE_WIRE_VERSION = 8
    private val ATTRIBUTE_IDENTITY = Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")

    private data class StencilResourceIdentity(
        val key: String,
    )

    private data class StencilCompositionEdge(
        val target: StencilResourceIdentity,
        val path: String,
    )
}

private object PortableJsonSchema {
    private val mapper = jsonMapper { addModule(kotlinModule()) }
    private val schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
        builder.schemas(
            listOf(
                "https://epistola.app/schemas/richtext-inline-v1.json",
                "https://epistola.app/schemas/richtext-block-v1.json",
            ).associateWith { id ->
                val filename = id.substringAfterLast("/")
                requireNotNull(
                    PortableJsonSchema::class.java.getResource(
                        "/META-INF/epistola-catalog/schemas/$filename",
                    ),
                ) { "Missing bundled catalog schema $filename" }.readText()
            },
        )
    }

    fun checkSchema(schema: Map<String, Any?>): List<String> = runCatching {
        schemaRegistry.getSchema(mapper.writeValueAsString(schema))
    }.fold(
        onSuccess = { emptyList() },
        onFailure = { listOf("invalid JSON Schema: ${it.message}") },
    )

    /**
     * [data] with each absent property's `default` filled in, recursing into nested objects. A present
     * value, an explicit `null` included, is kept: a `default` stands in only for an absent key.
     * Composition keywords (`allOf`, `oneOf`, `anyOf`) are not expanded.
     */
    @Suppress("UNCHECKED_CAST")
    fun withDefaults(
        data: Map<String, Any?>,
        schema: Map<String, Any?>,
    ): Map<String, Any?> {
        val properties = schema["properties"] as? Map<String, Any?> ?: return data
        val result = LinkedHashMap(data)
        for ((name, property) in properties) {
            val propertySchema = property as? Map<String, Any?> ?: continue
            if (name !in result && "default" in propertySchema) result[name] = propertySchema["default"]
            (result[name] as? Map<String, Any?>)?.let { nested -> result[name] = withDefaults(nested, propertySchema) }
        }
        return result
    }

    fun validate(
        value: Any?,
        schema: Map<String, Any?>,
    ): List<String> {
        val schemaNode = mapper.valueToTree<ObjectNode>(schema)
        relaxDateTimeInPlace(schemaNode)
        val jsonSchema = schemaRegistry.getSchema(mapper.writeValueAsString(schemaNode))
        return jsonSchema.validate(mapper.writeValueAsString(value), InputFormat.JSON)
            .map { "${it.instanceLocation}: ${it.message}" }
            .sorted()
    }

    private fun relaxDateTimeInPlace(node: JsonNode) {
        when (node) {
            is ObjectNode -> {
                val format = node.get("format")
                if (format?.isString == true && format.asString() == "date-time") {
                    node.remove("format")
                    if (!node.has("pattern")) node.put("pattern", LENIENT_DATE_TIME_PATTERN)
                }
                node.properties().forEach { (_, child) -> relaxDateTimeInPlace(child) }
            }
            is ArrayNode -> node.forEach(::relaxDateTimeInPlace)
            else -> Unit
        }
    }

    private const val LENIENT_DATE_TIME_PATTERN =
        "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:\\d{2})?$"
}

/** Reports [key] at [path] unless it satisfies the one resource key rule ([CatalogKeys.RESOURCE]). */
private fun validateKey(
    key: String,
    path: String,
    findings: MutableList<CatalogValidationFinding>,
    what: String = "key",
) {
    if (!CatalogKeys.isValid(key)) {
        findings.error(
            CatalogValidationCodes.RESOURCE_KEY_INVALID,
            path,
            "$what must be ${CatalogKeys.MIN_LENGTH} to ${CatalogKeys.MAX_LENGTH} characters matching ${CatalogKeys.PATTERN}",
        )
    }
}

private fun MutableList<CatalogValidationFinding>.error(
    code: String,
    path: String,
    message: String,
) {
    add(CatalogValidationFinding(code, ValidationSeverity.ERROR, path, message))
}

private fun List<CatalogValidationFinding>.sorted(): List<CatalogValidationFinding> = sortedWith(
    compareBy({ it.path }, { it.code }, { it.severity }, { it.message }),
)
