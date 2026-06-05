package app.epistola.client.error

import java.net.URI

/**
 * Constants and helpers describing the Epistola RFC 9457 problem `type` URI scheme.
 *
 * Intentionally duplicated from the server module's `app.epistola.api.error.ProblemDetails`
 * (the `TYPE_BASE` constant): the client is published as a standalone artifact and must not
 * depend on the server module. Keep the two in sync.
 *
 * The machine-readable discriminator is the problem `type` URI — there is no separate `code`
 * member. Application-level errors use a `https://epistola.app/errors/{slug}` type; framework
 * errors keep RFC 9457's default `about:blank`.
 */
object ProblemTypes {

    /** Base URI for Epistola problem `type` values, e.g. `https://epistola.app/errors/not-found`. */
    const val TYPE_BASE: String = "https://epistola.app/errors/"

    /** The RFC 9457 default problem type, used when no specific type is supplied. */
    val BLANK_TYPE: URI = URI.create("about:blank")

    /**
     * Extracts the kebab-case slug from an Epistola problem `type` URI (the part after
     * [TYPE_BASE]), or `null` when [type] is `about:blank` or any non-Epistola URI.
     */
    fun slugFor(type: URI): String? {
        val s = type.toString()
        if (!s.startsWith(TYPE_BASE)) return null
        return s.removePrefix(TYPE_BASE).takeIf { it.isNotEmpty() }
    }
}

/**
 * The canonical problem `type` slugs the Epistola API emits, as documented in the contract's
 * error-type registry (`docs/error-types.md`).
 *
 * These are convenience constants for `when (e.typeSlug)` switches. `typeSlug` is deliberately a
 * plain `String?` (not an enum) so the API can introduce new problem types without forcing a
 * client release — always keep an `else` branch.
 */
object KnownProblemSlugs {
    /** 400 — the request body or parameters failed validation (`ValidationProblemDetail`). */
    const val VALIDATION_ERROR: String = "validation-error"

    /** 400 — the request is malformed or not applicable to the resource state (no `errors`). */
    const val BAD_REQUEST: String = "bad-request"

    /** 401 — missing or invalid credentials. */
    const val UNAUTHORIZED: String = "unauthorized"

    /** 403 — authenticated but not allowed to perform the operation. */
    const val FORBIDDEN: String = "forbidden"

    /** 404 — the addressed resource does not exist. */
    const val NOT_FOUND: String = "not-found"

    /** 409 — the request conflicts with the current state of the resource. */
    const val CONFLICT: String = "conflict"

    /** 429 — too many requests; the client is being rate limited. */
    const val RATE_LIMITED: String = "rate-limited"
}
