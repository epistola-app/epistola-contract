package app.epistola.api.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PortableModelGenerationTest {

    @Test
    fun `catalog model version remains integer backed`() {
        assertEquals(1, TemplateDocument.ModelVersion._1.value)
    }

    @Test
    fun `inherited theme does not require override fields`() {
        val themeRef = ThemeRef(type = ThemeRef.Type.INHERIT)

        assertEquals("inherit", themeRef.type.value)
        assertNull(themeRef.themeId)
        assertNull(themeRef.catalogKey)
    }
}
