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
    fun `surfaces the missing dependencies of a release-dependencies-missing problem in order`() {
        val body = """
            {"type":"https://epistola.app/errors/release-dependencies-missing",
             "title":"Release dependencies missing","status":409,
             "missingDependencies":[
               {"registry":"exchange.epistola.app","namespace":"epistola","catalogKey":"system",
                "releaseVersion":"3.2.0","reason":"UNAVAILABLE"},
               {"registry":"registry.gemeente-x.nl","namespace":"huisstijl","catalogKey":"branding",
                "releaseVersion":"1.4.2","reason":"SOME_FUTURE_REASON"}]}
        """.trimIndent()

        val ex = assertFailsWith<ProblemDetailException> {
            handleErrorResponse(response(HttpStatus.CONFLICT, body))
        }
        assertEquals(KnownProblemSlugs.RELEASE_DEPENDENCIES_MISSING, ex.typeSlug)
        assertTrue(ex.isReleaseDependenciesMissingProblem)
        assertEquals(
            listOf(
                MissingReleaseDependency("exchange.epistola.app", "epistola", "system", "3.2.0", MissingReleaseDependency.Reasons.UNAVAILABLE),
                // A reason this client does not know yet is kept, not dropped.
                MissingReleaseDependency("registry.gemeente-x.nl", "huisstijl", "branding", "1.4.2", "SOME_FUTURE_REASON"),
            ),
            ex.missingDependencies,
        )
    }

    @Test
    fun `a problem without missingDependencies reports none`() {
        val body = """{"type":"https://epistola.app/errors/conflict","title":"Conflict","status":409}"""

        val ex = assertFailsWith<ProblemDetailException> {
            handleErrorResponse(response(HttpStatus.CONFLICT, body))
        }
        assertTrue(ex.missingDependencies.isEmpty())
        assertFalse(ex.isReleaseDependenciesMissingProblem)
    }

    @Test
    fun `malformed missingDependencies entries are skipped, not guessed at`() {
        val body = """
            {"type":"https://epistola.app/errors/release-dependencies-missing",
             "title":"Release dependencies missing","status":409,
             "missingDependencies":[
               "not an object",
               {"registry":"exchange.epistola.app","namespace":"epistola","catalogKey":"system","releaseVersion":"3.2.0"},
               {"registry":"exchange.epistola.app","namespace":"epistola","catalogKey":"system","releaseVersion":3,"reason":"UNAVAILABLE"},
               {"registry":"exchange.epistola.app","namespace":"epistola","catalogKey":"system",
                "releaseVersion":"3.2.0","reason":"UNAVAILABLE"}]}
        """.trimIndent()

        val ex = assertFailsWith<ProblemDetailException> {
            handleErrorResponse(response(HttpStatus.CONFLICT, body))
        }
        assertEquals(listOf("UNAVAILABLE"), ex.missingDependencies.map { it.reason })

        val notAnArray = """
            {"type":"https://epistola.app/errors/release-dependencies-missing",
             "title":"Release dependencies missing","status":409,"missingDependencies":{"registry":"x"}}
        """.trimIndent()
        val ex2 = assertFailsWith<ProblemDetailException> {
            handleErrorResponse(response(HttpStatus.CONFLICT, notAnArray))
        }
        assertTrue(ex2.missingDependencies.isEmpty())
        assertFalse(ex2.isReleaseDependenciesMissingProblem)
    }

    @Test
    fun `surfaces the unreleased dependency drafts of a release-dependencies-unreleased problem in order`() {
        val body = """
            {"type":"https://epistola.app/errors/release-dependencies-unreleased",
             "title":"Release dependencies unreleased","status":409,
             "unreleasedDependencies":[
               {"registry":"exchange.epistola.app","namespace":"acme-corp","catalogKey":"branding",
                "draftId":"0193a6b2-8a10-7d2e-8b3c-4d5e6f708192","draftName":"House style 2027"},
               "not an object",
               {"registry":"exchange.epistola.app","namespace":"acme-corp","catalogKey":"fonts","draftId":"x"},
               {"registry":"registry.gemeente-x.nl","namespace":"huisstijl","catalogKey":"fonts",
                "draftId":"0193a6b2-9c21-7e3f-8c4d-5e6f708192a3","draftName":"New typefaces, round 2"}]}
        """.trimIndent()

        val ex = assertFailsWith<ProblemDetailException> {
            handleErrorResponse(response(HttpStatus.CONFLICT, body))
        }
        assertEquals(KnownProblemSlugs.RELEASE_DEPENDENCIES_UNRELEASED, ex.typeSlug)
        assertTrue(ex.isReleaseDependenciesUnreleasedProblem)
        assertFalse(ex.isReleaseDependenciesMissingProblem)
        assertEquals(
            listOf(
                UnreleasedCatalogDependency("exchange.epistola.app", "acme-corp", "branding", "0193a6b2-8a10-7d2e-8b3c-4d5e6f708192", "House style 2027"),
                UnreleasedCatalogDependency("registry.gemeente-x.nl", "huisstijl", "fonts", "0193a6b2-9c21-7e3f-8c4d-5e6f708192a3", "New typefaces, round 2"),
            ),
            ex.unreleasedDependencies,
        )
    }

    @Test
    fun `a problem without unreleasedDependencies reports none`() {
        val body = """{"type":"https://epistola.app/errors/conflict","title":"Conflict","status":409}"""

        val ex = assertFailsWith<ProblemDetailException> {
            handleErrorResponse(response(HttpStatus.CONFLICT, body))
        }
        assertTrue(ex.unreleasedDependencies.isEmpty())
        assertFalse(ex.isReleaseDependenciesUnreleasedProblem)
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
        val body = """{"type":"about:blank","title":"X","status":409,"themeKey":"classic"}"""
        val parsed = parseProblem(body.toByteArray())
        assertEquals(409, parsed?.problem?.status)
        assertEquals("classic", parsed?.problem?.extensions?.get("themeKey"))
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
