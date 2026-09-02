// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.error

import app.epistola.protocol.ProblemTypeUris
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

    /**
     * Base URI for Epistola problem `type` values, e.g. `https://epistola.app/errors/not-found`.
     * Generated from the spec's `x-problem-types` registry.
     */
    const val TYPE_BASE: String = GENERATED_PROBLEM_TYPE_BASE

    /** The RFC 9457 default problem type, used when no specific type is supplied. */
    val BLANK_TYPE: URI = ProblemTypeUris.BLANK_TYPE

    // The conversion itself is shared with the Jakarta client and the server stubs; only the base
    // differs per module, and that is generated.
    private val uris = ProblemTypeUris.of(TYPE_BASE)

    /**
     * Extracts the kebab-case slug from an Epistola problem `type` URI (the part after
     * [TYPE_BASE]), or `null` when [type] is `about:blank` or any non-Epistola URI.
     */
    fun slugFor(type: URI): String? = uris.slugFor(type)
}

// KnownProblemSlugs is generated from the spec's x-problem-types extension by the
// generateProblemSlugs Gradle task (build/generated-problem-slugs). It lives in this
// package; see ProblemRegistryTest for the guard that keeps it aligned with TYPE_BASE.
