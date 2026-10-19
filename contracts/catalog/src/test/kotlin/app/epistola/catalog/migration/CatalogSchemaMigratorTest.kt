// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.migration

import app.epistola.catalog.protocol.CatalogDependency
import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.CatalogKeys
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.PublisherInfo
import app.epistola.catalog.protocol.ReleaseInfo
import app.epistola.catalog.protocol.StencilResource
import app.epistola.catalog.protocol.TemplateResource
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogSchemaMigratorTest {
    private val mapper = jsonMapper { addModule(kotlinModule()) }

    @Test
    fun `v4 golden wire version migrates to current`() {
        val result = CatalogSchemaMigrator.migrateManifest(resource("wire-v4/catalog.json"), OPTIONS)

        assertTrue(result.valid)
        assertEquals(4, result.sourceVersion)
        assertEquals(CatalogWireSchema.CURRENT_VERSION, assertNotNull(result.value).schemaVersion)
        assertEquals("fixture", result.value.catalog.key)
        assertEquals(emptyList(), result.value.catalog.attributes)
        assertEquals(emptySet(), result.value.catalog.keywords)
        assertEquals(null, result.value.catalog.license)
    }

    @Test
    fun `current golden wire version binds without migration`() {
        val result = CatalogSchemaMigrator.migrateManifest(resource("wire-v8/catalog.json"))

        assertTrue(result.valid)
        assertEquals(CatalogWireSchema.CURRENT_VERSION, result.sourceVersion)
        assertTrue(result.notices.isEmpty())
        assertEquals("fixture", assertNotNull(result.value).catalog.key)
        assertEquals(REGISTRY, result.value.catalog.registry)
        assertEquals(NAMESPACE, result.value.catalog.namespace)
        assertEquals(
            listOf(CatalogDependency(REGISTRY, NAMESPACE, "shared", "1.4.0", SHARED_FINGERPRINT)),
            result.value.catalogDependencies,
        )
        assertEquals(REGISTRY, result.value.dependencies.orEmpty().single().registry)
        assertEquals(NAMESPACE, result.value.dependencies.orEmpty().single().namespace)
    }

    @Test
    fun `v6 golden wire version with conforming keywords migrates without notices`() {
        val result = CatalogSchemaMigrator.migrateManifest(resource("wire-v6/catalog.json"), OPTIONS)

        assertTrue(result.valid, result.findings.toString())
        assertEquals(6, result.sourceVersion)
        assertEquals(CatalogWireSchema.CURRENT_VERSION, assertNotNull(result.value).schemaVersion)
        assertEquals(setOf("documents", "government"), result.value.catalog.keywords)
        assertTrue(result.notices.none { it.code.startsWith("CATALOG_KEYWORD_") }, result.notices.toString())
    }

    @Test
    fun `sub-current version is rejected even when it resembles the current shape`() {
        val bytes = resource("wire-v4/catalog.json").readAllBytes()
            .toString(Charsets.UTF_8)
            .replace("\"schemaVersion\": 4", "\"schemaVersion\": 3")
            .toByteArray()
        val result = CatalogSchemaMigrator.migrateManifest(ByteArrayInputStream(bytes))

        assertEquals(CatalogMigrationCodes.SCHEMA_TOO_OLD, result.findings.single().code)
        assertEquals(3, result.sourceVersion)
    }

    @Test
    fun `newer and malformed manifests return findings`() {
        val tooNew = minimalManifest(CatalogWireSchema.CURRENT_VERSION + 1)
        val malformed = CatalogSchemaMigrator.migrateManifest(ByteArrayInputStream("{".toByteArray()))

        assertEquals(CatalogMigrationCodes.SCHEMA_TOO_NEW, tooNew.findings.single().code)
        assertEquals(CatalogMigrationCodes.SCHEMA_UNKNOWN, malformed.findings.single().code)
    }

    @Test
    fun `v5 migration supplies empty optional discovery metadata without inventing attributes`() {
        val result = CatalogSchemaMigrator.migrateManifest(resource("wire-v5/catalog.json"), OPTIONS)
        val catalog = assertNotNull(result.value).catalog
        val tree = resource("wire-v5/catalog.json").use(mapper::readTree) as ObjectNode
        val step = CatalogV5ToV6Migration().migrateManifest(tree)

        assertTrue(result.valid)
        assertEquals(5, result.sourceVersion)
        assertEquals(emptyList(), catalog.attributes)
        assertEquals(emptySet(), catalog.keywords)
        assertEquals(null, catalog.license)
        assertEquals(
            listOf(CatalogMigrationCodes.REGISTRY_ASSIGNED, CatalogMigrationCodes.NAMESPACE_ASSIGNED),
            result.notices.map { it.code },
        )
        assertEquals(resource("migrations/v5-to-v6/manifest-expected.json").use(mapper::readTree), tree)
        assertEquals(resource("migrations/v5-to-v6/notices.json").use(mapper::readTree), mapper.valueToTree(step.notices))
    }

    @Test
    fun `v6 keywords normalize with notices while keywords that were invalid v6 remain findings`() {
        val merged = manifestWithKeywords(6, "Government", "government")
        val duplicate = manifestWithKeywords(6, "documents", "documents")
        val untrimmed = manifestWithKeywords(6, " documents ")
        val nonString = manifestWithKeywords(6, 1)

        assertEquals(setOf("government"), assertNotNull(merged.value).catalog.keywords)
        assertEquals(
            listOf(
                CatalogMigrationNotice(
                    CatalogMigrationCodes.KEYWORD_NORMALIZED,
                    "catalog.json.catalog.keywords[0]",
                    "keyword 'Government' was normalized to 'government' and merged with an identical keyword",
                ),
            ),
            merged.notices.filter { it.code.startsWith("CATALOG_KEYWORD_") },
        )
        assertEquals(CatalogMigrationCodes.KEYWORD_DUPLICATE, duplicate.findings.single().code)
        assertEquals("catalog.json.catalog.keywords[1]", duplicate.findings.single().path)
        assertEquals(CatalogMigrationCodes.KEYWORD_INVALID, untrimmed.findings.single().code)
        assertEquals(CatalogMigrationCodes.KEYWORD_INVALID, nonString.findings.single().code)
    }

    @Test
    fun `a v6 asset dependency naming no catalog is reported, not guessed`() {
        val input = mapper.readTree(
            """{"schemaVersion":6,"catalog":{"slug":"invoices","name":"Invoices"},
               "dependencies":[{"type":"asset","slug":"logo"},
                               {"type":"theme","catalogKey":"shared","slug":"base"}]}""",
        ) as ObjectNode
        val step = CatalogV6ToV7Migration().migrateManifest(input)

        val finding = step.findings.single()
        assertEquals(CatalogMigrationCodes.DEPENDENCY_UNQUALIFIED, finding.code)
        assertEquals("catalog.json.dependencies[0]", finding.path)
    }

    @Test
    fun `a v6 manifest whose dependencies are all qualified migrates cleanly`() {
        val input = mapper.readTree(
            """{"schemaVersion":6,"catalog":{"slug":"invoices","name":"Invoices"},
               "dependencies":[{"type":"asset","catalogKey":"shared","slug":"logo"}]}""",
        ) as ObjectNode

        assertTrue(CatalogV6ToV7Migration().migrateManifest(input).findings.isEmpty())
    }

    @Test
    fun `v6 variant ids migrate to v7 slugs, values unchanged`() {
        val input = resource("migrations/v6-to-v7/template-variants-input.json").use(mapper::readTree) as ObjectNode
        val step = CatalogV6ToV7Migration().migrateResource(input, "resources/template/invoice.json", CatalogMigrationContext(6, emptyManifest()))
        val expected = resource("migrations/v6-to-v7/template-variants-expected.json").use(mapper::readTree)

        assertTrue(step.findings.isEmpty(), step.findings.toString())
        // A rename with no repair: nothing references a variant across catalogs, so no stored
        // reference elsewhere names it and none is left dangling. Hence no notices either.
        assertTrue(step.notices.isEmpty(), step.notices.toString())
        assertEquals(expected, input)
    }

    @Test
    fun `a v6 variant already carrying a slug keeps it`() {
        val input = mapper.readTree(
            """{"schemaVersion":6,"resource":{"type":"template","slug":"invoice","name":"Invoice",
               "variants":[{"slug":"keep","id":"discard"}]}}""",
        ) as ObjectNode
        CatalogV6ToV7Migration().migrateResource(input, "resources/template/invoice.json", CatalogMigrationContext(6, emptyManifest()))

        assertEquals("keep", input["resource"]["variants"][0]["slug"].asString())
    }

    @Test
    fun `v6 golden keywords migrate to their v7 form with notices`() {
        val input = resource("migrations/v6-to-v7/manifest-input.json").use(mapper::readTree) as ObjectNode
        val step = CatalogV6ToV7Migration().migrateManifest(input)
        val result = CatalogSchemaMigrator.migrateManifest(resource("migrations/v6-to-v7/manifest-input.json"), OPTIONS)
        val expected = resource("migrations/v6-to-v7/manifest-expected.json").use(mapper::readTree)

        assertTrue(step.findings.isEmpty(), step.findings.toString())
        assertEquals(expected, input)
        assertEquals(resource("migrations/v6-to-v7/notices.json").use(mapper::readTree), mapper.valueToTree(step.notices))
        assertTrue(result.valid, result.findings.toString())
        assertEquals(step.notices, result.notices.filter { it.code.startsWith("CATALOG_KEYWORD_") })
        assertEquals(expected["catalog"]["keywords"].mapTo(mutableListOf()) { it.asString() }, assertNotNull(result.value).catalog.keywords.toList())
    }

    @Test
    fun `native v7 keywords are checked against every wire rule before binding`() {
        fun codes(vararg keywords: Any) = manifestWithKeywords(CatalogWireSchema.CURRENT_VERSION, *keywords).findings
            .map { it.code to it.path }

        assertEquals(listOf(CatalogMigrationCodes.KEYWORD_INVALID to "catalog.json.catalog.keywords[0]"), codes("Government"))
        assertEquals(listOf(CatalogMigrationCodes.KEYWORD_INVALID to "catalog.json.catalog.keywords[0]"), codes("getting started"))
        assertEquals(listOf(CatalogMigrationCodes.KEYWORD_INVALID to "catalog.json.catalog.keywords[0]"), codes(1))
        assertEquals(listOf(CatalogMigrationCodes.KEYWORD_TOO_LONG to "catalog.json.catalog.keywords[0]"), codes("a".repeat(31)))
        assertEquals(listOf(CatalogMigrationCodes.KEYWORD_DUPLICATE to "catalog.json.catalog.keywords[1]"), codes("a", "a"))
        assertEquals(
            listOf(CatalogMigrationCodes.KEYWORD_LIMIT_EXCEEDED to "catalog.json.catalog.keywords"),
            codes(*Array(21) { "keyword-$it" }),
        )
        assertTrue(manifestWithKeywords(CatalogWireSchema.CURRENT_VERSION, "1-loket", "a".repeat(30)).valid)
    }

    @Test
    fun `v7 golden manifest takes the caller's registry and namespace and declares the catalogs it names`() {
        val input = resource("migrations/v7-to-v8/manifest-input.json").use(mapper::readTree) as ObjectNode
        val step = CatalogV7ToV8Migration().migrateManifest(input, OPTIONS_ACME)
        val result = CatalogSchemaMigrator.migrateManifest(resource("migrations/v7-to-v8/manifest-input.json"), OPTIONS_ACME)
        val expected = resource("migrations/v7-to-v8/manifest-expected.json").use(mapper::readTree)

        assertTrue(step.findings.isEmpty(), step.findings.toString())
        assertEquals(expected, input)
        assertEquals(resource("migrations/v7-to-v8/notices.json").use(mapper::readTree), mapper.valueToTree(step.notices))
        assertTrue(result.valid, result.findings.toString())
        assertEquals(step.notices, result.notices)
        val manifest = assertNotNull(result.value)
        assertEquals(REGISTRY, manifest.catalog.registry)
        assertEquals(ACME, manifest.catalog.namespace)
        assertEquals(
            listOf(
                CatalogDependency(REGISTRY, ACME, "brand", null),
                CatalogDependency(REGISTRY, ACME, "shared", null),
            ),
            manifest.catalogDependencies,
        )
        assertTrue(manifest.dependencies.orEmpty().all { it.registry == REGISTRY && it.namespace == ACME })
    }

    @Test
    fun `a v7 manifest without a registry or namespace from the caller is a finding, never a guess`() {
        fun findings(options: CatalogMigrationOptions): List<Pair<String, String>> {
            val result = CatalogSchemaMigrator.migrateManifest(resource("migrations/v7-to-v8/manifest-input.json"), options)
            assertEquals(null, result.value)
            return result.findings.map { it.code to it.path }
        }

        assertEquals(
            listOf(
                CatalogMigrationCodes.REGISTRY_MISSING to "catalog.json.catalog.registry",
                CatalogMigrationCodes.NAMESPACE_MISSING to "catalog.json.catalog.namespace",
            ),
            findings(CatalogMigrationOptions.DEFAULT),
        )
        // The source-compatible one-argument form names a namespace but no registry.
        assertEquals(
            listOf(CatalogMigrationCodes.REGISTRY_MISSING to "catalog.json.catalog.registry"),
            findings(CatalogMigrationOptions(ACME)),
        )
        assertEquals(
            listOf(CatalogMigrationCodes.NAMESPACE_MISSING to "catalog.json.catalog.namespace"),
            findings(CatalogMigrationOptions(registry = REGISTRY)),
        )
        // An archive has no default registry, so `~` is not one; the joined pre-registry form is no namespace.
        assertEquals(
            listOf(
                CatalogMigrationCodes.REGISTRY_INVALID to "catalog.json.catalog.registry",
                CatalogMigrationCodes.NAMESPACE_INVALID to "catalog.json.catalog.namespace",
            ),
            findings(CatalogMigrationOptions(namespace = "exchange.epistola.app/acme-corp", registry = "~")),
        )
    }

    @Test
    fun `a v7 dependency on its own catalog gets a registry and namespace but no catalog dependency`() {
        val input = mapper.readTree(
            """{"schemaVersion":7,"catalog":{"slug":"letters","name":"Letters"},
               "dependencies":[{"type":"theme","catalogKey":"letters","slug":"base"}]}""",
        ) as ObjectNode
        val step = CatalogV7ToV8Migration().migrateManifest(input, OPTIONS_ACME)

        assertTrue(step.findings.isEmpty(), step.findings.toString())
        assertEquals(REGISTRY, input["dependencies"][0]["registry"].asString())
        assertEquals(ACME, input["dependencies"][0]["namespace"].asString())
        assertEquals(0, input["catalogDependencies"].size())
    }

    @Test
    fun `a v8 manifest names its own registry, in full, and namespace everywhere`() {
        fun findings(edit: (ObjectNode) -> Unit): List<Pair<String, String>> {
            val tree = resource("wire-v8/catalog.json").use(mapper::readTree) as ObjectNode
            edit(tree)
            return CatalogSchemaMigrator.migrateManifest(ByteArrayInputStream(mapper.writeValueAsBytes(tree)), OPTIONS_ACME)
                .findings.map { it.code to it.path }
        }

        assertEquals(
            listOf(CatalogMigrationCodes.NAMESPACE_INVALID to "catalog.json.catalog.namespace"),
            findings { (it["catalog"] as ObjectNode).remove("namespace") },
        )
        assertEquals(
            listOf(CatalogMigrationCodes.NAMESPACE_INVALID to "catalog.json.catalog.namespace"),
            findings { (it["catalog"] as ObjectNode).put("namespace", "exchange.epistola.app/epistola") },
        )
        assertEquals(
            listOf(CatalogMigrationCodes.REGISTRY_INVALID to "catalog.json.catalog.registry"),
            findings { (it["catalog"] as ObjectNode).remove("registry") },
        )
        assertEquals(
            listOf(CatalogMigrationCodes.REGISTRY_INVALID to "catalog.json.catalog.registry"),
            findings { (it["catalog"] as ObjectNode).put("registry", "~") },
        )
        assertEquals(
            listOf(CatalogMigrationCodes.REGISTRY_INVALID to "catalog.json.dependencies[0].registry"),
            findings { (it["dependencies"][0] as ObjectNode).put("registry", "exchange") },
        )
        assertEquals(
            listOf(CatalogMigrationCodes.REGISTRY_INVALID to "catalog.json.catalogDependencies[0].registry"),
            findings { (it["catalogDependencies"][0] as ObjectNode).remove("registry") },
        )
        assertEquals(
            listOf(CatalogMigrationCodes.NAMESPACE_INVALID to "catalog.json.dependencies[0].namespace"),
            findings { (it["dependencies"][0] as ObjectNode).remove("namespace") },
        )
        assertEquals(
            listOf(CatalogMigrationCodes.NAMESPACE_INVALID to "catalog.json.catalogDependencies[0].namespace"),
            findings { (it["catalogDependencies"][0] as ObjectNode).put("namespace", "Acme.com") },
        )
        // The caller's registry and namespace apply only to an archive that names none.
        val native = CatalogSchemaMigrator.migrateManifest(
            resource("wire-v8/catalog.json"),
            CatalogMigrationOptions(namespace = ACME, registry = "acme.com"),
        )
        assertEquals(REGISTRY, assertNotNull(native.value).catalog.registry)
        assertEquals(NAMESPACE, native.value.catalog.namespace)
    }

    @Test
    fun `a v8 catalog dependency records its release fingerprint exactly when it records a release`() {
        fun findings(edit: (ObjectNode) -> Unit): List<Pair<String, String>> {
            val tree = resource("wire-v8/catalog.json").use(mapper::readTree) as ObjectNode
            edit(tree["catalogDependencies"][0] as ObjectNode)
            return CatalogSchemaMigrator.migrateManifest(ByteArrayInputStream(mapper.writeValueAsBytes(tree)), OPTIONS)
                .findings.map { it.code to it.path }
        }
        val invalid = listOf(CatalogMigrationCodes.DEPENDENCY_CATALOG_FINGERPRINT_INVALID to "catalog.json.catalogDependencies[0].releaseFingerprint")

        assertEquals(emptyList(), findings { })
        assertEquals(emptyList(), findings { it.putNull("releaseVersion").putNull("releaseFingerprint") })
        assertEquals(invalid, findings { it.remove("releaseFingerprint") })
        assertEquals(invalid, findings { it.putNull("releaseFingerprint") })
        assertEquals(invalid, findings { it.putNull("releaseVersion") })
        assertEquals(invalid, findings { it.put("releaseFingerprint", SHARED_FINGERPRINT.uppercase()) })
        assertEquals(invalid, findings { it.put("releaseFingerprint", SHARED_FINGERPRINT.drop(1)) })
        assertEquals(invalid, findings { it.put("releaseFingerprint", 1) })
        // A fingerprint names its algorithm: bare hex, and an algorithm this library does not know,
        // cannot be verified.
        assertEquals(invalid, findings { it.put("releaseFingerprint", SHARED_FINGERPRINT.removePrefix("v5:")) })
        assertEquals(invalid, findings { it.put("releaseFingerprint", SHARED_FINGERPRINT.replace("v5:", "v9:")) })
        assertEquals(emptyList(), findings { it.put("releaseFingerprint", SHARED_FINGERPRINT.replace("v5:", "v4:")) })
        // The model binds either alone, so that manifests stored without the fingerprint still load.
        val stored = resource("wire-v8/catalog.json").use(mapper::readTree) as ObjectNode
        (stored["catalogDependencies"][0] as ObjectNode).remove("releaseFingerprint")
        assertEquals(
            CatalogDependency(REGISTRY, NAMESPACE, "shared", "1.4.0"),
            mapper.treeToValue(stored, CatalogManifest::class.java).catalogDependencies.orEmpty().single(),
        )
    }

    @Test
    fun `a v8 manifest numbers its release and every pinned release MAJOR_MINOR_PATCH`() {
        fun findings(edit: (ObjectNode) -> Unit): List<Pair<String, String>> {
            val tree = resource("wire-v8/catalog.json").use(mapper::readTree) as ObjectNode
            edit(tree)
            return CatalogSchemaMigrator.migrateManifest(ByteArrayInputStream(mapper.writeValueAsBytes(tree)), OPTIONS)
                .findings.map { it.code to it.path }
        }
        val release = listOf(CatalogMigrationCodes.RELEASE_VERSION_INVALID to "catalog.json.release.version")
        val pinned = listOf(CatalogMigrationCodes.DEPENDENCY_CATALOG_VERSION_INVALID to "catalog.json.catalogDependencies[0].releaseVersion")
        fun dependency(tree: ObjectNode) = tree["catalogDependencies"][0] as ObjectNode

        assertEquals(emptyList(), findings { (it["release"] as ObjectNode).put("version", "10.20.30") })
        listOf("1.0.0-beta.1", "1.0.0+5", "1.01.0", "v1.0.0", "1.0", "1.0.${"1".repeat(47)}").forEach { version ->
            assertEquals(release, findings { (it["release"] as ObjectNode).put("version", version) }, version)
        }
        listOf("1.4.0-rc.1", "^1.4.0", "1.4").forEach { version ->
            assertEquals(pinned, findings { dependency(it).put("releaseVersion", version) }, version)
        }
        assertEquals(pinned, findings { dependency(it).put("releaseVersion", 1) })
    }

    @Test
    fun `a pre-release or build version from before wire v8 is a migration finding, never stripped`() {
        fun findings(fixture: String, version: String): List<CatalogMigrationFinding> {
            val tree = resource(fixture).use(mapper::readTree) as ObjectNode
            (tree["release"] as ObjectNode).put("version", version)
            val result = CatalogSchemaMigrator.migrateManifest(ByteArrayInputStream(mapper.writeValueAsBytes(tree)), OPTIONS_ACME)
            assertNull(result.value)
            return result.findings
        }

        listOf("migrations/v7-to-v8/manifest-input.json", "wire-v6/catalog.json").forEach { fixture ->
            listOf("1.0.0-beta.1", "1.0.0+build.5").forEach { version ->
                val found = findings(fixture, version)
                assertEquals(listOf(CatalogMigrationCodes.RELEASE_VERSION_INVALID to "catalog.json.release.version"), found.map { it.code to it.path }, "$fixture $version")
                assertTrue(version in found.single().message, found.single().message)
            }
        }
        val input = resource("migrations/v7-to-v8/manifest-input.json").use(mapper::readTree) as ObjectNode
        (input["release"] as ObjectNode).put("version", "2.0.0-rc.1")
        CatalogV7ToV8Migration().migrateManifest(input, OPTIONS_ACME)
        assertEquals("2.0.0-rc.1", input["release"]["version"].asString())
    }

    @Test
    fun `v7 golden stencil loses its version and every stencil reference version with notices`() {
        val manifest = assertNotNull(CatalogSchemaMigrator.migrateManifest(resource("migrations/v7-to-v8/manifest-input.json"), OPTIONS_ACME).value)
        val path = "resources/stencil/letter.json"
        val tree = resource("migrations/v7-to-v8/stencil-input.json").use(mapper::readTree) as ObjectNode
        val step = CatalogV7ToV8Migration().migrateResource(tree, path, CatalogMigrationContext(7, manifest))
        val result = CatalogSchemaMigrator.migrateResourceDetail(
            "stencil",
            resource("migrations/v7-to-v8/stencil-input.json"),
            CatalogMigrationContext(7, manifest),
            path,
        )
        val expected = resource("migrations/v7-to-v8/stencil-expected.json").use(mapper::readTree)

        assertEquals(expected, tree)
        assertEquals(resource("migrations/v7-to-v8/stencil-notices.json").use(mapper::readTree), mapper.valueToTree(step.notices))
        assertTrue(result.valid, result.findings.toString())
        assertEquals(step.notices, result.notices)
        val stencil = assertNotNull(result.value).resource as StencilResource
        assertTrue(stencil.content.nodes.values.none { it.props.orEmpty().containsKey("version") })
    }

    @Test
    fun `v7 keys migrate to their v8 names, values unchanged and without notices, and data examples gain keys with notices`() {
        val tree = resource("migrations/v7-to-v8/template-input.json").use(mapper::readTree) as ObjectNode
        val step = CatalogV7ToV8Migration().migrateResource(tree, "resources/template/letter.json", CatalogMigrationContext(7, emptyManifest()))

        assertTrue(step.findings.isEmpty(), step.findings.toString())
        // Like v7's rename of a variant's id to slug: the value is unchanged, so there is nothing to
        // report. A derived example key is new content, so each one is.
        assertTrue(step.notices.all { it.code == CatalogMigrationCodes.DATA_EXAMPLE_KEY_ASSIGNED }, step.notices.toString())
        assertEquals(resource("migrations/v7-to-v8/template-notices.json").use(mapper::readTree), mapper.valueToTree(step.notices))
        assertEquals(resource("migrations/v7-to-v8/template-expected.json").use(mapper::readTree), tree)
        // The key leads each example, ahead of the name it was derived from.
        assertEquals(listOf("key", "name", "data"), tree["resource"]["dataExamples"][0].propertyNames().toList())
    }

    @Test
    fun `v7 golden manifest pads short keys and cuts long ones, and every reference with them`() {
        val input = resource("migrations/v7-to-v8/key-bounds-manifest-input.json").use(mapper::readTree) as ObjectNode
        val step = CatalogV7ToV8Migration().migrateManifest(input, OPTIONS_ACME)
        val result = CatalogSchemaMigrator.migrateManifest(resource("migrations/v7-to-v8/key-bounds-manifest-input.json"), OPTIONS_ACME)

        assertTrue(step.findings.isEmpty(), step.findings.toString())
        assertEquals(resource("migrations/v7-to-v8/key-bounds-manifest-expected.json").use(mapper::readTree), input)
        assertEquals(
            resource("migrations/v7-to-v8/key-bounds-manifest-notices.json").use(mapper::readTree),
            mapper.valueToTree(step.notices.filter { it.code.startsWith("CATALOG_RESOURCE_KEY_") }),
        )
        assertTrue(result.valid, result.findings.toString())
        assertEquals(step.notices, result.notices)
        val manifest = assertNotNull(result.value)
        assertTrue(manifest.resources.all { CatalogKeys.isValid(it.key) }, manifest.resources.toString())
        assertTrue(manifest.dependencies.orEmpty().all { CatalogKeys.isValid(it.key) }, manifest.dependencies.toString())
    }

    @Test
    fun `v7 golden template pads its keys and every key it names, with one notice per key it defines`() {
        val manifest = assertNotNull(CatalogSchemaMigrator.migrateManifest(resource("migrations/v7-to-v8/key-bounds-manifest-input.json"), OPTIONS_ACME).value)
        val path = "resources/template/ab.json"
        val tree = resource("migrations/v7-to-v8/key-bounds-template-input.json").use(mapper::readTree) as ObjectNode
        val step = CatalogV7ToV8Migration().migrateResource(tree, path, CatalogMigrationContext(7, manifest))
        val result = CatalogSchemaMigrator.migrateResourceDetail(
            "template",
            resource("migrations/v7-to-v8/key-bounds-template-input.json"),
            CatalogMigrationContext(7, manifest),
            path,
        )

        assertTrue(step.findings.isEmpty(), step.findings.toString())
        assertEquals(resource("migrations/v7-to-v8/key-bounds-template-expected.json").use(mapper::readTree), tree)
        assertEquals(resource("migrations/v7-to-v8/key-bounds-template-notices.json").use(mapper::readTree), mapper.valueToTree(step.notices))
        assertTrue(result.valid, result.findings.toString())
        assertEquals(step.notices, result.notices)
        val template = assertNotNull(result.value).resource as TemplateResource
        assertEquals("ab0", template.key)
        assertEquals(listOf("nl0", "english"), template.variants.map { it.key })
        assertEquals(listOf("a00", "standard"), template.dataExamples.orEmpty().map { it.key })
    }

    @Test
    fun `a padded or cut key that collides is a finding, and the migration never chooses another key`() {
        val input = resource("migrations/v7-to-v8/key-bounds-collision-input.json").use(mapper::readTree) as ObjectNode
        val step = CatalogV7ToV8Migration().migrateManifest(input, OPTIONS_ACME)
        val result = CatalogSchemaMigrator.migrateManifest(resource("migrations/v7-to-v8/key-bounds-collision-input.json"), OPTIONS_ACME)

        assertEquals(resource("migrations/v7-to-v8/key-bounds-collision-findings.json").use(mapper::readTree), mapper.valueToTree(step.findings))
        assertNull(result.value)
        assertEquals(step.findings, result.findings)
    }

    @Test
    fun `variants and data examples collide within their template, and variant attribute keys within their variant`() {
        fun findings(resource: String): List<Pair<String, String>> {
            val tree = mapper.readTree("""{"schemaVersion":7,"resource":$resource}""") as ObjectNode
            return CatalogV7ToV8Migration().migrateResource(tree, "resources/template/letter.json", CatalogMigrationContext(7, emptyManifest()))
                .findings.map { it.code to it.path }
        }
        val template = """{"type":"template","slug":"letter","name":"Letter","templateModel":{"root":"r","nodes":{},"slots":{}}"""

        assertEquals(
            listOf(CatalogMigrationCodes.RESOURCE_KEY_PADDING_COLLISION to "resources/template/letter.json.resource.variants[0].key"),
            findings("""$template,"variants":[{"slug":"nl"},{"slug":"nl0"}]}"""),
        )
        assertEquals(
            listOf(CatalogMigrationCodes.RESOURCE_KEY_PADDING_COLLISION to "resources/template/letter.json.resource.variants[0].attributes.la"),
            findings("""$template,"variants":[{"slug":"dutch","attributes":{"la":"nl","la0":"nl"}}]}"""),
        )
        assertEquals(
            listOf(CatalogMigrationCodes.RESOURCE_KEY_PADDING_COLLISION to "resources/template/letter.json.resource.dataExamples[1].key"),
            findings("""$template,"variants":[],"dataExamples":[{"key":"ab0","name":"A","data":{}},{"key":"ab","name":"B","data":{}}]}"""),
        )
    }

    @Test
    fun `a qualified variant attribute key migrates its attribute part, as that attribute's own key did`() {
        val tree = mapper.readTree(
            """{"schemaVersion":7,"resource":{"type":"template","slug":"letter","name":"Letter","templateModel":{"root":"r","nodes":{},"slots":{}},""" +
                """"variants":[{"slug":"dutch","attributes":{"system.la":"nl","system.locale":"nl-NL"}}]}}""",
        ) as ObjectNode

        val step = CatalogV7ToV8Migration().migrateResource(tree, "resources/template/letter.json", CatalogMigrationContext(7, emptyManifest()))

        assertEquals(emptyList<CatalogMigrationFinding>(), step.findings)
        assertEquals(listOf("system.la0", "system.locale"), tree["resource"]["variants"][0]["attributes"].propertyNames().toList())
        assertEquals("system.la0", CatalogKeys.migrateAttributeKey("system.la"))
        assertEquals("a00", CatalogKeys.migrateAttributeKey("a"))
    }

    @Test
    fun `the key rewrite is a pure function of the key`() {
        assertEquals("a00", CatalogKeys.migrate("a"))
        assertEquals("nl0", CatalogKeys.migrate("nl"))
        assertEquals("abc", CatalogKeys.migrate("abc"))
        assertEquals("a".repeat(50), CatalogKeys.migrate("a".repeat(64)))
        assertEquals("a".repeat(49), CatalogKeys.migrate("a".repeat(49) + "-b"))
        // Not repaired: a key with characters outside the pattern stays a finding.
        assertEquals("A", CatalogKeys.migrate("A"))
        assertEquals("a_b", CatalogKeys.migrate("a_b"))
        assertEquals(CatalogKeys.Rewrite.PADDED, CatalogKeys.rewrite("nl"))
        assertEquals(CatalogKeys.Rewrite.TRUNCATED, CatalogKeys.rewrite("a".repeat(51)))
        assertNull(CatalogKeys.rewrite("a".repeat(50)))
    }

    @Test
    fun `data example keys are derived as the published fixture defines`() {
        val fixture = resource("migrations/v7-to-v8/data-example-keys.json").use(mapper::readTree)

        assertEquals(CatalogKeys.MAX_LENGTH, fixture["maxLength"].asInt())
        assertEquals(CatalogKeys.MIN_LENGTH, fixture["minLength"].asInt())
        fixture["cases"].forEach { case ->
            val taken = mutableSetOf<String>()
            val keys: List<String> = case["names"].toList().map { name -> CatalogKeys.deriveDataExampleKey(name.asString(), taken).also(taken::add) }

            assertEquals(case["keys"].toList().map { it.asString() }, keys, case["names"].toString())
            keys.forEach { assertTrue(CatalogKeys.isValid(it), it) }
        }
    }

    @Test
    fun `an example that already has a key keeps it, and a derived key avoids it`() {
        val tree = mapper.readTree(
            """{"schemaVersion":7,"resource":{"type":"template","slug":"letter","name":"Letter","variants":[],
               "templateModel":{"root":"r","nodes":{},"slots":{}},
               "dataExamples":[{"name":"Standard","data":{}},{"key":"standard","name":"Other","data":{}}]}}""",
        ) as ObjectNode
        val step = CatalogV7ToV8Migration().migrateResource(tree, "resources/template/letter.json", CatalogMigrationContext(7, emptyManifest()))

        assertEquals(listOf("standard-2", "standard"), tree["resource"]["dataExamples"].toList().map { it["key"].asString() })
        assertEquals(listOf("resources/template/letter.json.resource.dataExamples[0].key"), step.notices.map { it.path })
    }

    @Test
    fun `a v8 manifest's detailUrl is a relative path below the manifest, and earlier versions are not held to it`() {
        fun findings(
            version: Int,
            detailUrl: String,
        ): List<Pair<String, String>> {
            val tree = resource("wire-v8/catalog.json").use(mapper::readTree) as ObjectNode
            (tree["resources"][0] as ObjectNode).put("detailUrl", detailUrl)
            if (version < 8) {
                tree.put("schemaVersion", version)
                tree.remove("catalogDependencies")
                CatalogKeyRenames.toLegacy(CatalogKeyRenames.manifestSites(tree))
                (tree["catalog"] as ObjectNode).remove(listOf("registry", "namespace"))
                (tree["dependencies"] as ArrayNode).forEach { (it as ObjectNode).remove(listOf("registry", "namespace")) }
            }
            return CatalogSchemaMigrator.migrateManifest(ByteArrayInputStream(mapper.writeValueAsBytes(tree)), OPTIONS)
                .findings.map { it.code to it.path }
        }
        val invalid = listOf(CatalogMigrationCodes.DETAIL_URL_INVALID to "catalog.json.resources[0].detailUrl")

        ACCEPTED_PATHS.forEach { assertEquals(emptyList(), findings(8, it), it) }
        REJECTED_PATHS.forEach { assertEquals(invalid, findings(8, it), it) }
        REJECTED_PATHS.forEach { assertEquals(emptyList(), findings(7, it), "v7 stays readable: $it") }
    }

    @Test
    fun `a v8 binary's contentUrl is held to the same rule, and earlier versions are not`() {
        fun findings(
            version: Int,
            type: String,
            contentUrl: String,
        ): List<Pair<String, String>> {
            val keyMember = if (version >= 8) "key" else "slug"
            val hash = "0".repeat(64)
            val resource = mapper.readTree(
                when (type) {
                    "image" -> """{"type":"image","$keyMember":"logo","name":"Logo","mediaType":"image/png","contentHash":"$hash"}"""
                    else -> """{"type":"font","$keyMember":"inter","name":"Inter","kind":"sans","variants":[{"weight":400,"italic":false,"mediaType":"font/ttf","contentHash":"$hash"}]}"""
                },
            ) as ObjectNode
            val owner = if (type == "image") resource else resource["variants"][0] as ObjectNode
            owner.put("contentUrl", contentUrl)
            val json = mapper.createObjectNode().put("schemaVersion", version).set("resource", resource)
            return CatalogSchemaMigrator.migrateResourceDetail(type, ByteArrayInputStream(mapper.writeValueAsBytes(json)), CatalogMigrationContext(version, emptyManifest()), "resources/$type/x.json")
                .findings.map { it.code to it.path }
        }

        ACCEPTED_PATHS.forEach { path ->
            assertEquals(emptyList(), findings(8, "image", path), path)
            assertEquals(emptyList(), findings(8, "font", path), path)
        }
        REJECTED_PATHS.forEach { path ->
            assertEquals(listOf(CatalogMigrationCodes.ASSET_PATH_INVALID to "resources/image/x.json.resource.contentUrl"), findings(8, "image", path), path)
            assertEquals(listOf(CatalogMigrationCodes.ASSET_PATH_INVALID to "resources/font/x.json.resource.variants[0].contentUrl"), findings(8, "font", path), path)
            assertEquals(emptyList(), findings(7, "image", path), "v7 stays readable: $path")
        }
    }

    @Test
    fun `a v8 manifest that still uses an old name is not wire v8`() {
        val json = """
            {"schemaVersion":8,"catalog":{"registry":"$REGISTRY","namespace":"$NAMESPACE","slug":"x","name":"X",
              "presentation":{"iconAssetSlug":"logo","imageAssetSlugs":[]}},
             "publisher":{"name":"X"},"release":{"version":"1.0.0"},
             "resources":[{"type":"theme","slug":"base","name":"Base","detailUrl":"./resources/theme/base.json"}],
             "dependencies":[{"type":"theme","registry":"$REGISTRY","namespace":"$NAMESPACE","catalogKey":"shared","slug":"corporate"}],
             "catalogDependencies":[{"registry":"$REGISTRY","namespace":"$NAMESPACE","catalogKey":"shared","version":"*",
              "releaseVersion":null,"releaseFingerprint":null}]}
        """.trimIndent()

        val result = CatalogSchemaMigrator.migrateManifest(ByteArrayInputStream(json.toByteArray()))

        assertNull(result.value)
        assertEquals(
            listOf(
                "catalog.json.catalog.slug",
                "catalog.json.catalog.presentation.iconAssetSlug",
                "catalog.json.catalog.presentation.imageAssetSlugs",
                "catalog.json.resources[0].slug",
                "catalog.json.dependencies[0].slug",
            ),
            result.findings.map { it.path },
        )
        assertTrue(result.findings.all { it.code == CatalogMigrationCodes.SCHEMA_UNKNOWN }, result.findings.toString())
        assertEquals("'slug' is not a catalog-v8 member; wire v8 calls it 'key'", result.findings.first().message)
    }

    @Test
    fun `a v8 resource detail that still uses an old name is not wire v8`() {
        val v7 = resource("migrations/v7-to-v8/template-input.json").readAllBytes().decodeToString()
        val v8 = v7.replace("\"schemaVersion\": 7", "\"schemaVersion\": 8")
        val path = "resources/template/letter.json"

        val result = CatalogSchemaMigrator.migrateResourceDetail("template", ByteArrayInputStream(v8.toByteArray()), CatalogMigrationContext(8, emptyManifest()), path)

        assertNull(result.value)
        assertEquals(
            listOf(
                "$path.resource.slug",
                "$path.resource.themeId",
                "$path.resource.templateModel.themeRef.themeId",
                "$path.resource.templateModel.nodes.address.props.stencilId",
                "$path.resource.templateModel.nodes.logo.props.assetId",
                "$path.resource.variants[0].slug",
                "$path.resource.variants[0].templateModel.themeRef.themeId",
            ),
            result.findings.map { it.path },
        )
        assertTrue(result.findings.all { it.code == CatalogMigrationCodes.SCHEMA_UNKNOWN }, result.findings.toString())
    }

    @Test
    fun `a v8 resource detail with the new names binds`() {
        val path = "resources/template/letter.json"
        val result = CatalogSchemaMigrator.migrateResourceDetail(
            "template",
            resource("migrations/v7-to-v8/template-expected.json"),
            CatalogMigrationContext(8, emptyManifest()),
            path,
        )

        assertTrue(result.valid, result.findings.toString())
        assertEquals("corporate", (assertNotNull(result.value).resource as TemplateResource).themeKey)
    }

    @Test
    fun `a v7 stencil reference isDraft is left for the validator to report`() {
        val tree = mapper.readTree(
            """{"schemaVersion":7,"resource":{"type":"template","slug":"letter","name":"Letter","variants":[],
               "templateModel":{"root":"r","nodes":{"n":{"id":"n","type":"stencil","props":
               {"stencilId":"address","version":1,"isDraft":false}}},"slots":{}}}}""",
        ) as ObjectNode
        val step = CatalogV7ToV8Migration().migrateResource(tree, "resources/template/letter.json", CatalogMigrationContext(7, emptyManifest()))
        val props = tree["resource"]["templateModel"]["nodes"]["n"]["props"]

        assertEquals(listOf("resources/template/letter.json.resource.templateModel.nodes.n.props.version"), step.notices.map { it.path })
        assertTrue(props.has("isDraft"))
        assertFalse(props.has("version"))
    }

    @Test
    fun `detail must match catalog version and declared type`() {
        val manifest = assertNotNull(CatalogSchemaMigrator.migrateManifest(resource("wire-v4/catalog.json"), OPTIONS).value)
        val context = CatalogMigrationContext(4, manifest)
        val detailBytes = resource("wire-v4/resources/theme/default.json").readAllBytes()
        val wrongVersion = detailBytes.toString(Charsets.UTF_8)
            .replace("\"schemaVersion\": 4", "\"schemaVersion\": 3")
            .toByteArray()

        val version = CatalogSchemaMigrator.migrateResourceDetail(
            "theme",
            ByteArrayInputStream(wrongVersion),
            context,
            "resources/theme/default.json",
        )
        val type = CatalogSchemaMigrator.migrateResourceDetail(
            "template",
            ByteArrayInputStream(detailBytes),
            context,
            "resources/theme/default.json",
        )

        assertEquals(CatalogMigrationCodes.SCHEMA_VERSION_MISMATCH, version.findings.single().code)
        assertEquals(CatalogMigrationCodes.RESOURCE_TYPE_MISMATCH, type.findings.single().code)
    }

    @Test
    fun `v4 stencil markers migrate recursively and stale true markers produce notices`() {
        val manifest = assertNotNull(CatalogSchemaMigrator.migrateManifest(resource("wire-v4/catalog.json"), OPTIONS).value)
        val result = CatalogSchemaMigrator.migrateResourceDetail(
            "stencil",
            resource("migrations/v4-to-v5/stencil-input.json"),
            CatalogMigrationContext(4, manifest),
            "resources/stencil/letter.json",
        )
        val currentManifest = assertNotNull(CatalogSchemaMigrator.migrateManifest(resource("wire-v5/catalog.json"), OPTIONS).value)
        val expected = CatalogSchemaMigrator.migrateResourceDetail(
            "stencil",
            resource("migrations/v4-to-v5/stencil-expected.json"),
            CatalogMigrationContext(5, currentManifest),
            "resources/stencil/letter.json",
        )
        val expectedNotices = resource("migrations/v4-to-v5/notices.json").use(mapper::readTree)

        assertTrue(result.valid)
        assertEquals(expected.value, result.value)
        // Later steps also remove the stencil versions catalog v8 no longer has.
        assertEquals(
            expectedNotices,
            mapper.valueToTree(result.notices.filter { it.code == CatalogMigrationCodes.STALE_DRAFT_MARKER_REMOVED }),
        )
    }

    @Test
    fun `v4 migration preserves object-valued type properties in JSON Schema`() {
        val tree = mapper.readTree(
            """
            {
              "schemaVersion": 4,
              "resource": {
                "parameterSchema": {
                  "type": "object",
                  "properties": {
                    "type": {
                      "type": "string",
                      "description": "Type activiteit"
                    }
                  }
                }
              }
            }
            """.trimIndent(),
        ) as ObjectNode
        val parameterSchema = tree["resource"]["parameterSchema"].toString()

        val result = CatalogV4ToV5Migration().migrateResource(tree, "resources/stencil/letter.json", CatalogMigrationContext(6, emptyManifest()))

        assertTrue(result.findings.isEmpty())
        assertTrue(result.notices.isEmpty())
        assertEquals(parameterSchema, tree["resource"]["parameterSchema"].toString())
    }

    @Test
    fun `v4 stencil marker must be boolean`() {
        val manifest = assertNotNull(CatalogSchemaMigrator.migrateManifest(resource("wire-v4/catalog.json"), OPTIONS).value)
        val malformed = resource("migrations/v4-to-v5/stencil-input.json").readAllBytes()
            .toString(Charsets.UTF_8)
            .replace("\"isDraft\": true", "\"isDraft\": \"true\"")
            .toByteArray()

        val result = CatalogSchemaMigrator.migrateResourceDetail(
            "stencil",
            ByteArrayInputStream(malformed),
            CatalogMigrationContext(4, manifest),
            "resources/stencil/letter.json",
        )

        assertEquals(CatalogMigrationCodes.DRAFT_MARKER_INVALID, result.findings.single().code)
        assertEquals(
            "resources/stencil/letter.json.resource.content.nodes.nested.props.isDraft",
            result.findings.single().path,
        )
    }

    private fun minimalManifest(version: Int): CatalogMigrationResult<CatalogManifest> {
        val json = """
            {"schemaVersion":$version,"catalog":{"registry":"$REGISTRY","namespace":"$NAMESPACE","${keyMember(version)}":"x","name":"X"},"publisher":{"name":"X"},
            "release":{"version":"1.0.0"},"resources":[]}
        """.trimIndent()
        return CatalogSchemaMigrator.migrateManifest(ByteArrayInputStream(json.toByteArray()), OPTIONS)
    }

    private fun manifestWithKeywords(
        version: Int,
        vararg keywords: Any,
    ): CatalogMigrationResult<CatalogManifest> {
        val encoded = keywords.joinToString(",") { mapper.writeValueAsString(it) }
        val json = """
            {"schemaVersion":$version,"catalog":{${namespaceMember(version)}"${keyMember(version)}":"x","name":"X","keywords":[$encoded]},
            "publisher":{"name":"X"},"release":{"version":"1.0.0"},"resources":[]}
        """.trimIndent()
        return CatalogSchemaMigrator.migrateManifest(ByteArrayInputStream(json.toByteArray()), OPTIONS)
    }

    /** A catalog names its registry and namespace from wire v8; before that the migrator is told them. */
    private fun namespaceMember(version: Int) = if (version >= 8) "\"registry\":\"$REGISTRY\",\"namespace\":\"$NAMESPACE\"," else ""

    /** A catalog's address is its `key` from wire v8, its `slug` before. */
    private fun keyMember(version: Int) = if (version >= 8) "key" else "slug"

    private fun resource(path: String) = requireNotNull(javaClass.getResourceAsStream("/META-INF/epistola-catalog/fixtures/v1/$path"))

    /** A manifest with no resources: enough for a migration that does not look anything up. */
    private fun emptyManifest() = CatalogManifest(
        schemaVersion = 6,
        catalog = CatalogInfo("invoices", "Invoices"),
        publisher = PublisherInfo("Example"),
        release = ReleaseInfo("1.0.0"),
        resources = emptyList(),
    )

    private companion object {
        const val REGISTRY = "exchange.epistola.app"
        const val NAMESPACE = "epistola"
        const val SHARED_FINGERPRINT = "v5:69e540ae7bea571f81ff51919c8fd7d4dfca8d792da9fcc5a559034218feaa42"
        val OPTIONS = CatalogMigrationOptions(NAMESPACE, REGISTRY)
        const val ACME = "acme-corp"
        val OPTIONS_ACME = CatalogMigrationOptions(ACME, REGISTRY)

        /** Paths into the catalog wire v8 accepts (#95). */
        val ACCEPTED_PATHS = listOf(
            "./resources/template/a.json",
            "resources/a.json",
            "bin/" + "0".repeat(64),
            "./a",
            "a/b/c.json",
            "resources/~user/.hidden/a_b-c.json",
            "resources/a%20b.json",
            "a..b/c",
            "...",
        )

        /** Paths into the catalog wire v8 rejects: a scheme, absolute, a backslash, empty, dot or encoded segments. */
        val REJECTED_PATHS = listOf(
            "https://other.example/x.json",
            "file:resources/a.json",
            "C:resources/a.json",
            "/abs/x.json",
            "//host/x.json",
            "resources\\a.json",
            "../x.json",
            "resources/../../x.json",
            "./../x.json",
            "././x.json",
            "resources/./a.json",
            "resources/..",
            "resources//a.json",
            "resources/",
            "",
            "./",
            "%2e%2e/x.json",
            "%2E%2E/x.json",
            "resources%2fa.json",
            "resources%2Fa.json",
            "resources%5ca.json",
            "resources%5Ca.json",
            "resources/a b.json",
            "resources/a?b.json",
            "resources/a#b.json",
            "resources/%zz.json",
        )
    }
}
