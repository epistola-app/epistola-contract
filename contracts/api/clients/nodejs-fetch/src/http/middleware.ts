// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { isProblemJson, parseProblem } from '../error/problemDetailParser.js'
import type { FetchParams, Middleware, RequestContext, ResponseContext } from '../generated/api/runtime.js'
import type { ClientIdentity } from '../identity/clientIdentity.js'
import { findOperation, operationPath } from './operations.js'

/** What the request middleware adds to every request the generated API classes make. */
export interface EpistolaRequestOptions {
  /** The API base URL the operation paths are relative to. */
  readonly basePath: string
  /** Identity headers (`User-Agent` + `X-EP-Node-Id`), added when the request does not carry them. */
  readonly identity?: ClientIdentity
  /** Produces the `Authorization` value for a request, fresh each time; undefined when unauthenticated. */
  readonly authorization?: () => string | undefined
  /** Aborts a request that has not completed within this many milliseconds; undefined for no limit. */
  readonly requestTimeoutMs?: number
}

/**
 * The request half of the Epistola conventions, as a generated-runtime `Middleware`:
 *
 * - the identity headers, unless the caller already set them;
 * - the `Authorization` header, always — a self-signed JWT is minted per request and must not be
 *   the one from the last request;
 * - the `Accept` header the operation is declared with, unless the caller set one. The generated
 *   API classes set `Content-Type` but never `Accept`, and without it Node's `fetch` sends
 *   `*​/*` — a request that never says it accepts `application/problem+json`, so a server doing
 *   strict content negotiation answers an error with 406 instead of the problem document this
 *   client is built to parse;
 * - an abort signal for the request timeout, unless the caller passed their own.
 */
export function epistolaRequestMiddleware(options: EpistolaRequestOptions): Middleware {
  return {
    async pre(context: RequestContext): Promise<FetchParams> {
      const { url, init } = context
      const headers = new Headers(init.headers)

      if (options.identity) {
        for (const [name, value] of Object.entries(options.identity.headers())) {
          if (!headers.has(name)) {
            headers.set(name, value)
          }
        }
      }

      const authorization = options.authorization?.()
      if (authorization !== undefined) {
        headers.set('Authorization', authorization)
      }

      if (!headers.has('Accept')) {
        const operation = findOperation(init.method ?? 'GET', operationPath(url, options.basePath))
        if (operation && operation.accept !== '') {
          headers.set('Accept', operation.accept)
        }
      }

      const signal = init.signal ?? (options.requestTimeoutMs !== undefined ? AbortSignal.timeout(options.requestTimeoutMs) : undefined)
      return { url, init: signal ? { ...init, headers, signal } : { ...init, headers } }
    },
  }
}

/**
 * The response half: an error response with an `application/problem+json` body is thrown as a
 * typed {@link ProblemDetailException} before the generated runtime throws its generic
 * `ResponseError`. Error responses that are *not* parseable problem+json — a different content
 * type, an empty body, malformed JSON — pass through untouched, so behaviour is never worse than
 * the generated default.
 */
export function problemDetailMiddleware(): Middleware {
  return {
    async post(context: ResponseContext): Promise<Response | void> {
      const { response } = context
      if (response.status < 400 || !isProblemJson(response.headers.get('content-type'))) {
        return undefined
      }
      const body = await response.text()
      if (body.trim() === '') {
        return undefined
      }
      const problem = parseProblem(body, response.status, response)
      if (problem !== undefined) {
        throw problem
      }
      return undefined
    },
  }
}
