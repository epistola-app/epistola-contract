# Changelog — Epistola .NET Client

All notable changes to the `Epistola.Contract.Client` NuGet package are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). This package's
version tracks the Epistola contract version (`info.version` in the OpenAPI spec), so it releases in
lockstep with the other generated artifacts; the repository-level [CHANGELOG](../CHANGELOG.md)
records contract/spec changes. This file records changes specific to the .NET client library.

## [Unreleased]

### Added

- `ApiKeyAuth` and `EpistolaHttpClientBuilder.ApiKey(...)` now support
  `Authorization: ApiKey <key>` authentication. `X-API-Key` remains a server-side
  compatibility path but is deprecated.
- **Initial release** — a .NET 8 client for the Epistola API, generated from the OpenAPI contract
  with OpenAPI Generator (`csharp` / `HttpClient`), at feature parity with the Kotlin client.
  - `ClientIdentity` — mandatory `User-Agent` / `X-EP-Node-Id` headers, built via a fluent builder.
  - `JwtSigner` — self-signed RSA / EC P-256 JWT bearer authentication.
  - `ProblemDetailException` and the opt-in `ProblemDetailHandler` — RFC 9457 problem-detail error
    handling, with `KnownProblemSlugs` generated from the spec's `x-problem-types` registry.
  - `EpistolaMediaTypeHandler` — sets the versioned `application/vnd.epistola.v1+json` request media
    type (the generator otherwise emits `application/json`).
  - `EpistolaHttpClientBuilder` — composes the identity / JWT / media-type / problem-detail
    `DelegatingHandler` chain into an `HttpClient` for the generated APIs.
  - `ResultCollector` — NDJSON result streaming with constant memory, gzip (plus optional lz4/zstd),
    adaptive polling, and murmur3 partition-routing helpers.
  - Client-side JSON-Schema validation (`TemplateSchemaValidator`, `ValidatingGenerationApi`) and
    generated `Validate()` extension methods enforcing model constraints.
