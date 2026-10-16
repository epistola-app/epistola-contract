// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.validation

import app.epistola.catalog.archive.ArchiveContentProvider
import app.epistola.catalog.archive.CatalogArchive
import app.epistola.catalog.canonical.CatalogCanonicalizer
import app.epistola.catalog.canonical.CatalogFingerprintVersion
import app.epistola.catalog.migration.CatalogWireSchema.CURRENT_VERSION
import app.epistola.catalog.protocol.AttributeAssignment
import app.epistola.catalog.protocol.AttributeResource
import app.epistola.catalog.protocol.CatalogDependency
import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.CatalogKeywords
import app.epistola.catalog.protocol.CatalogLicense
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.CatalogPresentation
import app.epistola.catalog.protocol.CatalogVariantMetadata
import app.epistola.catalog.protocol.CodeListBindingRef
import app.epistola.catalog.protocol.CodeListEntryEntry
import app.epistola.catalog.protocol.CodeListResource
import app.epistola.catalog.protocol.DataExampleEntry
import app.epistola.catalog.protocol.DependencyRef
import app.epistola.catalog.protocol.FontResource
import app.epistola.catalog.protocol.FontVariantEntry
import app.epistola.catalog.protocol.ImageResource
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
import app.epistola.template.model.ThemeRefOverride
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
        val manifest = fixture("wire-v8/catalog.json").use { mapper.readValue(it, CatalogManifest::class.java) }
        val details = listOf("theme/default", "template/letter").associateWith { key ->
            fixture("wire-v8/resources/$key.json").use { mapper.readValue(it, ResourceDetail::class.java) }
        }
        val archive = archive(manifest, details)

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
            // Wire v8 names the algorithm; earlier wire versions carry bare hex.
            val fingerprint = if (version == CatalogFingerprintVersion.V5) {
                CatalogCanonicalizer.qualifiedFingerprint(withoutFingerprint, version).toString()
            } else {
                CatalogCanonicalizer.fingerprint(withoutFingerprint, version).value
            }
            val catalog = archive(
                manifest(release = ReleaseInfo("1.0.0", fingerprint = fingerprint)),
                emptyMap(),
            )
            when (version) {
                CatalogFingerprintVersion.V5 -> Unit
                CatalogFingerprintVersion.V4 -> catalog.sourceSchemaVersion = 7
                else -> catalog.sourceSchemaVersion = 5
            }
            val report = CatalogValidator.validate(catalog)

            assertTrue(
                report.findings.none { it.code == CatalogValidationCodes.RELEASE_FINGERPRINT_MISMATCH },
                "$version: ${report.findings}",
            )
        }
    }

    @Test
    fun `v6 and v7 source catalogs must still carry a v4 fingerprint after migration`() {
        val withoutFingerprint = archive(manifest(), emptyMap())
        listOf(6, 7).forEach { source ->
            CatalogFingerprintVersion.entries.forEach { version ->
                val fingerprint = CatalogCanonicalizer.fingerprint(withoutFingerprint, version).value
                val catalog = archive(manifest(release = ReleaseInfo("1.0.0", fingerprint = fingerprint)), emptyMap())
                    .also { it.sourceSchemaVersion = source }

                val mismatch = CatalogValidationCodes.RELEASE_FINGERPRINT_MISMATCH in CatalogValidator.validate(catalog).codes()

                assertEquals(version != CatalogFingerprintVersion.V4, mismatch, "v$source $version")
            }
        }
    }

    @Test
    fun `current catalogs must carry a v5 fingerprint that names its algorithm`() {
        val withoutFingerprint = archive(manifest(), emptyMap())
        fun codes(fingerprint: String) = CatalogValidator.validate(archive(manifest(release = ReleaseInfo("1.0.0", fingerprint = fingerprint)), emptyMap()))
            .codes()
            .filter { it == CatalogValidationCodes.RELEASE_FINGERPRINT_INVALID || it == CatalogValidationCodes.RELEASE_FINGERPRINT_MISMATCH }
        CatalogFingerprintVersion.entries.forEach { version ->
            val qualified = CatalogCanonicalizer.qualifiedFingerprint(withoutFingerprint, version)

            // An older algorithm covers less, so naming one is invalid even when it matches.
            val expected = if (version == CatalogFingerprintVersion.V5) emptyList() else listOf(CatalogValidationCodes.RELEASE_FINGERPRINT_INVALID)
            assertEquals(expected, codes(qualified.toString()), version.toString())
            assertEquals(listOf(CatalogValidationCodes.RELEASE_FINGERPRINT_INVALID), codes(qualified.fingerprint.value), "bare $version")
        }
        assertEquals(listOf(CatalogValidationCodes.RELEASE_FINGERPRINT_MISMATCH), codes("v5:" + "0".repeat(64)))
        assertEquals(listOf(CatalogValidationCodes.RELEASE_FINGERPRINT_INVALID), codes("v9:" + "0".repeat(64)))
    }

    @Test
    fun `a catalog names its registry in full and its namespace`() {
        fun findings(
            registry: String?,
            namespace: String?,
        ) = CatalogValidator.validate(
            archive(
                CatalogManifest(
                    CURRENT_VERSION,
                    CatalogInfo("fixture", "Fixture").withNamespace(registry, namespace),
                    PublisherInfo("Epistola"),
                    ReleaseInfo("1.0.0"),
                    resources = emptyList(),
                ),
                emptyMap(),
            ),
        ).findings.map { it.code to it.path }

        assertEquals(emptyList(), findings("acme.com", "acme"))
        assertEquals(emptyList(), findings("exchange.epistola.app", "acme-corp"))
        listOf(null, "acme.com", "exchange.epistola.app/acme-corp", "Acme", "ab").forEach { namespace ->
            assertEquals(
                listOf(CatalogValidationCodes.NAMESPACE_INVALID to "catalog.json.catalog.namespace"),
                findings(REGISTRY, namespace),
                namespace.toString(),
            )
        }
        // An archive has no default registry: `~` and a bare name are not registries.
        listOf(null, "~", "acme", "Acme.com", "acme.com/").forEach { registry ->
            assertEquals(
                listOf(CatalogValidationCodes.REGISTRY_INVALID to "catalog.json.catalog.registry"),
                findings(registry, NAMESPACE),
                registry.toString(),
            )
        }
    }

    @Test
    fun `every catalog a resource dependency names is declared once, and never the catalog itself`() {
        val shared = CatalogDependency(REGISTRY, NAMESPACE, "shared", "^1.2.0", "1.4.0", FINGERPRINT)
        fun findings(
            dependencies: List<DependencyRef>,
            catalogDependencies: List<CatalogDependency>,
        ) = CatalogValidator.validate(
            archive(manifest(dependencies = dependencies, catalogDependencies = catalogDependencies), emptyMap()),
        ).findings.map { it.code to it.path }

        assertEquals(emptyList(), findings(listOf(DependencyRef.Theme("shared", "base", NAMESPACE, REGISTRY)), listOf(shared)))
        // Declared but unused is allowed: a catalog may pin a dependency its content reaches only indirectly.
        assertEquals(emptyList(), findings(emptyList(), listOf(shared)))
        assertEquals(
            listOf(CatalogValidationCodes.DEPENDENCY_CATALOG_UNDECLARED to "catalog.json.dependencies[0]"),
            findings(listOf(DependencyRef.Theme("shared", "base", "acme", REGISTRY)), listOf(shared)),
        )
        // The same namespace and key in another registry is another catalog.
        assertEquals(
            listOf(CatalogValidationCodes.DEPENDENCY_CATALOG_UNDECLARED to "catalog.json.dependencies[0]"),
            findings(listOf(DependencyRef.Theme("shared", "base", NAMESPACE, "acme.com")), listOf(shared)),
        )
        assertEquals(
            listOf(CatalogValidationCodes.DEPENDENCY_CATALOG_UNDECLARED to "catalog.json.dependencies[0]"),
            findings(listOf(DependencyRef.Image("brand", "logo", NAMESPACE, REGISTRY)), listOf(shared)),
        )
        assertEquals(
            listOf(
                CatalogValidationCodes.NAMESPACE_INVALID to "catalog.json.dependencies[0].namespace",
                CatalogValidationCodes.REGISTRY_INVALID to "catalog.json.dependencies[0].registry",
            ),
            findings(listOf(DependencyRef.Theme("shared", "base")), listOf(shared)),
        )
        assertEquals(
            listOf(CatalogValidationCodes.REGISTRY_INVALID to "catalog.json.dependencies[0].registry"),
            findings(listOf(DependencyRef.Theme("shared", "base", NAMESPACE)), listOf(shared)),
        )
        assertEquals(
            listOf(CatalogValidationCodes.DEPENDENCY_CATALOG_DUPLICATE to "catalog.json.catalogDependencies[1]"),
            findings(emptyList(), listOf(shared, shared.copy(version = "^2.0.0"))),
        )
        assertEquals(
            listOf(CatalogValidationCodes.DEPENDENCY_CATALOG_SELF to "catalog.json.catalogDependencies[0]"),
            findings(emptyList(), listOf(CatalogDependency(REGISTRY, NAMESPACE, "fixture", "*", null))),
        )
        assertEquals(
            listOf(CatalogValidationCodes.DEPENDENCY_CATALOG_SELF to "catalog.json.dependencies[0]"),
            findings(listOf(DependencyRef.Theme("fixture", "base", NAMESPACE, REGISTRY)), emptyList()),
        )
        // Not itself: the same namespace and key in another registry.
        assertEquals(emptyList(), findings(emptyList(), listOf(CatalogDependency("acme.com", NAMESPACE, "fixture", "*", null))))
        assertEquals(
            listOf(CatalogValidationCodes.NAMESPACE_INVALID to "catalog.json.catalogDependencies[0].namespace"),
            findings(emptyList(), listOf(shared.copy(namespace = "exchange.epistola.app/epistola"))),
        )
        assertEquals(
            listOf(CatalogValidationCodes.REGISTRY_INVALID to "catalog.json.catalogDependencies[0].registry"),
            findings(emptyList(), listOf(shared.copy(registry = "~"))),
        )
        assertEquals(
            listOf(
                CatalogValidationCodes.DEPENDENCY_CATALOG_VERSION_INVALID to "catalog.json.catalogDependencies[0].releaseVersion",
                CatalogValidationCodes.DEPENDENCY_CATALOG_VERSION_INVALID to "catalog.json.catalogDependencies[0].version",
            ),
            findings(emptyList(), listOf(shared.copy(version = " ", releaseVersion = "latest"))),
        )
        assertEquals(
            listOf(CatalogValidationCodes.DEPENDENCY_CATALOG_VERSION_INVALID to "catalog.json.catalogDependencies[0].version"),
            findings(emptyList(), listOf(shared.copy(version = "1".repeat(CatalogDependency.MAX_VERSION_LENGTH + 1)))),
        )
    }

    @Test
    fun `a catalog dependency records its release fingerprint exactly when it records a release`() {
        val shared = CatalogDependency(REGISTRY, NAMESPACE, "shared", "^1.2.0", "1.4.0", FINGERPRINT)
        fun findings(dependency: CatalogDependency) = CatalogValidator.validate(
            archive(manifest(catalogDependencies = listOf(dependency)), emptyMap()),
        ).findings.map { it.code to it.path }
        val invalid = listOf(CatalogValidationCodes.DEPENDENCY_CATALOG_FINGERPRINT_INVALID to "catalog.json.catalogDependencies[0].releaseFingerprint")

        assertEquals(emptyList(), findings(shared))
        assertEquals(emptyList(), findings(shared.copy(version = "*", releaseVersion = null, releaseFingerprint = null)))
        assertEquals(invalid, findings(shared.copy(releaseFingerprint = null)))
        assertEquals(invalid, findings(shared.copy(releaseVersion = null)))
        assertEquals(invalid, findings(shared.copy(releaseFingerprint = FINGERPRINT.uppercase())))
        assertEquals(invalid, findings(shared.copy(releaseFingerprint = "sha256:${FINGERPRINT.removePrefix("v5:")}")))
        assertEquals(invalid, findings(shared.copy(releaseFingerprint = FINGERPRINT.removePrefix("v5:"))))
        assertEquals(invalid, findings(shared.copy(releaseFingerprint = FINGERPRINT.replace("v5:", "v9:"))))
    }

    @Test
    fun `references inside content default to the owning catalog's registry and namespace and resolve through the resolver`() {
        val resolved = mutableListOf<CatalogResourceReference>()
        val policy = CatalogValidationPolicy(
            dependencyResolver = CatalogDependencyResolver { reference ->
                resolved += reference
                ResourceResolution.MISSING
            },
        )
        val attribute = AttributeResource(
            "locale",
            "Locale",
            codeListBinding = CodeListBindingRef(catalogKey = "shared", key = "countries", namespace = "acme-corp"),
        )
        val sameCatalog = AttributeResource(
            "region",
            "Region",
            codeListBinding = CodeListBindingRef(key = "regions", namespace = NAMESPACE, registry = REGISTRY),
        )
        val invalid = AttributeResource(
            "country",
            "Country",
            codeListBinding = CodeListBindingRef(key = "countries", namespace = "Not A Namespace"),
        )
        // The same namespace in another registry is another catalog, so this resolves elsewhere.
        val otherRegistry = AttributeResource(
            "district",
            "District",
            codeListBinding = CodeListBindingRef(key = "regions", namespace = NAMESPACE, registry = "acme.com"),
        )
        val registryOnly = AttributeResource(
            "province",
            "Province",
            codeListBinding = CodeListBindingRef(key = "regions", registry = "Not.A.Registry"),
        )
        val regions = CodeListResource("regions", "Regions", entries = emptyList())
        val details = listOf(attribute, sameCatalog, invalid, otherRegistry, registryOnly, regions).associate { resource ->
            "${resource.type}/${resource.key}" to ResourceDetail(CURRENT_VERSION, resource)
        }
        val entries = details.map { (key, detail) ->
            ResourceEntry(detail.resource.type, detail.resource.key, detail.resource.name, detailUrl = "./resources/$key.json")
        }

        val report = CatalogValidator.validate(archive(manifest(resources = entries), details), policy)

        assertEquals(
            listOf(
                CatalogValidationCodes.RESOURCE_REFERENCE_MISSING to "resources/attribute/country.json.resource.codeListBinding",
                CatalogValidationCodes.NAMESPACE_INVALID to "resources/attribute/country.json.resource.codeListBinding.namespace",
                CatalogValidationCodes.RESOURCE_REFERENCE_MISSING to "resources/attribute/district.json.resource.codeListBinding",
                CatalogValidationCodes.RESOURCE_REFERENCE_MISSING to "resources/attribute/locale.json.resource.codeListBinding",
                CatalogValidationCodes.RESOURCE_REFERENCE_MISSING to "resources/attribute/province.json.resource.codeListBinding",
                CatalogValidationCodes.NAMESPACE_INVALID to "resources/attribute/province.json.resource.codeListBinding.namespace",
                CatalogValidationCodes.REGISTRY_INVALID to "resources/attribute/province.json.resource.codeListBinding.registry",
            ),
            report.findings.map { it.code to it.path },
        )
        // The owning catalog's registry, namespace and key fill in wherever a reference omits one.
        assertEquals(
            listOf(
                CatalogResourceReference("codeList", "countries", "fixture", "Not A Namespace", REGISTRY),
                CatalogResourceReference("codeList", "countries", "shared", "acme-corp", REGISTRY),
                CatalogResourceReference("codeList", "regions", "fixture", NAMESPACE, "Not.A.Registry"),
                CatalogResourceReference("codeList", "regions", "fixture", NAMESPACE, "acme.com"),
            ),
            resolved.sortedWith(compareBy({ it.key }, { it.catalogKey }, { it.registry })),
        )
    }

    @Test
    fun `a theme reference in a template names a registry only together with a namespace`() {
        fun findings(
            theme: ThemeRefOverride,
            themeRegistry: String? = null,
            themeNamespace: String? = null,
        ): List<Pair<String, String>> {
            val template = TemplateResource(
                key = "letter",
                name = "Letter",
                templateModel = TemplateDocument(
                    root = "root",
                    nodes = mapOf("root" to Node("root", "root", slots = listOf("root-children"))),
                    slots = mapOf("root-children" to Slot("root-children", "root", "children", emptyList())),
                    themeRef = theme,
                ),
                variants = emptyList(),
                themeKey = themeNamespace?.let { "corporate" },
                themeNamespace = themeNamespace,
                themeRegistry = themeRegistry,
            )
            val details = mapOf("template/letter" to ResourceDetail(CURRENT_VERSION, template))
            val entries = listOf(ResourceEntry("template", "letter", "Letter", detailUrl = "./resources/template/letter.json"))
            return CatalogValidator.validate(archive(manifest(resources = entries), details)).findings.map { it.code to it.path }
        }

        assertEquals(emptyList(), findings(ThemeRefOverride("corporate", "shared", "acme", "acme.com")))
        assertEquals(emptyList(), findings(ThemeRefOverride("corporate", "shared", "acme")))
        assertEquals(
            listOf(CatalogValidationCodes.NAMESPACE_INVALID to "resources/template/letter.json.resource.templateModel.themeRef.namespace"),
            findings(ThemeRefOverride("corporate", "shared", registry = "acme.com")),
        )
        assertEquals(
            listOf(CatalogValidationCodes.REGISTRY_INVALID to "resources/template/letter.json.resource.templateModel.themeRef.registry"),
            findings(ThemeRefOverride("corporate", "shared", "acme", "~")),
        )
        assertEquals(
            listOf(CatalogValidationCodes.NAMESPACE_INVALID to "resources/template/letter.json.resource.themeNamespace"),
            findings(ThemeRefOverride("corporate", "shared"), themeRegistry = "acme.com"),
        )
    }

    @Test
    fun `catalog discovery metadata accepts qualified generic attributes and bounded lowercase keywords`() {
        val catalog = CatalogInfo.create(
            "fixture",
            "Fixture",
            attributes = listOf(
                AttributeAssignment("system", "locale", "en_US"),
                AttributeAssignment("fixture", "brand", ""),
            ),
            keywords = (1..CatalogKeywords.MAX_COUNT - 2).mapTo(mutableSetOf()) { "keyword-$it" } +
                setOf("1-loket", "a".repeat(CatalogKeywords.MAX_LENGTH)),
        )

        val report = CatalogValidator.validate(archive(manifest(catalog = catalog), emptyMap()))

        assertTrue(report.valid, report.findings.toString())
    }

    @Test
    fun `catalog keywords must be lowercase ASCII hyphenated terms`() {
        listOf("Government", "getting started", "financiële", "-a", "a-", "a--b", "snake_case", "").forEach { keyword ->
            val catalog = CatalogInfo.create("fixture", "Fixture", keywords = setOf(keyword))

            val report = CatalogValidator.validate(archive(manifest(catalog = catalog), emptyMap()))

            assertEquals(
                listOf(CatalogValidationCodes.KEYWORD_INVALID to "catalog.json.catalog.keywords[0]"),
                report.findings.map { it.code to it.path },
                keyword,
            )
        }
    }

    @Test
    fun `catalog keywords are bounded in length and count`() {
        val tooLong = CatalogInfo.create("fixture", "Fixture", keywords = setOf("a".repeat(CatalogKeywords.MAX_LENGTH + 1)))
        val tooMany = CatalogInfo.create(
            "fixture",
            "Fixture",
            keywords = (0..CatalogKeywords.MAX_COUNT).mapTo(mutableSetOf()) { "keyword-$it" },
        )

        assertEquals(
            listOf(CatalogValidationCodes.KEYWORD_TOO_LONG to "catalog.json.catalog.keywords[0]"),
            CatalogValidator.validate(archive(manifest(catalog = tooLong), emptyMap())).findings.map { it.code to it.path },
        )
        assertEquals(
            listOf(CatalogValidationCodes.KEYWORD_LIMIT_EXCEEDED to "catalog.json.catalog.keywords"),
            CatalogValidator.validate(archive(manifest(catalog = tooMany), emptyMap())).findings.map { it.code to it.path },
        )
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
            "image/icon" to ResourceDetail(CURRENT_VERSION, ImageResource("icon", "Icon", "image/svg+xml", contentUrl = "./resources/asset/icon.svg", contentHash = "0000000000000000000000000000000000000000000000000000000000000000")),
            "image/hero" to ResourceDetail(CURRENT_VERSION, ImageResource("hero", "Hero", "IMAGE/PNG", contentUrl = "./resources/asset/hero.png", contentHash = "0000000000000000000000000000000000000000000000000000000000000000")),
        )
        val entries = details.map { (key, detail) ->
            ResourceEntry(detail.resource.type, detail.resource.key, detail.resource.name, detailUrl = "./resources/$key.json")
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
            "theme/not-asset" to ResourceDetail(CURRENT_VERSION, app.epistola.catalog.protocol.ThemeResource("not-asset", "Theme")),
            "image/document" to ResourceDetail(CURRENT_VERSION, ImageResource("document", "Document", "application/pdf", contentUrl = "./document.pdf", contentHash = "0000000000000000000000000000000000000000000000000000000000000000")),
        )
        val entries = details.map { (key, detail) ->
            ResourceEntry(detail.resource.type, detail.resource.key, detail.resource.name, detailUrl = "./resources/$key.json")
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

    /**
     * One rule for every type (wire v8), and it is the publication gate that has to catch it.
     *
     * A 51-character key is storable nowhere -- consumers store keys in `VARCHAR(50)` -- so without
     * this the publisher is told the catalog is fine and every consumer's install dies on a
     * database error instead. A key shorter than 3 is refused the same way, for every type.
     */
    @Test
    fun `a key outside the one resource key rule is refused, whatever the type`() {
        for (key in listOf("a".repeat(51), "ab", "a", "Upper", "a--b")) {
            for (resource in listOf<app.epistola.catalog.protocol.CatalogResource>(
                app.epistola.catalog.protocol.ThemeResource(key, "Theme"),
                ImageResource(key, "Logo", "image/svg+xml", contentHash = "0".repeat(64)),
            )) {
                val manifest = manifest(resources = listOf(ResourceEntry(resource.type, key, "R", detailUrl = "./resources/${resource.type}/$key.json")))
                val report = CatalogValidator.validate(archive(manifest, mapOf("${resource.type}/$key" to ResourceDetail(CURRENT_VERSION, resource))))
                assertTrue(
                    report.findings.any { it.code == CatalogValidationCodes.RESOURCE_KEY_INVALID && it.path == "resources/${resource.type}/$key.json.resource.key" },
                    "$key: ${report.findings}",
                )
            }
        }
    }

    /** Every type admits a leading digit (wire v8), because an image's key may be a UUID string. */
    @Test
    fun `a key may start with a digit, whatever the type`() {
        val key = "01966a00-0000-7000-8000-000000000001"
        for (resource in listOf<app.epistola.catalog.protocol.CatalogResource>(
            app.epistola.catalog.protocol.ThemeResource(key, "Theme"),
            ImageResource(key, "Logo", "image/svg+xml", contentHash = "0".repeat(64)),
        )) {
            val manifest = manifest(resources = listOf(ResourceEntry(resource.type, key, "R", detailUrl = "./resources/${resource.type}/$key.json")))
            val report = CatalogValidator.validate(archive(manifest, mapOf("${resource.type}/$key" to ResourceDetail(CURRENT_VERSION, resource))))
            assertTrue(CatalogValidationCodes.RESOURCE_KEY_INVALID !in report.codes(), "findings: ${report.findings}")
        }
    }

    /** The positions that name a resource key follow the same rule as the key itself. */
    @Test
    fun `variant keys, variant attribute keys and the theme a template names follow the resource key rule`() {
        val report = validateTemplate(
            letter().copy(
                themeKey = "ab",
                variants = listOf(app.epistola.catalog.protocol.VariantEntry("nl", attributes = mapOf("la" to "nl", "locale" to "nl"))),
            ),
        )

        assertEquals(
            listOf(
                "resources/template/letter.json.resource.themeKey",
                "resources/template/letter.json.resource.variants[0].attributes.la",
                "resources/template/letter.json.resource.variants[0].key",
            ),
            report.findings.filter { it.code == CatalogValidationCodes.RESOURCE_KEY_INVALID }.map { it.path }.sorted(),
        )
    }

    /** Another catalog's attribute is named `<catalogKey>.<key>`, each part by its own rule. */
    @Test
    fun `a variant attribute key may name another catalog's attribute`() {
        val report = validateTemplate(
            letter().copy(
                variants = listOf(
                    app.epistola.catalog.protocol.VariantEntry(
                        "nl",
                        attributes = mapOf("system.locale" to "nl-NL", "locale" to "nl", "system.la" to "nl", "9sys.locale" to "nl", "a.b.locale" to "nl"),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(
                "resources/template/letter.json.resource.variants[0].attributes.9sys.locale",
                "resources/template/letter.json.resource.variants[0].attributes.a.b.locale",
                "resources/template/letter.json.resource.variants[0].attributes.system.la",
                "resources/template/letter.json.resource.variants[0].key",
            ),
            report.findings.filter { it.code == CatalogValidationCodes.RESOURCE_KEY_INVALID }.map { it.path }.sorted(),
        )
    }

    @Test
    fun `manifest and detail findings aggregate deterministically`() {
        val detail = ResourceDetail(3, ImageResource("Bad Slug", "Different", "bad", width = 0, contentUrl = "../asset", contentHash = "0000000000000000000000000000000000000000000000000000000000000000"))
        val manifest = manifest(
            ReleaseInfo("latest", "yesterday", "bad"),
            listOf(ResourceEntry("theme", "declared", "Declared", detailUrl = "wrong.json")),
        )
        val report = CatalogValidator.validate(archive(manifest, mapOf("image/actual" to detail)))

        assertTrue(CatalogValidationCodes.RELEASE_VERSION_INVALID in report.codes())
        assertTrue(CatalogValidationCodes.RELEASE_TIMESTAMP_INVALID in report.codes())
        assertTrue(CatalogValidationCodes.RELEASE_FINGERPRINT_INVALID in report.codes())
        assertTrue(CatalogValidationCodes.MANIFEST_DETAIL_MISSING in report.codes())
        assertTrue(CatalogValidationCodes.MANIFEST_DETAIL_UNDECLARED in report.codes())
        assertTrue(CatalogValidationCodes.RESOURCE_KEY_INVALID in report.codes())
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
        val stencil = StencilResource("address", "Address", content = invalid)
        val details = mapOf(
            "template/invoice" to ResourceDetail(CURRENT_VERSION, template),
            "stencil/address" to ResourceDetail(CURRENT_VERSION, stencil),
        )
        val entries = details.map { (key, detail) ->
            ResourceEntry(detail.resource.type, detail.resource.key, detail.resource.name, detailUrl = "./resources/$key.json")
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
            content = validDocument(),
            parameterSchema = mapOf("type" to "array"),
        )
        val key = "stencil/address"
        val detail = ResourceDetail(CURRENT_VERSION, stencil)
        val report = CatalogValidator.validate(
            archive(
                manifest(resources = listOf(ResourceEntry("stencil", "address", "Address", detailUrl = "./resources/$key.json"))),
                mapOf(key to detail),
            ),
        )

        assertTrue(TemplateValidationCodes.PARAMETER_SCHEMA_INVALID_TYPE in report.codes(), report.findings.toString())
    }

    @Test
    fun `catalog accepts stencil composition within the catalog`() {
        val address = StencilResource("address", "Address", content = validDocument())
        val letter = StencilResource(
            "letter",
            "Letter",
            content = documentWithStencil("nested-address", "address"),
        )

        val report = validateStencils(letter, address)

        assertTrue(report.valid, report.findings.toString())
        assertTrue(report.findings.isEmpty())
    }

    @Test
    fun `catalog rejects a nested stencil that is not present`() {
        val contact = StencilResource("contact", "Contact", content = validDocument())
        val letter = StencilResource(
            "letter",
            "Letter",
            content = documentWithStencil("nested-address", "address"),
        )

        val report = validateStencils(letter, contact)

        assertEquals(
            listOf("resources/stencil/letter.json.resource.content.nodes.nested-address.props.stencilKey"),
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
            content = documentWithStencil("nested-letter", "letter"),
        )

        val report = validateStencils(letter)

        assertEquals(
            listOf("resources/stencil/letter.json.resource.content.nodes.nested-letter.props.stencilKey"),
            report.findings.filter { it.code == TemplateValidationCodes.STENCIL_RECURSION }.map { it.path },
        )
    }

    @Test
    fun `catalog rejects transitive recursion across stencil resources`() {
        val letter = StencilResource(
            "letter",
            "Letter",
            content = documentWithStencil("nested-address", "address"),
        )
        val address = StencilResource(
            "address",
            "Address",
            content = documentWithStencil("nested-letter", "letter"),
        )

        val report = validateStencils(letter, address)

        assertEquals(
            listOf(
                "resources/stencil/letter.json.resource.content.nodes.nested-address.props.stencilKey",
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
            themeKey = "missing",
            templateModel = validDocument(),
            variants = emptyList(),
        )
        val key = "template/invoice"
        val detail = ResourceDetail(CURRENT_VERSION, template)
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
            // A face no longer names a resource, so the missing-reference case is covered by a
            // reference that still is one.
            themeKey = "absent",
        )
        val resources = listOf(
            template,
            AttributeResource("locale", "Locale", listOf("nl"), CodeListBindingRef(key = "countries")),
            CodeListResource("countries", "Countries", entries = listOf(CodeListEntryEntry("NL", "NL"), CodeListEntryEntry("NL", "Duplicate"))),
            ImageResource("face", "Face", "bad", width = -1, contentUrl = "./resources/asset/missing.woff2", contentHash = "0000000000000000000000000000000000000000000000000000000000000000"),
            FontResource(
                "brand",
                "Brand",
                "unknown",
                listOf(
                    FontVariantEntry(
                        weight = 0,
                        italic = false,
                        mediaType = "font/ttf",
                        contentHash = "0000000000000000000000000000000000000000000000000000000000000000",
                        contentUrl = "./resources/font/missing.woff2",
                    ),
                    FontVariantEntry(
                        weight = 0,
                        italic = false,
                        mediaType = "font/ttf",
                        contentHash = "0000000000000000000000000000000000000000000000000000000000000000",
                        contentUrl = "./resources/font/missing.woff2",
                    ),
                ),
            ),
        )
        val details = resources.associate { "${it.type}/${it.key}" to ResourceDetail(CURRENT_VERSION, it) }
        val entries = details.map { (key, detail) ->
            ResourceEntry(detail.resource.type, detail.resource.key, detail.resource.name, detailUrl = "./resources/$key.json")
        }
        val report = CatalogValidator.validate(archive(manifest(resources = entries), details))

        assertTrue(CatalogValidationCodes.TEMPLATE_DATA_EXAMPLE_INVALID in report.codes())
        assertTrue(CatalogValidationCodes.TEMPLATE_EXAMPLE_NAME_DUPLICATE in report.codes())
        assertTrue(CatalogValidationCodes.TEMPLATE_VARIANT_KEY_DUPLICATE in report.codes())
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
        val detail = ResourceDetail(CURRENT_VERSION, resource)
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
    fun `data example keys are required, well-formed and unique within their template`() {
        fun codes(vararg examples: DataExampleEntry) = validateTemplate(letter(dataExamples = examples.toList())).codes()

        assertTrue(
            listOf(CatalogValidationCodes.TEMPLATE_EXAMPLE_KEY_INVALID, CatalogValidationCodes.TEMPLATE_EXAMPLE_KEY_DUPLICATE)
                .none { it in codes(DataExampleEntry("A", emptyMap(), "abc"), DataExampleEntry("B", emptyMap(), "1-b")) },
        )
        assertTrue(CatalogValidationCodes.TEMPLATE_EXAMPLE_KEY_INVALID in codes(DataExampleEntry("A", emptyMap())), "a key is required")
        listOf("", "a", "ab", "Upper", "a_b", "-ab", "ab-", "a".repeat(51)).forEach { key ->
            assertTrue(CatalogValidationCodes.TEMPLATE_EXAMPLE_KEY_INVALID in codes(DataExampleEntry("A", emptyMap(), key)), key)
        }
        val duplicate = validateTemplate(letter(dataExamples = listOf(DataExampleEntry("A", emptyMap(), "same"), DataExampleEntry("B", emptyMap(), "same"))))
        assertEquals(
            listOf(CatalogValidationCodes.TEMPLATE_EXAMPLE_KEY_DUPLICATE to "resources/template/letter.json.resource.dataExamples[1].key"),
            duplicate.findings.map { it.code to it.path },
        )
        // The name rule is unchanged and separate.
        assertTrue(CatalogValidationCodes.TEMPLATE_EXAMPLE_NAME_DUPLICATE in codes(DataExampleEntry("A", emptyMap(), "a"), DataExampleEntry("A", emptyMap(), "b")))
    }

    @Test
    fun `a template description is at most 2000 characters and is the source of its manifest entry's`() {
        val limit = "é".repeat(TemplateResource.MAX_DESCRIPTION_LENGTH)

        assertEquals(emptyList(), validateTemplate(letter(description = limit), entryDescription = limit).findings)
        assertEquals(emptyList(), validateTemplate(letter(description = "Mine."), entryDescription = null).findings)
        assertEquals(emptyList(), validateTemplate(letter(description = null), entryDescription = "A listing summary.").findings)
        assertEquals(
            listOf(CatalogValidationCodes.TEMPLATE_DESCRIPTION_INVALID to "resources/template/letter.json.resource.description"),
            validateTemplate(letter(description = limit + "x")).findings.map { it.code to it.path },
        )
        assertEquals(
            listOf(CatalogValidationCodes.RESOURCE_DESCRIPTION_MISMATCH to "resources/template/letter.json.resource.description"),
            validateTemplate(letter(description = "Mine."), entryDescription = "Theirs.").findings.map { it.code to it.path },
        )
    }

    @Test
    fun `variant metadata names one to sixteen reverse-DNS namespaces and stays within 8 KiB`() {
        fun findings(metadata: Map<String, Map<String, Any?>>) = validateTemplate(letter(metadata = metadata)).findings.map { it.code to it.path }
        val path = "resources/template/letter.json.resource.variants[0].metadata"

        assertEquals(emptyList(), findings(mapOf("app.epistola.suite" to mapOf("renderingDefaultsVersion" to 6))))
        assertEquals(emptyList(), findings((1..16).associate { "com.example.n$it" to emptyMap() }))
        assertEquals(emptyList(), findings(mapOf("a.b" to mapOf("x" to "y".repeat(CatalogVariantMetadata.MAX_SERIALIZED_BYTES - 20)))))
        assertEquals(listOf(CatalogValidationCodes.VARIANT_METADATA_INVALID to path), findings(emptyMap()))
        assertEquals(listOf(CatalogValidationCodes.VARIANT_METADATA_INVALID to path), findings((1..17).associate { "com.example.n$it" to emptyMap() }))
        listOf("suite", "App.epistola.suite", "app..suite", "app.epistola.", "1app.suite", "app.epistola_suite", "app." + "a".repeat(97)).forEach { namespace ->
            assertEquals(listOf(CatalogValidationCodes.VARIANT_METADATA_INVALID to "$path.$namespace"), findings(mapOf(namespace to emptyMap())), namespace)
        }
        assertEquals(
            listOf(CatalogValidationCodes.VARIANT_METADATA_TOO_LARGE to path),
            findings(mapOf("a.b" to mapOf("x" to "y".repeat(CatalogVariantMetadata.MAX_SERIALIZED_BYTES)))),
        )
    }

    @Test
    fun `a v8 detailUrl must be a relative path below the manifest, where earlier versions only had to name the canonical file`() {
        fun findings(
            detailUrl: String,
            sourceVersion: Int,
        ): List<Pair<String, String>> {
            val detail = ResourceDetail(CURRENT_VERSION, letter())
            val catalog = archive(manifest(resources = listOf(ResourceEntry("template", "letter", "Letter", detailUrl = detailUrl))), mapOf("template/letter" to detail))
            catalog.sourceSchemaVersion = sourceVersion
            return CatalogValidator.validate(catalog).findings.map { it.code to it.path }
        }
        val path = "catalog.json.resources[0].detailUrl"

        assertEquals(emptyList(), findings("./resources/template/letter.json", 8))
        assertEquals(emptyList(), findings("resources/template/letter.json", 8))
        listOf("https://x.example/resources/template/letter.json", "/resources/template/letter.json", "../resources/template/letter.json", "resources/template/%2e%2e/letter.json").forEach {
            assertEquals(listOf(CatalogValidationCodes.MANIFEST_DETAIL_URL_INVALID to path), findings(it, 8), it)
            assertEquals(listOf(CatalogValidationCodes.MANIFEST_DETAIL_PATH_INVALID to path), findings(it, 7), it)
        }
        // Well-formed, but not where the resource's detail lives.
        assertEquals(listOf(CatalogValidationCodes.MANIFEST_DETAIL_PATH_INVALID to path), findings("./resources/template/other.json", 8))
    }

    @Test
    fun `a v8 contentUrl must be a relative path below the manifest, where earlier versions had to start with dot slash`() {
        val hash = "0".repeat(64)
        fun findings(
            contentUrl: String,
            sourceVersion: Int,
        ): List<String> {
            val detail = ResourceDetail(CURRENT_VERSION, ImageResource("logo", "Logo", "image/png", contentHash = hash, contentUrl = contentUrl))
            val catalog = archive(
                manifest(resources = listOf(ResourceEntry("image", "logo", "Logo", detailUrl = "./resources/image/logo.json"))),
                mapOf("image/logo" to detail),
                additionalPaths = setOf("bin/$hash", "assets/logo.png"),
            )
            catalog.sourceSchemaVersion = sourceVersion
            return CatalogValidator.validate(catalog, CatalogValidationPolicy(verifyFingerprint = false)).findings
                .filter { it.code == CatalogValidationCodes.ASSET_PATH_INVALID }.map { it.path }
        }
        val invalid = listOf("resources/image/logo.json.resource.contentUrl")

        assertEquals(emptyList(), findings("bin/$hash", 8))
        assertEquals(emptyList(), findings("./assets/logo.png", 8))
        assertEquals(invalid, findings("../assets/logo.png", 8))
        assertEquals(invalid, findings("assets/%2E%2E/logo.png", 8))
        assertEquals(invalid, findings("bin/$hash", 7), "before v8 a declared path starts with ./")
        assertEquals(emptyList(), findings("./assets/logo.png", 6))
    }

    private fun letter(
        description: String? = null,
        dataExamples: List<DataExampleEntry>? = null,
        metadata: Map<String, Map<String, Any?>>? = null,
    ) = TemplateResource(
        "letter",
        "Letter",
        dataExamples = dataExamples,
        templateModel = validDocument(),
        variants = listOf(VariantEntry("english", metadata = metadata)),
        description = description,
    )

    private fun validateTemplate(
        template: TemplateResource,
        entryDescription: String? = null,
    ): CatalogValidationReport = CatalogValidator.validate(
        archive(
            manifest(resources = listOf(ResourceEntry("template", template.key, template.name, entryDescription, detailUrl = "./resources/template/${template.key}.json"))),
            mapOf("template/${template.key}" to ResourceDetail(CURRENT_VERSION, template)),
        ),
    )

    @Test
    fun `finding code registry covers every stable catalog finding code`() {
        val fixtureCodes = fixture("catalog-validation-cases.json").use(mapper::readTree).propertyNames().toSet()
        val publishedCodes = CatalogValidationCodes::class.java.declaredFields
            .filter { it.type == String::class.java }
            .map { it.get(null) as String }
            .toSet()

        assertEquals(publishedCodes, fixtureCodes)
    }

    /** A current manifest; a [catalog] naming no namespace is given [REGISTRY] and [NAMESPACE]. */
    private fun manifest(
        release: ReleaseInfo = ReleaseInfo("1.0.0"),
        resources: List<ResourceEntry> = emptyList(),
        catalog: CatalogInfo = CatalogInfo("fixture", "Fixture"),
        dependencies: List<DependencyRef>? = null,
        catalogDependencies: List<CatalogDependency>? = null,
    ) = CatalogManifest(
        CURRENT_VERSION,
        catalog.takeIf { it.namespace != null } ?: catalog.withNamespace(REGISTRY, NAMESPACE),
        PublisherInfo("Epistola"),
        release,
        resources = resources,
        dependencies = dependencies,
        catalogDependencies = catalogDependencies,
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
        extraProps: Map<String, Any?> = emptyMap(),
    ): TemplateDocument {
        val nested = Node(
            nodeId,
            "stencil",
            slots = listOf("$nodeId-children"),
            props = mapOf("stencilKey" to slug) + extraProps,
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
            "stencil/${stencil.key}" to ResourceDetail(CURRENT_VERSION, stencil)
        }
        val entries = stencils.map { stencil ->
            ResourceEntry(
                "stencil",
                stencil.key,
                stencil.name,
                detailUrl = "./resources/stencil/${stencil.key}.json",
            )
        }
        return CatalogValidator.validate(archive(manifest(resources = entries), details))
    }

    private fun compositionCatalogScenario(scenario: String): CatalogValidationReport = when (scenario) {
        "CATALOG_REFERENCE_RESOLVES_IN_CATALOG" -> validateStencils(
            StencilResource(
                "letter",
                "Letter",
                content = documentWithStencil("nested-address", "address"),
            ),
            StencilResource("address", "Address", content = validDocument()),
        )
        "CATALOG_REFERENCE_MISSING" -> validateStencils(
            StencilResource(
                "letter",
                "Letter",
                content = documentWithStencil("nested-address", "address"),
            ),
            StencilResource("contact", "Contact", content = validDocument()),
        )
        "CATALOG_REFERENCE_PINS_VERSION" -> validateStencils(
            StencilResource(
                "letter",
                "Letter",
                content = documentWithStencil("nested-address", "address", mapOf("version" to 2)),
            ),
            StencilResource("address", "Address", content = validDocument()),
        )
        "CATALOG_TRANSITIVE_RESOURCE_CYCLE" -> validateStencils(
            StencilResource(
                "letter",
                "Letter",
                content = documentWithStencil("nested-address", "address"),
            ),
            StencilResource(
                "address",
                "Address",
                content = documentWithStencil("nested-letter", "letter"),
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
            key = "stencil-$index",
            name = "Stencil $index",
            content = if (next < depth) {
                documentWithStencil("nested-stencil-$next", "stencil-$next")
            } else {
                validDocument()
            },
        )
    }

    private fun CatalogValidationReport.codes() = findings.map(CatalogValidationFinding::code).toSet()

    private fun fixture(path: String) = requireNotNull(
        javaClass.getResourceAsStream("/META-INF/epistola-catalog/fixtures/v1/$path"),
    )

    private companion object {
        const val REGISTRY = "exchange.epistola.app"
        const val NAMESPACE = "epistola"
        const val FINGERPRINT = "v5:69e540ae7bea571f81ff51919c8fd7d4dfca8d792da9fcc5a559034218feaa42"
    }
}
