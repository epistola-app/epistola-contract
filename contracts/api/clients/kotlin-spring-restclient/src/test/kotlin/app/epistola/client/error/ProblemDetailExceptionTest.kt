// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.error

import app.epistola.client.model.DataModelValidationError
import app.epistola.client.model.ProblemDetail
import app.epistola.client.model.ValidationError
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.client.RestClientResponseException
import java.net.URI
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProblemDetailExceptionTest {

    private fun exception(
        problem: ProblemDetail,
        errors: List<ValidationError> = emptyList(),
        validationErrors: Map<String, List<DataModelValidationError>> = emptyMap(),
        body: String = "{}",
    ) = ProblemDetailException(
        problem = problem,
        errors = errors,
        validationErrors = validationErrors,
        statusCode = HttpStatus.valueOf(problem.status),
        statusText = HttpStatus.valueOf(problem.status).reasonPhrase,
        headers = HttpHeaders(),
        responseBody = body.toByteArray(),
        responseCharset = StandardCharsets.UTF_8,
    )

    @Test
    fun `typeSlug strips the Epistola type base`() {
        val ex = exception(
            ProblemDetail(
                type = URI.create("https://epistola.app/errors/not-found"),
                title = "Not Found",
                status = 404,
                detail = "Tenant 'acme' was not found",
            ),
        )
        assertEquals("not-found", ex.typeSlug)
        assertEquals("Not Found", ex.title)
        assertEquals(404, ex.problemStatus)
        assertEquals("Tenant 'acme' was not found", ex.detail)
        assertEquals(URI.create("https://epistola.app/errors/not-found"), ex.type)
    }

    @Test
    fun `typeSlug is null for about blank and non-Epistola types`() {
        assertNull(exception(ProblemDetail(title = "Bad Request", status = 400)).typeSlug)
        assertNull(
            exception(
                ProblemDetail(type = URI.create("https://example.com/oops"), title = "X", status = 400),
            ).typeSlug,
        )
    }

    @Test
    fun `plain problem has no validation errors`() {
        val ex = exception(ProblemDetail(title = "Conflict", status = 409))
        assertTrue(ex.errors.isEmpty())
        assertFalse(ex.isValidationProblem)
        assertTrue(ex.validationErrors.isEmpty())
        assertFalse(ex.isDataModelValidationProblem)
    }

    @Test
    fun `data-model validation problem exposes per-example failures`() {
        val ex = exception(
            ProblemDetail(
                type = URI.create("https://epistola.app/errors/data-model-validation-error"),
                title = "Data Model Validation Error",
                status = 422,
            ),
            validationErrors = mapOf(
                "Example 1" to listOf(DataModelValidationError(path = "/name", message = "required property 'name' not found")),
            ),
        )
        assertTrue(ex.isDataModelValidationProblem)
        assertFalse(ex.isValidationProblem)
        assertEquals(KnownProblemSlugs.DATA_MODEL_VALIDATION_ERROR, ex.typeSlug)
        assertEquals("/name", ex.validationErrors.getValue("Example 1")[0].path)
    }

    @Test
    fun `validation problem exposes field errors`() {
        val ex = exception(
            ProblemDetail(
                type = URI.create("https://epistola.app/errors/validation-error"),
                title = "Bad Request",
                status = 400,
            ),
            errors = listOf(
                ValidationError(field = "name", message = "must not be blank"),
                ValidationError(field = "slug", message = "invalid format", rejectedValue = "A B"),
            ),
        )
        assertTrue(ex.isValidationProblem)
        assertEquals(2, ex.errors.size)
        assertEquals("name", ex.errors[0].field)
        assertEquals("validation-error", ex.typeSlug)
    }

    @Test
    fun `is a RestClientResponseException carrying the original body`() {
        val body = """{"type":"about:blank","title":"Conflict","status":409}"""
        val ex = exception(ProblemDetail(title = "Conflict", status = 409), body = body)
        // Assignable to the generated @Throws type so existing catch sites keep working.
        val asParent: RestClientResponseException = ex
        assertEquals(body, asParent.responseBodyAsString)
        assertEquals(HttpStatus.CONFLICT, asParent.statusCode)
    }
}
