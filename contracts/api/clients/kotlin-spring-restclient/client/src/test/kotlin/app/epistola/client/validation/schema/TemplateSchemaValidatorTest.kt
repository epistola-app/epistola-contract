package app.epistola.client.validation.schema

import app.epistola.client.api.TemplatesApi
import app.epistola.client.model.TemplateDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TemplateSchemaValidatorTest {

    private val templatesApi = mockk<TemplatesApi>()

    private val invoiceSchema = mapOf(
        "type" to "object",
        "required" to listOf("customer", "invoiceNumber"),
        "properties" to mapOf(
            "customer" to mapOf(
                "type" to "object",
                "required" to listOf("name", "email"),
                "properties" to mapOf(
                    "name" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 200),
                    "email" to mapOf("type" to "string", "format" to "email"),
                ),
            ),
            "invoiceNumber" to mapOf(
                "type" to "string",
                "pattern" to "^INV-\\d{4}-\\d{3}$",
            ),
            "lineItems" to mapOf(
                "type" to "array",
                "minItems" to 1,
                "items" to mapOf(
                    "type" to "object",
                    "required" to listOf("description", "quantity"),
                    "properties" to mapOf(
                        "description" to mapOf("type" to "string"),
                        "quantity" to mapOf("type" to "integer", "minimum" to 1),
                    ),
                ),
            ),
        ),
    )

    private fun templateDto(schema: Any? = invoiceSchema) = TemplateDto(
        id = "invoice",
        tenantId = "acme",
        name = "Invoice",
        variants = emptyList(),
        createdAt = OffsetDateTime.now(),
        lastModified = OffsetDateTime.now(),
        schema = schema,
    )

    @Test
    fun `valid data passes without exception`() {
        every { templatesApi.getTemplate("acme", "default", "invoice") } returns templateDto()
        val validator = TemplateSchemaValidator(templatesApi)

        validator.validate(
            "acme",
            "default",
            "invoice",
            mapOf(
                "customer" to mapOf("name" to "Jane", "email" to "jane@example.com"),
                "invoiceNumber" to "INV-2026-001",
            ),
        )
    }

    @Test
    fun `missing required field throws with correct keyword`() {
        every { templatesApi.getTemplate("acme", "default", "invoice") } returns templateDto()
        val validator = TemplateSchemaValidator(templatesApi)

        val ex = assertFailsWith<TemplateDataValidationException> {
            validator.validate(
                "acme",
                "default",
                "invoice",
                mapOf("customer" to mapOf("name" to "Jane", "email" to "j@e.com")),
            )
        }

        assertTrue(ex.errors.any { it.keyword == "required" && it.message.contains("invoiceNumber") })
    }

    @Test
    fun `wrong type throws with correct error`() {
        every { templatesApi.getTemplate("acme", "default", "invoice") } returns templateDto()
        val validator = TemplateSchemaValidator(templatesApi)

        val ex = assertFailsWith<TemplateDataValidationException> {
            validator.validate(
                "acme",
                "default",
                "invoice",
                mapOf(
                    "customer" to "not-an-object",
                    "invoiceNumber" to "INV-2026-001",
                ),
            )
        }

        assertTrue(ex.errors.any { it.keyword == "type" })
    }

    @Test
    fun `nested object validation reports correct path`() {
        every { templatesApi.getTemplate("acme", "default", "invoice") } returns templateDto()
        val validator = TemplateSchemaValidator(templatesApi)

        val ex = assertFailsWith<TemplateDataValidationException> {
            validator.validate(
                "acme",
                "default",
                "invoice",
                mapOf(
                    "customer" to mapOf("name" to "Jane"),
                    "invoiceNumber" to "INV-2026-001",
                ),
            )
        }

        assertTrue(ex.errors.any { it.path.contains("customer") && it.keyword == "required" })
    }

    @Test
    fun `array item validation reports correct path`() {
        every { templatesApi.getTemplate("acme", "default", "invoice") } returns templateDto()
        val validator = TemplateSchemaValidator(templatesApi)

        val ex = assertFailsWith<TemplateDataValidationException> {
            validator.validate(
                "acme",
                "default",
                "invoice",
                mapOf(
                    "customer" to mapOf("name" to "Jane", "email" to "j@e.com"),
                    "invoiceNumber" to "INV-2026-001",
                    "lineItems" to listOf(
                        mapOf("description" to "Item", "quantity" to 0),
                    ),
                ),
            )
        }

        assertTrue(ex.errors.any { it.path.contains("lineItems") && it.path.contains("0") })
    }

    @Test
    fun `null schema on template skips validation`() {
        every { templatesApi.getTemplate("acme", "default", "invoice") } returns templateDto(schema = null)
        val validator = TemplateSchemaValidator(templatesApi)

        // Should not throw
        validator.validate(
            "acme",
            "default",
            "invoice",
            mapOf("anything" to "goes"),
        )
    }

    @Test
    fun `schema is cached across calls`() {
        every { templatesApi.getTemplate("acme", "default", "invoice") } returns templateDto()
        val validator = TemplateSchemaValidator(templatesApi)

        val validData = mapOf(
            "customer" to mapOf("name" to "Jane", "email" to "j@e.com"),
            "invoiceNumber" to "INV-2026-001",
        )

        validator.validate(
            "acme",
            "default",
            "invoice",
            validData,
        )
        validator.validate(
            "acme",
            "default",
            "invoice",
            validData,
        )

        verify(exactly = 1) { templatesApi.getTemplate("acme", "default", "invoice") }
    }

    @Test
    fun `cache eviction triggers re-fetch`() {
        every { templatesApi.getTemplate("acme", "default", "invoice") } returns templateDto()
        val cache = TtlSchemaCache()
        val validator = TemplateSchemaValidator(templatesApi, cache = cache)

        val validData = mapOf(
            "customer" to mapOf("name" to "Jane", "email" to "j@e.com"),
            "invoiceNumber" to "INV-2026-001",
        )

        validator.validate(
            "acme",
            "default",
            "invoice",
            validData,
        )
        cache.evict("acme", "invoice")
        validator.validate(
            "acme",
            "default",
            "invoice",
            validData,
        )

        verify(exactly = 2) { templatesApi.getTemplate("acme", "default", "invoice") }
    }

    @Test
    fun `pattern validation reports correct keyword`() {
        every { templatesApi.getTemplate("acme", "default", "invoice") } returns templateDto()
        val validator = TemplateSchemaValidator(templatesApi)

        val ex = assertFailsWith<TemplateDataValidationException> {
            validator.validate(
                "acme",
                "default",
                "invoice",
                mapOf(
                    "customer" to mapOf("name" to "Jane", "email" to "j@e.com"),
                    "invoiceNumber" to "INVALID",
                ),
            )
        }

        assertTrue(ex.errors.any { it.keyword == "pattern" })
    }

    @Test
    fun `formatErrors produces readable output`() {
        every { templatesApi.getTemplate("acme", "default", "invoice") } returns templateDto()
        val validator = TemplateSchemaValidator(templatesApi)

        val ex = assertFailsWith<TemplateDataValidationException> {
            validator.validate(
                "acme",
                "default",
                "invoice",
                emptyMap<String, Any>(),
            )
        }

        val formatted = ex.formatErrors()
        assertTrue(formatted.contains("customer"))
        assertTrue(formatted.contains("invoiceNumber"))
    }

    @Test
    fun `multiple errors are collected`() {
        every { templatesApi.getTemplate("acme", "default", "invoice") } returns templateDto()
        val validator = TemplateSchemaValidator(templatesApi)

        val ex = assertFailsWith<TemplateDataValidationException> {
            validator.validate(
                "acme",
                "default",
                "invoice",
                emptyMap<String, Any>(),
            )
        }

        assertEquals(2, ex.errors.size)
    }
}
