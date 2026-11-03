// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.migration

import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The legacy fingerprint algorithms leave out what wire v8 added; here, a stencil's `readsTemplateData`. */
class CatalogV8AdditionsTest {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `a stencil's readsTemplateData is not legacy content`() {
        val stencil = mapper.readTree("""{"type":"stencil","key":"letterhead","name":"L","readsTemplateData":true}""") as ObjectNode

        val legacy = CatalogV8Additions.withoutAdditions(stencil) as ObjectNode

        assertFalse(legacy.has("readsTemplateData"))
        assertTrue(stencil.has("readsTemplateData"), "the input is not changed")
        assertEquals(setOf("type", "key", "name"), legacy.propertyNames().toSet())
    }

    @Test
    fun `tags on a type other than a stencil are not legacy content`() {
        val theme = mapper.readTree("""{"type":"theme","key":"brand","name":"B","tags":["letter"]}""") as ObjectNode
        val template = mapper.readTree("""{"type":"template","key":"invoice","name":"I","tags":["letter"]}""") as ObjectNode
        val stencil = mapper.readTree("""{"type":"stencil","key":"letterhead","name":"L","tags":["letter"]}""") as ObjectNode

        assertEquals(setOf("type", "key", "name"), CatalogV8Additions.withoutAdditions(theme).propertyNames().toSet())
        assertEquals(setOf("type", "key", "name"), CatalogV8Additions.withoutAdditions(template).propertyNames().toSet())
        assertTrue(CatalogV8Additions.withoutAdditions(stencil).has("tags"), "a stencil had tags before v8")
    }

    @Test
    fun `a variant's description is not legacy content`() {
        val template = mapper.readTree(
            """{"type":"template","key":"invoice","name":"I","variants":[{"key":"formal","description":"For courts.","metadata":{"app.example":{}}}]}""",
        ) as ObjectNode

        assertEquals(setOf("key"), CatalogV8Additions.withoutAdditions(template)["variants"][0].propertyNames().toSet())
    }
}
