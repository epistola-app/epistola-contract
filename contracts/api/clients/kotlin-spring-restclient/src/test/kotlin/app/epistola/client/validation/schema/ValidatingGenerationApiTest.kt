// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.validation.schema

import app.epistola.client.api.GenerationApi
import app.epistola.client.api.TemplatesApi
import app.epistola.client.error.ProblemDetailException
import app.epistola.client.model.BatchGenerationItem
import app.epistola.client.model.GenerateBatchRequest
import app.epistola.client.model.GenerateDocumentRequest
import app.epistola.client.model.ProblemDetail
import app.epistola.client.model.TemplateDataValidationResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import java.net.URI
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * [ValidatingGenerationApi] reaches one exception type by two routes, and which route applies is
 * the validator's call. Both are pinned here, including that the server-backed default spends no
 * extra request to reach it.
 */
class ValidatingGenerationApiTest {

    private val templatesApi = mockk<TemplatesApi>()
    private val generationApi = mockk<GenerationApi>(relaxed = true)

    private val request = GenerateDocumentRequest(
        catalogId = "default",
        templateId = "invoice",
        data = emptyMap<String, Any>(),
    )

    private val batch = GenerateBatchRequest(
        items = listOf(
            BatchGenerationItem(catalogId = "default", templateId = "invoice", data = emptyMap<String, Any>()),
            BatchGenerationItem(catalogId = "default", templateId = "reminder", data = emptyMap<String, Any>()),
        ),
    )

    /** A validator that answers in-process, so it takes the pre-flight. */
    private fun local(vararg paths: String) = TemplateDataValidator { _, _, _, _ ->
        paths.map { TemplateDataValidationException.ValidationError(it, "is required but was not supplied", "required") }
    }

    private fun templateDataInvalid(
        invalidFields: List<Map<String, Any?>> = emptyList(),
        missingFields: List<Map<String, Any?>> = emptyList(),
    ) = ProblemDetailException(
        problem = ProblemDetail(
            type = URI.create("https://epistola.app/errors/template-data-invalid"),
            title = "Template data invalid",
            status = 400,
            detail = "The supplied data does not fit the template's data contract",
            extensions = mapOf("invalidFields" to invalidFields, "missingFields" to missingFields),
        ),
        errors = emptyList(),
        validationErrors = emptyMap(),
        statusCode = HttpStatus.BAD_REQUEST,
        statusText = "Bad Request",
        headers = HttpHeaders(),
        responseBody = "{}".toByteArray(),
        responseCharset = StandardCharsets.UTF_8,
    )

    @Test
    fun `the default validator submits without a pre-flight request`() {
        val api = ValidatingGenerationApi(generationApi, templatesApi)

        api.generateDocument("acme", request)

        // The point of ServerTemplateDataValidator.preflightsGeneration being false: the server
        // validates what it is given, so asking it first would be a second round trip for the
        // same verdict.
        verify(exactly = 0) { templatesApi.validateTemplateData(any(), any(), any(), any()) }
        verify(exactly = 1) { generationApi.generateDocument("acme", request) }
    }

    @Test
    fun `a rejected submission becomes a TemplateDataValidationException`() {
        every { generationApi.generateDocument("acme", request) } throws templateDataInvalid(
            invalidFields = listOf(mapOf("path" to "/customer/email", "keyword" to "format", "message" to "must be a valid email address")),
            missingFields = listOf(
                mapOf("path" to "/invoiceNumber", "required" to true),
                mapOf("path" to "/customer/phone", "required" to false),
            ),
        )

        val thrown = assertFailsWith<TemplateDataValidationException> {
            ValidatingGenerationApi(generationApi, templatesApi).generateDocument("acme", request)
        }

        // The optional missing field is not a finding; the required one is.
        assertEquals(listOf("/customer/email", "/invoiceNumber"), thrown.errors.map { it.path })
        assertEquals(listOf("format", "required"), thrown.errors.map { it.keyword })
    }

    @Test
    fun `a batch rejected by the server is translated the same way`() {
        every { generationApi.generateDocumentBatch("acme", batch) } throws templateDataInvalid(
            invalidFields = listOf(mapOf("path" to "/items/1/data/total", "keyword" to "type", "message" to "string found, number expected")),
        )

        val thrown = assertFailsWith<TemplateDataValidationException> {
            ValidatingGenerationApi(generationApi, templatesApi).generateDocumentBatch("acme", batch)
        }

        assertEquals("/items/1/data/total", thrown.errors.single().path)
    }

    @Test
    fun `any other problem propagates untouched`() {
        val notFound = ProblemDetailException(
            problem = ProblemDetail(
                type = URI.create("https://epistola.app/errors/not-found"),
                title = "Not Found",
                status = 404,
                detail = "Template 'invoice' was not found",
            ),
            errors = emptyList(),
            validationErrors = emptyMap(),
            statusCode = HttpStatus.NOT_FOUND,
            statusText = "Not Found",
            headers = HttpHeaders(),
            responseBody = "{}".toByteArray(),
            responseCharset = StandardCharsets.UTF_8,
        )
        every { generationApi.generateDocument("acme", request) } throws notFound

        val thrown = assertFailsWith<ProblemDetailException> {
            ValidatingGenerationApi(generationApi, templatesApi).generateDocument("acme", request)
        }

        assertSame(notFound, thrown)
    }

    @Test
    fun `a local validator throws before the request is sent`() {
        val api = ValidatingGenerationApi(generationApi, local("/name"))

        assertFailsWith<TemplateDataValidationException> { api.generateDocument("acme", request) }

        verify(exactly = 0) { generationApi.generateDocument(any(), any()) }
    }

    @Test
    fun `a local validator reports every item of a batch at once, prefixed by its index`() {
        val api = ValidatingGenerationApi(generationApi, local("/name"))

        val thrown = assertFailsWith<TemplateDataValidationException> { api.generateDocumentBatch("acme", batch) }

        assertEquals(listOf("items[0]/name", "items[1]/name"), thrown.errors.map { it.path })
        verify(exactly = 0) { generationApi.generateDocumentBatch(any(), any()) }
    }

    @Test
    fun `a local validator that finds nothing lets the request through`() {
        val api = ValidatingGenerationApi(generationApi, TemplateDataValidator { _, _, _, _ -> emptyList() })

        api.generateDocumentBatch("acme", batch)

        verify(exactly = 1) { generationApi.generateDocumentBatch("acme", batch) }
    }

    @Test
    fun `a validator reached through the server still pre-flights nothing per batch item`() {
        every { templatesApi.validateTemplateData(any(), any(), any(), any()) } returns TemplateDataValidationResult(true, null, null, null)

        ValidatingGenerationApi(generationApi, templatesApi).generateDocumentBatch("acme", batch)

        verify(exactly = 0) { templatesApi.validateTemplateData(any(), any(), any(), any()) }
    }
}
