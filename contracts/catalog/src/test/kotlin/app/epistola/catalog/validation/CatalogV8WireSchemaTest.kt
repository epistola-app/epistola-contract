// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.validation

import app.epistola.catalog.canonical.QualifiedCatalogFingerprint
import app.epistola.catalog.protocol.CatalogArchivePaths
import app.epistola.catalog.protocol.CatalogKeys
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.CatalogPresets
import app.epistola.catalog.protocol.CatalogReleaseVersions
import app.epistola.catalog.protocol.CatalogVariantMetadata
import app.epistola.catalog.protocol.ReleaseBase
import app.epistola.catalog.protocol.TemplateResource
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
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

    @Test
    fun `a catalog's default theme and declared presets follow the v8 schema and the exported rules`() {
        val defs = tree("schemas/catalog-manifest-v8.schema.json")["\$defs"]
        val themeRef = defs["CatalogThemeRef"]["properties"]
        assertEquals(CatalogKeys.RESOURCE, CatalogKeys.Rule(themeRef["key"]["pattern"].asString(), themeRef["key"]["minLength"].asInt(), themeRef["key"]["maxLength"].asInt()))
        assertEquals(
            CatalogKeys.CATALOG,
            CatalogKeys.Rule(themeRef["catalogKey"]["pattern"].asString(), themeRef["catalogKey"]["minLength"].asInt(), themeRef["catalogKey"]["maxLength"].asInt()),
        )
        val preset = defs["CatalogPresetDeclaration"]["properties"]
        assertEquals(CatalogPresets.KEY_PATTERN, preset["key"]["pattern"].asString())
        assertEquals(CatalogPresets.KEY_MAX_LENGTH, preset["key"]["maxLength"].asInt())
        assertEquals(CatalogPresets.NAME_MAX_LENGTH, preset["name"]["maxLength"].asInt())
        assertEquals(CatalogPresets.DESCRIPTION_MAX_LENGTH, preset["description"]["maxLength"].asInt())

        fun errors(edit: (ObjectNode) -> Unit): List<String> {
            val manifest = tree("fixtures/v1/wire-v8/catalog.json") as ObjectNode
            edit(manifest["catalog"] as ObjectNode)
            return manifestSchema.validate(mapper.writeValueAsString(manifest), InputFormat.JSON).map { it.toString() }
        }
        val valid = mapOf<String, (ObjectNode) -> Unit>(
            "neither member" to { },
            "a null default theme" to { it.putNull("defaultTheme") },
            "an empty preset list" to { it.putArray("presets") },
            "a default theme in the catalog itself" to { it.putObject("defaultTheme").put("key", "default") },
            "a default theme in another catalog" to {
                it.putObject("defaultTheme").put("key", "corporate").put("catalogKey", "shared").put("namespace", "acme").put("registry", "acme.com")
            },
            "presets" to { catalog ->
                catalog.putArray("presets").apply {
                    addObject().put("key", "muted").put("name", "Muted")
                    addObject().put("key", "h1").put("name", "Heading").put("description", "Top-level heading")
                    addObject().put("key", "label").put("name", "Label").putNull("description")
                }
            },
        )
        valid.forEach { (what, edit) -> assertEquals(emptyList(), errors(edit), "v8 schema rejected $what") }
        val invalid = mapOf<String, (ObjectNode) -> Unit>(
            "a default theme without a key" to { it.putObject("defaultTheme").put("catalogKey", "shared") },
            "a default theme naming a registry without a namespace" to { it.putObject("defaultTheme").put("key", "corporate").put("registry", "acme.com") },
            "a default theme with an unknown member" to { it.putObject("defaultTheme").put("key", "corporate").put("version", "1.0.0") },
            "a default theme given as a string" to { it.put("defaultTheme", "corporate") },
            "a preset without a name" to { it.putArray("presets").addObject().put("key", "muted") },
            "an uppercase preset key" to { it.putArray("presets").addObject().put("key", "Muted").put("name", "Muted") },
            "a preset key with a leading digit" to { it.putArray("presets").addObject().put("key", "1-col").put("name", "One column") },
            "a 501-character preset description" to { it.putArray("presets").addObject().put("key", "muted").put("name", "Muted").put("description", "d".repeat(501)) },
            "null presets" to { it.putNull("presets") },
        )
        invalid.forEach { (what, edit) -> assertTrue(errors(edit).isNotEmpty(), "v8 schema accepted $what") }

        val withBoth = tree("fixtures/v1/wire-v8/catalog.json") as ObjectNode
        valid.getValue("a default theme in another catalog")(withBoth["catalog"] as ObjectNode)
        valid.getValue("presets")(withBoth["catalog"] as ObjectNode)
        val kotlinMapper = jsonMapper { addModule(kotlinModule()) }
        val bound = kotlinMapper.treeToValue(withBoth, CatalogManifest::class.java)
        assertEquals(withBoth["catalog"]["defaultTheme"], kotlinMapper.valueToTree<JsonNode>(bound.catalog)["defaultTheme"])
        assertEquals(bound, kotlinMapper.readValue(kotlinMapper.writeValueAsBytes(bound), CatalogManifest::class.java))
        assertEquals(
            emptyList(),
            manifestSchema.validate(kotlinMapper.writeValueAsString(bound), InputFormat.JSON).map { it.toString() },
            "the model writes what the schema accepts",
        )
    }

    @Test
    fun `a release's base follows the v8 schema, a release number and a known fingerprint and nothing more`() {
        val schema = tree("schemas/catalog-manifest-v8.schema.json")["\$defs"]["ReleaseBase"]
        val base = schema["properties"]
        assertEquals(listOf("version", "fingerprint"), base.propertyNames().toList())
        assertEquals(listOf("version", "fingerprint"), buildList { schema["required"].forEach { add(it.asString()) } })
        assertEquals(CatalogReleaseVersions.PATTERN, base["version"]["pattern"].asString())
        assertEquals(CatalogReleaseVersions.MAX_LENGTH, base["version"]["maxLength"].asInt())
        assertEquals(ReleaseBase.FINGERPRINT_PATTERN, base["fingerprint"]["pattern"].asString())
        assertEquals(QualifiedCatalogFingerprint.PATTERN, ReleaseBase.FINGERPRINT_PATTERN)
        assertTrue(ReleaseBase.isValidFingerprint("v5:" + "0".repeat(64)))
        listOf("0".repeat(64), "v9:" + "0".repeat(64), "v5:" + "0".repeat(63)).forEach { assertTrue(!ReleaseBase.isValidFingerprint(it), it) }

        fun errors(edit: (ObjectNode) -> Unit): List<String> {
            val manifest = tree("fixtures/v1/wire-v8/catalog.json") as ObjectNode
            edit(manifest["release"] as ObjectNode)
            return manifestSchema.validate(mapper.writeValueAsString(manifest), InputFormat.JSON).map { it.toString() }
        }
        fun ObjectNode.base() = this["base"] as ObjectNode
        val digest = "0".repeat(64)

        val valid = mapOf<String, (ObjectNode) -> Unit>(
            "the fixture's base" to { },
            "no base" to { it.remove("base") },
            "a base fingerprinted under an older algorithm" to { it.base().put("fingerprint", "v4:$digest") },
        )
        valid.forEach { (what, edit) -> assertEquals(emptyList(), errors(edit), "v8 schema rejected $what") }
        val invalid = mapOf<String, (ObjectNode) -> Unit>(
            "a base without a version" to { it.base().remove("version") },
            "a base without a fingerprint" to { it.base().remove("fingerprint") },
            "a base version with a pre-release" to { it.base().put("version", "1.0.0-beta.1") },
            "a bare-hex base fingerprint" to { it.base().put("fingerprint", digest) },
            "a base with per-resource fingerprints" to {
                it.base().putObject("resourceFingerprints").put("algorithm", "v3").putObject("resources")
            },
            "an unknown member" to { it.base().put("releaseId", "k7m2x9qa") },
            "a null base" to { it.putNull("base") },
        )
        invalid.forEach { (what, edit) -> assertTrue(errors(edit).isNotEmpty(), "v8 schema accepted $what") }
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
