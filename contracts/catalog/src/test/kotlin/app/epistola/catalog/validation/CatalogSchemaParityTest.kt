// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.validation

import app.epistola.catalog.canonical.QualifiedCatalogFingerprint
import app.epistola.catalog.protocol.CatalogDependency
import app.epistola.catalog.protocol.CatalogNamespaces
import tools.jackson.module.kotlin.jsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogSchemaParityTest {
    @Test
    fun `theme override schema accepts the catalog key exposed by the Kotlin model`() {
        val schema = requireNotNull(
            javaClass.getResourceAsStream("/META-INF/epistola-catalog/schemas/template-shared.schema.json"),
        ).use(jsonMapper()::readTree)
        val overrideProperties = schema["\$defs"]["ThemeRef"]["oneOf"][1]["properties"]

        assertTrue(overrideProperties.has("catalogKey"), overrideProperties.toString())
        assertEquals(CatalogNamespaces.PATTERN, overrideProperties["namespace"]["pattern"].asString())
        assertEquals(CatalogNamespaces.REGISTRY_PATTERN, overrideProperties["registry"]["pattern"].asString())
        assertEquals("namespace", schema["\$defs"]["ThemeRef"]["oneOf"][1]["dependentRequired"]["registry"][0].asString())
    }

    @Test
    fun `v8 registry and namespace rules match the schema`() {
        val manifest = schema("catalog-manifest-v8.schema.json")
        val detail = schema("resource-detail-v8.schema.json")
        val namespace = manifest["\$defs"]["CatalogNamespace"]
        val registry = manifest["\$defs"]["CatalogRegistry"]
        val binding = detail["\$defs"]["CodeListBindingRef"]
        val template = detail["\$defs"]["TemplateResource"]

        assertEquals(CatalogNamespaces.PATTERN, namespace["pattern"].asString())
        assertEquals(CatalogNamespaces.MIN_LENGTH, namespace["minLength"].asInt())
        assertEquals(CatalogNamespaces.MAX_LENGTH, namespace["maxLength"].asInt())
        assertEquals(CatalogNamespaces.REGISTRY_PATTERN, registry["pattern"].asString())
        assertEquals(CatalogNamespaces.REGISTRY_MAX_LENGTH, registry["maxLength"].asInt())
        for (properties in listOf(binding["properties"])) {
            assertEquals(CatalogNamespaces.PATTERN, properties["namespace"]["pattern"].asString())
            assertEquals(CatalogNamespaces.MAX_LENGTH, properties["namespace"]["maxLength"].asInt())
            assertEquals(CatalogNamespaces.REGISTRY_PATTERN, properties["registry"]["pattern"].asString())
            assertEquals(CatalogNamespaces.REGISTRY_MAX_LENGTH, properties["registry"]["maxLength"].asInt())
        }
        assertEquals(CatalogNamespaces.PATTERN, template["properties"]["themeNamespace"]["pattern"].asString())
        assertEquals(CatalogNamespaces.REGISTRY_PATTERN, template["properties"]["themeRegistry"]["pattern"].asString())
        assertEquals("namespace", binding["dependentRequired"]["registry"][0].asString())
        assertEquals("themeNamespace", template["dependentRequired"]["themeRegistry"][0].asString())
        listOf("CatalogInfo", "CatalogDependencyRef", "CatalogDependency").forEach { name ->
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
