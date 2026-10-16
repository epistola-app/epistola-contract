// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.validation

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A node names the resource it shows by its key from catalog wire v8: a stencil node by `stencilKey`,
 * an image node by `imageKey`. The names they had until wire v7 are undeclared props in current
 * content; the v7-to-v8 migration renames them, so only content written with the old names after
 * the rename reaches the validator with them.
 */
class TemplateValidatorKeyNamesTest {
    @Test
    fun `stencil and image nodes declare their key props`() {
        val report = TemplateValidator.validate(document(stencilProps = mapOf("stencilKey" to "address"), imageProps = mapOf("imageKey" to "logo")))

        assertEquals(emptyList(), report.findings.filter { it.code == TemplateValidationCodes.TEMPLATE_NODE_PROPERTY_INVALID })
        assertEquals(emptyList(), report.findings.filter { it.code == TemplateValidationCodes.STENCIL_REFERENCE_INVALID })
    }

    @Test
    fun `the pre-v8 names are no longer props of stencil and image nodes`() {
        val report = TemplateValidator.validate(document(stencilProps = mapOf("stencilId" to "address"), imageProps = mapOf("assetId" to "logo")))

        assertEquals(
            setOf(
                TemplateValidationCodes.TEMPLATE_NODE_PROPERTY_INVALID to "nodes.address.props.stencilId",
                TemplateValidationCodes.STENCIL_REFERENCE_INVALID to "nodes.address.props.stencilKey",
                TemplateValidationCodes.TEMPLATE_NODE_PROPERTY_INVALID to "nodes.logo.props.assetId",
            ),
            report.findings.mapTo(mutableSetOf()) { it.code to it.path },
        )
    }

    private fun document(
        stencilProps: Map<String, Any?>,
        imageProps: Map<String, Any?>,
    ) = TemplateDocument(
        root = "root",
        nodes = mapOf(
            "root" to Node("root", "root", slots = listOf("children")),
            "address" to Node("address", "stencil", slots = listOf("address-children"), props = stencilProps),
            "logo" to Node("logo", "image", props = imageProps),
        ),
        slots = mapOf(
            "children" to Slot("children", "root", "children", listOf("address", "logo")),
            "address-children" to Slot("address-children", "address", "children"),
        ),
    )
}
