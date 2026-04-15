package app.epistola.client.validation.schema

import app.epistola.client.api.GenerationApi
import app.epistola.client.api.TemplatesApi
import app.epistola.client.model.BatchGenerationItem
import app.epistola.client.model.GenerateBatchRequest
import app.epistola.client.model.GenerateDocumentRequest
import app.epistola.client.model.TemplateDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ValidatingGenerationApiTest {

    private val templatesApi = mockk<TemplatesApi>()
    private val generationApi = mockk<GenerationApi>(relaxed = true)

    private val schema = mapOf(
        "type" to "object",
        "required" to listOf("name"),
        "properties" to mapOf(
            "name" to mapOf("type" to "string"),
        ),
    )

    private fun templateDto(id: String = "invoice", schemaObj: Any? = schema) = TemplateDto(
        id = id,
        tenantId = "acme",
        name = "Test",
        variants = emptyList(),
        createdAt = OffsetDateTime.now(),
        lastModified = OffsetDateTime.now(),
        schema = schemaObj,
    )

    @Test
    fun `generateDocument with invalid data throws before HTTP call`() {
        every { templatesApi.getTemplate("acme", "default", "invoice") } returns templateDto()

        val api = ValidatingGenerationApi(generationApi, templatesApi)
        val request = GenerateDocumentRequest(
            catalogId = "default",
            templateId = "invoice",
            data = emptyMap<String, Any>(),
        )

        assertFailsWith<TemplateDataValidationException> {
            api.generateDocument("acme", request)
        }

        verify(exactly = 0) { generationApi.generateDocument(any(), any()) }
    }

    @Test
    fun `generateDocument with valid data delegates to wrapped api`() {
        every { templatesApi.getTemplate("acme", "default", "invoice") } returns templateDto()

        val api = ValidatingGenerationApi(generationApi, templatesApi)
        val request = GenerateDocumentRequest(
            catalogId = "default",
            templateId = "invoice",
            data = mapOf("name" to "Jane"),
        )

        api.generateDocument("acme", request)

        verify(exactly = 1) { generationApi.generateDocument("acme", request) }
    }

    @Test
    fun `batch with multiple invalid items collects all errors`() {
        every { templatesApi.getTemplate("acme", "default", "invoice") } returns templateDto()

        val api = ValidatingGenerationApi(generationApi, templatesApi)
        val request = GenerateBatchRequest(
            items = listOf(
                BatchGenerationItem(catalogId = "default", templateId = "invoice", data = emptyMap<String, Any>()),
                BatchGenerationItem(catalogId = "default", templateId = "invoice", data = emptyMap<String, Any>()),
            ),
        )

        val ex = assertFailsWith<TemplateDataValidationException> {
            api.generateDocumentBatch("acme", request)
        }

        assertTrue(ex.errors.any { it.path.startsWith("items[0]") })
        assertTrue(ex.errors.any { it.path.startsWith("items[1]") })
    }

    @Test
    fun `batch items with different templates are validated against correct schemas`() {
        val otherSchema = mapOf(
            "type" to "object",
            "required" to listOf("title"),
            "properties" to mapOf(
                "title" to mapOf("type" to "string"),
            ),
        )
        every { templatesApi.getTemplate("acme", "default", "invoice") } returns templateDto("invoice", schema)
        every { templatesApi.getTemplate("acme", "default", "report") } returns templateDto("report", otherSchema)

        val api = ValidatingGenerationApi(generationApi, templatesApi)
        val request = GenerateBatchRequest(
            items = listOf(
                BatchGenerationItem(catalogId = "default", templateId = "invoice", data = emptyMap<String, Any>()),
                BatchGenerationItem(catalogId = "default", templateId = "report", data = emptyMap<String, Any>()),
            ),
        )

        val ex = assertFailsWith<TemplateDataValidationException> {
            api.generateDocumentBatch("acme", request)
        }

        assertTrue(ex.errors.any { it.path.startsWith("items[0]") && it.message.contains("name") })
        assertTrue(ex.errors.any { it.path.startsWith("items[1]") && it.message.contains("title") })
    }

    @Test
    fun `batch with mix of valid and invalid items only reports invalid ones`() {
        every { templatesApi.getTemplate("acme", "default", "invoice") } returns templateDto()

        val api = ValidatingGenerationApi(generationApi, templatesApi)
        val request = GenerateBatchRequest(
            items = listOf(
                BatchGenerationItem(catalogId = "default", templateId = "invoice", data = mapOf("name" to "Jane")),
                BatchGenerationItem(catalogId = "default", templateId = "invoice", data = emptyMap<String, Any>()),
            ),
        )

        val ex = assertFailsWith<TemplateDataValidationException> {
            api.generateDocumentBatch("acme", request)
        }

        assertTrue(ex.errors.none { it.path.startsWith("items[0]") })
        assertTrue(ex.errors.any { it.path.startsWith("items[1]") })
    }
}
