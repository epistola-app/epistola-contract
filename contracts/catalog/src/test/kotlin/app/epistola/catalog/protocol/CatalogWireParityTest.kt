// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.protocol

import app.epistola.template.model.ThemeRefOverride
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CatalogWireParityTest {
    private val mapper = jsonMapper { addModule(kotlinModule()) }

    @Test
    fun `v5 manifest round trips through the public Kotlin model`() {
        val manifest = fixture("wire-v5/catalog.json").use { mapper.readValue(it, CatalogManifest::class.java) }

        assertEquals(manifest, mapper.readValue(mapper.writeValueAsBytes(manifest), CatalogManifest::class.java))
    }

    @Test
    fun `v5 resource detail round trips through the public Kotlin model`() {
        val detail = fixture("wire-v5/resources/theme/default.json").use { mapper.readValue(it, ResourceDetail::class.java) }

        assertEquals(detail, mapper.readValue(mapper.writeValueAsBytes(detail), ResourceDetail::class.java))
    }

    @Test
    fun `v6 manifest and resource detail round trip through the public Kotlin model`() {
        val manifest = fixture("wire-v6/catalog.json").use { mapper.readValue(it, CatalogManifest::class.java) }
        val detail = fixture("wire-v6/resources/theme/default.json").use { mapper.readValue(it, ResourceDetail::class.java) }

        assertEquals(manifest, mapper.readValue(mapper.writeValueAsBytes(manifest), CatalogManifest::class.java))
        assertEquals(detail, mapper.readValue(mapper.writeValueAsBytes(detail), ResourceDetail::class.java))
    }

    @Test
    fun `v7 manifest and resource detail round trip through the public Kotlin model`() {
        val manifest = fixture("wire-v7/catalog.json").use { mapper.readValue(it, CatalogManifest::class.java) }
        val detail = fixture("wire-v7/resources/theme/default.json").use { mapper.readValue(it, ResourceDetail::class.java) }

        assertEquals(manifest, mapper.readValue(mapper.writeValueAsBytes(manifest), CatalogManifest::class.java))
        assertEquals(detail, mapper.readValue(mapper.writeValueAsBytes(detail), ResourceDetail::class.java))
    }

    @Test
    fun `v8 manifest and resource detail round trip through the public Kotlin model`() {
        val manifest = fixture("wire-v8/catalog.json").use { mapper.readValue(it, CatalogManifest::class.java) }
        val detail = fixture("wire-v8/resources/theme/default.json").use { mapper.readValue(it, ResourceDetail::class.java) }

        assertEquals("exchange.epistola.app", manifest.catalog.registry)
        assertEquals("epistola", manifest.catalog.namespace)
        assertEquals(
            listOf(CatalogDependency("exchange.epistola.app", "epistola", "shared", "1.4.0", "v5:69e540ae7bea571f81ff51919c8fd7d4dfca8d792da9fcc5a559034218feaa42", alias = "shared")),
            manifest.catalogDependencies,
        )
        assertEquals("k7m2x9qa", manifest.release.releaseId)
        assertEquals(mapOf("renderingDefaultsVersion" to 6), manifest.release.metadata?.get("app.epistola.suite"))
        assertEquals(ReleaseBase("0.9.0", "v5:3f1c9a7e5b2d4c6a8e0f1b3d5c7a9e1f2b4d6c8a0e2f4b6d8c0a2e4f6b8d0c2a"), manifest.release.base)
        assertEquals(manifest, mapper.readValue(mapper.writeValueAsBytes(manifest), CatalogManifest::class.java))
        assertEquals(detail, mapper.readValue(mapper.writeValueAsBytes(detail), ResourceDetail::class.java))
    }

    @Test
    fun `a stored catalog dependency that still carries a version selector binds, and the selector is dropped`() {
        val stored = """
            {"registry":"exchange.epistola.app","namespace":"epistola","catalogKey":"shared","version":"^1.2.0",
             "releaseVersion":"1.4.0","releaseFingerprint":"v5:69e540ae7bea571f81ff51919c8fd7d4dfca8d792da9fcc5a559034218feaa42"}
        """.trimIndent()
        val dependency = mapper.readValue(stored, CatalogDependency::class.java)

        assertEquals(
            CatalogDependency("exchange.epistola.app", "epistola", "shared", "1.4.0", "v5:69e540ae7bea571f81ff51919c8fd7d4dfca8d792da9fcc5a559034218feaa42"),
            dependency,
        )
        assertTrue(!mapper.valueToTree<JsonNode>(dependency).has("version"))
    }

    @Test
    fun `a v8 template's description, example keys and metadata are written back member for member`() {
        val source = fixture("wire-v8/resources/template/letter.json").use(mapper::readTree)
        val detail = mapper.treeToValue(source, ResourceDetail::class.java)
        val template = detail.resource as TemplateResource

        assertEquals("standard-letter", template.dataExamples.orEmpty().single().key)
        assertEquals(mapOf("com.example.authoring" to mapOf("reviewedBy" to "design")), template.metadata)
        assertTrue(template.description.orEmpty().startsWith("A one-page letter"))
        val written = mapper.valueToTree<JsonNode>(detail)["resource"]
        for (member in listOf("description", "dataExamples", "metadata")) {
            assertEquals(source["resource"][member], written[member], member)
        }
        assertEquals(detail, mapper.readValue(mapper.writeValueAsBytes(detail), ResourceDetail::class.java))
    }

    @Test
    fun `a release's id, metadata and base are written back member for member, an unknown namespace included`() {
        val source = fixture("wire-v8/catalog.json").use(mapper::readTree)
        val written = mapper.valueToTree<JsonNode>(mapper.treeToValue(source, CatalogManifest::class.java))

        for (member in listOf("version", "releaseId", "metadata", "base")) {
            assertEquals(source["release"][member], written["release"][member], member)
        }
    }

    @Test
    fun `a release without an id, metadata or base, as stored before wire v8 had them, binds and omits them`() {
        val release = mapper.readValue("""{"version":"1.0.0"}""", ReleaseInfo::class.java)

        assertEquals(ReleaseInfo("1.0.0"), release)
        assertTrue(listOf("releaseId", "metadata", "base").none(mapper.valueToTree<JsonNode>(release)::has))
        assertTrue(!mapper.valueToTree<JsonNode>(release.copy(metadata = emptyMap())).has("metadata"), "empty metadata is omitted")
    }

    @Test
    fun `a namespace the reader does not recognise is kept as it is`() {
        val json = """{"type":"theme","key":"default","name":"Default","metadata":{"com.example.renderer":
            {"nested":{"list":[1,"two",null,{"x":false}]},"n":1.5},"app.epistola.suite":{"authoredWith":"editor"}}}"""
        val theme = mapper.readValue(json, ThemeResource::class.java)

        assertEquals(mapper.readTree(json)["metadata"], mapper.valueToTree<JsonNode>(theme)["metadata"])
    }

    @Test
    fun `members wire v8 added bind when absent, so documents stored earlier still load, and are then omitted`() {
        val example = mapper.readValue("""{"name":"Standard letter","data":{}}""", DataExampleEntry::class.java)
        val theme = mapper.readValue("""{"type":"theme","key":"default","name":"Default"}""", ThemeResource::class.java)

        assertEquals(null, example.key)
        assertEquals(null, theme.metadata)
        assertTrue(!mapper.valueToTree<JsonNode>(example).has("key"))
        assertTrue(!mapper.valueToTree<JsonNode>(theme).has("metadata"))
        assertTrue(!mapper.valueToTree<JsonNode>(theme.copy(metadata = emptyMap())).has("metadata"), "empty metadata is omitted")
        assertEquals(listOf("key", "name", "data"), mapper.valueToTree<JsonNode>(example.copy(key = "standard-letter")).propertyNames().toList())
    }

    @Test
    fun `the model binds documents stored under v7, which name no registry or namespace and pin stencil versions`() {
        val manifest = fixture("migrations/v7-to-v8/manifest-input.json").use { mapper.readValue(it, CatalogManifest::class.java) }
        val stencil = fixture("migrations/v7-to-v8/stencil-input.json").use { mapper.readValue(it, ResourceDetail::class.java) }

        assertEquals(null, manifest.catalog.registry)
        assertEquals(null, manifest.catalog.namespace)
        assertEquals(null, manifest.catalogDependencies)
        assertEquals(listOf("shared", "shared", "brand"), manifest.dependencies.orEmpty().map { it.catalogKey })
        assertEquals("letter", stencil.resource.key)
    }

    @Test
    fun `a reference names another catalog by alias alone, and one stored with a registry and namespace still binds without them`() {
        val binding = mapper.valueToTree<JsonNode>(CodeListBindingRef(key = "countries"))
        val font = mapper.valueToTree<JsonNode>(FontRef(key = "inter"))
        val theme = mapper.valueToTree<JsonNode>(ThemeRefOverride("corporate", "shared"))
        val defaultTheme = mapper.valueToTree<JsonNode>(CatalogThemeRef("corporate", "shared"))
        val dependency = mapper.valueToTree<JsonNode>(DependencyRef.Theme("shared", "corporate"))

        listOf(binding, font, theme, defaultTheme, dependency).forEach { node ->
            assertTrue(!node.has("namespace") && !node.has("registry"), node.toString())
        }
        assertEquals(
            CodeListBindingRef("shared", "x"),
            mapper.readValue("""{"catalogKey":"shared","key":"x","namespace":"acme","registry":"acme.com"}""", CodeListBindingRef::class.java),
        )
        assertEquals(
            CatalogThemeRef("corporate", "shared"),
            mapper.readValue("""{"key":"corporate","catalogKey":"shared","namespace":"acme","registry":"acme.com"}""", CatalogThemeRef::class.java),
        )
        assertEquals(
            DependencyRef.Font("shared", "inter"),
            mapper.readValue("""{"type":"font","registry":"acme.com","namespace":"acme","catalogKey":"shared","key":"inter"}""", DependencyRef::class.java),
        )
    }

    @Test
    fun `a catalog dependency is declared under an alias, which defaults to its key for a manifest stored without one`() {
        val declared = CatalogDependency("acme.com", "acme", "shared", "1.4.0", "v5:${"0".repeat(64)}", alias = "acme-shared")
        val written = mapper.valueToTree<JsonNode>(declared)

        assertEquals(listOf("alias", "registry", "namespace", "catalogKey", "releaseVersion", "releaseFingerprint"), written.propertyNames().toList())
        assertEquals(declared, mapper.treeToValue(written, CatalogDependency::class.java))
        val stored = mapper.readValue(
            """{"registry":"acme.com","namespace":"acme","catalogKey":"shared","releaseVersion":null,"releaseFingerprint":null}""",
            CatalogDependency::class.java,
        )
        assertEquals("shared", stored.alias)
        assertEquals("shared", mapper.valueToTree<JsonNode>(stored)["alias"].asString())
    }

    @Test
    fun `the model binds keywords that break v7 rules so manifests stored under v6 still load`() {
        val stored = fixture("migrations/v6-to-v7/manifest-input.json").use { mapper.readValue(it, CatalogManifest::class.java) }

        assertEquals(24, stored.catalog.keywords.size)
        assertTrue("Getting Started" in stored.catalog.keywords)
    }

    @Test
    fun `catalog info factory normalizes additive v6 metadata`() {
        val info = CatalogInfo.create(
            key = "fixture",
            name = "Fixture",
            attributes = listOf(AttributeAssignment("system", "locale", "nl-NL")),
            keywords = linkedSetOf("zoning", "documents"),
            presentation = CatalogPresentation("icon", listOf("hero")),
            license = CatalogLicense("Creative Commons Attribution 4.0 International", "CC-BY-4.0"),
        )

        assertEquals(listOf("documents", "zoning"), info.keywords.toList())
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (info.keywords as MutableSet<String>).add("mutable")
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (info.attributes as MutableList<AttributeAssignment>).add(AttributeAssignment("system", "brand", "epistola"))
        }
        assertEquals(
            "en-GB",
            info.copyWithMetadata(attributes = listOf(AttributeAssignment("system", "locale", "en-GB")))
                .attributes.single().value,
        )
        assertEquals("CC-BY-4.0", info.copyWithMetadata().license?.spdxExpression)
    }

    @Test
    fun `legacy catalog info constructor remains source compatible`() {
        val info = CatalogInfo("fixture", "Fixture")

        assertEquals("fixture", info.key)
        assertEquals("Fixture", info.name)
        assertEquals(null, info.description)
        assertEquals(emptyList(), info.attributes)
        assertEquals(emptySet(), info.keywords)
        assertEquals(null, info.presentation)
        assertEquals(null, info.license)
    }

    @Test
    fun `versioned and current wire schemas are published`() {
        listOf(
            "catalog-manifest-v5.schema.json",
            "catalog-manifest.schema.json",
            "resource-detail-v5.schema.json",
            "catalog-manifest-v6.schema.json",
            "resource-detail-v6.schema.json",
            "catalog-manifest-v7.schema.json",
            "resource-detail-v7.schema.json",
            "catalog-manifest-v8.schema.json",
            "resource-detail-v8.schema.json",
            "template-document-v8.schema.json",
            "template-shared-v8.schema.json",
            "theme-v8.schema.json",
            "resource-detail.schema.json",
        ).forEach { name ->
            assertNotNull(javaClass.getResource("/META-INF/epistola-catalog/schemas/$name"), name)
        }
    }

    private fun fixture(path: String) = requireNotNull(
        javaClass.getResourceAsStream("/META-INF/epistola-catalog/fixtures/v1/$path"),
    )

    @Test
    fun `the schemas wire v8 freezes match the live ones until a later wire version exists`() {
        // resource-detail-v8 references these copies, not the live schemas, so a later change to
        // a template document or a theme cannot change what wire v8 accepts. While v8 is the current
        // version the copies must match the live schemas, apart from their ids: a change to a live
        // schema is then a change to v8 and needs a new wire version, with its own copies, once v8
        // is released.
        val frozen = listOf("template-document", "template-shared", "theme")
        fun normalized(name: String): String = requireNotNull(javaClass.getResource("/META-INF/epistola-catalog/schemas/$name"), name::toString)
            .readText()
            .let(mapper::readTree)
            .let { tree -> (tree as tools.jackson.databind.node.ObjectNode).apply { remove("\$id") } }
            .toString()
            .let { text -> frozen.fold(text) { acc, schema -> acc.replace("\"$schema-v8.schema.json", "\"$schema.schema.json") } }

        frozen.forEach { schema ->
            assertEquals(normalized("$schema.schema.json"), normalized("$schema-v8.schema.json"), "$schema-v8.schema.json has drifted from $schema.schema.json")
        }
    }
}
