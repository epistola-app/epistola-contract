# Changelog — Epistola Node.js Client

All notable changes to the `@epistola.app/epistola-client` package are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). This package's
version tracks the Epistola contract version (`info.version` in the OpenAPI spec), so it releases in
lockstep with the other generated artifacts. The repository-level [CHANGELOG](../../../../CHANGELOG.md)
records contract changes; this file records changes to the hand-written library only.

## [Unreleased]

## [1.4.0] - 2026-09-30

### Changed

- A date property that is `null` in a response is read as `null` instead of `undefined`, matching
  its declared `Date | null` type. Code that checks such a field with `=== undefined` must also
  handle `null`.

## [1.3.1] - 2026-09-21

### Changed

- `JwtSigner` is documented as **experimental**: Epistola Suite may not implement self-signed JWT
  authentication yet. The README's quick start authenticates with an API key.

## [1.3.0] - 2026-09-21

### Added

- **Initial release:** a TypeScript client on the platform's `fetch`, generated with OpenAPI
  Generator (`typescript-fetch`), at feature parity with the Kotlin, Jakarta EE, .NET and Python
  clients. It has no runtime dependencies.
  - `EpistolaClient`: one entry point that assembles identity, API-key or JWT authentication, the
    `Accept` header and RFC 9457 problem parsing. Problem parsing is always installed.
  - `ClientIdentity`: the required `User-Agent` and `X-EP-Node-Id` headers, generated from
    `x-client-identity`.
  - `JwtSigner`: self-signed RS256 and ES256 JWT bearer authentication on `node:crypto`.
  - `ProblemDetailException`: extends the generated `ResponseError` with `typeSlug`, `errors`,
    `validationErrors` and a catch-all `extensions` map. `KnownProblemSlugs` is generated from
    `x-problem-types`.
  - `ResultCollector`: constant-memory NDJSON result streaming with gzip and, where Node supports
    it, zstd, detected from the stream. It polls adaptively, leaves a batch unacknowledged when the
    handler throws, and provides murmur3 partition routing.
  - Client-side JSON Schema validation (`TemplateSchemaValidator`, `ValidatingGenerationApi`) on
    Ajv, an optional peer dependency loaded on first use, and generated `validate<Model>` helpers.
  - `CONTRACT_OPERATIONS`, generated from the spec, so each request asks for exactly the media
    types its operation declares.
