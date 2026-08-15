// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.protocol

import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResourceDetailTest {
    private val mapper = jsonMapper { addModule(kotlinModule()) }

    @Test
    fun `template resource without pdfaEnabled defaults to true`() {
        val json = """
            {"schemaVersion":5,"resource":{"type":"template","slug":"invoice","name":"Invoice",
            "templateModel":{"root":"n-root","nodes":{"n-root":{"id":"n-root","type":"root"}},"slots":{}},
            "variants":[]}}
        """.trimIndent()

        val detail = mapper.readValue(json, ResourceDetail::class.java)

        assertTrue((detail.resource as TemplateResource).pdfaEnabled)
    }

    @Test
    fun `template resource with pdfaEnabled false deserializes to false`() {
        val json = """
            {"schemaVersion":5,"resource":{"type":"template","slug":"invoice","name":"Invoice",
            "pdfaEnabled":false,
            "templateModel":{"root":"n-root","nodes":{"n-root":{"id":"n-root","type":"root"}},"slots":{}},
            "variants":[]}}
        """.trimIndent()

        val detail = mapper.readValue(json, ResourceDetail::class.java)

        assertFalse((detail.resource as TemplateResource).pdfaEnabled)
    }

    @Test
    fun `pdfaEnabled round-trips through serialization`() {
        val json = """
            {"schemaVersion":5,"resource":{"type":"template","slug":"invoice","name":"Invoice",
            "pdfaEnabled":false,
            "templateModel":{"root":"n-root","nodes":{"n-root":{"id":"n-root","type":"root"}},"slots":{}},
            "variants":[]}}
        """.trimIndent()
        val detail = mapper.readValue(json, ResourceDetail::class.java)

        val roundTripped = mapper.readValue(mapper.writeValueAsString(detail), ResourceDetail::class.java)

        assertEquals(false, (roundTripped.resource as TemplateResource).pdfaEnabled)
        assertEquals(detail, roundTripped)
    }
}
