// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.canonical

import app.epistola.catalog.archive.ArchiveContentProvider
import app.epistola.catalog.archive.CatalogArchive
import app.epistola.catalog.protocol.AttributeAssignment
import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.CatalogPresentation
import app.epistola.catalog.protocol.CompatibilityInfo
import app.epistola.catalog.protocol.IncludeEntry
import app.epistola.catalog.protocol.ResourceDetail
import app.epistola.catalog.protocol.StencilResource
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CatalogCanonicalizerTest {
    private val mapper = jsonMapper { addModule(kotlinModule()) }

    @Test
    fun `golden canonical fingerprints match`() {
        val archive = goldenArchive()
        val expected = resource("fingerprints.json").use(mapper::readTree)

        assertEquals(
            expected["catalogFingerprint"].asString(),
            CatalogCanonicalizer.fingerprint(archive).value,
        )
        assertEquals(
            expected["currentCatalogFingerprint"].asString(),
            CatalogCanonicalizer.currentFingerprint(archive).value,
        )
        CatalogFingerprintVersion.entries.forEach { version ->
            assertEquals(
                expected["catalogFingerprints"][version.name].asString(),
                CatalogCanonicalizer.fingerprint(archive, version).value,
                version.name,
            )
        }
        assertEquals(
            expected["resourceFingerprints"]["theme/default"].asString(),
            CatalogCanonicalizer.perResourceFingerprints(archive).getValue("theme/default"),
        )
    }

    @Test
    fun `fingerprint ignores release metadata manifest URLs and JSON key order`() {
        val original = goldenArchive()
        val changedManifest = original.manifest.copy(
            release = original.manifest.release.copy(version = "9.9.9", releasedAt = "2099-01-01T00:00:00Z"),
            resources = original.manifest.resources.map { it.copy(detailUrl = "ignored/layout.json", updatedAt = "now") },
        )
        val reorderedDetail = """
            {"resource":{"spacingUnit":1.20,"slug":"default","type":"theme","name":"Default",
            "pageSettings":null,"documentStyles":{"color":"#111111","fontSize":"10pt"},
            "description":null,"blockStylePresets":null},"schemaVersion":4}
        """.trimIndent().toByteArray()
        val reordered = CatalogArchive(
            manifest = changedManifest,
            resourceDetails = original.resourceDetails,
            paths = original.paths,
            content = ArchiveContentProvider { path ->
                if (path.endsWith("default.json")) ByteArrayInputStream(reorderedDetail) else error(path)
            },
        )

        assertEquals(CatalogCanonicalizer.currentFingerprint(original), CatalogCanonicalizer.currentFingerprint(reordered))
        assertEquals(
            CatalogCanonicalizer.perResourceFingerprints(original),
            CatalogCanonicalizer.perResourceFingerprints(reordered),
        )
    }

    @Test
    fun `asset content participates in resource and catalog fingerprints`() {
        val first = assetArchive("first".toByteArray())
        val second = assetArchive("second".toByteArray())

        assertNotEquals(CatalogCanonicalizer.currentFingerprint(first), CatalogCanonicalizer.currentFingerprint(second))
        assertNotEquals(
            CatalogCanonicalizer.perResourceFingerprints(first),
            CatalogCanonicalizer.perResourceFingerprints(second),
        )
    }

    @Test
    fun `compatibility and includes participate in catalog fingerprints`() {
        val original = goldenArchive()
        val changedCompatibility = original.copyWithManifest(
            original.manifest.copy(compatibility = CompatibilityInfo(">=2.0.0")),
        )
        val changedIncludes = original.copyWithManifest(
            original.manifest.copy(includes = listOf(IncludeEntry("https://example.test/catalog.json"))),
        )

        assertNotEquals(CatalogCanonicalizer.currentFingerprint(original), CatalogCanonicalizer.currentFingerprint(changedCompatibility))
        assertNotEquals(CatalogCanonicalizer.currentFingerprint(original), CatalogCanonicalizer.currentFingerprint(changedIncludes))
        assertEquals(
            CatalogCanonicalizer.fingerprint(original),
            CatalogCanonicalizer.fingerprint(changedCompatibility),
        )
        assertEquals(
            CatalogCanonicalizer.fingerprint(original),
            CatalogCanonicalizer.fingerprint(changedIncludes),
        )
    }

    @Test
    fun `v4 fingerprints include catalog v6 discovery metadata`() {
        val original = goldenArchive()
        val locale = AttributeAssignment("system", "locale", "nl-NL")
        val brand = AttributeAssignment("system", "brand", "epistola")
        val baseInfo = CatalogInfo.create("fixture", "Fixture", attributes = listOf(locale, brand))
        val base = original.copyWithManifest(original.manifest.copy(schemaVersion = 6, catalog = baseInfo))
        val keyword = base.copyWithManifest(
            base.manifest.copy(catalog = baseInfo.copyWithMetadata(keywords = setOf("government"))),
        )
        val attribute = base.copyWithManifest(
            base.manifest.copy(
                catalog = baseInfo.copyWithMetadata(
                    attributes = listOf(AttributeAssignment("system", "locale", "en-GB"), brand),
                ),
            ),
        )
        val reordered = base.copyWithManifest(
            base.manifest.copy(catalog = baseInfo.copyWithMetadata(attributes = listOf(brand, locale))),
        )
        val presentation = base.copyWithManifest(
            base.manifest.copy(catalog = baseInfo.copyWithMetadata(presentation = CatalogPresentation("icon"))),
        )

        assertNotEquals(CatalogCanonicalizer.currentFingerprint(base), CatalogCanonicalizer.currentFingerprint(keyword))
        assertNotEquals(CatalogCanonicalizer.currentFingerprint(base), CatalogCanonicalizer.currentFingerprint(attribute))
        assertEquals(CatalogCanonicalizer.currentFingerprint(base), CatalogCanonicalizer.currentFingerprint(reordered))
        assertNotEquals(CatalogCanonicalizer.currentFingerprint(base), CatalogCanonicalizer.currentFingerprint(presentation))
        assertEquals(
            CatalogCanonicalizer.fingerprint(base, CatalogFingerprintVersion.V3),
            CatalogCanonicalizer.fingerprint(keyword, CatalogFingerprintVersion.V3),
        )
    }

    @Test
    fun `catalog v4 draft flag syntax is semantically equivalent to catalog v5 provenance`() {
        val legacy = stencilArchive(4, mapOf("stencilId" to "address", "version" to 3, "isDraft" to false))
        val current = stencilArchive(5, mapOf("stencilId" to "address", "version" to 3))

        assertEquals(
            CatalogCanonicalizer.currentFingerprint(legacy),
            CatalogCanonicalizer.currentFingerprint(current),
        )
        assertEquals(
            CatalogCanonicalizer.currentPerResourceFingerprints(legacy),
            CatalogCanonicalizer.currentPerResourceFingerprints(current),
        )
        assertTrue(
            CatalogCanonicalizer.matchesFingerprint(
                current,
                CatalogCanonicalizer.fingerprint(legacy).value,
            ),
        )
    }

    @Test
    fun `semantic fingerprints preserve JSON Schema properties named type`() {
        val original = templateArchive("Type activiteit")
        val changed = templateArchive("Gewijzigd type activiteit")

        val fingerprint = CatalogCanonicalizer.currentFingerprint(original)

        assertTrue(CatalogCanonicalizer.matchesFingerprint(original, fingerprint.value))
        assertEquals(
            setOf("template/permit-confirmation"),
            CatalogCanonicalizer.currentPerResourceFingerprints(original).keys,
        )
        assertNotEquals(fingerprint, CatalogCanonicalizer.currentFingerprint(changed))
    }

    private fun goldenArchive(): CatalogArchive {
        val manifest = resource("wire-v4/catalog.json").use { mapper.readValue(it, CatalogManifest::class.java) }
        val detail = resource("wire-v4/resources/theme/default.json").use { mapper.readValue(it, ResourceDetail::class.java) }
        return CatalogArchive(
            manifest = manifest,
            resourceDetails = mapOf("theme/default" to detail),
            paths = setOf("resources/theme/default.json"),
            content = ArchiveContentProvider { path -> resource("wire-v4/$path") },
        )
    }

    private fun assetArchive(bytes: ByteArray): CatalogArchive {
        val json = """
            {"schemaVersion":4,"resource":{"type":"asset","slug":"logo","name":"Logo",
            "mediaType":"image/png","contentUrl":"./resources/asset/logo.png"}}
        """.trimIndent().toByteArray()
        val detail = mapper.readValue(json, ResourceDetail::class.java)
        val base = goldenArchive()
        return CatalogArchive(
            manifest = base.manifest.copy(resources = emptyList()),
            resourceDetails = mapOf("asset/logo" to detail),
            paths = setOf("resources/asset/logo.json", "resources/asset/logo.png"),
            content = ArchiveContentProvider { path ->
                ByteArrayInputStream(if (path.endsWith(".json")) json else bytes)
            },
        )
    }

    private fun stencilArchive(
        schemaVersion: Int,
        props: Map<String, Any?>,
    ): CatalogArchive {
        val content = TemplateDocument(
            root = "root",
            nodes = mapOf(
                "root" to Node("root", "root", listOf("root-children")),
                "nested" to Node("nested", "stencil", props = props),
            ),
            slots = mapOf("root-children" to Slot("root-children", "root", "children", listOf("nested"))),
        )
        val detail = ResourceDetail(
            schemaVersion,
            StencilResource("letter", "Letter", 1, content = content),
        )
        val bytes = mapper.writeValueAsBytes(detail)
        return CatalogArchive(
            manifest = goldenArchive().manifest.copy(schemaVersion = schemaVersion, resources = emptyList()),
            resourceDetails = mapOf("stencil/letter" to detail),
            paths = setOf("resources/stencil/letter.json"),
            content = ArchiveContentProvider { ByteArrayInputStream(bytes) },
        )
    }

    private fun templateArchive(typeDescription: String): CatalogArchive {
        val document = TemplateDocument(
            root = "root",
            nodes = mapOf("root" to Node("root", "root")),
            slots = emptyMap(),
        )
        val detail = ResourceDetail(
            schemaVersion = 6,
            resource = TemplateResource(
                slug = "permit-confirmation",
                name = "Permit confirmation",
                dataModel = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "activities" to mapOf(
                            "type" to "array",
                            "items" to mapOf(
                                "type" to "object",
                                "properties" to mapOf(
                                    "type" to mapOf(
                                        "type" to "string",
                                        "description" to typeDescription,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                templateModel = document,
                variants = emptyList(),
            ),
        )
        return CatalogArchive(
            manifest = goldenArchive().manifest.copy(schemaVersion = 6, resources = emptyList()),
            resourceDetails = mapOf("template/permit-confirmation" to detail),
            paths = emptySet(),
            content = ArchiveContentProvider { error("unexpected archive content read") },
        )
    }

    private fun CatalogArchive.copyWithManifest(manifest: CatalogManifest) = CatalogArchive(
        manifest = manifest,
        resourceDetails = resourceDetails,
        paths = paths,
        content = content,
    )

    private fun resource(path: String) = requireNotNull(javaClass.getResourceAsStream("/META-INF/epistola-catalog/fixtures/v1/$path"))
}
