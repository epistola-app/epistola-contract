// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.validation.schema

import app.epistola.client.api.TemplatesApi
import app.epistola.client.model.InvalidDataField
import app.epistola.client.model.MissingDataField
import app.epistola.client.model.TemplateDataValidationError
import app.epistola.client.model.TemplateDataValidationResult
import app.epistola.client.model.ValidateTemplateDataRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [TemplateSchemaValidator] over the shipped default, [ServerTemplateDataValidator].
 *
 * The behaviour worth pinning is the mapping, not the HTTP: the server answers in three members of
 * one result and the client owes its callers a single shape, documented on [TemplateDataValidator].
 */
class TemplateSchemaValidatorTest {

    private val templatesApi = mockk<TemplatesApi>()

    private fun result(
        valid: Boolean,
        errors: List<TemplateDataValidationError>? = null,
        missingFields: List<MissingDataField>? = null,
        invalidFields: List<InvalidDataField>? = null,
    ) = TemplateDataValidationResult(valid, errors, missingFields, invalidFields)

    private fun answering(result: TemplateDataValidationResult) {
        every { templatesApi.validateTemplateData("acme", "default", "invoice", any()) } returns result
    }

    @Test
    fun `valid data passes without throwing`() {
        answering(result(valid = true))

        TemplateSchemaValidator(templatesApi).validate("acme", "default", "invoice", mapOf("name" to "Jane"))

        verify(exactly = 1) { templatesApi.validateTemplateData("acme", "default", "invoice", any()) }
    }

    @Test
    fun `the data is sent as the request body`() {
        answering(result(valid = true))
        val body = slot<ValidateTemplateDataRequest>()
        every { templatesApi.validateTemplateData("acme", "default", "invoice", capture(body)) } returns result(valid = true)

        TemplateSchemaValidator(templatesApi).validate("acme", "default", "invoice", mapOf("name" to "Jane"))

        assertEquals(mapOf("name" to "Jane"), body.captured.data)
    }

    @Test
    fun `invalidFields become errors keyed by their JSON Pointer`() {
        answering(
            result(
                valid = false,
                invalidFields = listOf(
                    InvalidDataField(path = "/customer/email", keyword = "format", message = "must be a valid email address"),
                    InvalidDataField(path = "/lineItems/0/quantity", keyword = "minimum", message = "must be at least 1"),
                ),
            ),
        )

        val thrown = assertFailsWith<TemplateDataValidationException> {
            TemplateSchemaValidator(templatesApi).validate("acme", "default", "invoice", emptyMap<String, Any>())
        }

        assertEquals(
            listOf("/customer/email", "/lineItems/0/quantity"),
            thrown.errors.map { it.path },
        )
        assertEquals(listOf("format", "minimum"), thrown.errors.map { it.keyword })
        assertTrue(thrown.errors.first().message.contains("email"))
    }

    @Test
    fun `a missing required field is an error and a missing optional field is not`() {
        answering(
            result(
                valid = false,
                missingFields = listOf(
                    MissingDataField(path = "/customer/address", required = true, schema = mapOf("type" to "object")),
                    MissingDataField(path = "/customer/phone", required = false, schema = mapOf("type" to "string")),
                ),
            ),
        )

        val thrown = assertFailsWith<TemplateDataValidationException> {
            TemplateSchemaValidator(templatesApi).validate("acme", "default", "invoice", emptyMap<String, Any>())
        }

        assertEquals(listOf("/customer/address"), thrown.errors.map { it.path })
        assertEquals("required", thrown.errors.single().keyword)
    }

    @Test
    fun `errors is used when the server sends no field members`() {
        answering(
            result(
                valid = false,
                errors = listOf(TemplateDataValidationError(path = "/invoiceNumber", message = "does not match the required format", keyword = "pattern")),
            ),
        )

        val thrown = assertFailsWith<TemplateDataValidationException> {
            TemplateSchemaValidator(templatesApi).validate("acme", "default", "invoice", emptyMap<String, Any>())
        }

        assertEquals("/invoiceNumber", thrown.errors.single().path)
        assertEquals("pattern", thrown.errors.single().keyword)
    }

    @Test
    fun `the field members win over errors, which may describe the same failure differently`() {
        // The one case where both arrive. `errors[].path` documents itself as a JSON Pointer but is
        // specified with a JSONPath example, so preferring invalidFields is what keeps the
        // interface's promise from depending on which member the server filled.
        answering(
            result(
                valid = false,
                errors = listOf(TemplateDataValidationError(path = "\$.customer.email", message = "must be a valid email address", keyword = "format")),
                invalidFields = listOf(InvalidDataField(path = "/customer/email", keyword = "format", message = "must be a valid email address")),
            ),
        )

        val thrown = assertFailsWith<TemplateDataValidationException> {
            TemplateSchemaValidator(templatesApi).validate("acme", "default", "invoice", emptyMap<String, Any>())
        }

        assertEquals("/customer/email", thrown.errors.single().path)
    }

    @Test
    fun `an invalid result with nothing to report still throws`() {
        // Defensive: `valid=false` is the verdict, and reporting no reason must not become "fine".
        answering(result(valid = false))

        assertFailsWith<TemplateDataValidationException> {
            TemplateSchemaValidator(templatesApi).validate("acme", "default", "invoice", emptyMap<String, Any>())
        }
    }

    @Test
    fun `the version selectors are passed through when configured`() {
        val body = slot<ValidateTemplateDataRequest>()
        every { templatesApi.validateTemplateData("acme", "default", "invoice", capture(body)) } returns result(valid = true)

        val validator = ServerTemplateDataValidator(templatesApi, variantId = "nl-nl", environmentId = "production")
        TemplateSchemaValidator(validator).validate("acme", "default", "invoice", emptyMap<String, Any>())

        assertEquals("nl-nl", body.captured.variantId)
        assertEquals("production", body.captured.environmentId)
    }

    @Test
    fun `a plugged-in validator replaces the server entirely`() {
        val findings = listOf(TemplateDataValidationException.ValidationError("/name", "is required but was not supplied", "required"))
        val local = TemplateDataValidator { _, _, _, _ -> findings }

        val thrown = assertFailsWith<TemplateDataValidationException> {
            TemplateSchemaValidator(local).validate("acme", "default", "invoice", emptyMap<String, Any>())
        }

        assertEquals(findings, thrown.errors)
        verify(exactly = 0) { templatesApi.validateTemplateData(any(), any(), any(), any()) }
    }

    @Test
    fun `the server-backed validator declines generation pre-flight, a local one takes it`() {
        assertFalse(ServerTemplateDataValidator(templatesApi).preflightsGeneration)
        assertTrue(TemplateDataValidator { _, _, _, _ -> emptyList() }.preflightsGeneration)
    }
}
