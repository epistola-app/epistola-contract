// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.validation

import app.epistola.catalog.validation.TemplateValidationCodes.NODE_PARAMETER_BINDINGS_INVALID_SHAPE
import app.epistola.catalog.validation.TemplateValidationCodes.NODE_PARAMS_ALIAS_RESERVED
import app.epistola.catalog.validation.TemplateValidationCodes.TEMPLATE_NODE_PROPERTY_INVALID
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR 0006: a stencil instance may carry `readsTemplateData` (from its stencil, while expanded), and
 * a placeholder may pass the stencil's parameters to its fill (`passParameters`) under a name
 * (`parametersAs`) that follows the rules of a `paramsAlias`.
 */
class TemplateValidatorStencilScopeTest {

    @Test
    fun `a stencil instance may carry readsTemplateData, and a placeholder passParameters and parametersAs`() {
        val report = TemplateValidator.validate(
            template(
                stencilProps = mapOf("readsTemplateData" to true),
                placeholderProps = mapOf("passParameters" to true, "parametersAs" to "letter"),
            ),
        )

        assertTrue(report.valid, report.findings.toString())
    }

    @Test
    fun `the flags must be booleans`() {
        val report = TemplateValidator.validate(
            template(stencilProps = mapOf("readsTemplateData" to "yes"), placeholderProps = mapOf("passParameters" to 1)),
        )

        assertEquals(
            listOf(
                TEMPLATE_NODE_PROPERTY_INVALID to "nodes.body.props.passParameters",
                TEMPLATE_NODE_PROPERTY_INVALID to "nodes.inst.props.readsTemplateData",
            ),
            report.findings.filter { it.code == TEMPLATE_NODE_PROPERTY_INVALID }.map { it.code to it.path }.sortedBy { it.second },
        )
    }

    @Test
    fun `parametersAs follows the rules of a paramsAlias`() {
        val reserved = TemplateValidator.validate(template(placeholderProps = mapOf("parametersAs" to "item")))
        val invalid = TemplateValidator.validate(template(placeholderProps = mapOf("parametersAs" to "Not A Name")))

        assertEquals(listOf(NODE_PARAMS_ALIAS_RESERVED), reserved.findings.filter { it.path == "nodes.body.props.parametersAs" }.map { it.code })
        assertEquals(listOf(NODE_PARAMETER_BINDINGS_INVALID_SHAPE), invalid.findings.filter { it.path == "nodes.body.props.parametersAs" }.map { it.code })
    }

    @Test
    fun `other props on a placeholder are still refused`() {
        val report = TemplateValidator.validate(template(placeholderProps = mapOf("exposes" to true)))

        assertEquals(
            listOf("nodes.body.props.exposes"),
            report.findings.filter { it.code == TEMPLATE_NODE_PROPERTY_INVALID }.map { it.path },
        )
    }

    /** A template with one stencil instance filling placeholder `body`. */
    private fun template(stencilProps: Map<String, Any?> = emptyMap(), placeholderProps: Map<String, Any?> = emptyMap()) = TemplateDocument(
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("root-children")),
            "inst" to Node(id = "inst", type = "stencil", slots = listOf("inst-children"), props = mapOf("stencilKey" to "letterhead") + stencilProps),
            "body" to Node(id = "body", type = "placeholder", slots = listOf("body-fill"), props = mapOf("name" to "body") + placeholderProps),
            "text" to Node(id = "text", type = "text", props = mapOf("content" to null)),
        ),
        slots = mapOf(
            "root-children" to Slot("root-children", "root", "children", listOf("inst")),
            "inst-children" to Slot("inst-children", "inst", "children", listOf("body")),
            "body-fill" to Slot("body-fill", "body", "fill", listOf("text")),
        ),
    )
}
