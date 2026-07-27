package app.epistola.catalog.validation

import tools.jackson.module.kotlin.jsonMapper
import kotlin.test.Test
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
}
