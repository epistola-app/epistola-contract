package app.epistola.catalog.validation

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class JacksonCoexistenceTest {
    @Test
    fun `jackson 2 consumer can coexist with internal jackson 3 runtime`() {
        val document = TemplateDocument(
            root = "n-root",
            nodes = mapOf("n-root" to Node("n-root", "root", slots = listOf("s-root"))),
            slots = mapOf("s-root" to Slot("s-root", "n-root", "children")),
        )

        val jackson2Json = ObjectMapper().writeValueAsString(document)
        val report = TemplateValidator.validate(document)

        assertContains(jackson2Json, "\"root\":\"n-root\"")
        assertTrue(report.valid)
        assertTrue(Class.forName("tools.jackson.databind.json.JsonMapper").name.startsWith("tools.jackson"))
    }
}
