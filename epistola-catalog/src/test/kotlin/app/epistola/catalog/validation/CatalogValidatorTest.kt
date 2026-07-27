package app.epistola.catalog.validation

import app.epistola.catalog.archive.ArchiveContentProvider
import app.epistola.catalog.archive.CatalogArchive
import app.epistola.catalog.protocol.AssetResource
import app.epistola.catalog.protocol.AttributeResource
import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.CodeListBindingRef
import app.epistola.catalog.protocol.CodeListEntryEntry
import app.epistola.catalog.protocol.CodeListResource
import app.epistola.catalog.protocol.DataExampleEntry
import app.epistola.catalog.protocol.FontResource
import app.epistola.catalog.protocol.FontVariantEntry
import app.epistola.catalog.protocol.PublisherInfo
import app.epistola.catalog.protocol.ReleaseInfo
import app.epistola.catalog.protocol.ResourceDetail
import app.epistola.catalog.protocol.ResourceEntry
import app.epistola.catalog.protocol.StencilResource
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.catalog.protocol.VariantEntry
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogValidatorTest {
    private val mapper = jsonMapper { addModule(kotlinModule()) }

    @Test
    fun `golden current catalog is valid`() {
        val manifest = fixture("wire-v4/catalog.json").use { mapper.readValue(it, CatalogManifest::class.java) }
        val detail = fixture("wire-v4/resources/theme/default.json").use { mapper.readValue(it, ResourceDetail::class.java) }
        val archive = archive(manifest, mapOf("theme/default" to detail))

        assertEquals(emptyList(), CatalogValidator.validate(archive).findings)
    }

    @Test
    fun `manifest and detail findings aggregate deterministically`() {
        val detail = ResourceDetail(3, AssetResource("Bad Slug", "Different", "bad", width = 0, contentUrl = "../asset"))
        val manifest = manifest(
            ReleaseInfo("latest", "yesterday", "bad"),
            listOf(ResourceEntry("theme", "declared", "Declared", detailUrl = "wrong.json")),
        )
        val report = CatalogValidator.validate(archive(manifest, mapOf("asset/actual" to detail)))

        assertTrue(CatalogValidationCodes.RELEASE_VERSION_INVALID in report.codes())
        assertTrue(CatalogValidationCodes.RELEASE_TIMESTAMP_INVALID in report.codes())
        assertTrue(CatalogValidationCodes.RELEASE_FINGERPRINT_INVALID in report.codes())
        assertTrue(CatalogValidationCodes.MANIFEST_DETAIL_MISSING in report.codes())
        assertTrue(CatalogValidationCodes.MANIFEST_DETAIL_UNDECLARED in report.codes())
        assertTrue(CatalogValidationCodes.RESOURCE_SLUG_INVALID in report.codes())
        assertTrue(CatalogValidationCodes.ASSET_PATH_INVALID in report.codes())
        assertEquals(report.findings.sortedWith(compareBy({ it.path }, { it.code }, { it.severity }, { it.message })), report.findings)
    }

    @Test
    fun `template validator runs for template stencil and variant documents`() {
        val invalid = validDocument().copy(root = "missing")
        val template = TemplateResource(
            "invoice",
            "Invoice",
            templateModel = invalid,
            variants = listOf(VariantEntry("print", templateModel = invalid)),
        )
        val stencil = StencilResource("address", "Address", 1, content = invalid)
        val details = mapOf(
            "template/invoice" to ResourceDetail(4, template),
            "stencil/address" to ResourceDetail(4, stencil),
        )
        val entries = details.map { (key, detail) ->
            ResourceEntry(detail.resource.type, detail.resource.slug, detail.resource.name, detailUrl = "./resources/$key.json")
        }
        val report = CatalogValidator.validate(archive(manifest(resources = entries), details))
        val graphPaths = report.findings.filter { it.code == TemplateValidationCodes.TEMPLATE_GRAPH_INVALID }.map { it.path }

        assertTrue(graphPaths.any { it.endsWith(".templateModel.root") }, graphPaths.toString())
        assertTrue(graphPaths.any { it.endsWith(".variants[0].templateModel.root") }, graphPaths.toString())
        assertTrue(graphPaths.any { it.endsWith(".content.root") }, graphPaths.toString())
    }

    @Test
    fun `resource validators cover references schemas lists fonts assets and attributes`() {
        val template = TemplateResource(
            "invoice",
            "Invoice",
            dataModel = mapOf("type" to "object", "properties" to mapOf("total" to mapOf("type" to "number")), "required" to listOf("total")),
            dataExamples = listOf(
                app.epistola.catalog.protocol.DataExampleEntry("broken", mapOf("total" to "not-number")),
                app.epistola.catalog.protocol.DataExampleEntry("broken", emptyMap()),
            ),
            templateModel = validDocument(),
            variants = listOf(VariantEntry("same", isDefault = true), VariantEntry("same", isDefault = true)),
        )
        val resources = listOf(
            template,
            AttributeResource("locale", "Locale", listOf("nl"), CodeListBindingRef(slug = "countries")),
            CodeListResource("countries", "Countries", entries = listOf(CodeListEntryEntry("NL", "NL"), CodeListEntryEntry("NL", "Duplicate"))),
            AssetResource("face", "Face", "bad", width = -1, contentUrl = "./resources/asset/missing.woff2"),
            FontResource(
                "brand",
                "Brand",
                "unknown",
                listOf(FontVariantEntry(0, false, "missing"), FontVariantEntry(0, false, "missing")),
            ),
        )
        val details = resources.associate { "${it.type}/${it.slug}" to ResourceDetail(4, it) }
        val entries = details.map { (key, detail) ->
            ResourceEntry(detail.resource.type, detail.resource.slug, detail.resource.name, detailUrl = "./resources/$key.json")
        }
        val report = CatalogValidator.validate(archive(manifest(resources = entries), details))

        assertTrue(CatalogValidationCodes.TEMPLATE_DATA_EXAMPLE_INVALID in report.codes())
        assertTrue(CatalogValidationCodes.TEMPLATE_EXAMPLE_NAME_DUPLICATE in report.codes())
        assertTrue(CatalogValidationCodes.TEMPLATE_VARIANT_ID_DUPLICATE in report.codes())
        assertTrue(CatalogValidationCodes.TEMPLATE_VARIANT_DEFAULT_INVALID in report.codes())
        assertTrue(CatalogValidationCodes.ATTRIBUTE_VALUES_CONFLICT in report.codes())
        assertTrue(CatalogValidationCodes.CODE_LIST_CODE_DUPLICATE in report.codes())
        assertTrue(CatalogValidationCodes.ASSET_FILE_MISSING in report.codes())
        assertTrue(CatalogValidationCodes.FONT_VARIANT_INVALID in report.codes())
        assertTrue(CatalogValidationCodes.FONT_VARIANT_DUPLICATE in report.codes())
        assertTrue(CatalogValidationCodes.RESOURCE_REFERENCE_MISSING in report.codes())
    }

    @Test
    fun `example validation supports full schemas rich text refs and local datetimes`() {
        val dataModel = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "title" to mapOf("type" to "string", "minLength" to 3),
                "when" to mapOf("type" to "string", "format" to "date-time"),
                "body" to mapOf("\$ref" to "https://epistola.app/schemas/richtext-inline-v1.json"),
            ),
            "required" to listOf("title", "when", "body"),
        )
        val richText = mapOf(
            "type" to "doc",
            "content" to listOf(
                mapOf(
                    "type" to "paragraph",
                    "content" to listOf(mapOf("type" to "text", "text" to "Hello")),
                ),
            ),
        )
        val resource = TemplateResource(
            "invoice",
            "Invoice",
            dataModel = dataModel,
            dataExamples = listOf(
                DataExampleEntry(
                    "invalid-title",
                    mapOf("title" to "x", "when" to "2026-07-27T10:30", "body" to richText),
                ),
            ),
            templateModel = validDocument(),
            variants = emptyList(),
        )
        val key = "template/invoice"
        val detail = ResourceDetail(4, resource)
        val report = CatalogValidator.validate(
            archive(
                manifest(resources = listOf(ResourceEntry("template", "invoice", "Invoice", detailUrl = "./resources/$key.json"))),
                mapOf(key to detail),
            ),
        )

        assertTrue(CatalogValidationCodes.TEMPLATE_DATA_SCHEMA_INVALID !in report.codes(), report.findings.toString())
        assertTrue(CatalogValidationCodes.TEMPLATE_DATA_EXAMPLE_INVALID in report.codes(), report.findings.toString())
    }

    @Test
    fun `golden case registry covers every stable catalog finding code`() {
        val fixtureCodes = fixture("catalog-validation-cases.json").use(mapper::readTree).propertyNames().toSet()
        val publishedCodes = CatalogValidationCodes::class.java.declaredFields
            .filter { it.type == String::class.java }
            .map { it.get(null) as String }
            .toSet()

        assertEquals(publishedCodes, fixtureCodes)
    }

    private fun manifest(
        release: ReleaseInfo = ReleaseInfo("1.0.0"),
        resources: List<ResourceEntry> = emptyList(),
    ) = CatalogManifest(
        4,
        CatalogInfo("fixture", "Fixture"),
        PublisherInfo("Epistola"),
        release,
        resources = resources,
    )

    private fun archive(
        manifest: CatalogManifest,
        details: Map<String, ResourceDetail>,
    ): CatalogArchive {
        val paths = details.keys.mapTo(mutableSetOf("catalog.json")) { "resources/$it.json" }
        return CatalogArchive(
            manifest,
            details,
            paths,
            ArchiveContentProvider { ByteArrayInputStream("{}".toByteArray()) },
        )
    }

    private fun validDocument() = TemplateDocument(
        root = "n-root",
        nodes = mapOf("n-root" to Node("n-root", "root", slots = listOf("s-root"))),
        slots = mapOf("s-root" to Slot("s-root", "n-root", "children")),
    )

    private fun CatalogValidationReport.codes() = findings.map(CatalogValidationFinding::code).toSet()

    private fun fixture(path: String) = requireNotNull(
        javaClass.getResourceAsStream("/META-INF/epistola-catalog/fixtures/v1/$path"),
    )
}
