// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.error

import app.epistola.client.infrastructure.Serializer
import app.epistola.client.model.DataModelValidationError
import app.epistola.client.model.ProblemDetail
import app.epistola.client.model.ValidationError
import com.fasterxml.jackson.core.type.TypeReference
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpResponse
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.nio.charset.StandardCharsets

/** The RFC 9457 problem media type, `application/problem+json`. */
val PROBLEM_JSON: MediaType = MediaType.APPLICATION_PROBLEM_JSON

/**
 * Installs an **opt-in** error handler that turns `application/problem+json` error responses into
 * a typed [ProblemDetailException] consumers can inspect (problem `type`, status, validation
 * errors).
 *
 * Opt-in by design — mirroring the server module's `ProblemDetails` helper, nothing is
 * auto-registered. Add it to the `RestClient.Builder` you already build:
 *
 * ```kotlin
 * val restClient = RestClient.builder()
 *     .baseUrl("https://epistola.example.com/api")
 *     .requestInterceptor(identity.interceptor())
 *     .requestInterceptor(signer.interceptor())
 *     .installProblemDetailHandler()   // <-- opt-in
 *     .build()
 * ```
 *
 * Error responses that are **not** parseable problem+json (a different content type, an empty
 * body, or malformed JSON) fall back to Spring's standard `HttpClientErrorException` /
 * `HttpServerErrorException`, so behaviour is never worse than the generated default.
 */
fun RestClient.Builder.installProblemDetailHandler(): RestClient.Builder = defaultStatusHandler(HttpStatusCode::isError) { _, response -> handleErrorResponse(response) }

/**
 * Inspects an error [response] and always throws: a [ProblemDetailException] when the body is a
 * parseable `application/problem+json` document, otherwise the same
 * [HttpClientErrorException] / [HttpServerErrorException] Spring's default handler would raise.
 *
 * Internal and self-contained so it can be unit-tested against a stubbed [ClientHttpResponse]
 * without a live server.
 */
internal fun handleErrorResponse(response: ClientHttpResponse) {
    val statusCode = response.statusCode
    val statusText = runCatching { response.statusText }.getOrDefault("")
    val headers = response.headers
    val bytes = response.body.readAllBytes()
    val contentType = headers.contentType
    val charset = contentType?.charset ?: StandardCharsets.UTF_8

    if (contentType != null && PROBLEM_JSON.isCompatibleWith(contentType) && bytes.isNotEmpty()) {
        val parsed = parseProblem(bytes)
        if (parsed != null) {
            throw ProblemDetailException(
                parsed.problem,
                parsed.errors,
                parsed.validationErrors,
                statusCode,
                statusText,
                headers,
                bytes,
                charset,
            )
        }
    }

    // Not a parseable problem document — reproduce Spring's default behaviour exactly.
    throw if (statusCode.is4xxClientError) {
        HttpClientErrorException.create(statusCode, statusText, headers, bytes, charset)
    } else {
        HttpServerErrorException.create(statusCode, statusText, headers, bytes, charset)
    } as RestClientResponseException
}

/**
 * A parsed problem body: the base [ProblemDetail] plus the field-level validation [errors]
 * (`ValidationProblemDetail`) and the per-example [validationErrors] map
 * (`DataModelValidationProblemDetail`). Both extension collections are empty unless the
 * corresponding member was present.
 */
internal class ParsedProblem(
    val problem: ProblemDetail,
    val errors: List<ValidationError>,
    val validationErrors: Map<String, List<DataModelValidationError>>,
)

/**
 * Parses a problem+json [bytes] body into a [ParsedProblem] — the base [ProblemDetail] fields plus,
 * when present, the `errors` array (field-level [ValidationError]s) and the `validationErrors` map
 * (per-example [DataModelValidationError]s). Returns `null` on any parse failure.
 *
 * `ProblemDetail`, `ValidationProblemDetail`, and `DataModelValidationProblemDetail` are independent
 * classes, so the base problem and each of these two known extensions are read separately, off the
 * raw tree rather than through a composed model. Any *other* member is not lost either: `ProblemDetail`
 * is hand-written with a catch-all (see [ProblemDetail.extensions]), so `errors` and
 * `validationErrors` land there too, alongside whatever else the body carries — reading them here as
 * well is what gives them a properly typed shape ([ValidationError], [DataModelValidationError])
 * instead of raw parsed JSON.
 */
internal fun parseProblem(bytes: ByteArray): ParsedProblem? = try {
    val mapper = Serializer.jacksonObjectMapper
    val tree = mapper.readTree(bytes)
    val problem = mapper.treeToValue(tree, ProblemDetail::class.java)
    val errorsNode = tree.get(ProblemExtensionMembers.ERRORS)
    val errors: List<ValidationError> = if (errorsNode != null && errorsNode.isArray) {
        mapper.convertValue(errorsNode, object : TypeReference<List<ValidationError>>() {})
    } else {
        emptyList()
    }
    val validationErrorsNode = tree.get(ProblemExtensionMembers.VALIDATION_ERRORS)
    val validationErrors: Map<String, List<DataModelValidationError>> =
        if (validationErrorsNode != null && validationErrorsNode.isObject) {
            mapper.convertValue(validationErrorsNode, object : TypeReference<Map<String, List<DataModelValidationError>>>() {})
        } else {
            emptyMap()
        }
    ParsedProblem(problem, errors, validationErrors)
} catch (_: Exception) {
    null
}
