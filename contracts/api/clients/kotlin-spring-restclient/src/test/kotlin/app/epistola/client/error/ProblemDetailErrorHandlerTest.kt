// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.error

import io.mockk.every
import io.mockk.mockk
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpResponse
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClientResponseException
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProblemDetailErrorHandlerTest {

    private fun response(
        status: HttpStatus,
        body: String,
        contentType: MediaType? = PROBLEM_JSON,
    ): ClientHttpResponse = mockk {
        every { statusCode } returns status
        every { statusText } returns status.reasonPhrase
        every { headers } returns HttpHeaders().apply { contentType?.let { this.contentType = it } }
        every { this@mockk.body } returns ByteArrayInputStream(body.toByteArray())
    }

    @Test
    fun `parses a plain problem response into a typed exception`() {
        val body = """
            {"type":"https://epistola.app/errors/not-found","title":"Not Found",
             "status":404,"detail":"Tenant 'acme' was not found"}
        """.trimIndent()

        val ex = assertFailsWith<ProblemDetailException> {
            handleErrorResponse(response(HttpStatus.NOT_FOUND, body))
        }
        assertEquals("not-found", ex.typeSlug)
        assertEquals("Tenant 'acme' was not found", ex.detail)
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    @Test
    fun `populates validation errors for a validation problem`() {
        val body = """
            {"type":"https://epistola.app/errors/validation-error","title":"Bad Request",
             "status":400,"errors":[{"field":"name","message":"must not be blank"}]}
        """.trimIndent()

        val ex = assertFailsWith<ProblemDetailException> {
            handleErrorResponse(response(HttpStatus.BAD_REQUEST, body))
        }
        assertTrue(ex.isValidationProblem)
        assertEquals(1, ex.errors.size)
        assertEquals("name", ex.errors[0].field)
        assertEquals("validation-error", ex.typeSlug)
    }

    @Test
    fun `populates per-example validation errors for a data-model validation problem`() {
        val body = """
            {"type":"https://epistola.app/errors/data-model-validation-error",
             "title":"Data Model Validation Error","status":422,
             "detail":"Data examples failed validation against schema",
             "validationErrors":{"Example 1":[{"path":"/name","message":"required property 'name' not found"}]}}
        """.trimIndent()

        val ex = assertFailsWith<ProblemDetailException> {
            handleErrorResponse(response(HttpStatus.UNPROCESSABLE_ENTITY, body))
        }
        assertTrue(ex.isDataModelValidationProblem)
        assertFalse(ex.isValidationProblem)
        assertEquals(KnownProblemSlugs.DATA_MODEL_VALIDATION_ERROR, ex.typeSlug)
        assertEquals(1, ex.validationErrors["Example 1"]?.size)
        assertEquals("/name", ex.validationErrors.getValue("Example 1")[0].path)
    }

    @Test
    fun `falls back to a plain exception for non-problem error bodies`() {
        val ex = assertFailsWith<RestClientResponseException> {
            handleErrorResponse(response(HttpStatus.INTERNAL_SERVER_ERROR, "gateway boom", MediaType.TEXT_PLAIN))
        }
        assertFalse(ex is ProblemDetailException)
        assertTrue(ex is HttpServerErrorException)
    }

    @Test
    fun `falls back when the problem body is empty`() {
        val ex = assertFailsWith<RestClientResponseException> {
            handleErrorResponse(response(HttpStatus.NOT_FOUND, ""))
        }
        assertFalse(ex is ProblemDetailException)
    }

    @Test
    fun `falls back when the problem body is malformed`() {
        val ex = assertFailsWith<RestClientResponseException> {
            handleErrorResponse(response(HttpStatus.BAD_REQUEST, "{not json"))
        }
        assertFalse(ex is ProblemDetailException)
    }

    @Test
    fun `parseProblem carries an unmodelled extension member through treeToValue, not just readValue`() {
        // treeToValue is a separate Jackson code path from readValue; the creator-based any-setter
        // has to work through it too, since that is what parseProblem actually calls.
        val body = """{"type":"about:blank","title":"X","status":409,"themeId":"classic"}"""
        val parsed = parseProblem(body.toByteArray())
        assertEquals(409, parsed?.problem?.status)
        assertEquals("classic", parsed?.problem?.extensions?.get("themeId"))
        assertTrue(parsed?.errors?.isEmpty() == true)
        assertTrue(parsed?.validationErrors?.isEmpty() == true)
    }

    @Test
    fun `an unregistered problem type's extension members reach the exception`() {
        // catalog-schema-too-old is not in KnownProblemSlugs — nothing about that stops typeSlug
        // (generic URI-suffix stripping) or extensions (a generic catch-all) from working for it.
        val body = """
            {"type":"https://epistola.app/errors/catalog-schema-too-old","title":"Catalog schema too old",
             "status":409,"detail":"Deploy a build whose bundled catalog schema is at least baselineVersion",
             "version":3,"baselineVersion":5}
        """.trimIndent()

        val ex = assertFailsWith<ProblemDetailException> {
            handleErrorResponse(response(HttpStatus.CONFLICT, body))
        }
        assertEquals("catalog-schema-too-old", ex.typeSlug)
        assertEquals(3, ex.extensions["version"])
        assertEquals(5, ex.extensions["baselineVersion"])
    }

    @Test
    fun `parseProblem returns null on malformed json`() {
        assertNull(parseProblem("nope".toByteArray()))
    }
}
