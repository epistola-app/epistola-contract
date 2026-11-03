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

    @Test
    fun `a stencil reads only its parameters unless it says otherwise, and says so only when it does`() {
        val content = """{"root":"n-root","nodes":{"n-root":{"id":"n-root","type":"root"}},"slots":{}}"""
        val plain = mapper.readValue(
            """{"schemaVersion":8,"resource":{"type":"stencil","key":"letterhead","name":"Letterhead","content":$content}}""",
            ResourceDetail::class.java,
        ).resource as StencilResource
        val reading = mapper.readValue(
            """{"schemaVersion":8,"resource":{"type":"stencil","key":"letterhead","name":"Letterhead","content":$content,"readsTemplateData":true}}""",
            ResourceDetail::class.java,
        ).resource as StencilResource

        assertFalse(plain.readsTemplateData)
        assertTrue(reading.readsTemplateData)
        // Left out when false, so V5 fingerprints of stencils that do not use it do not move.
        assertFalse(mapper.writeValueAsString(plain).contains("readsTemplateData"))
        assertTrue(mapper.writeValueAsString(reading).contains("\"readsTemplateData\":true"))
    }

    @Test
    fun `every resource carries tags, left out when empty except on a stencil`() {
        val theme = ThemeResource(key = "brand", name = "Brand")
        val tagged = ThemeResource(key = "brand", name = "Brand", tags = listOf("letter"))
        val content = """{"root":"n-root","nodes":{"n-root":{"id":"n-root","type":"root"}},"slots":{}}"""
        val stencil = mapper.readValue(
            """{"schemaVersion":8,"resource":{"type":"stencil","key":"letterhead","name":"Letterhead","content":$content}}""",
            ResourceDetail::class.java,
        ).resource

        // A type that had no tags writes none when empty, so its fingerprints do not move.
        assertFalse(mapper.writeValueAsString(theme).contains("tags"))
        assertTrue(mapper.writeValueAsString(tagged).contains("\"tags\":[\"letter\"]"))
        assertEquals(listOf("letter"), (mapper.readValue(mapper.writeValueAsString(ResourceDetail(8, tagged)), ResourceDetail::class.java).resource).tags)
        // A stencil writes them as it always did, so neither do its fingerprints.
        assertTrue(mapper.writeValueAsString(stencil).contains("\"tags\":[]"))
        assertEquals(emptyList(), stencil.tags)
    }

    @Test
    fun `a variant carries a description, left out when it has none`() {
        val plain = VariantEntry(key = "default")
        val described = VariantEntry(key = "formal", description = "For letters to courts.")

        // Left out when empty, so a variant without one serializes, and fingerprints, as before.
        assertFalse(mapper.writeValueAsString(plain).contains("description"))
        assertFalse(mapper.writeValueAsString(VariantEntry(key = "default", description = "")).contains("description"))
        assertTrue(mapper.writeValueAsString(described).contains("\"description\":\"For letters to courts.\""))
        assertEquals("For letters to courts.", mapper.readValue(mapper.writeValueAsString(described), VariantEntry::class.java).description)
    }
}
