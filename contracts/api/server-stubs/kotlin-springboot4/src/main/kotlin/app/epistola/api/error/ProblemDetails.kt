// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.api.error

import app.epistola.api.model.DataModelValidationError
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

    /**
     * Base URI for Epistola problem `type` values, e.g. `https://epistola.app/errors/not-found`.
     * Taken from [GENERATED_PROBLEM_TYPE_BASE], which the build writes from the spec's
     * `x-problem-types` registry.
     */
    const val TYPE_BASE: String = GENERATED_PROBLEM_TYPE_BASE

    /** The RFC 9457 default problem type, used when no specific [type] is supplied. */
    val BLANK_TYPE: URI = URI.create("about:blank")

    /**
     * Extension member carrying field-level validation errors. Generated from the
     * `ValidationProblemDetail` schema, so it stays the name the clients read.
     */
    const val ERRORS_PROPERTY: String = ProblemExtensionMembers.ERRORS

    /**
     * Extension member carrying per-example data-model validation failures. Generated from the
     * `DataModelValidationProblemDetail` schema, so it stays the name the clients read.
     */
    const val VALIDATION_ERRORS_PROPERTY: String = ProblemExtensionMembers.VALIDATION_ERRORS

    /** Builds a problem `type` URI from a kebab-case slug, e.g. `typeFor("not-found")`. */
    fun typeFor(slug: String): URI = URI.create(TYPE_BASE + slug)

    /**
     * The canonical problem `type` slugs from the contract's error-type registry, for use with
     * [typeFor].
     *
     * Every value here comes from [KnownProblemSlugs], which the build generates from the spec's
     * `x-problem-types` extension — the same registry the published clients generate their
     * constants from, so a `when (e.typeSlug)` on the client lines up with what this server emits.
     * Prefer referring to [KnownProblemSlugs] directly in new code; this object is the shape the
     * server module has always exposed, and `ProblemRegistryTest` fails if it stops covering the
     * registry. The slug list is open: implementations may also emit slugs not listed here.
     */
    object KnownSlugs {
        /** 400 — the request body or parameters failed validation (use [validation]). */
        const val VALIDATION_ERROR: String = KnownProblemSlugs.VALIDATION_ERROR

        /** 400 — malformed or not applicable to the resource state (no `errors`). */
        const val BAD_REQUEST: String = KnownProblemSlugs.BAD_REQUEST

        /** 401 — missing or invalid credentials. */
        const val UNAUTHORIZED: String = KnownProblemSlugs.UNAUTHORIZED

        /** 401 — API-key authentication is disabled for this deployment. */
        const val API_KEY_AUTH_DISABLED: String = KnownProblemSlugs.API_KEY_AUTH_DISABLED

        /** 403 — authenticated but not allowed to perform the operation. */
        const val FORBIDDEN: String = KnownProblemSlugs.FORBIDDEN

        /** 404 — the addressed resource does not exist. */
        const val NOT_FOUND: String = KnownProblemSlugs.NOT_FOUND

        /** 409 — the request conflicts with the current state of the resource. */
        const val CONFLICT: String = KnownProblemSlugs.CONFLICT

        /** 422 — data examples do not validate against the data model (use [dataModelValidation]). */
        const val DATA_MODEL_VALIDATION_ERROR: String = KnownProblemSlugs.DATA_MODEL_VALIDATION_ERROR

        /** 429 — too many requests; the client is being rate limited. */
        const val RATE_LIMITED: String = KnownProblemSlugs.RATE_LIMITED
    }

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

    /**
     * Builds a data-model validation [ProblemDetail] — like [of] plus the [validationErrors]
     * extension member mapping each data-example name to the failures it produced (the
     * `DataModelValidationProblemDetail` shape from the contract, `data-model-validation-error`).
     */
    fun dataModelValidation(
        status: HttpStatusCode,
        type: URI = BLANK_TYPE,
        validationErrors: Map<String, List<DataModelValidationError>>,
        detail: String? = null,
        instance: URI? = null,
    ): ProblemDetail {
        val problem = of(status, type, detail, instance)
        problem.setProperty(VALIDATION_ERRORS_PROPERTY, validationErrors)
        return problem
    }
}
