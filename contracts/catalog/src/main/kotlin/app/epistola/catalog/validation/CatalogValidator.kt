// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.validation

import app.epistola.catalog.archive.ArchiveContentProvider
import app.epistola.catalog.archive.CatalogArchive
import app.epistola.catalog.archive.CatalogArchivePolicy
import app.epistola.catalog.archive.CatalogArchiveReader
import app.epistola.catalog.canonical.CatalogCanonicalizer
import app.epistola.catalog.canonical.CatalogFingerprintVersion
import app.epistola.catalog.migration.CatalogWireSchema
import app.epistola.catalog.protocol.AttributeResource
import app.epistola.catalog.protocol.BinaryRef
import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.CatalogKeywords
import app.epistola.catalog.protocol.CatalogResource
import app.epistola.catalog.protocol.CatalogSlugs
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
 */
data class CatalogValidationPolicy(
    val archive: CatalogArchivePolicy = CatalogArchivePolicy(),
    val dependencyResolver: CatalogDependencyResolver = CatalogDependencyResolver.UNKNOWN,
    val verifyFingerprint: Boolean = true,
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
    const val RESOURCE_SLUG_MISMATCH = "CATALOG_RESOURCE_SLUG_MISMATCH"
    const val RESOURCE_NAME_MISMATCH = "CATALOG_RESOURCE_NAME_MISMATCH"
    const val RESOURCE_SLUG_INVALID = "CATALOG_RESOURCE_SLUG_INVALID"
    const val RESOURCE_REFERENCE_MISSING = "CATALOG_RESOURCE_REFERENCE_MISSING"
    const val DEPENDENCY_REFERENCE_MISSING = "CATALOG_DEPENDENCY_REFERENCE_MISSING"
    const val DEPENDENCY_DUPLICATE = "CATALOG_DEPENDENCY_DUPLICATE"
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
    const val TEMPLATE_DATA_SCHEMA_INVALID = "CATALOG_TEMPLATE_DATA_SCHEMA_INVALID"
    const val TEMPLATE_DATA_EXAMPLE_INVALID = "CATALOG_TEMPLATE_DATA_EXAMPLE_INVALID"
    const val TEMPLATE_VARIANT_ID_DUPLICATE = "CATALOG_TEMPLATE_VARIANT_ID_DUPLICATE"
    const val TEMPLATE_VARIANT_DEFAULT_INVALID = "CATALOG_TEMPLATE_VARIANT_DEFAULT_INVALID"
}

