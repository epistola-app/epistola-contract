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
import app.epistola.catalog.protocol.CatalogResource
import app.epistola.catalog.protocol.CatalogThemeRef
import app.epistola.catalog.protocol.CompatibilityInfo
import app.epistola.catalog.protocol.DependencyRef
import app.epistola.catalog.protocol.IncludeEntry
import app.epistola.catalog.protocol.PublisherInfo
import app.epistola.catalog.protocol.ReleaseBase
import app.epistola.catalog.protocol.ReleaseInfo
import app.epistola.catalog.protocol.ResourceDetail
import app.epistola.catalog.protocol.StencilResource
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.catalog.protocol.ThemeResource
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
        // V5 keeps the value it was published with; V6 is the current algorithm.
        listOf(CatalogFingerprintVersion.V5, CatalogFingerprintVersion.V6).forEach { version ->
            assertEquals(expected["wireV8CatalogFingerprints"][version.name].asString(), CatalogCanonicalizer.fingerprint(archive, version).value, version.name)
        }
        assertEquals(CatalogFingerprintVersion.V6, CatalogCanonicalizer.CURRENT_FINGERPRINT_VERSION)
        assertEquals("v6:${expected["wireV8CatalogFingerprints"]["V6"].asString()}", CatalogCanonicalizer.currentQualifiedFingerprint(archive).toString())
    }

    @Test
    fun `v6 covers content only, so catalogs that differ only in name, presentation or release have the same fingerprint`() {
        val original = wireV8Archive()
        val manifest = original.manifest
        val info = manifest.catalog
        val renamed = CatalogInfo.create(
            key = "letters",
            name = "Brieven",
            description = "Another description",
            attributes = listOf(AttributeAssignment("system", "locale", "en-GB")),
            keywords = setOf("letters"),
            presentation = CatalogPresentation("default"),
            license = info.license,
            namespace = "gemeente-x",
            registry = "gemeente-x.nl",
            defaultTheme = info.defaultTheme,
            presets = info.presets,
        )
        val other = original.copyWithManifest(
            manifest.copy(
                catalog = renamed,
                publisher = PublisherInfo("Gemeente X", "https://gemeente-x.nl"),
                includes = listOf(IncludeEntry("https://gemeente-x.nl/letters.html", "About")),
                release = ReleaseInfo("1.3.1", "2026-10-22T00:00:00Z", "v6:${"0".repeat(64)}", "r4t8w2nc", base = ReleaseBase("1.3.0", "v6:${"1".repeat(64)}")),
                // The dependency renamed and mirrored under another name, pinned to the same release under the same alias.
                catalogDependencies = manifest.catalogDependencies.orEmpty().map {
                    it.copy(registry = "registry.gemeente-x.nl", namespace = "mirror", catalogKey = "shared-mirror", releaseVersion = "1.4.0")
                },
            ),
        )

        assertEquals(CatalogCanonicalizer.fingerprint(original, CatalogFingerprintVersion.V6), CatalogCanonicalizer.fingerprint(other, CatalogFingerprintVersion.V6))
        assertEquals(CatalogCanonicalizer.currentFingerprint(original), CatalogCanonicalizer.currentFingerprint(other))
        // V5 covered the name, so it tells the two apart; it keeps doing so.
        assertNotEquals(CatalogCanonicalizer.fingerprint(original, CatalogFingerprintVersion.V5), CatalogCanonicalizer.fingerprint(other, CatalogFingerprintVersion.V5))
    }

    @Test
    fun `v6 identifies a release across wire versions, so the same content under another schemaVersion has the same v6`() {
        val expected = resource("fingerprints.json").use(mapper::readTree)
        val v8 = wireV8Archive()
        val golden = goldenArchive()
        fun stated(catalog: CatalogArchive, schemaVersion: Int) = catalog.copyWithManifest(catalog.manifest.copy(schemaVersion = schemaVersion))

        listOf(4, 7, 9, 12).forEach { schemaVersion ->
            assertEquals(expected["wireV8CatalogFingerprints"]["V6"].asString(), CatalogCanonicalizer.currentFingerprint(stated(v8, schemaVersion)).value, "$schemaVersion")
            assertEquals(expected["catalogFingerprints"]["V6"].asString(), CatalogCanonicalizer.currentFingerprint(stated(golden, schemaVersion)).value, "$schemaVersion")
            // V5 never covered the wire version either, and keeps the values it was published with.
            assertEquals(expected["wireV8CatalogFingerprints"]["V5"].asString(), CatalogCanonicalizer.fingerprint(stated(v8, schemaVersion), CatalogFingerprintVersion.V5).value, "$schemaVersion")
            assertEquals(expected["catalogFingerprints"]["V5"].asString(), CatalogCanonicalizer.fingerprint(stated(golden, schemaVersion), CatalogFingerprintVersion.V5).value, "$schemaVersion")
        }
    }

    @Test
    fun `v6 covers every resource, the content settings, the pins by alias, the resource index and the compatibility`() {
        val base = wireV8Archive()
        val template = base.resourceDetails.getValue("template/letter").resource as TemplateResource
        fun changed(edit: (CatalogManifest) -> CatalogManifest) = base.copyWithManifest(edit(base.manifest))
        val shared = base.manifest.catalogDependencies.orEmpty().single()
        val variants = mapOf(
            "a resource" to inMemory(base, template.copy(description = "Another description.")),
            "the default theme" to changed { it.copy(catalog = it.catalog.copyWithMetadata(defaultTheme = CatalogThemeRef("default"))) },
            "the default theme's alias" to changed { it.copy(catalog = it.catalog.copyWithMetadata(defaultTheme = CatalogThemeRef("default", "shared"))) },
            "the presets" to changed { it.copy(catalog = it.catalog.copyWithMetadata(presets = listOf(CatalogPresetDeclaration("muted", "Muted")))) },
            "the license" to changed { it.copy(catalog = it.catalog.copyWithMetadata(license = CatalogLicense("Proprietary"))) },
            "no license" to changed { it.copy(catalog = it.catalog.copyWithMetadata(license = null)) },
            "the compatibility" to changed { it.copy(compatibility = CompatibilityInfo(">=2.0.0")) },
            "a pinned release" to changed { it.copy(catalogDependencies = listOf(shared.copy(releaseFingerprint = "v6:${"0".repeat(64)}"))) },
            "an alias" to changed {
                it.copy(
                    catalogDependencies = listOf(shared.copy(alias = "acme-shared")),
                    dependencies = it.dependencies.orEmpty().map { dependency -> DependencyRef.Font("acme-shared", dependency.key) },
                )
            },
            "a dependency declared" to changed { it.copy(catalogDependencies = it.catalogDependencies.orEmpty() + CatalogDependency("acme.com", "acme", "brand", null)) },
            "the resource dependency index" to changed { it.copy(dependencies = emptyList()) },
            "a resource entry's name" to changed { it.copy(resources = it.resources.map { entry -> entry.copy(name = "${entry.name} 2") }) },
        )

        variants.forEach { (what, variant) ->
            assertNotEquals(CatalogCanonicalizer.currentFingerprint(base), CatalogCanonicalizer.currentFingerprint(variant), what)
        }
        // Order carries no meaning: presets and dependencies are sorted.
        val two = changed { it.copy(catalogDependencies = it.catalogDependencies.orEmpty() + CatalogDependency("acme.com", "acme", "brand", null)) }
        assertEquals(
            CatalogCanonicalizer.currentFingerprint(two),
            CatalogCanonicalizer.currentFingerprint(two.copyWithManifest(two.manifest.copy(catalogDependencies = two.manifest.catalogDependencies.orEmpty().reversed()))),
        )
    }

    @Test
    fun `v5 covers a template's description and data example keys, and the legacy algorithms do not`() {
        val original = wireV8Archive()
        val template = original.resourceDetails.getValue("template/letter").resource as TemplateResource
        val changes = listOf(
            template.copy(description = "Another description."),
            template.copy(dataExamples = template.dataExamples.orEmpty().map { it.copy(key = "other-key") }),
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
        val changes = mapOf(
            "a template's description" to template.copy(description = "Another description."),
            "a data example's key" to template.copy(dataExamples = template.dataExamples.orEmpty().map { it.copy(key = "other-key") }),
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
        fun withDataModel(json: String) = inMemory(
            original,
            template.copy(
                dataModel = mapper.readValue(json, Map::class.java).let {
                    @Suppress("UNCHECKED_CAST")
                    (it as Map<String, Any?>)
                },
            ),
        )

        assertEquals(
            CatalogCanonicalizer.completePerResourceFingerprints(withDataModel("""{"type":"object","properties":{"b":{"type":"string"},"a":{"minimum":1.0}}}""")),
            CatalogCanonicalizer.completePerResourceFingerprints(withDataModel("""{"properties":{"a":{"minimum":1.0},"b":{"type":"string"}},"type":"object"}""")),
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
    fun `no fingerprint of any algorithm covers a resource's metadata`() {
        val original = wireV8Archive()
        val template = original.resourceDetails.getValue("template/letter").resource as TemplateResource
        val theme = original.resourceDetails.getValue("theme/default").resource as ThemeResource
        val versions = listOf(
            CatalogFingerprintVersion.V1,
            CatalogFingerprintVersion.V2,
            CatalogFingerprintVersion.V3,
            CatalogFingerprintVersion.V4,
            CatalogFingerprintVersion.V5,
            CatalogFingerprintVersion.V6,
        )
        val base = inMemory(original, template.copy(metadata = null))
        val variants = listOf(
            inMemory(original, template.copy(metadata = mapOf("app.epistola.suite" to mapOf("renderingDefaultsVersion" to 7)))),
            inMemory(original, template.copy(metadata = mapOf("a.example" to mapOf("v" to true), "b.example" to mapOf("x" to listOf(1, 2))))),
            inMemory(base, theme.copy(metadata = mapOf("com.example.authoring" to mapOf("reviewedBy" to "design")))),
        )

        variants.forEach { changed ->
            versions.forEach { version ->
                assertEquals(CatalogCanonicalizer.fingerprint(base, version), CatalogCanonicalizer.fingerprint(changed, version), version.toString())
            }
            assertEquals(CatalogCanonicalizer.perResourceFingerprints(base), CatalogCanonicalizer.perResourceFingerprints(changed))
            assertEquals(CatalogCanonicalizer.currentPerResourceFingerprints(base), CatalogCanonicalizer.currentPerResourceFingerprints(changed))
            assertEquals(CatalogCanonicalizer.completePerResourceFingerprints(base), CatalogCanonicalizer.completePerResourceFingerprints(changed))
        }
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

    /** [catalog] with [resource] in place of the one at its address, and no files, so every algorithm reads the model. */
    private fun inMemory(
        catalog: CatalogArchive,
        resource: CatalogResource,
    ) = CatalogArchive(
        catalog.manifest,
        catalog.resourceDetails + ("${resource.type}/${resource.key}" to ResourceDetail(CURRENT_VERSION, resource)),
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

        val v5 = CatalogFingerprintVersion.V5
        assertNotEquals(CatalogCanonicalizer.fingerprint(original, v5), CatalogCanonicalizer.fingerprint(changedCompatibility, v5))
        assertNotEquals(CatalogCanonicalizer.fingerprint(original, v5), CatalogCanonicalizer.fingerprint(changedIncludes, v5))
        // V6: the compatibility declaration is content, the linked documents are presentation.
        assertNotEquals(CatalogCanonicalizer.currentFingerprint(original), CatalogCanonicalizer.currentFingerprint(changedCompatibility))
        assertEquals(CatalogCanonicalizer.currentFingerprint(original), CatalogCanonicalizer.currentFingerprint(changedIncludes))
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

        val v5 = CatalogFingerprintVersion.V5
        assertNotEquals(CatalogCanonicalizer.fingerprint(base, v5), CatalogCanonicalizer.fingerprint(keyword, v5))
        assertNotEquals(CatalogCanonicalizer.fingerprint(base, v5), CatalogCanonicalizer.fingerprint(attribute, v5))
        assertEquals(CatalogCanonicalizer.fingerprint(base, v5), CatalogCanonicalizer.fingerprint(reordered, v5))
        assertNotEquals(CatalogCanonicalizer.fingerprint(base, v5), CatalogCanonicalizer.fingerprint(presentation, v5))
        assertNotEquals(CatalogCanonicalizer.fingerprint(base, v5), CatalogCanonicalizer.fingerprint(license, v5))
        // V6 leaves discovery metadata out: it is presentation, information about the name. The license is content.
        listOf(keyword, attribute, reordered, presentation).forEach {
            assertEquals(CatalogCanonicalizer.currentFingerprint(base), CatalogCanonicalizer.currentFingerprint(it))
        }
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

        val v5 = CatalogFingerprintVersion.V5
        variants.forEach { (what, variant) ->
            assertNotEquals(CatalogCanonicalizer.fingerprint(base, v5), CatalogCanonicalizer.fingerprint(variant, v5), what)
            assertEquals(
                CatalogCanonicalizer.fingerprint(base, CatalogFingerprintVersion.V4),
                CatalogCanonicalizer.fingerprint(variant, CatalogFingerprintVersion.V4),
                what,
            )
        }
        assertEquals(
            CatalogCanonicalizer.fingerprint(base, v5),
            CatalogCanonicalizer.fingerprint(changed { it.copy(catalogDependencies = listOf(brand, shared)) }, v5),
        )
        // V6 covers what the catalog renders with, not who it or its dependencies are, nor a pin's number.
        listOf("namespace", "registry", "dependency namespace", "declared registry", "pinned release").forEach { what ->
            assertEquals(CatalogCanonicalizer.currentFingerprint(base), CatalogCanonicalizer.currentFingerprint(variants.getValue(what)), what)
        }
        listOf("release fingerprint", "declared catalogs").forEach { what ->
            assertNotEquals(CatalogCanonicalizer.currentFingerprint(base), CatalogCanonicalizer.currentFingerprint(variants.getValue(what)), what)
        }
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

        assertEquals(expected["wireV8CatalogFingerprints"]["V5"].asString(), CatalogCanonicalizer.fingerprint(explicit, CatalogFingerprintVersion.V5).value)
        assertEquals(expected["catalogFingerprints"]["V5"].asString(), CatalogCanonicalizer.fingerprint(goldenArchive(), CatalogFingerprintVersion.V5).value)
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
            listOf(CatalogFingerprintVersion.V5, CatalogFingerprintVersion.V6).forEach { version ->
                assertNotEquals(CatalogCanonicalizer.fingerprint(base, version), CatalogCanonicalizer.fingerprint(variant, version), "$what under $version")
            }
            CatalogFingerprintVersion.entries.filter { it < CatalogFingerprintVersion.V5 }.forEach { version ->
                assertEquals(CatalogCanonicalizer.fingerprint(base, version), CatalogCanonicalizer.fingerprint(variant, version), "$what under $version")
            }
        }
        assertNotEquals(
            CatalogCanonicalizer.currentFingerprint(variants.getValue("default theme")),
            CatalogCanonicalizer.currentFingerprint(variants.getValue("default theme in another catalog")),
        )
        listOf(CatalogFingerprintVersion.V5, CatalogFingerprintVersion.V6).forEach { version ->
            assertEquals(
                CatalogCanonicalizer.fingerprint(withPresets, version),
                CatalogCanonicalizer.fingerprint(changed { it.copyWithMetadata(presets = listOf(label, muted)) }, version),
                "preset order carries no meaning under $version",
            )
        }
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
            CatalogCanonicalizer.fingerprint(legacy, CatalogFingerprintVersion.V5),
            CatalogCanonicalizer.fingerprint(current, CatalogFingerprintVersion.V5),
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
