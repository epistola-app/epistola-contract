// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.validation

import app.epistola.catalog.validation.TemplateValidationCodes.TEMPLATE_SLOT_INVALID
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #97.5: a conditional has an optional `else` branch beside its `body`. */
class TemplateValidatorConditionalTest {
    @Test
    fun `a conditional with a body and an else is valid`() {
        val document = conditional("body", "else")

        assertEquals(emptyList(), TemplateValidator.validate(document).findings)
    }

    @Test
    fun `a conditional without an else stays valid`() {
        assertEquals(emptyList(), TemplateValidator.validate(conditional("body")).findings)
    }

    @Test
    fun `a conditional slot that is neither body nor else is refused`() {
        val findings = TemplateValidator.validate(conditional("body", "otherwise")).findings

        assertTrue(findings.any { it.code == TEMPLATE_SLOT_INVALID && it.path == "slots.c-otherwise.name" }, "findings: $findings")
    }

    private fun conditional(vararg slotNames: String): TemplateDocument {
        val slots = slotNames.map { name ->
            Slot("c-$name", "c", name, listOf("t-$name"))
        }
        val texts = slotNames.associate { name ->
            "t-$name" to Node(
                "t-$name",
                "text",
                props = mapOf("content" to mapOf("type" to "doc", "content" to listOf(mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to name)))))),
            )
        }
        val node = Node(
            "c",
            "conditional",
            slots = slots.map(Slot::id),
            props = mapOf("condition" to mapOf("raw" to "\$exists(notes)", "language" to "jsonata"), "inverse" to false),
        )
        return TemplateDocument(
            root = "root",
            nodes = mapOf("root" to Node("root", "root", listOf("root-slot")), node.id to node) + texts,
            slots = mapOf("root-slot" to Slot("root-slot", "root", "children", listOf(node.id))) + slots.associateBy(Slot::id),
        )
    }
}
