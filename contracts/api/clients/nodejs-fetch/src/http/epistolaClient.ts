// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import type { JwtSigner } from '../auth/jwtSigner.js'
import { Configuration, type FetchAPI, type Middleware } from '../generated/api/runtime.js'
import type { ClientIdentity } from '../identity/clientIdentity.js'
import { epistolaRequestMiddleware, problemDetailMiddleware } from './middleware.js'

type Auth = { readonly kind: 'api-key'; readonly key: string } | { readonly kind: 'jwt'; readonly signer: JwtSigner }

/** Everything an {@link EpistolaClient} is built from. */
export interface EpistolaClientOptions {
  /** The API base URL, including the contract's `/api` base path, e.g. `https://epistola.example.com/api`. */
  readonly baseUrl: string
  /** `User-Agent` + `X-EP-Node-Id`. Every request the contract accepts requires both. */
  readonly identity?: ClientIdentity
  /** `Authorization: ApiKey <key>`. Mutually exclusive with `jwtSigner`. */
  readonly apiKey?: string
  /** `Authorization: Bearer <jwt>`, minted fresh per request. Mutually exclusive with `apiKey`. */
  readonly jwtSigner?: JwtSigner
  /** The `fetch` to use; defaults to the platform's. */
  readonly fetchApi?: FetchAPI
  /** Aborts a request that has not completed within this many milliseconds. Default: no limit. */
  readonly requestTimeoutMs?: number
  /** Extra middleware, run after the Epistola request middleware and before the problem-detail one. */
  readonly middleware?: readonly Middleware[]
}

/**
 * The blessed setup: identity headers, API-key or self-signed-JWT authentication, the `Accept`
 * header each operation is declared with, and RFC 9457 problem parsing — assembled into the
 * `Configuration` every generated API class takes, so that
 *
 * ```ts
 * const client = EpistolaClient.builder('https://epistola.example.com/api', 'epk_...')
 *   .identity(ClientIdentity.builder().nodeId('pod-1').build())
 *   .build()
 *
 * const templates = new TemplatesApi(client)
 * ```
 *
 * is the whole of it. Problem parsing is not opt-in here, because forgetting it fails silently:
 * every error response would come back as a bare `ResponseError` with the problem document
 * unread, and nothing would say so.
 *
 * It is also what {@link ResultCollector} polls with: {@link requestHeaders} hands out the same
 * identity and a fresh token for the one endpoint the generated classes do not cover.
 */
export class EpistolaClient extends Configuration {
  /** The API base URL, without a trailing slash. */
  readonly baseUrl: string

  /** The identity headers this client sends, if configured. */
  readonly identity: ClientIdentity | undefined

  /** The `fetch` this client uses, for requests made outside the generated API classes. */
  readonly fetch: FetchAPI

  private readonly auth: Auth | undefined

  constructor(options: EpistolaClientOptions) {
    const baseUrl = normalizeBaseUrl(options.baseUrl)
    if (options.apiKey !== undefined && options.jwtSigner !== undefined) {
      throw new RangeError('apiKey and jwtSigner are mutually exclusive')
    }
    const auth: Auth | undefined = options.jwtSigner
      ? { kind: 'jwt', signer: options.jwtSigner }
      : options.apiKey !== undefined
        ? { kind: 'api-key', key: normalizeApiKey(options.apiKey) }
        : undefined
    const fetchApi = options.fetchApi ?? fetch
    // `authorization` is looked up through the instance so a fresh token is minted per request.
    let self: EpistolaClient | undefined
    super({
      basePath: baseUrl,
      fetchApi,
      middleware: [
        epistolaRequestMiddleware({
          basePath: baseUrl,
          identity: options.identity,
          authorization: () => self?.authorizationHeader(),
          requestTimeoutMs: options.requestTimeoutMs,
        }),
        ...(options.middleware ?? []),
        problemDetailMiddleware(),
      ],
    })
    self = this
    this.baseUrl = baseUrl
    this.identity = options.identity
    this.fetch = fetchApi
    this.auth = auth
  }

  /** A {@link EpistolaClientBuilder}, with `baseUrl` and a static `apiKey` already set when given. */
  static builder(baseUrl?: string, apiKey?: string): EpistolaClientBuilder {
    const builder = new EpistolaClientBuilder()
    if (baseUrl !== undefined) builder.baseUrl(baseUrl)
    if (apiKey !== undefined) builder.apiKey(apiKey)
    return builder
  }

