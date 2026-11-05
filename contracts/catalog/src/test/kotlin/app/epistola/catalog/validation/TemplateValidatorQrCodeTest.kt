// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.validation

import app.epistola.catalog.validation.TemplateValidationCodes.TEMPLATE_NODE_PROPERTY_INVALID
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A QR code carries its own text alternative, or is marked decorative, as an image does. */
class TemplateValidatorQrCodeTest {
    @Test
    fun `a QR code with alt text is valid`() {
        assertEquals(emptyList(), TemplateValidator.validate(qrCode("alt" to "Pay this invoice online")).findings)
    }

    @Test
    fun `a decorative QR code is valid`() {
        assertEquals(emptyList(), TemplateValidator.validate(qrCode("alt" to "", "decorative" to true)).findings)
    }

    @Test
    fun `a QR code without either stays valid`() {
        assertEquals(emptyList(), TemplateValidator.validate(qrCode()).findings)
    }

    @Test
    fun `alt text and decorative must have their types`() {
        val findings = TemplateValidator.validate(qrCode("alt" to 42, "decorative" to "yes")).findings

        assertTrue(findings.any { it.code == TEMPLATE_NODE_PROPERTY_INVALID && it.path == "nodes.qr.props.alt" }, "findings: $findings")
        assertTrue(findings.any { it.code == TEMPLATE_NODE_PROPERTY_INVALID && it.path == "nodes.qr.props.decorative" }, "findings: $findings")
    }

    private fun qrCode(vararg props: Pair<String, Any?>): TemplateDocument {
        val node = Node(
            "qr",
            "qrcode",
            props = mapOf("value" to mapOf("raw" to "paymentLink", "language" to "jsonata"), "size" to "80pt") + props,
        )
        return TemplateDocument(
            root = "root",
            nodes = mapOf("root" to Node("root", "root", listOf("root-slot")), node.id to node),
            slots = mapOf("root-slot" to Slot("root-slot", "root", "children", listOf(node.id))),
        )
    }
}
