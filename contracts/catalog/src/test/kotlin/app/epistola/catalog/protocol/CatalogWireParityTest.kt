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
        assertEquals(listOf(CatalogDependency("exchange.epistola.app", "epistola", "shared", "^1.2.0", "1.4.0", "v5:69e540ae7bea571f81ff51919c8fd7d4dfca8d792da9fcc5a559034218feaa42")), manifest.catalogDependencies)
        assertEquals(manifest, mapper.readValue(mapper.writeValueAsBytes(manifest), CatalogManifest::class.java))
        assertEquals(detail, mapper.readValue(mapper.writeValueAsBytes(detail), ResourceDetail::class.java))
    }

    @Test
    fun `a v8 template's description, example keys and variant metadata are written back member for member`() {
        val source = fixture("wire-v8/resources/template/letter.json").use(mapper::readTree)
        val detail = mapper.treeToValue(source, ResourceDetail::class.java)
        val template = detail.resource as TemplateResource

        assertEquals("standard-letter", template.dataExamples.orEmpty().single().key)
        assertEquals(mapOf("app.epistola.suite" to mapOf("renderingDefaultsVersion" to 6)), template.variants.single().metadata)
        assertTrue(template.description.orEmpty().startsWith("A one-page letter"))
        val written = mapper.valueToTree<JsonNode>(detail)["resource"]
        for (member in listOf("description", "dataExamples")) {
            assertEquals(source["resource"][member], written[member], member)
        }
        assertEquals(source["resource"]["variants"][0]["metadata"], written["variants"][0]["metadata"])
        assertEquals(detail, mapper.readValue(mapper.writeValueAsBytes(detail), ResourceDetail::class.java))
    }

    @Test
    fun `a namespace the reader does not recognise is kept as it is`() {
        val json = """{"key":"english","metadata":{"com.example.renderer":{"nested":{"list":[1,"two",null,{"x":false}]},"n":1.5},
            "app.epistola.suite":{"renderingDefaultsVersion":6}}}"""
        val variant = mapper.readValue(json, VariantEntry::class.java)

        assertEquals(mapper.readTree(json)["metadata"], mapper.valueToTree<JsonNode>(variant)["metadata"])
    }

    @Test
    fun `members wire v8 added bind when absent, so documents stored earlier still load, and are then omitted`() {
        val example = mapper.readValue("""{"name":"Standard letter","data":{}}""", DataExampleEntry::class.java)
        val variant = mapper.readValue("""{"key":"english"}""", VariantEntry::class.java)

        assertEquals(null, example.key)
        assertEquals(null, variant.metadata)
        assertTrue(!mapper.valueToTree<JsonNode>(example).has("key"))
        assertTrue(!mapper.valueToTree<JsonNode>(variant).has("metadata"))
        assertTrue(!mapper.valueToTree<JsonNode>(variant.copy(metadata = emptyMap())).has("metadata"), "empty metadata is omitted")
        assertEquals(listOf("key", "name", "data"), mapper.valueToTree<JsonNode>(example.copy(key = "standard-letter")).propertyNames().toList())
    }

    @Test
    fun `the model binds documents stored under v7, which name no registry or namespace and pin stencil versions`() {
        val manifest = fixture("migrations/v7-to-v8/manifest-input.json").use { mapper.readValue(it, CatalogManifest::class.java) }
        val stencil = fixture("migrations/v7-to-v8/stencil-input.json").use { mapper.readValue(it, ResourceDetail::class.java) }

        assertEquals(null, manifest.catalog.registry)
        assertEquals(null, manifest.catalog.namespace)
        assertEquals(null, manifest.catalogDependencies)
        assertTrue(manifest.dependencies.orEmpty().all { it.registry == null && it.namespace == null })
        assertEquals("letter", stencil.resource.key)
    }

    @Test
    fun `optional content registries and namespaces are omitted when absent so earlier fingerprints still reproduce`() {
        val binding = mapper.valueToTree<JsonNode>(CodeListBindingRef(key = "countries"))
        val font = mapper.valueToTree<JsonNode>(FontRef(key = "inter"))
        val theme = mapper.valueToTree<JsonNode>(ThemeRefOverride("corporate"))

        listOf(binding, font, theme).forEach { node ->
            assertTrue(!node.has("namespace") && !node.has("registry"), node.toString())
        }
        val qualified = mapper.valueToTree<JsonNode>(CodeListBindingRef(key = "x", namespace = "acme", registry = "acme.com"))
        assertEquals("acme", qualified["namespace"].asString())
        assertEquals("acme.com", qualified["registry"].asString())
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
            "resource-detail.schema.json",
        ).forEach { name ->
            assertNotNull(javaClass.getResource("/META-INF/epistola-catalog/schemas/$name"), name)
        }
    }

    private fun fixture(path: String) = requireNotNull(
        javaClass.getResourceAsStream("/META-INF/epistola-catalog/fixtures/v1/$path"),
    )
}
