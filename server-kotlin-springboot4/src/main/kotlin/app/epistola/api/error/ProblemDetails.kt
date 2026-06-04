package app.epistola.api.error

import app.epistola.api.model.ValidationError
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import java.net.URI

/**
 * Builds [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem responses using Spring's
 * native [ProblemDetail], with the Epistola-specific extension members applied consistently
 * with the API contract.
 *
 * This is an **opt-in** helper — it registers no beans and installs no error handling. The
 * `@RestControllerAdvice` that turns exceptions into responses lives in the consuming
 * application (see the module README); this object just keeps the construction of `type`,
 * `code`, and `errors` consistent with the contract.
 *
 * The contract requires every problem body to carry a stable machine-readable [code]. Use
 * [of]/[validation] for application-level errors, and [ensureCode] inside an advice override
 * to stamp a fallback `code` onto framework-generated responses (malformed JSON, 405, etc.).
 *
 * Example usage:
 * ```
 * // application-level error
 * throw ErrorResponseException(
 *     HttpStatus.NOT_FOUND,
 *     ProblemDetails.of(
 *         status = HttpStatus.NOT_FOUND,
 *         code = "THEME_NOT_FOUND",
 *         detail = "Theme 'classic' was not found",
 *         type = ProblemDetails.typeFor("not-found"),
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

    /** Extension member carrying the stable machine-readable Epistola error code. */
    const val CODE_PROPERTY: String = "code"

    /** Extension member carrying field-level validation errors. */
    const val ERRORS_PROPERTY: String = "errors"

    /** Builds a problem `type` URI from a kebab-case slug, e.g. `typeFor("not-found")`. */
    fun typeFor(slug: String): URI = URI.create(TYPE_BASE + slug)

    /**
     * Builds a [ProblemDetail] for an application-level error and stamps the required [code]
     * extension member. `title` defaults to the status reason phrase; `type` is set explicitly
     * to [BLANK_TYPE] (`about:blank`) unless [type] is supplied, so the contract's required
     * `type` member is always present on the wire.
     */
    fun of(
        status: HttpStatusCode,
        code: String,
        detail: String? = null,
        type: URI? = null,
        instance: URI? = null,
    ): ProblemDetail {
        val problem = ProblemDetail.forStatus(status)
        problem.type = type ?: BLANK_TYPE
        if (detail != null) problem.detail = detail
        if (instance != null) problem.instance = instance
        problem.setProperty(CODE_PROPERTY, code)
        return problem
    }

    /**
     * Builds a validation [ProblemDetail] — like [of] plus the field-level [errors] extension
     * member (the `ValidationProblemDetail` shape from the contract).
     */
    fun validation(
        status: HttpStatusCode,
        code: String,
        errors: List<ValidationError>,
        detail: String? = null,
        type: URI? = null,
        instance: URI? = null,
    ): ProblemDetail {
        val problem = of(status, code, detail, type, instance)
        problem.setProperty(ERRORS_PROPERTY, errors)
        return problem
    }

    /**
     * Stamps [fallback] as the `code` only if the problem does not already carry one. Use this
     * in a `ResponseEntityExceptionHandler.handleExceptionInternal` override so that Spring's
     * framework-generated 4xx responses also satisfy the required-`code` contract.
     */
    fun ensureCode(problem: ProblemDetail, fallback: String): ProblemDetail {
        if (problem.properties?.get(CODE_PROPERTY) == null) {
            problem.setProperty(CODE_PROPERTY, fallback)
        }
        return problem
    }
}
