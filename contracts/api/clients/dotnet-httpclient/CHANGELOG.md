# Changelog — Epistola .NET Client

All notable changes to the `Epistola.Contract.Client` package are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). This package's
version tracks the Epistola contract version (`info.version` in the OpenAPI spec), so it releases in
lockstep with the other generated artifacts. The repository-level [CHANGELOG](../../../../CHANGELOG.md)
records contract changes; this file records changes to the hand-written library only.

## [Unreleased]

## [1.3.1] - 2026-09-21

### Changed

- `JwtSigner` is documented as **experimental**: Epistola Suite may not implement self-signed JWT
  authentication yet. The README's quick start authenticates with an API key.

## [1.2.0] - 2026-09-03

### Changed

- **Source-breaking:** `SchemaCache.GetOrLoad` and `TtlSchemaCache.Evict` take the catalog id, so custom cache implementations must be
  updated.

### Fixed

- `ResultCollector` could busy-loop after a burst of results or a server outage; the backoff is now
  floored at the minimum interval. **Anyone running a collector should upgrade.**
- The routing-key helper returned keys that routed to other nodes.
- The template schema cache ignored the catalog, so two catalogs holding the same template id
  shared one schema.
- Partition lookup divided by zero when the server reported no partitions.
- Partial updates sent `null` for every unset field, so a `PATCH` erased fields the caller never
  touched. The builder now installs a handler that omits unset top-level properties.
- Result collection dropped the base path of a base URL without a trailing slash, so polling went
  to `/tenants/…` instead of `/api/tenants/…`.

## [0.15.0] - 2026-07-28

### Changed

- **Breaking:** generated portable template models use the catalog contract's canonical names,
  without the `Dto` suffix.

## [0.14.0] - 2026-07-23

### Added

- `ApiKeyAuth` and `EpistolaHttpClientBuilder.ApiKey(...)` authenticate with
  `Authorization: ApiKey <key>`. The server still accepts the deprecated `X-API-Key`.

## [0.12.0] - 2026-07-17

### Added

- **Initial release:** a .NET 8 client generated with OpenAPI Generator (`csharp` / `HttpClient`),
  at feature parity with the Kotlin client.
  - `ClientIdentity`: the required `User-Agent` and `X-EP-Node-Id` headers.
  - `JwtSigner`: self-signed RSA and EC P-256 JWT bearer authentication.
  - `ProblemDetailException` and the opt-in `ProblemDetailHandler` for RFC 9457 errors, with
    `KnownProblemSlugs` generated from `x-problem-types`.
  - `EpistolaMediaTypeHandler`, which sends the versioned `application/vnd.epistola.v1+json` media
    type instead of the generator's `application/json`.
  - `EpistolaHttpClientBuilder`, which chains these handlers into an `HttpClient`.
  - `ResultCollector`: constant-memory NDJSON result streaming with gzip (optionally lz4 and zstd),
    adaptive polling and murmur3 partition routing.
  - Client-side JSON Schema validation (`TemplateSchemaValidator`, `ValidatingGenerationApi`) and
    generated `Validate()` extension methods.
