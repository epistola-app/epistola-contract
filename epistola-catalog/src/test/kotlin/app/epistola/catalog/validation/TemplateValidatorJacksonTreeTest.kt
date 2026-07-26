package app.epistola.catalog.validation

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemplateValidatorJacksonTreeTest {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `text content accepts the canonical portable document representation`() {
        listOf(
            mapper.readTree("""{"type":"doc","content":[]}"""),
            mapOf("type" to "doc", "content" to emptyList<Any>()),
        ).forEach { content ->
            val document = TemplateDocument(
                root = "root",
                nodes = mapOf(
                    "root" to Node("root", "root", slots = listOf("children")),
                    "text" to Node(
                        "text",
                        "text",
                        props = mapOf("content" to content),
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

    @Test
    fun `text content rejects non-canonical historical representations`() {
        listOf(
            mapper.readTree("""[{"type":"text","text":"Hello"}]"""),
            listOf(mapOf("type" to "text", "text" to "Hello")),
            "Hello {{name}}",
            mapOf("type" to "paragraph", "content" to emptyList<Any>()),
            mapOf("type" to "doc", "content" to "not-an-array"),
        ).forEach { content ->
            val document = TemplateDocument(
                root = "root",
                nodes = mapOf(
                    "root" to Node("root", "root", slots = listOf("children")),
                    "text" to Node("text", "text", props = mapOf("content" to content)),
                ),
                slots = mapOf("children" to Slot("children", "root", "children", listOf("text"))),
            )

            val report = TemplateValidator.validate(document)
            assertFalse(report.valid)
            assertTrue(
                report.findings.any { it.code == TemplateValidationCodes.TEMPLATE_NODE_PROPERTY_INVALID },
                report.findings.toString(),
            )
        }
    }

    @Test
    fun `unused static component slots may be omitted`() {
        val document = TemplateDocument(
            root = "root",
            nodes = mapOf(
                "root" to Node("root", "root", slots = listOf("children")),
                "stencil" to Node(
                    "stencil",
                    "stencil",
                    props = mapOf("stencilId" to "address", "version" to 1, "isDraft" to false),
                ),
            ),
            slots = mapOf("children" to Slot("children", "root", "children", listOf("stencil"))),
        )

        assertTrue(
            TemplateValidator.validate(document).findings
                .none { it.code == TemplateValidationCodes.TEMPLATE_SLOT_INVALID },
        )
    }
}
