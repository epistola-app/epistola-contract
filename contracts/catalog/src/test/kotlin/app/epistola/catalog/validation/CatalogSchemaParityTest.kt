// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.validation

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
    }

    @Test
    fun `v8 namespace rules match the schema`() {
        val manifest = schema("catalog-manifest-v8.schema.json")
        val detail = schema("resource-detail-v8.schema.json")
        val full = manifest["\$defs"]["CatalogNamespace"]
        val content = detail["\$defs"]["CodeListBindingRef"]["properties"]["namespace"]

        assertEquals(CatalogNamespaces.FULL_PATTERN, full["pattern"].asString())
        assertEquals(CatalogNamespaces.MIN_LENGTH, full["minLength"].asInt())
        assertEquals(CatalogNamespaces.MAX_LENGTH, full["maxLength"].asInt())
        assertEquals(CatalogNamespaces.PATTERN, content["pattern"].asString())
        assertEquals(CatalogNamespaces.PATTERN, detail["\$defs"]["TemplateResource"]["properties"]["themeNamespace"]["pattern"].asString())
        assertEquals(
            CatalogDependency.MAX_VERSION_LENGTH,
            manifest["\$defs"]["CatalogDependency"]["properties"]["version"]["maxLength"].asInt(),
        )
        assertTrue(!detail["\$defs"]["StencilResource"]["properties"].has("version"))
    }

    @Test
    fun `namespace rules accept both forms only where content allows the short one`() {
        listOf("acme.com", "exchange.epistola.app/acme-corp", "a.b").forEach {
            assertTrue(CatalogNamespaces.isValidFull(it), it)
            assertTrue(CatalogNamespaces.isValid(it), it)
        }
        assertTrue(!CatalogNamespaces.isValidFull("acme-corp"))
        assertTrue(CatalogNamespaces.isValid("acme-corp"))
        listOf("", "ab", "Acme.com", "acme.com/", "acme.com/Acme", "-acme.com", "acme..com", "a".repeat(318) + ".com").forEach {
            assertTrue(!CatalogNamespaces.isValidFull(it), it)
            assertTrue(!CatalogNamespaces.isValid(it), it)
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
