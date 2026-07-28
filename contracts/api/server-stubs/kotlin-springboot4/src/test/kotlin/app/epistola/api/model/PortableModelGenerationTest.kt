// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.api.model

import app.epistola.template.model.BlockStylePreset
import app.epistola.template.model.Node
import app.epistola.template.model.PageSettings
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class PortableModelGenerationTest {

    @Test
    fun `generated template request uses catalog document directly`() {
        val document = document()
        val request = UpdateDraftRequest(templateModel = document)

        assertSame(document, request.templateModel)
        assertEquals(1, request.templateModel?.modelVersion)
    }

    @Test
    fun `generated theme request uses catalog theme values directly`() {
        val pageSettings = PageSettings(backgroundColor = "#ffffff")
        val preset = BlockStylePreset(label = "Body", styles = mapOf("fontSize" to 12))
        val request = CreateThemeRequest(
            id = "default",
            name = "Default",
            documentStyles = mapOf("fontFamily" to "Inter"),
            pageSettings = pageSettings,
            blockStylePresets = mapOf("body" to preset),
        )

        assertSame(pageSettings, request.pageSettings)
        assertSame(preset, request.blockStylePresets?.get("body"))
    }

    @Test
    fun `jackson 3 round trips catalog model through generated request`() {
        val mapper = jsonMapper {
            addModule(kotlinModule())
        }
        val request = UpdateDraftRequest(templateModel = document())

        val json = mapper.writeValueAsString(request)
        val decoded = mapper.readValue(json, UpdateDraftRequest::class.java)

        assertEquals(request, decoded)
        assertEquals(ThemeRef.Inherit, decoded.templateModel?.themeRef)
    }

    @Test
    fun `server artifact does not contain duplicate portable model`() {
        assertFailsWith<ClassNotFoundException> {
            Class.forName("app.epistola.api.model.TemplateDocument")
        }
        assertFailsWith<ClassNotFoundException> {
            Class.forName("app.epistola.api.model.BlockStylePreset")
        }
    }

    private fun document() = TemplateDocument(
        root = "n-root",
        nodes = mapOf("n-root" to Node(id = "n-root", type = "root", slots = listOf("s-root"))),
        slots = mapOf("s-root" to Slot(id = "s-root", nodeId = "n-root", name = "children")),
        themeRef = ThemeRef.Inherit,
    )
}
