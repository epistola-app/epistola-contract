package app.epistola.client.error

import app.epistola.client.infrastructure.Serializer
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

/** A parsed problem body: the base [ProblemDetail] plus any field-level validation [errors]. */
internal class ParsedProblem(val problem: ProblemDetail, val errors: List<ValidationError>)

/**
 * Parses a problem+json [bytes] body into a [ParsedProblem] — the base [ProblemDetail] fields plus,
 * when an `errors` array is present, the field-level [ValidationError]s. Returns `null` on any
 * parse failure.
 *
 * The generated `ProblemDetail` and `ValidationProblemDetail` are independent models, so the base
 * problem and the `errors` extension are read separately. Reuses the generated
 * [Serializer.jacksonObjectMapper] (unknown members are ignored), so it tolerates extension members
 * the contract does not model.
 */
internal fun parseProblem(bytes: ByteArray): ParsedProblem? = try {
    val mapper = Serializer.jacksonObjectMapper
    val tree = mapper.readTree(bytes)
    val problem = mapper.treeToValue(tree, ProblemDetail::class.java)
    val errorsNode = tree.get("errors")
    val errors: List<ValidationError> = if (errorsNode != null && errorsNode.isArray) {
        mapper.convertValue(errorsNode, object : TypeReference<List<ValidationError>>() {})
    } else {
        emptyList()
    }
    ParsedProblem(problem, errors)
} catch (_: Exception) {
    null
}
