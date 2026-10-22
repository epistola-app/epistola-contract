// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.canonical

import app.epistola.catalog.archive.ArchiveContentProvider
import app.epistola.catalog.archive.CatalogArchive
import app.epistola.catalog.migration.CatalogWireSchema.CURRENT_VERSION
import app.epistola.catalog.protocol.AttributeAssignment
import app.epistola.catalog.protocol.CatalogDependency
import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.CatalogLicense
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.CatalogPresentation
import app.epistola.catalog.protocol.CatalogPresetDeclaration
import app.epistola.catalog.protocol.CatalogThemeRef
import app.epistola.catalog.protocol.CompatibilityInfo
import app.epistola.catalog.protocol.DependencyRef
import app.epistola.catalog.protocol.IncludeEntry
import app.epistola.catalog.protocol.ReleaseBase
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
    fun `golden v8 fingerprint covers declared dependencies with their release fingerprints`() {
        val archive = wireV8Archive()
        val expected = resource("fingerprints.json").use(mapper::readTree)

        assertEquals(expected["currentWireV8CatalogFingerprint"].asString(), CatalogCanonicalizer.currentFingerprint(archive).value)
    }

    @Test
    fun `v5 covers a template's description, data example keys and variant metadata, and the legacy algorithms do not`() {
        val original = wireV8Archive()
        val template = original.resourceDetails.getValue("template/letter").resource as TemplateResource
        val variant = template.variants.single()
        val changes = listOf(
            template.copy(description = "Another description."),
            template.copy(dataExamples = template.dataExamples.orEmpty().map { it.copy(key = "other-key") }),
            template.copy(variants = listOf(variant.copy(metadata = mapOf("app.epistola.suite" to mapOf("renderingDefaultsVersion" to 7))))),
            template.copy(variants = listOf(variant.copy(metadata = null))),
        )

        changes.forEach { changed ->
            // In memory, so that every algorithm reads the bound model rather than the fixture files.
            val before = inMemory(original, template)
            val after = inMemory(original, changed)
            assertNotEquals(CatalogCanonicalizer.currentFingerprint(before), CatalogCanonicalizer.currentFingerprint(after), changed.toString())
            listOf(CatalogFingerprintVersion.V1, CatalogFingerprintVersion.V2, CatalogFingerprintVersion.V3, CatalogFingerprintVersion.V4).forEach { version ->
                assertEquals(CatalogCanonicalizer.fingerprint(before, version), CatalogCanonicalizer.fingerprint(after, version), "$version: $changed")
            }
        }
    }

    @Test
    fun `golden complete per-resource fingerprints match, and the older per-resource forms keep their values`() {
        val archive = wireV8Archive()
        val expected = resource("fingerprints.json").use(mapper::readTree)["completeWireV8ResourceFingerprints"]

        assertEquals(
            expected.propertyNames().associateWith { expected[it].asString() },
            CatalogCanonicalizer.completePerResourceFingerprints(archive),
        )
        // Pinned before the complete form existed: adding it moves neither older form.
        assertEquals(WIRE_V8_V1_RESOURCE_FINGERPRINTS, CatalogCanonicalizer.perResourceFingerprints(archive))
        assertEquals(WIRE_V8_V3_RESOURCE_FINGERPRINTS, CatalogCanonicalizer.currentPerResourceFingerprints(archive))
        assertEquals(
            resource("fingerprints.json").use(mapper::readTree)["resourceFingerprints"]["theme/default"].asString(),
            CatalogCanonicalizer.perResourceFingerprints(goldenArchive()).getValue("theme/default"),
        )
    }

    @Test
    fun `the complete per-resource form follows a change to only what wire v8 added, of that resource alone`() {
        val original = wireV8Archive()
        val template = original.resourceDetails.getValue("template/letter").resource as TemplateResource
        val variant = template.variants.single()
        val changes = mapOf(
            "a template's description" to template.copy(description = "Another description."),
            "a data example's key" to template.copy(dataExamples = template.dataExamples.orEmpty().map { it.copy(key = "other-key") }),
            "a variant's metadata" to template.copy(
                variants = listOf(variant.copy(metadata = mapOf("app.epistola.suite" to mapOf("renderingDefaultsVersion" to 7)))),
            ),
            "a variant's metadata removed" to template.copy(variants = listOf(variant.copy(metadata = null))),
        )

        changes.forEach { (what, changed) ->
            // In memory, so that every form reads the bound model rather than the fixture files.
            val before = inMemory(original, template)
            val after = inMemory(original, changed)
            val complete = CatalogCanonicalizer.completePerResourceFingerprints(before)
            val completeAfter = CatalogCanonicalizer.completePerResourceFingerprints(after)

            assertEquals(complete.keys, completeAfter.keys, what)
            assertNotEquals(complete.getValue("template/letter"), completeAfter.getValue("template/letter"), what)
            assertEquals(complete - "template/letter", completeAfter - "template/letter", "$what moves no other resource")
            // The older forms do not see the change at all, which is why the complete form exists.
            assertEquals(CatalogCanonicalizer.perResourceFingerprints(before), CatalogCanonicalizer.perResourceFingerprints(after), what)
            assertEquals(CatalogCanonicalizer.currentPerResourceFingerprints(before), CatalogCanonicalizer.currentPerResourceFingerprints(after), what)
        }
    }

    @Test
    fun `the complete per-resource form ignores key order and number spelling, and never equals an older form`() {
        val original = wireV8Archive()
        val template = original.resourceDetails.getValue("template/letter").resource as TemplateResource
        fun withMetadata(json: String) = inMemory(
            original,
            template.copy(
                variants = listOf(
                    template.variants.single().copy(
                        metadata = mapper.readValue(json, Map::class.java).let {
                            @Suppress("UNCHECKED_CAST")
                            (it as Map<String, Map<String, Any?>>)
                        },
                    ),
                ),
            ),
        )

        assertEquals(
            CatalogCanonicalizer.completePerResourceFingerprints(withMetadata("""{"b.example":{"y":1,"x":[1,2]},"a.example":{"v":true}}""")),
            CatalogCanonicalizer.completePerResourceFingerprints(withMetadata("""{"a.example":{"v":true},"b.example":{"x":[1,2],"y":1}}""")),
        )
        val complete = CatalogCanonicalizer.completePerResourceFingerprints(original)
        val older = CatalogCanonicalizer.perResourceFingerprints(original).values + CatalogCanonicalizer.currentPerResourceFingerprints(original).values
        assertEquals(setOf("template/letter", "theme/default"), complete.keys)
        complete.values.forEach { value ->
            assertTrue(Regex("[0-9a-f]{64}").matches(value), value)
            assertTrue(value !in older, value)
        }
    }

    @Test
    fun `variant metadata canonicalizes with sorted keys and Jackson-normalized numbers`() {
        val original = wireV8Archive()
        val template = original.resourceDetails.getValue("template/letter").resource as TemplateResource
        fun withMetadata(json: String) = inMemory(
            original,
            template.copy(
                variants = listOf(
                    template.variants.single().copy(
                        metadata = mapper.readValue(json, Map::class.java).let {
                            @Suppress("UNCHECKED_CAST")
                            (it as Map<String, Map<String, Any?>>)
                        },
                    ),
                ),
            ),
        )

        assertEquals(
            CatalogCanonicalizer.currentFingerprint(withMetadata("""{"b.example":{"y":1,"x":[1,2]},"a.example":{"v":true}}""")),
            CatalogCanonicalizer.currentFingerprint(withMetadata("""{"a.example":{"v":true},"b.example":{"x":[1,2],"y":1}}""")),
        )
        assertNotEquals(
            CatalogCanonicalizer.currentFingerprint(withMetadata("""{"a.example":{"x":[1,2]}}""")),
            CatalogCanonicalizer.currentFingerprint(withMetadata("""{"a.example":{"x":[2,1]}}""")),
        )
    }

    private fun wireV8Archive(): CatalogArchive {
        val manifest = resource("wire-v8/catalog.json").use { mapper.readValue(it, CatalogManifest::class.java) }
        val details = listOf("theme/default", "template/letter").associateWith { key ->
            resource("wire-v8/resources/$key.json").use { mapper.readValue(it, ResourceDetail::class.java) }
        }
        return CatalogArchive(
            manifest = manifest,
            resourceDetails = details,
            paths = details.keys.mapTo(linkedSetOf()) { "resources/$it.json" },
            content = ArchiveContentProvider { path -> resource("wire-v8/$path") },
        )
    }

    /** [catalog] with [template] in place of its template, and no files, so every algorithm reads the model. */
    private fun inMemory(
        catalog: CatalogArchive,
        template: TemplateResource,
    ) = CatalogArchive(
        catalog.manifest,
        catalog.resourceDetails + ("template/letter" to ResourceDetail(CURRENT_VERSION, template)),
        emptySet(),
        ArchiveContentProvider { error(it) },
    )

    @Test
    fun `fingerprint ignores release metadata manifest URLs and JSON key order`() {
        val original = goldenArchive()
        val changedManifest = original.manifest.copy(
            release = original.manifest.release.copy(
                version = "9.9.9",
                releasedAt = "2099-01-01T00:00:00Z",
                releaseId = "k7m2x9qa",
                metadata = mapOf("app.epistola.suite" to mapOf("renderingDefaultsVersion" to 6)),
                base = ReleaseBase("9.9.8", "v5:${"0".repeat(64)}"),
            ),
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
    fun `no fingerprint covers a release's base, catalog or per resource`() {
        val original = wireV8Archive()
        val withoutBase = original.copyWithManifest(original.manifest.copy(release = original.manifest.release.copy(base = null)))
        val otherBase = original.copyWithManifest(
            original.manifest.copy(
                release = original.manifest.release.copy(
                    base = ReleaseBase("0.1.0", "v4:${"2".repeat(64)}"),
                ),
            ),
        )

        CatalogFingerprintVersion.entries.forEach { version ->
            assertEquals(CatalogCanonicalizer.fingerprint(withoutBase, version), CatalogCanonicalizer.fingerprint(original, version), "$version")
            assertEquals(CatalogCanonicalizer.fingerprint(withoutBase, version), CatalogCanonicalizer.fingerprint(otherBase, version), "$version")
        }
        listOf(
            CatalogCanonicalizer::perResourceFingerprints,
            CatalogCanonicalizer::currentPerResourceFingerprints,
            CatalogCanonicalizer::completePerResourceFingerprints,
        ).forEach { perResource ->
            assertEquals(perResource(withoutBase), perResource(original), perResource.name)
            assertEquals(perResource(withoutBase), perResource(otherBase), perResource.name)
        }
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
        assertNotEquals(
            CatalogCanonicalizer.completePerResourceFingerprints(first),
            CatalogCanonicalizer.completePerResourceFingerprints(second),
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
        val base = original.copyWithManifest(original.manifest.copy(schemaVersion = CURRENT_VERSION, catalog = baseInfo))
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
        val license = base.copyWithManifest(
            base.manifest.copy(catalog = baseInfo.copyWithMetadata(license = CatalogLicense("Proprietary"))),
        )

        assertNotEquals(CatalogCanonicalizer.currentFingerprint(base), CatalogCanonicalizer.currentFingerprint(keyword))
        assertNotEquals(CatalogCanonicalizer.currentFingerprint(base), CatalogCanonicalizer.currentFingerprint(attribute))
        assertEquals(CatalogCanonicalizer.currentFingerprint(base), CatalogCanonicalizer.currentFingerprint(reordered))
        assertNotEquals(CatalogCanonicalizer.currentFingerprint(base), CatalogCanonicalizer.currentFingerprint(presentation))
        assertNotEquals(CatalogCanonicalizer.currentFingerprint(base), CatalogCanonicalizer.currentFingerprint(license))
        assertEquals(
            CatalogCanonicalizer.fingerprint(base, CatalogFingerprintVersion.V3),
            CatalogCanonicalizer.fingerprint(keyword, CatalogFingerprintVersion.V3),
        )
    }

    @Test
    fun `v5 fingerprints cover catalog identity and declared dependencies, which v4 never did`() {
        val registry = "exchange.epistola.app"
        val namespace = "epistola"
        val original = goldenArchive()
        val shared = CatalogDependency(registry, namespace, "shared", "1.4.0", "v5:69e540ae7bea571f81ff51919c8fd7d4dfca8d792da9fcc5a559034218feaa42")
        val brand = CatalogDependency(registry, namespace, "brand", null)
        val base = original.copyWithManifest(
            original.manifest.copy(
                schemaVersion = CURRENT_VERSION,
                catalog = original.manifest.catalog.withNamespace(registry, namespace),
                dependencies = listOf(DependencyRef.Theme("shared", "corporate")),
                catalogDependencies = listOf(shared, brand),
            ),
        )
        fun changed(edit: (CatalogManifest) -> CatalogManifest) = base.copyWithManifest(edit(base.manifest))
        val variants = mapOf(
            "namespace" to changed { it.copy(catalog = it.catalog.withNamespace(registry, "acme")) },
            "registry" to changed { it.copy(catalog = it.catalog.withNamespace("acme.com", namespace)) },
            // V5 covers a resource dependency's catalog by the name its alias is declared for.
            "dependency namespace" to changed { it.copy(catalogDependencies = listOf(shared.copy(namespace = "acme"), brand)) },
            "declared registry" to changed { it.copy(catalogDependencies = listOf(shared.copy(registry = "acme.com"), brand)) },
            "pinned release" to changed { it.copy(catalogDependencies = listOf(shared.copy(releaseVersion = "1.5.0"), brand)) },
            "release fingerprint" to changed { it.copy(catalogDependencies = listOf(shared.copy(releaseFingerprint = "0".repeat(64)), brand)) },
            "declared catalogs" to changed { it.copy(catalogDependencies = listOf(shared)) },
        )

        variants.forEach { (what, variant) ->
            assertNotEquals(CatalogCanonicalizer.currentFingerprint(base), CatalogCanonicalizer.currentFingerprint(variant), what)
            assertEquals(
                CatalogCanonicalizer.fingerprint(base, CatalogFingerprintVersion.V4),
                CatalogCanonicalizer.fingerprint(variant, CatalogFingerprintVersion.V4),
                what,
            )
        }
        assertEquals(
            CatalogCanonicalizer.currentFingerprint(base),
            CatalogCanonicalizer.currentFingerprint(changed { it.copy(catalogDependencies = listOf(brand, shared)) }),
        )
        assertEquals(CatalogCanonicalizer.fingerprint(base, CatalogFingerprintVersion.V5), CatalogCanonicalizer.currentFingerprint(base))
    }

    @Test
    fun `a manifest stored before aliases, its references qualified by registry and namespace, keeps its V5 fingerprint`() {
        // The wire-v8 golden catalog as wire v8 wrote it before contract ADR 0003: the resource dependency names
        // its catalog by registry, namespace and key, and the declaration has no alias.
        val stored = """
            {"schemaVersion":8,
             "catalog":{"registry":"exchange.epistola.app","namespace":"epistola","key":"fixture","name":"Fixture Catalog",
               "description":"Portable golden fixture","attributes":[{"catalogKey":"system","key":"locale","value":"nl-NL"}],
               "keywords":["documents","government"],"presentation":null,
               "license":{"name":"Creative Commons Attribution 4.0 International","spdxExpression":"CC-BY-4.0",
                 "url":"https://creativecommons.org/licenses/by/4.0/","copyrightText":"Copyright 2026 Example Publisher"}},
             "publisher":{"name":"Epistola"},
             "release":{"version":"1.0.0"},
             "resources":[
               {"type":"theme","key":"default","name":"Default","detailUrl":"./resources/theme/default.json"},
               {"type":"template","key":"letter","name":"Letter",
                "description":"A one-page letter in the house style. The footer does not yet wrap long addresses.",
                "detailUrl":"./resources/template/letter.json"}],
             "dependencies":[{"type":"font","registry":"exchange.epistola.app","namespace":"epistola","catalogKey":"shared","key":"inter"}],
             "catalogDependencies":[{"registry":"exchange.epistola.app","namespace":"epistola","catalogKey":"shared",
               "releaseVersion":"1.4.0","releaseFingerprint":"v5:69e540ae7bea571f81ff51919c8fd7d4dfca8d792da9fcc5a559034218feaa42"}]}
        """.trimIndent()
        val current = wireV8Archive()
        val rebound = current.copyWithManifest(mapper.readValue(stored, CatalogManifest::class.java))
        val pinned = resource("fingerprints.json").use(mapper::readTree)["wireV8CatalogFingerprints"]["V5"].asString()

        assertEquals(pinned, CatalogCanonicalizer.fingerprint(rebound, CatalogFingerprintVersion.V5).value)
        assertEquals(pinned, CatalogCanonicalizer.fingerprint(current, CatalogFingerprintVersion.V5).value, "the same catalog, by alias")
    }

    @Test
    fun `a catalog without a default theme or declared presets keeps the fingerprints published before they existed`() {
        val expected = resource("fingerprints.json").use(mapper::readTree)
        val v8 = wireV8Archive()
        val explicit = v8.copyWithManifest(
            v8.manifest.copy(catalog = v8.manifest.catalog.copyWithMetadata(defaultTheme = null, presets = emptyList())),
        )

        assertEquals(expected["currentWireV8CatalogFingerprint"].asString(), CatalogCanonicalizer.currentFingerprint(explicit).value)
        assertEquals(expected["currentCatalogFingerprint"].asString(), CatalogCanonicalizer.currentFingerprint(goldenArchive()).value)
    }

    @Test
    fun `v5 covers the catalog's default theme and declared presets, in any preset order, and the legacy algorithms do not`() {
        val base = wireV8Archive()
        fun changed(edit: (CatalogInfo) -> CatalogInfo) = base.copyWithManifest(base.manifest.copy(catalog = edit(base.manifest.catalog)))
        val muted = CatalogPresetDeclaration("muted", "Muted")
        val label = CatalogPresetDeclaration("label", "Label", "Small caps for labels")
        val withPresets = changed { it.copyWithMetadata(presets = listOf(muted, label)) }
        val variants = mapOf(
            "default theme" to changed { it.copyWithMetadata(defaultTheme = CatalogThemeRef("default")) },
            "default theme in another catalog" to changed { it.copyWithMetadata(defaultTheme = CatalogThemeRef("default", "shared")) },
            "presets" to withPresets,
            "a preset's name" to changed { it.copyWithMetadata(presets = listOf(muted.copy(name = "Quiet"), label)) },
            "a preset's description" to changed { it.copyWithMetadata(presets = listOf(muted, label.copy(description = null))) },
        )

        variants.forEach { (what, variant) ->
            assertNotEquals(CatalogCanonicalizer.currentFingerprint(base), CatalogCanonicalizer.currentFingerprint(variant), what)
            CatalogFingerprintVersion.entries.filter { it != CatalogFingerprintVersion.V5 }.forEach { version ->
                assertEquals(CatalogCanonicalizer.fingerprint(base, version), CatalogCanonicalizer.fingerprint(variant, version), "$what under $version")
            }
        }
        assertNotEquals(
            CatalogCanonicalizer.currentFingerprint(variants.getValue("default theme")),
            CatalogCanonicalizer.currentFingerprint(variants.getValue("default theme in another catalog")),
        )
        assertEquals(
            CatalogCanonicalizer.currentFingerprint(withPresets),
            CatalogCanonicalizer.currentFingerprint(changed { it.copyWithMetadata(presets = listOf(label, muted)) }),
            "preset order carries no meaning",
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
            {"schemaVersion":4,"resource":{"type":"image","slug":"logo","name":"Logo",
            "mediaType":"image/png","contentUrl":"./resources/asset/logo.png","contentHash":"0000000000000000000000000000000000000000000000000000000000000000"}}
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
            StencilResource("letter", "Letter", content = content),
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
            schemaVersion = CURRENT_VERSION,
            resource = TemplateResource(
                key = "permit-confirmation",
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
            manifest = goldenArchive().manifest.copy(schemaVersion = CURRENT_VERSION, resources = emptyList()),
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

    private companion object {
        /** [perResourceFingerprints][CatalogCanonicalizer.perResourceFingerprints] of the wire-v8 golden catalog. */
        val WIRE_V8_V1_RESOURCE_FINGERPRINTS = mapOf(
            "template/letter" to "3ccb2946462c5a73f1e4524617dca7018cf4d244da8939e66da3b88c56423715",
            "theme/default" to "30edf3c18acf6882267ad37c7cddd60f12b2536c3517c618d36f9ce0610b3e95",
        )

        /** [currentPerResourceFingerprints][CatalogCanonicalizer.currentPerResourceFingerprints] of the wire-v8 golden catalog. */
        val WIRE_V8_V3_RESOURCE_FINGERPRINTS = mapOf(
            "template/letter" to "3ccb2946462c5a73f1e4524617dca7018cf4d244da8939e66da3b88c56423715",
            "theme/default" to "30edf3c18acf6882267ad37c7cddd60f12b2536c3517c618d36f9ce0610b3e95",
        )
    }

    private fun resource(path: String) = requireNotNull(javaClass.getResourceAsStream("/META-INF/epistola-catalog/fixtures/v1/$path"))
}
