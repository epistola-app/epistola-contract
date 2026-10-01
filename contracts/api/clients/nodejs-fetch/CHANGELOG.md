# Changelog — Epistola Node.js Client

All notable changes to the `@epistola.app/epistola-client` package are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). This package's
version tracks the Epistola contract version (`info.version` in the OpenAPI spec), so it releases in
lockstep with the other generated artifacts. The repository-level [CHANGELOG](../../../../CHANGELOG.md)
records contract changes; this file records changes to the hand-written library only.

## [Unreleased]

### Changed

- **Template data is validated by the server, not by a bundled schema compiler.**
  `TemplateSchemaValidator` and `ValidatingGenerationApi` now call `validateTemplateData` through a
  pluggable `TemplateDataValidator`, whose only shipped implementation is
  `ServerTemplateDataValidator`. `ajv` and `ajv-formats` are no longer peer dependencies, optional
  or otherwise, so the package has no optional dependencies at all. The verdict is the server's,
  which is the one that decides what renders.
- **`ValidationFailure.path` is now a JSON Pointer** (`/customer/email`) rather than a dotted key
  (`customer.name`), and a batch item's failures are prefixed `items[0]/customer/email` rather than
  `items[0].customer.name`. Code that split a path on `.` must read pointer segments instead. The
  format is pinned on `TemplateDataValidator`, so every implementation reports it the same way —
  previously each of the five clients reported a different one.
- `ValidatingGenerationApi` no longer pre-validates when the validator asks the server
  (`preflightsGeneration === false`), since the server checks the same data on submit; it translates
  the resulting `template-data-invalid` problem into `TemplateDataValidationException` instead. One
  request instead of two, or instead of one per batch item. A validator that answers in-process
  still pre-flights, batch aggregation included.
- `ProblemDetailException` gained `missingFields`, `invalidFields` and `isTemplateDataProblem`,
  typed views over the members contract 1.4.0 added. They were already reachable through
  `extensions`; this only saves the cast.

### Removed

- `AJV_INSTALL_HINT`, and the Ajv loader behind it. Nothing loads Ajv any more. The worked adapter
  in `test/validation/local/` shows how to validate in-process if you want to.

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
