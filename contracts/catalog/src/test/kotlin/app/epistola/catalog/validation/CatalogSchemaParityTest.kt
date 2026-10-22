// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.validation

import app.epistola.catalog.canonical.QualifiedCatalogFingerprint
import app.epistola.catalog.protocol.CatalogAliases
import app.epistola.catalog.protocol.CatalogDependency
import app.epistola.catalog.protocol.CatalogKeys
import app.epistola.catalog.protocol.CatalogNamespaces
import tools.jackson.module.kotlin.jsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogSchemaParityTest {
    @Test
    fun `theme override schema names the theme's catalog by an alias alone, as the Kotlin model does`() {
        val schema = requireNotNull(
            javaClass.getResourceAsStream("/META-INF/epistola-catalog/schemas/template-shared.schema.json"),
        ).use(jsonMapper()::readTree)
        val overrideProperties = schema["\$defs"]["ThemeRef"]["oneOf"][1]["properties"]

        assertEquals(listOf("type", "themeKey", "catalogKey"), overrideProperties.propertyNames().toList())
        assertAlias(overrideProperties["catalogKey"])
    }

    @Test
    fun `wherever wire v8 names another catalog, it names an alias by the alias rule`() {
        val manifest = schema("catalog-manifest-v8.schema.json")["\$defs"]
        val detail = schema("resource-detail-v8.schema.json")["\$defs"]
        listOf(
            manifest["CatalogDependency"]["properties"]["alias"],
            manifest["CatalogDependencyRef"]["properties"]["catalogKey"],
            manifest["CatalogThemeRef"]["properties"]["catalogKey"],
            detail["TemplateResource"]["properties"]["themeCatalogKey"],
            detail["CodeListBindingRef"]["properties"]["catalogKey"],
        ).forEach(::assertAlias)
        assertEquals(CatalogKeys.CATALOG_PATTERN, CatalogAliases.PATTERN)
        // A reference never names a registry or namespace: those are the declaration's.
        listOf(
            manifest["CatalogDependencyRef"],
            manifest["CatalogThemeRef"],
            detail["CodeListBindingRef"],
        ).forEach { definition ->
            val properties = definition["properties"]
            assertTrue(!properties.has("registry") && !properties.has("namespace") && !definition.has("dependentRequired"), definition.toString())
        }
        val template = detail["TemplateResource"]
        assertTrue(!template["properties"].has("themeRegistry") && !template["properties"].has("themeNamespace") && !template.has("dependentRequired"))
        val required = manifest["CatalogDependency"]["required"]
        assertTrue("alias" in List(required.size()) { required[it].asString() }, required.toString())
    }

    private fun assertAlias(property: tools.jackson.databind.JsonNode) {
        assertEquals(CatalogAliases.PATTERN, property["pattern"].asString(), property.toString())
        assertEquals(CatalogAliases.MIN_LENGTH, property["minLength"].asInt(), property.toString())
        assertEquals(CatalogAliases.MAX_LENGTH, property["maxLength"].asInt(), property.toString())
    }

    @Test
    fun `v8 registry and namespace rules match the schema`() {
        val manifest = schema("catalog-manifest-v8.schema.json")
        val detail = schema("resource-detail-v8.schema.json")
        val namespace = manifest["\$defs"]["CatalogNamespace"]
        val registry = manifest["\$defs"]["CatalogRegistry"]

        assertEquals(CatalogNamespaces.PATTERN, namespace["pattern"].asString())
        assertEquals(CatalogNamespaces.MIN_LENGTH, namespace["minLength"].asInt())
        assertEquals(CatalogNamespaces.MAX_LENGTH, namespace["maxLength"].asInt())
        assertEquals(CatalogNamespaces.REGISTRY_PATTERN, registry["pattern"].asString())
        assertEquals(CatalogNamespaces.REGISTRY_MAX_LENGTH, registry["maxLength"].asInt())
        // The manifest names a catalog by registry and namespace for the catalog itself and each declaration only.
        listOf("CatalogInfo", "CatalogDependency").forEach { name ->
            val node = manifest["\$defs"][name]["required"]
            val required = List(node.size()) { node[it].asString() }
            assertTrue("registry" in required && "namespace" in required, "$name requires registry and namespace: $required")
        }
        // A declaration pins an exact release: it carries no version selector.
        assertTrue(!manifest["\$defs"]["CatalogDependency"]["properties"].has("version"))
        assertEquals(
            CatalogDependency.RELEASE_FINGERPRINT_PATTERN,
            manifest["\$defs"]["CatalogDependency"]["properties"]["releaseFingerprint"]["pattern"].asString(),
        )
        assertEquals(
            QualifiedCatalogFingerprint.PATTERN,
            manifest["\$defs"]["ReleaseInfo"]["properties"]["fingerprint"]["pattern"].asString(),
        )
        assertTrue(!detail["\$defs"]["StencilResource"]["properties"].has("version"))
    }

    @Test
    fun `registry is a DNS name and namespace a slug`() {
        listOf("acme.com", "exchange.epistola.app", "a.b", "a".repeat(249) + ".com").forEach {
            assertTrue(CatalogNamespaces.isValidRegistry(it), it)
            assertTrue(!CatalogNamespaces.isValid(it), it)
        }
        listOf("acme-corp", "abc", "a" + "b".repeat(62)).forEach {
            assertTrue(CatalogNamespaces.isValid(it), it)
            assertTrue(!CatalogNamespaces.isValidRegistry(it), it)
        }
        listOf("", "ab", "Acme", "acme-", "1acme", "a".repeat(64), "acme.com/acme-corp", "~").forEach {
            assertTrue(!CatalogNamespaces.isValid(it), it)
        }
        listOf("", "acme", "Acme.com", "acme.com.", "-acme.com", "acme..com", "acme.com/acme-corp", "~", "a".repeat(250) + ".com").forEach {
            assertTrue(!CatalogNamespaces.isValidRegistry(it), it)
        }
    }

    @Test
    fun `local is the one registry without a dot`() {
        assertTrue(CatalogNamespaces.isValidRegistry(CatalogNamespaces.LOCAL_REGISTRY))
        assertTrue(CatalogNamespaces.isLocalRegistry("local"))
        listOf("local.example", "local.acme.com").forEach {
            assertTrue(CatalogNamespaces.isValidRegistry(it), it)
            assertTrue(!CatalogNamespaces.isLocalRegistry(it), it)
        }
        listOf("localhost", "Local", "locals", "local-", "xlocal", "local.", ".local", "local/hr").forEach {
            assertTrue(!CatalogNamespaces.isValidRegistry(it), it)
        }
    }

    private fun schema(name: String) = requireNotNull(
        javaClass.getResourceAsStream("/META-INF/epistola-catalog/schemas/$name"),
    ).use(jsonMapper()::readTree)

    @Test
    fun `template resource schema exposes pdfa enabled with the Kotlin default`() {
        val schema = requireNotNull(
            javaClass.getResourceAsStream("/META-INF/epistola-catalog/schemas/resource-detail-v8.schema.json"),
        ).use(jsonMapper()::readTree)
        val property = schema["\$defs"]["TemplateResource"]["properties"]["pdfaEnabled"]

        assertEquals("boolean", property["type"].asString())
        assertTrue(property["default"].asBoolean())
    }
}
