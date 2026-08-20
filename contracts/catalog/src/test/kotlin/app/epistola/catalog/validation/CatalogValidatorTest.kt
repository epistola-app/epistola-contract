// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.validation

import app.epistola.catalog.archive.ArchiveContentProvider
import app.epistola.catalog.archive.CatalogArchive
import app.epistola.catalog.canonical.CatalogCanonicalizer
import app.epistola.catalog.canonical.CatalogFingerprintVersion
import app.epistola.catalog.protocol.AssetResource
import app.epistola.catalog.protocol.AttributeAssignment
import app.epistola.catalog.protocol.AttributeResource
import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.CatalogLicense
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.CatalogPresentation
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
import tools.jackson.databind.JsonNode
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
        val manifest = fixture("wire-v6/catalog.json").use { mapper.readValue(it, CatalogManifest::class.java) }
        val detail = fixture("wire-v6/resources/theme/default.json").use { mapper.readValue(it, ResourceDetail::class.java) }
        val archive = archive(manifest, mapOf("theme/default" to detail))

        assertEquals(emptyList(), CatalogValidator.validate(archive).findings)
    }

    @Test
    fun `published conformance catalogs produce their expected reports`() {
        val index = fixture("conformance/catalog-cases.json").use(mapper::readTree)

        index["cases"].forEach { case ->
            val id = case["id"].asString()
            val root = case["root"].asString()
            val expectedReport = case["expectedReport"].asString()
            val files = case["files"].mapTo(linkedSetOf()) { it.asString() }
            val manifest = fixture("$root/catalog.json").use { mapper.readValue(it, CatalogManifest::class.java) }
            val details = files
                .asSequence()
                .filter { it.startsWith("resources/") && it.endsWith(".json") }
                .associate { path ->
                    val key = path.removePrefix("resources/").removeSuffix(".json")
                    key to fixture("$root/$path").use { mapper.readValue(it, ResourceDetail::class.java) }
                }
            val archive = CatalogArchive(
                manifest,
                details,
                files,
                ArchiveContentProvider { path ->
                    require(path in files) { "Conformance fixture '$id' has no file '$path'" }
                    fixture("$root/$path")
                },
            )
            val report = CatalogValidator.validate(archive)
            val expected = fixture("$root/$expectedReport").use(mapper::readTree)

            assertEquals(expected["valid"].asBoolean(), report.valid, id)
            assertEquals(expected["findings"], mapper.valueToTree<JsonNode>(report.findings), id)
        }
    }

    @Test
    fun `legacy source catalogs and current catalogs use their versioned fingerprint rules`() {
        val withoutFingerprint = archive(manifest(), emptyMap())
        CatalogFingerprintVersion.entries.forEach { version ->
            val fingerprint = CatalogCanonicalizer.fingerprint(withoutFingerprint, version).value
            val catalog = archive(
                manifest(release = ReleaseInfo("1.0.0", fingerprint = fingerprint)),
                emptyMap(),
            )
            if (version != CatalogFingerprintVersion.V4) catalog.sourceSchemaVersion = 5
            val report = CatalogValidator.validate(catalog)

            assertTrue(
                report.findings.none { it.code == CatalogValidationCodes.RELEASE_FINGERPRINT_MISMATCH },
                "$version: ${report.findings}",
            )
        }
    }

    @Test
    fun `catalog discovery metadata accepts qualified generic attributes and case-sensitive keywords`() {
        val catalog = CatalogInfo.create(
            "fixture",
            "Fixture",
            attributes = listOf(
                AttributeAssignment("system", "locale", "en_US"),
                AttributeAssignment("fixture", "brand", ""),
            ),
            keywords = setOf("Government", "government"),
        )

        val report = CatalogValidator.validate(archive(manifest(catalog = catalog), emptyMap()))

        assertTrue(report.valid, report.findings.toString())
    }

    @Test
    fun `catalog discovery metadata rejects malformed and duplicate attribute identities`() {
        val catalog = CatalogInfo.create(
            "fixture",
            "Fixture",
            attributes = listOf(
                AttributeAssignment("System", "locale", "nl-NL"),
                AttributeAssignment("system", "bad_key", "one"),
                AttributeAssignment("system", "bad_key", "two"),
            ),
            keywords = setOf(" documents "),
        )

        val report = CatalogValidator.validate(archive(manifest(catalog = catalog), emptyMap()))

        assertTrue(CatalogValidationCodes.CATALOG_ATTRIBUTE_IDENTITY_INVALID in report.codes(), report.findings.toString())
        assertTrue(CatalogValidationCodes.CATALOG_ATTRIBUTE_DUPLICATE in report.codes(), report.findings.toString())
        assertTrue(CatalogValidationCodes.KEYWORD_INVALID in report.codes(), report.findings.toString())
    }

    @Test
    fun `catalog license accepts standard and custom terms`() {
        listOf(
            CatalogLicense(
                name = "Creative Commons Attribution 4.0 International",
                spdxExpression = "CC-BY-4.0",
                url = "https://creativecommons.org/licenses/by/4.0/",
                copyrightText = "Copyright 2026 Example Publisher",
            ),
            CatalogLicense(name = "Proprietary"),
        ).forEach { license ->
            val catalog = CatalogInfo.create("fixture", "Fixture", license = license)
            val report = CatalogValidator.validate(archive(manifest(catalog = catalog), emptyMap()))

            assertTrue(report.valid, report.findings.toString())
        }
    }

    @Test
    fun `catalog license rejects malformed metadata`() {
        listOf(
            CatalogLicense(name = " "),
            CatalogLicense(name = "Example", spdxExpression = " CC-BY-4.0"),
            CatalogLicense(name = "Example", url = "mailto:legal@example.test"),
            CatalogLicense(name = "Example", url = "https:terms"),
            CatalogLicense(name = "Example", copyrightText = " copyright "),
        ).forEach { license ->
            val catalog = CatalogInfo.create("fixture", "Fixture", license = license)
            val report = CatalogValidator.validate(archive(manifest(catalog = catalog), emptyMap()))

            assertTrue(CatalogValidationCodes.CATALOG_LICENSE_INVALID in report.codes(), report.findings.toString())
        }
    }

    @Test
    fun `catalog presentation resolves same-catalog image assets`() {
        val details = mapOf(
            "asset/icon" to ResourceDetail(6, AssetResource("icon", "Icon", "image/svg+xml", contentUrl = "./resources/asset/icon.svg")),
            "asset/hero" to ResourceDetail(6, AssetResource("hero", "Hero", "IMAGE/PNG", contentUrl = "./resources/asset/hero.png")),
        )
        val entries = details.map { (key, detail) ->
            ResourceEntry(detail.resource.type, detail.resource.slug, detail.resource.name, detailUrl = "./resources/$key.json")
        }
        val catalog = CatalogInfo.create(
            "fixture",
            "Fixture",
            presentation = CatalogPresentation("icon", listOf("icon", "hero")),
        )

        val report = CatalogValidator.validate(
            archive(
                manifest(catalog = catalog, resources = entries),
                details,
                setOf("resources/asset/icon.svg", "resources/asset/hero.png"),
            ),
        )

        assertTrue(report.valid, report.findings.toString())
    }

    @Test
    fun `catalog presentation reports missing non-asset non-image and duplicate references`() {
        val details = mapOf(
            "theme/not-asset" to ResourceDetail(6, app.epistola.catalog.protocol.ThemeResource("not-asset", "Theme")),
            "asset/document" to ResourceDetail(6, AssetResource("document", "Document", "application/pdf", contentUrl = "./document.pdf")),
        )
        val entries = details.map { (key, detail) ->
            ResourceEntry(detail.resource.type, detail.resource.slug, detail.resource.name, detailUrl = "./resources/$key.json")
        }
        val catalog = CatalogInfo.create(
            "fixture",
            "Fixture",
            presentation = CatalogPresentation("missing", listOf("not-asset", "document", "document")),
        )

        val report = CatalogValidator.validate(archive(manifest(catalog = catalog, resources = entries), details))

        assertTrue(CatalogValidationCodes.PRESENTATION_ASSET_MISSING in report.codes())
        assertTrue(CatalogValidationCodes.PRESENTATION_RESOURCE_NOT_ASSET in report.codes())
        assertTrue(CatalogValidationCodes.PRESENTATION_ASSET_MEDIA_TYPE_INVALID in report.codes())
        assertTrue(CatalogValidationCodes.PRESENTATION_IMAGE_DUPLICATE in report.codes())
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
            "template/invoice" to ResourceDetail(6, template),
            "stencil/address" to ResourceDetail(6, stencil),
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
    fun `stencil parameter schema is validated as catalog content`() {
        val stencil = StencilResource(
            "address",
            "Address",
            1,
            content = validDocument(),
            parameterSchema = mapOf("type" to "array"),
        )
        val key = "stencil/address"
        val detail = ResourceDetail(6, stencil)
        val report = CatalogValidator.validate(
            archive(
                manifest(resources = listOf(ResourceEntry("stencil", "address", "Address", detailUrl = "./resources/$key.json"))),
                mapOf(key to detail),
            ),
        )

        assertTrue(TemplateValidationCodes.PARAMETER_SCHEMA_INVALID_TYPE in report.codes(), report.findings.toString())
    }

    @Test
    fun `catalog accepts exact version pinned stencil composition`() {
        val address = StencilResource("address", "Address", 2, content = validDocument())
        val letter = StencilResource(
            "letter",
            "Letter",
            1,
            content = documentWithStencil("nested-address", "address", 2),
        )

        val report = validateStencils(letter, address)

        assertTrue(report.valid, report.findings.toString())
        assertTrue(report.findings.isEmpty())
    }

    @Test
    fun `catalog rejects a nested stencil version that is not present`() {
        val address = StencilResource("address", "Address", 2, content = validDocument())
        val letter = StencilResource(
            "letter",
            "Letter",
            1,
            content = documentWithStencil("nested-address", "address", 1),
        )

        val report = validateStencils(letter, address)

        assertEquals(
            listOf("resources/stencil/letter.json.resource.content.nodes.nested-address.props.stencilId"),
            report.findings
                .filter { it.code == TemplateValidationCodes.STENCIL_REFERENCE_NOT_FOUND }
                .map { it.path },
        )
    }

    @Test
    fun `catalog rejects direct stencil resource self reference`() {
        val letter = StencilResource(
            "letter",
            "Letter",
            1,
            content = documentWithStencil("nested-letter", "letter", 1),
        )

        val report = validateStencils(letter)

        assertEquals(
            listOf("resources/stencil/letter.json.resource.content.nodes.nested-letter.props.stencilId"),
            report.findings.filter { it.code == TemplateValidationCodes.STENCIL_RECURSION }.map { it.path },
        )
    }

    @Test
    fun `catalog rejects transitive recursion across stencil resources`() {
        val letter = StencilResource(
            "letter",
            "Letter",
            1,
            content = documentWithStencil("nested-address", "address", 1),
        )
        val address = StencilResource(
            "address",
            "Address",
            1,
            content = documentWithStencil("nested-letter", "letter", 1),
        )

        val report = validateStencils(letter, address)

        assertEquals(
            listOf(
                "resources/stencil/letter.json.resource.content.nodes.nested-address.props.stencilId",
            ),
            report.findings.filter { it.code == TemplateValidationCodes.STENCIL_RECURSION }.map { it.path },
        )
    }

    @Test
    fun `golden composition fixture matches whole catalog outcomes`() {
        val fixture = fixture("stencil-composition-validation.json").use(mapper::readTree)

        fixture["validCases"]
            .filter { it["scope"].asString() == "catalog" }
            .forEach { valid ->
                val report = compositionCatalogScenario(valid["scenario"].asString())
                assertTrue(report.valid, "${valid["scenario"].asString()}: ${report.findings}")
            }
        fixture["invalidCases"]
            .filter { it["scope"].asString() == "catalog" }
            .forEach { invalid ->
                val report = compositionCatalogScenario(invalid["scenario"].asString())
                val expected: List<Pair<String, String>> = invalid["expectedFindings"].toList().map {
                    Pair(it["code"].asString(), it["path"].asString())
                }
                val actual: List<Pair<String, String>> = report.findings.map { it.code to it.path }
                assertEquals(
                    expected,
                    actual,
                    invalid["scenario"].asString(),
                )
            }
    }

    @Test
    fun `template resource theme reference must resolve`() {
        val template = TemplateResource(
            "invoice",
            "Invoice",
            themeId = "missing",
            templateModel = validDocument(),
            variants = emptyList(),
        )
        val key = "template/invoice"
        val detail = ResourceDetail(6, template)
        val report = CatalogValidator.validate(
            archive(
                manifest(resources = listOf(ResourceEntry("template", "invoice", "Invoice", detailUrl = "./resources/$key.json"))),
                mapOf(key to detail),
            ),
        )

        assertTrue(CatalogValidationCodes.RESOURCE_REFERENCE_MISSING in report.codes(), report.findings.toString())
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
        val details = resources.associate { "${it.type}/${it.slug}" to ResourceDetail(6, it) }
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
        val detail = ResourceDetail(6, resource)
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
    fun `finding code registry covers every stable catalog finding code`() {
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
        catalog: CatalogInfo = CatalogInfo("fixture", "Fixture"),
    ) = CatalogManifest(
        6,
        catalog,
        PublisherInfo("Epistola"),
        release,
        resources = resources,
    )

    private fun archive(
        manifest: CatalogManifest,
        details: Map<String, ResourceDetail>,
        additionalPaths: Set<String> = emptySet(),
    ): CatalogArchive {
        val paths = details.keys.mapTo(mutableSetOf("catalog.json")) { "resources/$it.json" } + additionalPaths
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

    private fun documentWithStencil(
        nodeId: String,
        slug: String,
        version: Int,
        draftVersion: Int? = null,
    ): TemplateDocument {
        val nested = Node(
            nodeId,
            "stencil",
            slots = listOf("$nodeId-children"),
            props = buildMap {
                put("stencilId", slug)
                put("version", version)
                draftVersion?.let { put("draftVersion", it) }
            },
        )
        return validDocument().copy(
            nodes = validDocument().nodes + (nested.id to nested),
            slots = validDocument().slots +
                ("s-root" to Slot("s-root", "n-root", "children", listOf(nested.id))) +
                ("$nodeId-children" to Slot("$nodeId-children", nested.id, "children")),
        )
    }

    private fun validateStencils(vararg stencils: StencilResource): CatalogValidationReport {
        val details = stencils.associate { stencil ->
            "stencil/${stencil.slug}" to ResourceDetail(6, stencil)
        }
        val entries = stencils.map { stencil ->
            ResourceEntry(
                "stencil",
                stencil.slug,
                stencil.name,
                detailUrl = "./resources/stencil/${stencil.slug}.json",
            )
        }
        return CatalogValidator.validate(archive(manifest(resources = entries), details))
    }

    private fun compositionCatalogScenario(scenario: String): CatalogValidationReport = when (scenario) {
        "CATALOG_REFERENCE_MATCHES_EXACT_VERSION" -> validateStencils(
            StencilResource(
                "letter",
                "Letter",
                1,
                content = documentWithStencil("nested-address", "address", 2),
            ),
            StencilResource("address", "Address", 2, content = validDocument()),
        )
        "CATALOG_REFERENCE_VERSION_MISMATCH" -> validateStencils(
            StencilResource(
                "letter",
                "Letter",
                1,
                content = documentWithStencil("nested-address", "address", 1),
            ),
            StencilResource("address", "Address", 2, content = validDocument()),
        )
        "CATALOG_REFERENCE_TARGETS_DRAFT" -> validateStencils(
            StencilResource(
                "letter",
                "Letter",
                1,
                content = documentWithStencil("nested-address", "address", 2, draftVersion = 3),
            ),
            StencilResource("address", "Address", 2, content = validDocument()),
        )
        "CATALOG_TRANSITIVE_RESOURCE_CYCLE" -> validateStencils(
            StencilResource(
                "letter",
                "Letter",
                1,
                content = documentWithStencil("nested-address", "address", 1),
            ),
            StencilResource(
                "address",
                "Address",
                1,
                content = documentWithStencil("nested-letter", "letter", 1),
            ),
        )
        "CATALOG_COMPOSITION_AT_DEPTH_LIMIT" ->
            validateStencils(*stencilChain(TemplateValidationLimits.MAX_STENCIL_NESTING_DEPTH))
        "CATALOG_COMPOSITION_EXCEEDS_DEPTH" ->
            validateStencils(*stencilChain(TemplateValidationLimits.MAX_STENCIL_NESTING_DEPTH + 1))
        else -> error("Unknown catalog composition fixture scenario: $scenario")
    }

    private fun stencilChain(depth: Int): Array<StencilResource> = Array(depth) { index ->
        val next = index + 1
        StencilResource(
            slug = "stencil-$index",
            name = "Stencil $index",
            version = 1,
            content = if (next < depth) {
                documentWithStencil("nested-stencil-$next", "stencil-$next", 1)
            } else {
                validDocument()
            },
        )
    }

    private fun CatalogValidationReport.codes() = findings.map(CatalogValidationFinding::code).toSet()

    private fun fixture(path: String) = requireNotNull(
        javaClass.getResourceAsStream("/META-INF/epistola-catalog/fixtures/v1/$path"),
    )
}
