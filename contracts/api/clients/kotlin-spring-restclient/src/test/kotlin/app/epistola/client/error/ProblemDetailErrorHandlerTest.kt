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
    fun `parses catalog-source-unavailable as a 503 and as a 502`() {
        for (status in listOf(HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.BAD_GATEWAY)) {
            val body = """
                {"type":"https://epistola.app/errors/catalog-source-unavailable",
                 "title":"Catalog Source Unavailable","status":${status.value()},
                 "detail":"registry.gemeente-x.nl could not be used"}
            """.trimIndent()

            val ex = assertFailsWith<ProblemDetailException> {
                handleErrorResponse(response(status, body))
            }
            assertEquals(KnownProblemSlugs.CATALOG_SOURCE_UNAVAILABLE, ex.typeSlug)
            assertEquals(status.value(), ex.problemStatus)
            assertEquals(status, ex.statusCode)
        }
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
             "validationErrors":{"standard-invoice":[{"path":"/name","message":"required property 'name' not found"}]}}
        """.trimIndent()

        val ex = assertFailsWith<ProblemDetailException> {
            handleErrorResponse(response(HttpStatus.UNPROCESSABLE_ENTITY, body))
        }
        assertTrue(ex.isDataModelValidationProblem)
        assertFalse(ex.isValidationProblem)
        assertEquals(KnownProblemSlugs.DATA_MODEL_VALIDATION_ERROR, ex.typeSlug)
        assertEquals(1, ex.validationErrors["standard-invoice"]?.size)
        assertEquals("/name", ex.validationErrors.getValue("standard-invoice")[0].path)
    }

    @Test
    fun `surfaces the missing dependencies of a release-dependencies-missing problem in order`() {
        val body = """
            {"type":"https://epistola.app/errors/release-dependencies-missing",
             "title":"Release dependencies missing","status":409,
             "missingDependencies":[
               {"registry":"exchange.epistola.app","namespace":"epistola","catalogKey":"system",
                "version":"3.2.0","reason":"unavailable"},
               {"registry":"registry.gemeente-x.nl","namespace":"huisstijl","catalogKey":"branding",
                "version":"1.4.2","reason":"SOME_FUTURE_REASON"}]}
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
               {"registry":"exchange.epistola.app","namespace":"epistola","catalogKey":"system","version":"3.2.0"},
               {"registry":"exchange.epistola.app","namespace":"epistola","catalogKey":"system","version":3,"reason":"unavailable"},
               {"registry":"exchange.epistola.app","namespace":"epistola","catalogKey":"system",
                "version":"3.2.0","reason":"unavailable"}]}
        """.trimIndent()

        val ex = assertFailsWith<ProblemDetailException> {
            handleErrorResponse(response(HttpStatus.CONFLICT, body))
        }
        assertEquals(listOf("unavailable"), ex.missingDependencies.map { it.reason })

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
                "releaseId":"r4t8w2nc","releaseName":"House style 2027"},
               "not an object",
               {"registry":"exchange.epistola.app","namespace":"acme-corp","catalogKey":"fonts","releaseId":"x"},
               {"registry":"registry.gemeente-x.nl","namespace":"huisstijl","catalogKey":"fonts",
                "releaseId":"h3v9p6db","releaseName":"New typefaces, round 2"}]}
        """.trimIndent()

        val ex = assertFailsWith<ProblemDetailException> {
            handleErrorResponse(response(HttpStatus.CONFLICT, body))
        }
        assertEquals(KnownProblemSlugs.RELEASE_DEPENDENCIES_UNRELEASED, ex.typeSlug)
        assertTrue(ex.isReleaseDependenciesUnreleasedProblem)
        assertFalse(ex.isReleaseDependenciesMissingProblem)
        assertEquals(
            listOf(
                UnreleasedCatalogDependency("exchange.epistola.app", "acme-corp", "branding", "r4t8w2nc", "House style 2027"),
                UnreleasedCatalogDependency("registry.gemeente-x.nl", "huisstijl", "fonts", "h3v9p6db", "New typefaces, round 2"),
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
    fun `surfaces why a catalog-read-only problem refused the write`() {
        val body = """
            {"type":"https://epistola.app/errors/catalog-read-only","title":"Catalog Read Only","status":409,
             "readOnlyReason":"editing-disabled"}
        """.trimIndent()

        val ex = assertFailsWith<ProblemDetailException> {
            handleErrorResponse(response(HttpStatus.CONFLICT, body))
        }
        assertEquals(KnownProblemSlugs.CATALOG_READ_ONLY, ex.typeSlug)
        assertEquals("editing-disabled", ex.readOnlyReason)
        assertTrue(ex.isCatalogReadOnlyProblem)
    }

    @Test
    fun `a problem without a string readOnlyReason reports none`() {
        val plain = """{"type":"https://epistola.app/errors/conflict","title":"Conflict","status":409}"""
        val notAString = """{"type":"https://epistola.app/errors/catalog-read-only","title":"Catalog Read Only","status":409,"readOnlyReason":7}"""

        for (body in listOf(plain, notAString)) {
            val ex = assertFailsWith<ProblemDetailException> { handleErrorResponse(response(HttpStatus.CONFLICT, body)) }
            assertNull(ex.readOnlyReason)
            assertFalse(ex.isCatalogReadOnlyProblem)
        }
    }

    @Test
    fun `surfaces the unresolved conflicts of a merge-conflicts-unresolved problem in order`() {
        val body = """
            {"type":"https://epistola.app/errors/merge-conflicts-unresolved","title":"Merge Conflicts Unresolved",
             "status":409,"unresolvedConflicts":["stencil/footer",7,"template/invoice"]}
        """.trimIndent()

        val ex = assertFailsWith<ProblemDetailException> { handleErrorResponse(response(HttpStatus.CONFLICT, body)) }
        assertEquals(KnownProblemSlugs.MERGE_CONFLICTS_UNRESOLVED, ex.typeSlug)
        assertEquals(listOf("stencil/footer", "template/invoice"), ex.unresolvedConflicts)
        assertTrue(ex.isMergeConflictsUnresolvedProblem)

        val plain = assertFailsWith<ProblemDetailException> {
            handleErrorResponse(response(HttpStatus.CONFLICT, """{"type":"https://epistola.app/errors/conflict","title":"Conflict","status":409}"""))
        }
        assertTrue(plain.unresolvedConflicts.isEmpty())
        assertFalse(plain.isMergeConflictsUnresolvedProblem)
    }

    @Test
    fun `surfaces the release a merge-base-missing problem asks for, and no other problem's version`() {
        val body = """
            {"type":"https://epistola.app/errors/merge-base-missing","title":"Merge Base Missing",
             "status":409,"version":"1.0.0","fingerprint":"v5:9999999999999999999999999999999999999999999999999999999999999999"}
        """.trimIndent()

        val ex = assertFailsWith<ProblemDetailException> { handleErrorResponse(response(HttpStatus.CONFLICT, body)) }
        assertEquals(KnownProblemSlugs.MERGE_BASE_MISSING, ex.typeSlug)
        assertEquals("1.0.0", ex.missingBaseVersion)
        assertEquals("v5:9999999999999999999999999999999999999999999999999999999999999999", ex.missingBaseFingerprint)
        assertTrue(ex.isMergeBaseMissingProblem)

        val other = assertFailsWith<ProblemDetailException> {
            handleErrorResponse(
                response(HttpStatus.CONFLICT, """{"type":"https://epistola.app/errors/catalog-schema-too-old","title":"Too old","status":409,"version":"4"}"""),
            )
        }
        assertEquals(null, other.missingBaseVersion)
        assertEquals(null, other.missingBaseFingerprint)
        assertFalse(other.isMergeBaseMissingProblem)
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
