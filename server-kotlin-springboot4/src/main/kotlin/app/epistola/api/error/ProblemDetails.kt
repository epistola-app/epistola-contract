package app.epistola.api.error

import app.epistola.api.model.ValidationError
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import java.net.URI

/**
 * Builds [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem responses using Spring's
 * native [ProblemDetail], keeping the `type` discriminator and the `errors` extension
 * consistent with the API contract.
 *
 * This is an **opt-in** helper — it registers no beans and installs no error handling. The
 * `@RestControllerAdvice` that turns exceptions into responses lives in the consuming
 * application (see the module README).
 *
 * The contract's machine-readable discriminator is the problem [type] URI (there is no separate
 * `code` member). Application-level errors use a `https://epistola.app/errors/{slug}` type;
 * framework-level errors with no specific type keep Spring's default `about:blank`.
 *
 * Example usage:
 * ```
 * // application-level error
 * throw ErrorResponseException(
 *     HttpStatus.NOT_FOUND,
 *     ProblemDetails.of(
 *         status = HttpStatus.NOT_FOUND,
 *         type = ProblemDetails.typeFor("not-found"),
 *         detail = "Theme 'classic' was not found",
 *     ),
 *     null,
 * )
 * ```
 */
object ProblemDetails {

    /** Base URI for Epistola problem `type` values, e.g. `https://epistola.app/errors/not-found`. */
    const val TYPE_BASE: String = "https://epistola.app/errors/"

    /** The RFC 9457 default problem type, used when no specific [type] is supplied. */
    val BLANK_TYPE: URI = URI.create("about:blank")

    /** Extension member carrying field-level validation errors. */
    const val ERRORS_PROPERTY: String = "errors"

    /** Builds a problem `type` URI from a kebab-case slug, e.g. `typeFor("not-found")`. */
    fun typeFor(slug: String): URI = URI.create(TYPE_BASE + slug)

    /**
     * Builds a [ProblemDetail] for an application-level error. `title` defaults to the status
     * reason phrase; [type] is the machine-readable discriminator and defaults to [BLANK_TYPE]
     * (`about:blank`) when not supplied.
     */
    fun of(
        status: HttpStatusCode,
        type: URI = BLANK_TYPE,
        detail: String? = null,
        instance: URI? = null,
    ): ProblemDetail {
        val problem = ProblemDetail.forStatus(status)
        problem.type = type
        if (detail != null) problem.detail = detail
        if (instance != null) problem.instance = instance
        return problem
    }

    /**
     * Builds a validation [ProblemDetail] — like [of] plus the field-level [errors] extension
     * member (the `ValidationProblemDetail` shape from the contract).
     */
    fun validation(
        status: HttpStatusCode,
        type: URI = BLANK_TYPE,
        errors: List<ValidationError>,
        detail: String? = null,
        instance: URI? = null,
    ): ProblemDetail {
        val problem = of(status, type, detail, instance)
        problem.setProperty(ERRORS_PROPERTY, errors)
        return problem
    }
}
