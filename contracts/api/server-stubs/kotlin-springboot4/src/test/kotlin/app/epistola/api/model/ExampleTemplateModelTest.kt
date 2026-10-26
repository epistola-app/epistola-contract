// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.api.model

import app.epistola.catalog.validation.TemplateValidator
import app.epistola.template.model.TemplateDocument
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * #18: the template model the API reference shows for `updateVariantContent` is the guide's worked
 * example (`contracts/api/docs/examples/template-letter.json`), and it is a valid template. The spec
 * inlines it because a bundle does not resolve a `$ref` inside an example, so this keeps the copy
 * equal to its source.
 */
class ExampleTemplateModelTest {
    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    private val source = Path.of("../../docs/examples/template-letter.json")

    @Suppress("UNCHECKED_CAST")
    private fun specExample(): Any? {
        val resource = javaClass.getResourceAsStream("/openapi/epistola-contract.yaml")
        assertNotNull(resource, "bundled spec missing from classpath")
        val spec = resource.use { Yaml().load<Map<String, Any>>(it) }
        val schemas = (spec["components"] as Map<String, Any>)["schemas"] as Map<String, Any>
        val request = schemas["UpdateVariantContentRequest"] as Map<String, Any>
        return (request["example"] as Map<String, Any>)["templateModel"]
    }

    @Test
    fun `the spec's example template model is the guide's worked example`() {
        val expected = mapper.readTree(Files.readString(source))

        assertEquals(expected, mapper.valueToTree(specExample()))
    }

    @Test
    fun `the spec's example template model is a valid template`() {
        val document = mapper.convertValue(specExample(), TemplateDocument::class.java)

        assertEquals(emptyList(), TemplateValidator.validate(document).findings)
    }
}