  /**
   * The `Authorization` header value for a request made now — `ApiKey <key>`, or `Bearer <jwt>`
   * with a token minted for this call — or undefined when the client is unauthenticated.
   */
  authorizationHeader(): string | undefined {
    switch (this.auth?.kind) {
      case 'api-key':
        return `ApiKey ${this.auth.key}`
      case 'jwt':
        return `Bearer ${this.auth.signer.createToken()}`
      default:
        return undefined
    }
  }

  /**
   * The per-request Epistola headers — identity plus a fresh `Authorization` — for a request made
   * outside the generated API classes. {@link ResultCollector} drives the raw NDJSON collect
   * endpoint with these.
   */
  requestHeaders(): Record<string, string> {
    const headers: Record<string, string> = { ...(this.identity?.headers() ?? {}) }
    const authorization = this.authorizationHeader()
    if (authorization !== undefined) {
      headers.Authorization = authorization
    }
    return headers
  }
}

/** Fluent builder for {@link EpistolaClient}. */
export class EpistolaClientBuilder {
  private baseUrlValue: string | undefined
  private identityValue: ClientIdentity | undefined
  private apiKeyValue: string | undefined
  private jwtSignerValue: JwtSigner | undefined
  private fetchApiValue: FetchAPI | undefined
  private requestTimeoutMsValue: number | undefined
  private readonly middlewareValue: Middleware[] = []

  /** The API base URL, including the contract's `/api` base path. */
  baseUrl(baseUrl: string): this {
    this.baseUrlValue = baseUrl
    return this
  }

  /** `User-Agent` + `X-EP-Node-Id`. */
  identity(identity: ClientIdentity): this {
    this.identityValue = identity
    return this
  }

  /** `Authorization: ApiKey <key>`. Mutually exclusive with {@link jwtSigner} — whichever is called last wins. */
  apiKey(apiKey: string): this {
    this.apiKeyValue = normalizeApiKey(apiKey)
    this.jwtSignerValue = undefined
    return this
  }

  /** `Authorization: Bearer <jwt>`, minted fresh per request. Mutually exclusive with {@link apiKey}. */
  jwtSigner(signer: JwtSigner): this {
    this.jwtSignerValue = signer
    this.apiKeyValue = undefined
    return this
  }

  /** The `fetch` to use (advanced: a custom agent, proxies, recording). Defaults to the platform's. */
  fetchApi(fetchApi: FetchAPI): this {
    this.fetchApiValue = fetchApi
    return this
  }

  /**
   * Aborts a request that has not completed within this many milliseconds; pass undefined for no
   * limit, which is the default and the right setting for polling, rendering and large transfers.
   */
  requestTimeoutMs(timeoutMs: number | undefined): this {
    if (timeoutMs !== undefined && !(timeoutMs > 0)) {
      throw new RangeError('requestTimeoutMs must be positive')
    }
    this.requestTimeoutMsValue = timeoutMs
    return this
  }

  /** Adds middleware, run after the Epistola request middleware and before the problem-detail one. */
  middleware(...middleware: Middleware[]): this {
    this.middlewareValue.push(...middleware)
    return this
  }

  /**
   * Builds an {@link EpistolaClient} from the configuration so far. Safe to call more than once on
   * the same builder — each call reads the builder's current state into an independent client,
   * which is how one builder yields the two timeout profiles a long-running consumer needs.
   */
  build(): EpistolaClient {
    if (this.baseUrlValue === undefined) {
      throw new RangeError('baseUrl is required')
    }
    return new EpistolaClient({
      baseUrl: this.baseUrlValue,
      identity: this.identityValue,
      apiKey: this.apiKeyValue,
      jwtSigner: this.jwtSignerValue,
      fetchApi: this.fetchApiValue,
      requestTimeoutMs: this.requestTimeoutMsValue,
      middleware: [...this.middlewareValue],
    })
  }
}

function normalizeBaseUrl(baseUrl: string): string {
  let parsed: URL
  try {
    parsed = new URL(baseUrl)
  } catch (cause) {
    throw new RangeError(`baseUrl must be an absolute URL, got ${JSON.stringify(baseUrl)}`, { cause })
  }
  if (parsed.search !== '' || parsed.hash !== '') {
    throw new RangeError('baseUrl must not carry a query string or fragment')
  }
  return parsed.toString().replace(/\/+$/, '')
}

function normalizeApiKey(apiKey: string): string {
  const value = apiKey.trim()
  if (value === '') {
    throw new RangeError('apiKey must not be blank')
  }
  return value
}
