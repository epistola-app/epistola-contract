# Changelog — Epistola Node.js Client

All notable changes to the `@epistola.app/epistola-client` npm package are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). This package's
version tracks the Epistola contract version (`info.version` in the OpenAPI spec), so it releases in
lockstep with the other generated artifacts; the repository-level [CHANGELOG](../../../../CHANGELOG.md)
records contract/spec changes. This file records changes specific to the Node.js client library.

## [Unreleased]

### Added

- **Initial release** — a Node.js client for the Epistola API, generated from the OpenAPI contract
  with OpenAPI Generator (`typescript-fetch`, on the platform's own `fetch`), at feature parity with
  the Kotlin, Jakarta EE, .NET and Python clients.
  - `EpistolaClient` — one entry point that assembles identity, API-key or self-signed-JWT
    authentication, the `Accept` header each operation is declared with, and RFC 9457 problem
    parsing into the `Configuration` every generated API class takes. Problem parsing is not opt-in:
    forgetting it fails silently, so it is always installed.
  - `ClientIdentity` — mandatory `User-Agent` / `X-EP-Node-Id` headers, built via a fluent builder.
    The header name and the `User-Agent` grammar are generated from the spec's `x-client-identity`
    extension, as the JVM modules do.
  - `JwtSigner` — self-signed RS256 / ES256 JWT bearer authentication on `node:crypto` alone, minting
    a fresh short-lived token per request. ES256 signatures are emitted in the raw `R || S` form JOSE
    requires rather than Node's default DER sequence.
  - `ProblemDetailException` — RFC 9457 problem-detail error handling, extending the generated
    `ResponseError`, with `typeSlug`, the `errors` / `validationErrors` extension members, and a
    catch-all `extensions` map for members the contract adds later. `KnownProblemSlugs` and the
    extension-member names are generated from the spec's `x-problem-types` registry.
  - `ResultCollector` — NDJSON result streaming with constant memory, gzip and (where Node's zlib has
    it) zstd decompression chosen by sniffing the stream rather than trusting `Content-Encoding`,
    adaptive polling floored at the minimum interval, sequence-based acknowledgement that leaves a
    batch unacknowledged when the handler throws, and murmur3 partition-routing helpers.
  - Client-side JSON Schema validation of template data (`TemplateSchemaValidator`,
    `ValidatingGenerationApi`, on Ajv) and generated `validateModel` / `validate<Model>` helpers
    covering every model that carries schema constraints.
  - `CONTRACT_OPERATIONS` — the method, path template and declared response media types of every
    operation, generated from the spec. The generated API classes set `Content-Type` but never
    `Accept`, and Node's `fetch` sends `*/*` by default, so the client asks for exactly what each
    operation is declared to return — the problem document included.
  - A problem-registry guard test keeping the hand-written problem-type base in sync with the value
    the build-time generator derives from the spec, and a driver for the cross-client conformance
    suite, which holds this client to the same wire behaviour as the other four.
