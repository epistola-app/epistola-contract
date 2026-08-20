// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.validation

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
    }

    @Test
    fun `template resource schema exposes pdfa enabled with the Kotlin default`() {
        val schema = requireNotNull(
            javaClass.getResourceAsStream("/META-INF/epistola-catalog/schemas/resource-detail-v6.schema.json"),
        ).use(jsonMapper()::readTree)
        val property = schema["\$defs"]["TemplateResource"]["properties"]["pdfaEnabled"]

        assertEquals("boolean", property["type"].asString())
        assertTrue(property["default"].asBoolean())
    }
}
