package app.epistola.catalog.validation

import app.epistola.catalog.archive.CatalogArchive
import app.epistola.catalog.archive.CatalogArchivePolicy
import app.epistola.catalog.archive.CatalogArchiveReader
import app.epistola.catalog.canonical.CatalogCanonicalizer
import app.epistola.catalog.migration.CatalogWireSchema
import app.epistola.catalog.protocol.AssetResource
import app.epistola.catalog.protocol.AttributeResource
import app.epistola.catalog.protocol.CatalogResource
import app.epistola.catalog.protocol.CodeListResource
import app.epistola.catalog.protocol.DependencyRef
import app.epistola.catalog.protocol.FontResource
import app.epistola.catalog.protocol.ResourceDetail
import app.epistola.catalog.protocol.ResourceEntry
import app.epistola.catalog.protocol.StencilResource
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.catalog.protocol.ThemeResource
import app.epistola.template.model.TemplateDocument
import java.io.InputStream
import java.net.URI
import java.time.OffsetDateTime

data class CatalogValidationFinding(
    val code: String,
    val severity: ValidationSeverity,
    val path: String,
    val message: String,
)

data class CatalogValidationReport(
    val findings: List<CatalogValidationFinding>,
) {
    val valid: Boolean get() = findings.none { it.severity == ValidationSeverity.ERROR }
}

fun interface CatalogDependencyResolver {
    fun resolve(reference: CatalogResourceReference): ResourceResolution

    companion object {
        val UNKNOWN = CatalogDependencyResolver { ResourceResolution.UNKNOWN }
    }
}

data class CatalogValidationPolicy(
    val archive: CatalogArchivePolicy = CatalogArchivePolicy(),
    val dependencyResolver: CatalogDependencyResolver = CatalogDependencyResolver.UNKNOWN,
    val verifyFingerprint: Boolean = true,
)

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
    const val ASSET_PATH_INVALID = "CATALOG_ASSET_PATH_INVALID"
    const val ASSET_FILE_MISSING = "CATALOG_ASSET_FILE_MISSING"
    const val ASSET_MEDIA_TYPE_INVALID = "CATALOG_ASSET_MEDIA_TYPE_INVALID"
    const val ASSET_DIMENSIONS_INVALID = "CATALOG_ASSET_DIMENSIONS_INVALID"
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

data class ResourceValidationContext(
    val catalogKey: String,
    val resources: Map<String, CatalogResource>,
    val paths: Set<String>,
    val dependencyResolver: CatalogDependencyResolver = CatalogDependencyResolver.UNKNOWN,
)

