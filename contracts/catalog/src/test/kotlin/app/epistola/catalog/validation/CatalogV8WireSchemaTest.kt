// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.validation

import app.epistola.catalog.protocol.CatalogArchivePaths
import app.epistola.catalog.protocol.CatalogKeys
import app.epistola.catalog.protocol.CatalogVariantMetadata
import app.epistola.catalog.protocol.TemplateResource
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wire-v8 schema members added for data example keys, relative paths into the catalog (#95),
 * variant metadata (#96) and a template description (#97), validated with the published schemas and
 * held to the Kotlin rules that consumers enforce. `scripts/check-wire-fixtures.mjs` runs the same
 * cases through Ajv.
 */
class CatalogV8WireSchemaTest {
    private val mapper = jsonMapper()
    private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
        builder.schemas(SCHEMAS.associate { name -> "https://epistola.app/schemas/$name" to text("schemas/$name") })
    }
    private val manifestSchema = registry.getSchema(text("schemas/catalog-manifest-v8.schema.json"))
    private val detailSchema = registry.getSchema(text("schemas/resource-detail-v8.schema.json"))

    @Test
    fun `the exported rules are the schema's`() {
        val manifest = tree("schemas/catalog-manifest-v8.schema.json")["\$defs"]
        val detail = tree("schemas/resource-detail-v8.schema.json")["\$defs"]

        assertEquals(CatalogArchivePaths.PATTERN, manifest["ResourceEntry"]["properties"]["detailUrl"]["pattern"].asString())
        assertEquals(CatalogArchivePaths.PATTERN, detail["ImageResource"]["properties"]["contentUrl"]["pattern"].asString())
        assertEquals(CatalogArchivePaths.PATTERN, detail["FontVariantEntry"]["properties"]["contentUrl"]["pattern"].asString())

        // One rule for every resource key, wherever wire v8 names one.
        val shared = tree("schemas/template-shared.schema.json")["\$defs"]
        val keyPositions = listOf("TemplateResource", "ThemeResource", "StencilResource", "AttributeResource", "ImageResource", "CodeListResource", "FontResource")
            .map { "$it.key" to detail[it]["properties"]["key"] } +
            listOf(
                "TemplateResource.themeKey" to detail["TemplateResource"]["properties"]["themeKey"],
                "CodeListBindingRef.key" to detail["CodeListBindingRef"]["properties"]["key"],
                "DataExampleEntry.key" to detail["DataExampleEntry"]["properties"]["key"],
                "VariantEntry.key" to detail["VariantEntry"]["properties"]["key"],
                "ResourceEntry.key" to manifest["ResourceEntry"]["properties"]["key"],
                "CatalogDependencyRef.key" to manifest["CatalogDependencyRef"]["properties"]["key"],
                "AttributeAssignment.key" to manifest["AttributeAssignment"]["properties"]["key"],
                "CatalogPresentation.iconImageKey" to manifest["CatalogPresentation"]["properties"]["iconImageKey"],
                "CatalogPresentation.imageKeys" to manifest["CatalogPresentation"]["properties"]["imageKeys"]["items"],
                "ThemeRef.themeKey" to shared["ThemeRef"]["oneOf"][1]["properties"]["themeKey"],
            )
        keyPositions.forEach { (where, key) ->
            assertEquals(CatalogKeys.RESOURCE, CatalogKeys.Rule(key["pattern"].asString(), key["minLength"].asInt(), key["maxLength"].asInt()), where)
        }
        // A variant names another catalog's attribute as <catalogKey>.<key>.
        val attributeKey = detail["VariantEntry"]["properties"]["attributes"]["oneOf"][0]["propertyNames"]
        assertEquals(CatalogKeys.ATTRIBUTE_KEY_PATTERN, attributeKey["pattern"].asString())
        assertEquals(CatalogKeys.MIN_LENGTH, attributeKey["minLength"].asInt())
        assertEquals(CatalogKeys.ATTRIBUTE_KEY_MAX_LENGTH, attributeKey["maxLength"].asInt())
        val catalogKey = manifest["CatalogInfo"]["properties"]["key"]
        assertEquals(CatalogKeys.CATALOG, CatalogKeys.Rule(catalogKey["pattern"].asString(), catalogKey["minLength"].asInt(), catalogKey["maxLength"].asInt()))
        assertTrue("key" in detail["DataExampleEntry"]["required"].toList().map { it.asString() })

        val metadata = detail["VariantEntry"]["properties"]["metadata"]
        assertEquals(listOf(CatalogVariantMetadata.NAMESPACE_PATTERN), metadata["patternProperties"].propertyNames().toList())
        assertEquals(CatalogVariantMetadata.NAMESPACE_MAX_LENGTH, metadata["propertyNames"]["maxLength"].asInt())
        assertEquals(CatalogVariantMetadata.MIN_NAMESPACES, metadata["minProperties"].asInt())
        assertEquals(CatalogVariantMetadata.MAX_NAMESPACES, metadata["maxProperties"].asInt())

        assertEquals(TemplateResource.MAX_DESCRIPTION_LENGTH, detail["TemplateResource"]["properties"]["description"]["maxLength"].asInt())
    }

    @Test
    fun `a path into the catalog is relative and below the manifest, in the schema and in Kotlin`() {
        ACCEPTED_PATHS.forEach { path ->
            assertTrue(CatalogArchivePaths.isValid(path), path)
            assertEquals(emptyList(), manifestErrors { it["resources"][0] as ObjectNode }.invoke(path), path)
            assertEquals(emptyList(), detailErrors(image(path)), path)
            assertEquals(emptyList(), detailErrors(font(path)), path)
        }
        REJECTED_PATHS.forEach { path ->
            assertTrue(!CatalogArchivePaths.isValid(path), path)
            assertTrue(manifestErrors { it["resources"][0] as ObjectNode }.invoke(path).isNotEmpty(), path)
            assertTrue(detailErrors(image(path)).isNotEmpty(), path)
            assertTrue(detailErrors(font(path)).isNotEmpty(), path)
        }
    }

    @Test
    fun `a template's description, example keys and variant metadata follow the v8 schema`() {
        assertEquals(emptyList(), detailErrors(tree("fixtures/v1/wire-v8/resources/template/letter.json")))

        val invalid = mapOf<String, (ObjectNode) -> Unit>(
            "an example without a key" to { (it["dataExamples"][0] as ObjectNode).remove("key") },
            "an uppercase example key" to { (it["dataExamples"][0] as ObjectNode).put("key", "Standard") },
            "a 65-character example key" to { (it["dataExamples"][0] as ObjectNode).put("key", "a".repeat(65)) },
            "a 2001-character description" to { it.put("description", "a".repeat(2001)) },
            "empty metadata" to { (it["variants"][0] as ObjectNode).putObject("metadata") },
            "a namespace that is not reverse-DNS" to { (it["variants"][0]["metadata"] as ObjectNode).putObject("suite") },
            "an uppercase namespace" to { (it["variants"][0]["metadata"] as ObjectNode).putObject("App.epistola.suite") },
            "a 101-character namespace" to { (it["variants"][0]["metadata"] as ObjectNode).putObject("a." + "b".repeat(99)) },
            "a namespace whose value is not an object" to { (it["variants"][0]["metadata"] as ObjectNode).put("com.example.x", 6) },
            "17 namespaces" to { variant -> (1..16).forEach { (variant["variants"][0]["metadata"] as ObjectNode).putObject("com.example.n$it") } },
        )
        invalid.forEach { (description, edit) ->
            val detail = tree("fixtures/v1/wire-v8/resources/template/letter.json")
            edit(detail["resource"] as ObjectNode)
            assertTrue(detailErrors(detail).isNotEmpty(), "v8 schema accepted $description")
        }
        val valid = mapOf<String, (ObjectNode) -> Unit>(
            "no description" to { it.remove("description") },
            "a null description" to { it.putNull("description") },
            "no metadata" to { (it["variants"][0] as ObjectNode).remove("metadata") },
            "16 namespaces" to { variant -> (1..15).forEach { (variant["variants"][0]["metadata"] as ObjectNode).putObject("com.example.n$it") } },
            "a namespace of any content" to { (it["variants"][0]["metadata"] as ObjectNode).putObject("com.example.x").putArray("list").add(1).add("two") },
            "an example key with a leading digit" to { (it["dataExamples"][0] as ObjectNode).put("key", "1e-brief") },
        )
        valid.forEach { (description, edit) ->
            val detail = tree("fixtures/v1/wire-v8/resources/template/letter.json")
            edit(detail["resource"] as ObjectNode)
            assertEquals(emptyList(), detailErrors(detail), "v8 schema rejected $description")
        }
    }

    private fun manifestErrors(entry: (ObjectNode) -> ObjectNode): (String) -> List<String> = { detailUrl ->
        val manifest = tree("fixtures/v1/wire-v8/catalog.json") as ObjectNode
        entry(manifest).put("detailUrl", detailUrl)
        manifestSchema.validate(mapper.writeValueAsString(manifest), InputFormat.JSON).map { it.toString() }
    }

    private fun detailErrors(detail: JsonNode): List<String> = detailSchema.validate(mapper.writeValueAsString(detail), InputFormat.JSON).map { it.toString() }

    private fun image(contentUrl: String): JsonNode = mapper.readTree(
        """{"schemaVersion":8,"resource":{"type":"image","key":"logo","name":"Logo","mediaType":"image/png","contentHash":"${"0".repeat(64)}"}}""",
    ).also { (it["resource"] as ObjectNode).put("contentUrl", contentUrl) }

    private fun font(contentUrl: String): JsonNode = mapper.readTree(
        """{"schemaVersion":8,"resource":{"type":"font","key":"inter","name":"Inter","kind":"sans",
           "variants":[{"weight":400,"italic":false,"mediaType":"font/ttf","contentHash":"${"0".repeat(64)}"}]}}""",
    ).also { ((it["resource"]["variants"] as ArrayNode)[0] as ObjectNode).put("contentUrl", contentUrl) }

    private fun text(path: String) = requireNotNull(javaClass.getResource("/META-INF/epistola-catalog/$path"), path::toString).readText()

    private fun tree(path: String): JsonNode = mapper.readTree(text(path))

    private companion object {
        val SCHEMAS = listOf(
            "template-shared.schema.json",
            "template-document.schema.json",
            "theme.schema.json",
            "catalog-manifest-v8.schema.json",
            "resource-detail-v8.schema.json",
            "richtext-inline-v1.json",
            "richtext-block-v1.json",
        )

        /** Paths into the catalog wire v8 accepts (#95). */
        val ACCEPTED_PATHS = listOf(
            "./resources/template/a.json",
            "resources/a.json",
            "bin/" + "0".repeat(64),
            "./a",
            "resources/~user/.hidden/a_b-c.json",
            "resources/a%20b.json",
            "a..b/c",
            "...",
        )

        /** Paths into the catalog wire v8 rejects. */
        val REJECTED_PATHS = listOf(
            "https://other.example/x.json",
            "file:resources/a.json",
            "/abs/x.json",
            "//host/x.json",
            "resources\\a.json",
            "../x.json",
            "resources/../../x.json",
            "././x.json",
            "resources/./a.json",
            "resources//a.json",
            "resources/",
            "",
            "%2e%2e/x.json",
            "%2E%2E/x.json",
            "resources%2fa.json",
            "resources%5Ca.json",
            "resources/a b.json",
        )
    }
}
