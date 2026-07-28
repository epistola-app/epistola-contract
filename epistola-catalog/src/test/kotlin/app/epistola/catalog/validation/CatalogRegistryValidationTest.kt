package app.epistola.catalog.validation

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import kotlin.test.Test
import kotlin.test.assertTrue

class CatalogRegistryValidationTest {
    private val mapper = jsonMapper { addModule(kotlinModule()) }

    @Test
    fun `every published component example satisfies registry validation`() {
        val root = javaClass.getResourceAsStream("/META-INF/epistola-catalog/component-registry.json")!!
            .use(mapper::readTree)
        val registryCodes = setOf(
            TemplateValidationCodes.TEMPLATE_SLOT_INVALID,
            TemplateValidationCodes.TEMPLATE_CHILD_TYPE_NOT_ALLOWED,
            TemplateValidationCodes.TEMPLATE_NODE_PROPERTY_INVALID,
            TemplateValidationCodes.TEMPLATE_STYLE_UNKNOWN,
            TemplateValidationCodes.TEMPLATE_STYLE_NOT_APPLICABLE,
            TemplateValidationCodes.TEMPLATE_EXPRESSION_INVALID,
        )

        root["components"].forEach { component ->
            component["examples"].elements().forEach { example ->
                val fragment = example["fragment"]
                val document = TemplateDocument(
                    root = fragment["rootNodeId"].asString(),
                    nodes = fragment["nodes"].properties().asSequence().associate { (id, node) ->
                        id to mapper.treeToValue(node, Node::class.java)
                    },
                    slots = fragment["slots"].properties().asSequence().associate { (id, slot) ->
                        id to mapper.treeToValue(slot, Slot::class.java)
                    },
                )
                val findings = TemplateValidator.validate(document).findings.filter { it.code in registryCodes }

                assertTrue(
                    findings.isEmpty(),
                    "${component["type"].asString()}/${example["name"].asString()}: $findings",
                )
            }
        }
    }

    @Test
    fun `registry accepts style combinations used by canonical catalogs`() {
        val table = Node(
            id = "table",
            type = "table",
            styles = mapOf("marginTop" to "1sp"),
        )
        val document = TemplateDocument(
            root = "root",
            nodes = mapOf(
                "root" to Node("root", "root", listOf("root-slot")),
                table.id to table,
            ),
            slots = mapOf("root-slot" to Slot("root-slot", "root", "children", listOf(table.id))),
        )

        val findings = TemplateValidator.validate(document).findings
            .filter { it.code == TemplateValidationCodes.TEMPLATE_STYLE_NOT_APPLICABLE }
        assertTrue(findings.isEmpty(), findings.toString())
    }

    private fun JsonNode?.elements(): List<JsonNode> = if (this != null && isArray) toList() else emptyList()
}
