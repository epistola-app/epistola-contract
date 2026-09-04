// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

/**
 * Constants and helpers describing the Epistola RFC 9457 problem `type` URI scheme.
 *
 * The machine-readable discriminator is the problem `type` URI — there is no separate `code`
 * member. Application-level errors use a `https://epistola.app/errors/{slug}` type; framework
 * errors keep RFC 9457's default `about:blank`.
 *
 * {@link TYPE_BASE} is hand-written and checked against the value generated from the spec's
 * `x-problem-types` registry by the problem-registry test, the same guard every other client has.
 */

/** Base URI for Epistola problem `type` values, e.g. `https://epistola.app/errors/not-found`. */
export const TYPE_BASE = 'https://epistola.app/errors/'

/** The RFC 9457 default problem type, used when no specific type is supplied. */
export const BLANK_TYPE = 'about:blank'

/** The RFC 9457 problem media type. */
export const PROBLEM_JSON = 'application/problem+json'

/**
 * The kebab-case slug of an Epistola problem `type` URI (the part after {@link TYPE_BASE}), or
 * undefined when `type` is `about:blank`, empty, or any non-Epistola URI.
 *
 * Callers switch on the result, so it stays a plain optional string rather than a union: the API
 * can introduce problem types without forcing a client release.
 */
export function slugFor(type: string | null | undefined): string | undefined {
  if (!type || !type.startsWith(TYPE_BASE)) {
    return undefined
  }
  const slug = type.slice(TYPE_BASE.length)
  return slug === '' ? undefined : slug
}

/** Builds a problem `type` URI from a kebab-case slug. */
export function typeFor(slug: string): string {
  return TYPE_BASE + slug
}
