// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.error

import app.epistola.client.model.DataModelValidationError
import app.epistola.client.model.ProblemDetail
import app.epistola.client.model.ValidationError
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.web.client.RestClientResponseException
import java.net.URI
import java.nio.charset.Charset

/**
 * A [RestClientResponseException] carrying a parsed [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)
 * [ProblemDetail] (`application/problem+json`) body.
 *
 * Thrown by the opt-in error handler installed via [installProblemDetailHandler]. It extends
 * [RestClientResponseException] on purpose: the generated APIs declare
 * `@Throws(RestClientResponseException::class)`, so existing `catch (e: RestClientResponseException)`
 * sites keep working and consumers retain the inherited `statusCode` /
 * [getResponseBodyAsString] accessors. (Note: because it is a sibling of `HttpClientErrorException`
 * / `HttpServerErrorException`, it is *not* caught by `catch (e: HttpClientErrorException)` — catch
 * [ProblemDetailException] or [RestClientResponseException].)
 *
 * The machine-readable discriminator is the problem [type] URI; switch on [typeSlug]. Field-level
 * validation errors (the `ValidationProblemDetail` shape from the contract) are surfaced via
 * [errors]; per-example data-model validation failures (the `DataModelValidationProblemDetail`
 * shape, `data-model-validation-error`) are surfaced via [validationErrors] — the generated
 * `ProblemDetail`, `ValidationProblemDetail`, and `DataModelValidationProblemDetail` models are
 * independent data classes, so the base fields and each extension are carried separately.
 */
class ProblemDetailException(
    /** The parsed base problem (`type`, `title`, `status`, `detail`, `instance`). */
    val problem: ProblemDetail,
    /** Field-level validation errors when the body was a `ValidationProblemDetail`, else empty. */
    val errors: List<ValidationError>,
    /**
     * Per-example data-model validation failures (example name → failures) when the body was a
     * `DataModelValidationProblemDetail` (`data-model-validation-error`, 422), else empty.
     */
    val validationErrors: Map<String, List<DataModelValidationError>> = emptyMap(),
    statusCode: HttpStatusCode,
    statusText: String,
    headers: HttpHeaders?,
    responseBody: ByteArray?,
    responseCharset: Charset?,
) : RestClientResponseException(
    buildMessage(statusCode, problem),
    statusCode,
    statusText,
    headers,
    responseBody,
    responseCharset,
) {
    /** The problem `type` URI (`about:blank` when unspecified). */
    val type: URI get() = problem.type

    /**
     * Kebab-case slug derived from [type] by stripping [ProblemTypes.TYPE_BASE], or `null` for
     * `about:blank` and non-Epistola types. Compare against [KnownProblemSlugs].
     */
    val typeSlug: String? get() = ProblemTypes.slugFor(problem.type)

    /** Short human-readable summary of the problem type (RFC 9457 `title`). */
    val title: String get() = problem.title

    /**
     * The HTTP status carried in the problem body. Usually equal to the response
     * [getStatusCode], but named distinctly to avoid clashing with the inherited member.
     */
    val problemStatus: Int get() = problem.status

    /** Occurrence-specific explanation (RFC 9457 `detail`), if the server provided one. */
    val detail: String? get() = problem.detail

    /** True when this problem carried field-level validation errors. */
    val isValidationProblem: Boolean get() = errors.isNotEmpty()

    /** True when this problem carried per-example data-model validation failures. */
    val isDataModelValidationProblem: Boolean get() = validationErrors.isNotEmpty()

    private companion object {
        fun buildMessage(status: HttpStatusCode, problem: ProblemDetail): String = "$status ${problem.title}" + (problem.detail?.let { ": $it" } ?: "")
    }
}
