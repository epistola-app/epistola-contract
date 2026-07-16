# Changelog — Epistola Python Client

All notable changes to the `epistola-client` PyPI package are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). This package's
version tracks the Epistola contract version (`info.version` in the OpenAPI spec), so it releases in
lockstep with the other generated artifacts; the repository-level [CHANGELOG](../CHANGELOG.md)
records contract/spec changes. This file records changes specific to the Python client library.

## [Unreleased]

### Added

- **Initial release** — a Python client for the Epistola API, generated from the OpenAPI contract
  with OpenAPI Generator (`python` / urllib3, pydantic v2 models), at feature parity with the Kotlin
  and .NET clients.
  - `ClientIdentity` — mandatory `User-Agent` / `X-EP-Node-Id` headers, built via a fluent builder.
  - `JwtSigner` — self-signed RSA / EC P-256 JWT bearer authentication, minting a fresh short-lived
    token per request.
  - `ProblemDetailException` and the opt-in problem-detail handler — RFC 9457 problem-detail error
    handling, with `KnownProblemSlugs` generated from the spec's `x-problem-types` registry.
  - `EpistolaClientBuilder` / `EpistolaApiClient` — compose identity, JWT, and problem-detail
    handling onto the generated `ApiClient` for the generated APIs. (No media-type handler is needed:
    the Python generator already emits the versioned `application/vnd.epistola.v1+json` content type.)
  - `ResultCollector` — NDJSON result streaming with constant memory, gzip (plus optional lz4/zstd),
    adaptive polling, and murmur3 partition-routing helpers.
  - Client-side JSON-Schema validation (`TemplateSchemaValidator`, `ValidatingGenerationApi`) and a
    generated `validate()` helper covering the models that carry schema constraints.
  - A `ProblemRegistryTest`-equivalent guard test keeping the hand-written problem-type base in sync
    with the value the build-time generator derives from the spec.