object ResourceValidator {
    fun validate(
        detail: ResourceDetail,
        context: ResourceValidationContext,
        path: String,
    ): CatalogValidationReport {
        val findings = mutableListOf<CatalogValidationFinding>()
        val resource = detail.resource
        if (!SLUG.matches(resource.slug)) {
            findings.error(CatalogValidationCodes.RESOURCE_SLUG_INVALID, "$path.resource.slug", "slug must contain lowercase letters, digits, and hyphens")
        }
        when (resource) {
            is TemplateResource -> validateTemplate(resource, context, "$path.resource", findings)
            is StencilResource -> validateDocument(resource.content, context, "$path.resource.content", true, findings)
            is ThemeResource -> validateTheme(resource, context, "$path.resource", findings)
            is AttributeResource -> validateAttribute(resource, context, "$path.resource", findings)
            is CodeListResource -> validateCodeList(resource, "$path.resource", findings)
            is AssetResource -> validateAsset(resource, context, "$path.resource", findings)
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
        validateDocument(resource.templateModel, context, "$path.templateModel", false, findings)
        val variantIds = mutableSetOf<String>()
        resource.variants.forEachIndexed { index, variant ->
            if (!variantIds.add(variant.id)) {
                findings.error(CatalogValidationCodes.TEMPLATE_VARIANT_ID_DUPLICATE, "$path.variants[$index].id", "variant id '${variant.id}' is duplicated")
            }
            variant.templateModel?.let { validateDocument(it, context, "$path.variants[$index].templateModel", false, findings) }
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
        stencil: Boolean,
        findings: MutableList<CatalogValidationFinding>,
    ) {
        val report = TemplateValidator.validate(
            document,
            object : TemplateValidationContext {
                override val documentKind = if (stencil) TemplateDocumentKind.STENCIL else TemplateDocumentKind.TEMPLATE

                override fun resolveResource(reference: CatalogResourceReference): ResourceResolution = if (
                    reference.catalogKey == null ||
                    reference.catalogKey == context.catalogKey
                ) {
                    if ("${reference.type}/${reference.slug}" in context.resources) ResourceResolution.PRESENT else ResourceResolution.MISSING
                } else {
                    context.dependencyResolver.resolve(reference)
                }
            },
        )
        findings += report.findings.map {
            val relativePath = it.path.removePrefix("$").removePrefix(".")
            CatalogValidationFinding(
                it.code,
                it.severity,
                if (relativePath.isEmpty()) path else "$path.$relativePath",
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
            @Suppress("UNCHECKED_CAST")
            validateFontRefs(value as? Map<String, Any?>, context, "$path.blockStylePresets.$name", findings)
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

    private fun validateAsset(
        resource: AssetResource,
        context: ResourceValidationContext,
        path: String,
        findings: MutableList<CatalogValidationFinding>,
    ) {
        val contentPath = resource.contentUrl.removePrefix("./")
        if (!resource.contentUrl.startsWith("./resources/asset/") || '\\' in resource.contentUrl || ".." in resource.contentUrl.split('/')) {
            findings.error(CatalogValidationCodes.ASSET_PATH_INVALID, "$path.contentUrl", "asset contentUrl must be a relative resources/asset path")
        } else if (contentPath !in context.paths) {
            findings.error(CatalogValidationCodes.ASSET_FILE_MISSING, "$path.contentUrl", "asset content file '$contentPath' is missing")
        }
        if (!MEDIA_TYPE.matches(resource.mediaType)) {
            findings.error(CatalogValidationCodes.ASSET_MEDIA_TYPE_INVALID, "$path.mediaType", "mediaType is not a valid type/subtype")
        }
        if ((resource.width != null && resource.width <= 0) || (resource.height != null && resource.height <= 0)) {
            findings.error(CatalogValidationCodes.ASSET_DIMENSIONS_INVALID, path, "asset dimensions must be positive")
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
            validateReference("asset", face.assetSlug, null, context, "$path.variants[$index].assetSlug", findings)
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
        val resolution = if (catalogKey == null || catalogKey == context.catalogKey) {
            if ("$type/$slug" in context.resources) ResourceResolution.PRESENT else ResourceResolution.MISSING
        } else {
            context.dependencyResolver.resolve(CatalogResourceReference(type, slug, catalogKey))
        }
        if (resolution == ResourceResolution.MISSING) {
            findings.error(CatalogValidationCodes.RESOURCE_REFERENCE_MISSING, path, "$type resource '$slug' cannot be resolved")
        }
    }

    private val SLUG = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
    private val MEDIA_TYPE = Regex("^[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+$")
}

object CatalogValidator {
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
            if (entry.detailUrl.removePrefix("./") != expectedPath) {
                findings.error(
                    CatalogValidationCodes.MANIFEST_DETAIL_PATH_INVALID,
                    "catalog.json.resources[$index].detailUrl",
                    "detailUrl must be './$expectedPath' or '$expectedPath'",
                )
            }
            if (expectedPath !in catalog.paths || key !in catalog.resourceDetails) {
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
        val context = ResourceValidationContext(manifest.catalog.slug, resources, catalog.paths, policy.dependencyResolver)
        catalog.resourceDetails.toSortedMap().forEach { (key, detail) ->
            findings += ResourceValidator.validate(detail, context, "resources/$key.json").findings
        }
        return CatalogValidationReport(findings.sorted())
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
            } else if (policy.verifyFingerprint && CatalogCanonicalizer.fingerprint(catalog).value != it) {
                findings.error(CatalogValidationCodes.RELEASE_FINGERPRINT_MISMATCH, "catalog.json.release.fingerprint", "fingerprint does not match canonical catalog content")
            }
        }
        catalog.manifest.includes.orEmpty().forEachIndexed { index, include ->
            if (runCatching { URI(include.url) }.getOrNull()?.isAbsolute != true) {
                findings.error(CatalogValidationCodes.MANIFEST_DETAIL_PATH_INVALID, "catalog.json.includes[$index].url", "include URL must be absolute")
            }
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
                is DependencyRef.Asset -> CatalogResourceReference("asset", dependency.slug)
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
}

private object PortableJsonSchema {
    private val supportedTypes = setOf("object", "array", "string", "number", "integer", "boolean", "null")

    fun checkSchema(schema: Map<String, Any?>): List<String> {
        val errors = mutableListOf<String>()
        val type = schema["type"]
        if (type != null && type !is String) errors += "schema type must be a string"
        if (type is String && type !in supportedTypes) errors += "schema type '$type' is unsupported"
        val properties = schema["properties"]
        if (properties != null && properties !is Map<*, *>) errors += "schema properties must be an object"
        val required = schema["required"]
        if (required != null && (required !is List<*> || required.any { it !is String })) errors += "schema required must be an array of strings"
        if (properties is Map<*, *> && required is List<*>) {
            required.filterIsInstance<String>().filterNot(properties::containsKey).forEach { errors += "required property '$it' is not declared" }
        }
        return errors
    }

    fun validate(
        value: Any?,
        schema: Map<String, Any?>,
        path: String = "$",
    ): List<String> {
        val errors = mutableListOf<String>()
        when (schema["type"] as? String) {
            "object" -> if (value !is Map<*, *>) errors += "$path must be an object"
            "array" -> if (value !is List<*>) errors += "$path must be an array"
            "string" -> if (value !is String) errors += "$path must be a string"
            "number" -> if (value !is Number) errors += "$path must be a number"
            "integer" -> if (value !is Byte && value !is Short && value !is Int && value !is Long) errors += "$path must be an integer"
            "boolean" -> if (value !is Boolean) errors += "$path must be a boolean"
            "null" -> if (value != null) errors += "$path must be null"
        }
        if (value is Map<*, *>) {
            val required = (schema["required"] as? List<*>)?.filterIsInstance<String>().orEmpty()
            required.filterNot(value::containsKey).forEach { errors += "$path.$it is required" }
            val properties = schema["properties"] as? Map<*, *>
            properties.orEmpty().forEach { (key, childSchema) ->
                if (key is String && childSchema is Map<*, *> && value.containsKey(key)) {
                    @Suppress("UNCHECKED_CAST")
                    errors += validate(value[key], childSchema as Map<String, Any?>, "$path.$key")
                }
            }
        }
        if (value is List<*>) {
            @Suppress("UNCHECKED_CAST")
            val items = schema["items"] as? Map<String, Any?>
            if (items != null) value.forEachIndexed { index, item -> errors += validate(item, items, "$path[$index]") }
        }
        return errors
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