/**
 * In-memory catalog graph supplied to [ResourceValidator].
 *
 * @property catalogKey identity used for same-catalog references.
 * @property resources resources keyed by `type/slug`.
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
        // Per type, not one loose rule for all of them: the bounds mirror the columns a consumer
        // stores a slug in, so a name that passes here is one every consumer can actually hold.
        // Checking the loose rule instead let a catalog publish a 30-character theme slug that
        // then failed on install with a database error, with no diagnosis in between.
        val rule = CatalogSlugs.byType[resource.type] ?: CatalogSlugs.ANY
        if (!CatalogSlugs.matches(rule, resource.slug)) {
            findings.error(
                CatalogValidationCodes.RESOURCE_SLUG_INVALID,
                "$path.resource.slug",
                "slug must be ${rule.minLength} to ${rule.maxLength} characters matching ${rule.pattern}",
            )
        }
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
        resource.themeId?.let { themeId ->
            validateReference("theme", themeId, resource.themeCatalogKey, context, "$path.themeId", findings)
        }
        validateDocument(resource.templateModel, context, "$path.templateModel", null, findings)
        val variantSlugs = mutableSetOf<String>()
        resource.variants.forEachIndexed { index, variant ->
            if (!variantSlugs.add(variant.slug)) {
                findings.error(CatalogValidationCodes.TEMPLATE_VARIANT_ID_DUPLICATE, "$path.variants[$index].slug", "variant slug '${variant.slug}' is duplicated")
            }
            variant.templateModel?.let { validateDocument(it, context, "$path.variants[$index].templateModel", null, findings) }
        }
        val defaults = resource.variants.count { it.isDefault }
        if (defaults > 1) {
            findings.error(CatalogValidationCodes.TEMPLATE_VARIANT_DEFAULT_INVALID, "$path.variants", "at most one variant may be the default")
        }
        val exampleNames = mutableSetOf<String>()
        resource.dataExamples.orEmpty().forEachIndexed { index, example ->
            if (!exampleNames.add(example.name)) {
                findings.error(
                    CatalogValidationCodes.TEMPLATE_EXAMPLE_NAME_DUPLICATE,
                    "$path.dataExamples[$index].name",
                    "data example name '${example.name}' is duplicated",
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
                    PortableJsonSchema.validate(example.data, schema).forEach { message ->
                        findings.error(CatalogValidationCodes.TEMPLATE_DATA_EXAMPLE_INVALID, "$path.dataExamples[$index].data", message)
                    }
                }
            }
        }
    }

    private fun validateDocument(
        document: TemplateDocument,
        context: ResourceValidationContext,
        path: String,
        containingStencil: StencilResource?,
        findings: MutableList<CatalogValidationFinding>,
    ) {
        val report = TemplateValidator.validate(
            document,
            object : TemplateValidationContext {
                override val documentKind =
                    if (containingStencil == null) TemplateDocumentKind.TEMPLATE else TemplateDocumentKind.STENCIL
                override val currentCatalogKey: String = context.catalogKey
                override val containingStencil: CatalogResourceReference? = containingStencil?.let {
                    CatalogResourceReference("stencil", it.slug, context.catalogKey, it.version)
                }
                override val allowDraftStencilReferences: Boolean = false

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
        val slug = font["slug"] as? String ?: return
        val catalogKey = font["catalogKey"] as? String
        validateReference("font", slug, catalogKey, context, "$path.fontFamily", findings)
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
            validateReference("codeList", it.slug, it.catalogKey, context, "$path.codeListBinding", findings)
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
        if (contentUrl != null && (!contentUrl.startsWith("./") || '\\' in contentUrl || ".." in contentUrl.split('/'))) {
            findings.error(CatalogValidationCodes.ASSET_PATH_INVALID, "$path.contentUrl", "contentUrl must be a safe relative archive path")
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
        type: String,
        slug: String,
        catalogKey: String?,
        context: ResourceValidationContext,
        path: String,
        findings: MutableList<CatalogValidationFinding>,
    ) {
        val resolution = resolveReference(CatalogResourceReference(type, slug, catalogKey), context)
        if (resolution == ResourceResolution.MISSING) {
            findings.error(CatalogValidationCodes.RESOURCE_REFERENCE_MISSING, path, "$type resource '$slug' cannot be resolved")
        }
    }

    private fun resolveReference(
        reference: CatalogResourceReference,
        context: ResourceValidationContext,
    ): ResourceResolution {
        if (reference.catalogKey != null && reference.catalogKey != context.catalogKey) {
            return context.dependencyResolver.resolve(reference)
        }
        val resource = context.resources["${reference.type}/${reference.slug}"]
            ?: return ResourceResolution.MISSING
        if (
            reference.type == "stencil" &&
            reference.version != null &&
            (resource as? StencilResource)?.version != reference.version
        ) {
            return ResourceResolution.MISSING
        }
        return ResourceResolution.PRESENT
    }

    private val MEDIA_TYPE = Regex("^[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+$")
}

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
        val read = CatalogArchiveReader.read(input, policy.archive)
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
        validateDependencies(manifest.dependencies.orEmpty(), policy, findings)

        val manifestKeys = mutableSetOf<String>()
        val entriesByKey = linkedMapOf<String, ResourceEntry>()
        manifest.resources.forEachIndexed { index, entry ->
            val key = "${entry.type}/${entry.slug}"
            if (!manifestKeys.add(key)) {
                findings.error(CatalogValidationCodes.MANIFEST_RESOURCE_DUPLICATE, "catalog.json.resources[$index]", "resource '$key' is duplicated")
            }
            entriesByKey[key] = entry
            val expectedPath = "resources/$key.json"
            // An image migrated from catalog v6 keeps its file under `resources/asset/`: the
            // migration renames the type and cannot move the file. Accepted so such an archive
            // stays installable; anything written at v7 uses the type's own directory.
            val legacyPath = if (entry.type == "image") "resources/asset/${entry.slug}.json" else null
            val declaredPath = entry.detailUrl.removePrefix("./")
            if (declaredPath != expectedPath && declaredPath != legacyPath) {
                findings.error(
                    CatalogValidationCodes.MANIFEST_DETAIL_PATH_INVALID,
                    "catalog.json.resources[$index].detailUrl",
                    "detailUrl must be './$expectedPath' or '$expectedPath'",
                )
            }
            if ((expectedPath !in catalog.paths && legacyPath !in catalog.paths) || key !in catalog.resourceDetails) {
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
                if (detail.resource.slug != entry.slug) {
                    findings.error(CatalogValidationCodes.RESOURCE_SLUG_MISMATCH, "$path.resource.slug", "resource slug differs from manifest")
                }
                if (detail.resource.name != entry.name) {
                    findings.error(CatalogValidationCodes.RESOURCE_NAME_MISMATCH, "$path.resource.name", "resource name differs from manifest")
                }
            }
        }
        val resources = catalog.resourceDetails.mapValues { it.value.resource }
        validateCatalogInfo(manifest.catalog, resources, findings)
        val context = ResourceValidationContext(manifest.catalog.slug, resources, catalog.paths, policy.dependencyResolver)
        catalog.resourceDetails.toSortedMap().forEach { (key, detail) ->
            findings += ResourceValidator.validate(detail, context, "resources/$key.json").findings
        }
        validateStencilComposition(resources, manifest.catalog.slug, findings)
        return CatalogValidationReport(findings.distinct().sorted())
    }

    private fun validateStencilComposition(
        resources: Map<String, CatalogResource>,
        catalogKey: String,
        findings: MutableList<CatalogValidationFinding>,
    ) {
        val stencils = resources.values
            .filterIsInstance<StencilResource>()
            .associateBy { StencilResourceIdentity(it.slug, it.version) }
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
                    val slug = node.props?.get("stencilId") as? String ?: return@mapNotNull null
                    val version = (node.props["version"] as? Number)?.toInt() ?: return@mapNotNull null
                    val referenceCatalog = node.props["catalogKey"] as? String ?: catalogKey
                    if (referenceCatalog != catalogKey) return@mapNotNull null
                    StencilCompositionEdge(
                        target = StencilResourceIdentity(slug, version),
                        path = "resources/stencil/${stencil.slug}.json.resource.content.nodes.${node.id}.props.stencilId",
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
                        "stencil '${target.slug}' would contain itself transitively",
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

        stencils.keys.sortedWith(compareBy({ it.slug }, { it.version })).forEach { stencil ->
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
            if (!SHA256.matches(it)) {
                findings.error(CatalogValidationCodes.RELEASE_FINGERPRINT_INVALID, "catalog.json.release.fingerprint", "fingerprint must be a lowercase SHA-256 hex string")
            } else if (policy.verifyFingerprint) {
                val matches = when {
                    catalog.sourceSchemaVersion >= CatalogWireSchema.CURRENT_VERSION ->
                        CatalogCanonicalizer.matchesFingerprint(catalog, it, CatalogFingerprintVersion.V4)
                    // Migration may have rewritten keywords the fingerprint was computed over.
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
            if (!ATTRIBUTE_IDENTITY.matches(attribute.catalog)) {
                findings.error(
                    CatalogValidationCodes.CATALOG_ATTRIBUTE_IDENTITY_INVALID,
                    "$path.catalog",
                    "attribute catalog must contain lowercase letters, digits, and hyphens",
                )
            }
            if (!ATTRIBUTE_IDENTITY.matches(attribute.key)) {
                findings.error(
                    CatalogValidationCodes.CATALOG_ATTRIBUTE_IDENTITY_INVALID,
                    "$path.key",
                    "attribute key must contain lowercase letters, digits, and hyphens",
                )
            }
            if (!seenAttributes.add(attribute.catalog to attribute.key)) {
                findings.error(
                    CatalogValidationCodes.CATALOG_ATTRIBUTE_DUPLICATE,
                    path,
                    "attribute '${attribute.catalog}.${attribute.key}' is duplicated",
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
        presentation.iconAssetSlug?.let { slug ->
            validatePresentationAsset(slug, "catalog.json.catalog.presentation.iconAssetSlug", resources, findings)
        }
        val seen = mutableSetOf<String>()
        presentation.imageAssetSlugs.forEachIndexed { index, slug ->
            val path = "catalog.json.catalog.presentation.imageAssetSlugs[$index]"
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
            val code = if (resources.values.any { it.slug == slug }) {
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

    private fun validateDependencies(
        dependencies: List<DependencyRef>,
        policy: CatalogValidationPolicy,
        findings: MutableList<CatalogValidationFinding>,
    ) {
        val seen = mutableSetOf<String>()
        dependencies.forEachIndexed { index, dependency ->
            val reference = when (dependency) {
                is DependencyRef.Theme -> CatalogResourceReference("theme", dependency.slug, dependency.catalogKey)
                is DependencyRef.Stencil -> CatalogResourceReference("stencil", dependency.slug, dependency.catalogKey)
                is DependencyRef.CodeList -> CatalogResourceReference("codeList", dependency.slug, dependency.catalogKey)
                is DependencyRef.Font -> CatalogResourceReference("font", dependency.slug, dependency.catalogKey)
                is DependencyRef.Image -> CatalogResourceReference("image", dependency.slug, dependency.catalogKey)
            }
            val key = "${reference.type}|${reference.catalogKey.orEmpty()}|${reference.slug}"
            if (!seen.add(key)) {
                findings.error(CatalogValidationCodes.DEPENDENCY_DUPLICATE, "catalog.json.dependencies[$index]", "dependency '$key' is duplicated")
            }
            if (policy.dependencyResolver.resolve(reference) == ResourceResolution.MISSING) {
                findings.error(
                    CatalogValidationCodes.DEPENDENCY_REFERENCE_MISSING,
                    "catalog.json.dependencies[$index]",
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
    private val ATTRIBUTE_IDENTITY = Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")

    private data class StencilResourceIdentity(
        val slug: String,
        val version: Int,
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
