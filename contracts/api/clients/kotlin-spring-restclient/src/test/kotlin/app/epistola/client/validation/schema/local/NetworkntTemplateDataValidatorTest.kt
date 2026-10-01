// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.validation.schema.local

import app.epistola.client.api.TemplatesApi
import app.epistola.client.model.TemplateDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reference local adapter, held to the contract [app.epistola.client.validation.schema.TemplateDataValidator]
 * states — above all that `path` is a JSON Pointer.
 *
 * This is what makes the interface's promise testable rather than aspirational: a real engine, with
 * its own idea of how to name a location, converted to the one shape callers read.
 */
class NetworkntTemplateDataValidatorTest {

    private val templatesApi = mockk<TemplatesApi>()

    private val invoiceSchema = mapOf(
        "type" to "object",
        "required" to listOf("customer", "invoiceNumber"),
        "properties" to mapOf(
            "customer" to mapOf(
                "type" to "object",
                "required" to listOf("name", "email"),
                "properties" to mapOf(
                    "name" to mapOf("type" to "string", "minLength" to 1),
                    "email" to mapOf("type" to "string", "format" to "email"),
                ),
            ),
            "invoiceNumber" to mapOf("type" to "string", "pattern" to "^INV-\\d{4}-\\d{3}$"),
            "lineItems" to mapOf(
                "type" to "array",
                "items" to mapOf(
                    "type" to "object",
                    "required" to listOf("quantity"),
                    "properties" to mapOf("quantity" to mapOf("type" to "integer", "minimum" to 1)),
                ),
            ),
        ),
    )

    private fun templateDto(schema: Any? = invoiceSchema) = TemplateDto(
        slug = "invoice",
        id = "invoice",
        tenantId = "acme",
        name = "Invoice",
        variants = emptyList(),
        createdAt = OffsetDateTime.now(),
        lastModified = OffsetDateTime.now(),
        schema = schema,
    )

    private val validData = mapOf(
        "customer" to mapOf("name" to "Jane Smith", "email" to "jane@example.com"),
        "invoiceNumber" to "INV-2026-001",
        "lineItems" to listOf(mapOf("quantity" to 3)),
    )

    private fun validator() = NetworkntTemplateDataValidator(templatesApi)

    @Test
    fun `valid data yields no findings`() {
        every { templatesApi.getTemplate("acme", "default", "invoice") } returns templateDto()

        assertEquals(emptyList(), validator().validate("acme", "default", "invoice", validData))
    }

    @Test
    fun `paths are JSON Pointers, not the engine's JSONPath`() {
        every { templatesApi.getTemplate("acme", "default", "invoice") } returns templateDto()
        val data = validData.toMutableMap().apply {
            put("customer", mapOf("name" to "", "email" to "jane@example.com"))
        }

        val findings = validator().validate("acme", "default", "invoice", data)

        // The regression this guards: networknt's default renders `$.customer.name`.
        assertEquals("/customer/name", findings.single().path)
        assertEquals("minLength", findings.single().keyword)
        assertTrue(findings.none { it.path.startsWith("$") })
    }

    @Test
    fun `format is not asserted, which is why the server owns the verdict`() {
        every { templatesApi.getTemplate("acme", "default", "invoice") } returns templateDto()
        val data = validData.toMutableMap().apply {
            put("customer", mapOf("name" to "Jane Smith", "email" to "not-an-email"))
        }

        // Not a defect in the adapter: under 2020-12 `format` is an annotation, not an assertion.
        // It is recorded because it is the clearest case of a local engine reaching a different
        // answer than Epistola will, and the reason ServerTemplateDataValidator is the default.
        assertEquals(emptyList(), validator().validate("acme", "default", "invoice", data))
    }

    @Test
    fun `a pointer into an array uses the index as a segment`() {
        every { templatesApi.getTemplate("acme", "default", "invoice") } returns templateDto()
        val data = validData.toMutableMap().apply { put("lineItems", listOf(mapOf("quantity" to 0))) }

        val findings = validator().validate("acme", "default", "invoice", data)

        assertEquals("/lineItems/0/quantity", findings.single().path)
        assertEquals("minimum", findings.single().keyword)
    }

    @Test
    fun `a missing required field is reported at its parent with the required keyword`() {
        every { templatesApi.getTemplate("acme", "default", "invoice") } returns templateDto()

        val findings = validator().validate("acme", "default", "invoice", mapOf("invoiceNumber" to "INV-2026-001"))

        assertEquals("required", findings.single().keyword)
        assertEquals("", findings.single().path)
    }

    @Test
    fun `a template without a schema claims nothing about the data`() {
        every { templatesApi.getTemplate("acme", "default", "invoice") } returns templateDto(schema = null)

        assertEquals(emptyList(), validator().validate("acme", "default", "invoice", mapOf("anything" to 1)))
    }

    @Test
    fun `the schema is fetched once and reused`() {
        every { templatesApi.getTemplate("acme", "default", "invoice") } returns templateDto()
        val validator = validator()

        validator.validate("acme", "default", "invoice", validData)
        validator.validate("acme", "default", "invoice", validData)

        // Without the cache a "local" validator costs a round trip per call, which is worse than
        // asking the server to validate outright.
        verify(exactly = 1) { templatesApi.getTemplate("acme", "default", "invoice") }
    }
}
