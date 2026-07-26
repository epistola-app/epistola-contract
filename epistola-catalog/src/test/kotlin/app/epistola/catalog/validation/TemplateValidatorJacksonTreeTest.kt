package app.epistola.catalog.validation

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertTrue

class TemplateValidatorJacksonTreeTest {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `Jackson tree properties retain their portable JSON shape`() {
        val document = TemplateDocument(
            root = "root",
            nodes = mapOf(
                "root" to Node("root", "root", slots = listOf("children")),
                "text" to Node(
                    "text",
                    "text",
                    props = mapOf(
                        "content" to mapper.readTree("""{"type":"doc","content":[]}"""),
                    ),
                ),
            ),
            slots = mapOf("children" to Slot("children", "root", "children", listOf("text"))),
        )

        assertTrue(
            TemplateValidator.validate(document).findings
                .none { it.code == TemplateValidationCodes.TEMPLATE_NODE_PROPERTY_INVALID },
        )
    }
}
