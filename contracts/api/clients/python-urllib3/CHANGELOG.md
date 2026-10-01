# Changelog — Epistola Python Client

All notable changes to the `epistola-client` package are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). This package's
version tracks the Epistola contract version (`info.version` in the OpenAPI spec), so it releases in
lockstep with the other generated artifacts. The repository-level [CHANGELOG](../../../../CHANGELOG.md)
records contract changes; this file records changes to the hand-written library only.

## [Unreleased]

### Changed

- **Template data is validated by the server, not by a bundled library.** `TemplateSchemaValidator`
  and `ValidatingGenerationApi` now call `validateTemplateData` through a pluggable
  `TemplateDataValidator` protocol, whose only shipped implementation is
  `ServerTemplateDataValidator`. **`jsonschema` is no longer a dependency**, so installing this
  client no longer installs a schema library. The verdict is the server's, which is the one that
  decides what renders.
- **`ValidationFailure.path` is now a JSON Pointer** (`/customer/email`) rather than a dotted key
  (`customer.name`), and a batch item's failures are prefixed `items[0]/customer/email` rather than
  `items[0].customer.name`. Code that split a path on `.` must read pointer segments instead. The
  format is pinned on `TemplateDataValidator`, so every implementation reports it the same way —
  previously each of the five clients reported a different one.
- `ValidatingGenerationApi` no longer pre-validates when the validator asks the server
  (`preflights_generation` is `False`), since the server checks the same data on submit; it
  translates the resulting `template-data-invalid` problem into `TemplateDataValidationError`
  instead. One request instead of two, or instead of one per batch item. A validator that answers
  in-process still pre-flights, batch aggregation included.
- `ProblemDetailException` gained `missing_fields`, `invalid_fields` and
  `is_template_data_problem`. `parse_problem` reads the two arrays contract 1.4.0 added; it had
  been discarding them.
- `TemplateSchemaValidator` and `ValidatingGenerationApi` take either a `TemplatesApi` or a
  `TemplateDataValidator`. The `cache=` parameter is gone, with `SchemaCache`/`TtlSchemaCache`.

### Removed

- `SchemaCache` and `TtlSchemaCache`. Caching a fetched schema is an in-process validator's
  concern; the reference adapter in `tests/validation/local/` shows one, about a hundred lines,
  ready to copy.


## [1.3.1] - 2026-09-21

### Changed

- `JwtSigner` is documented as **experimental**: Epistola Suite may not implement self-signed JWT
  authentication yet. The README's quick start authenticates with an API key.

## [1.3.0] - 2026-09-21

### Fixed

- Binary downloads (`download_document`, `download_image_content`) sent only
  `application/problem+json` in `Accept`, never the PDF or image they return. The client now
  accepts every media type an operation declares.

## [1.2.0] - 2026-09-03

### Changed

- **Source-breaking:** `SchemaCache.get_or_load` and `TtlSchemaCache.evict` take the catalog id, so custom cache implementations must be
  updated.

### Fixed

- `ResultCollector` could busy-loop after a burst of results or a server outage; the backoff is now
  floored at the minimum interval. **Anyone running a collector should upgrade.**
- The routing-key helper returned keys that routed to other nodes.
- The template schema cache ignored the catalog, so two catalogs holding the same template id
  shared one schema.
- Partition lookup divided by zero when the server reported no partitions.
- Only the first line of an uncompressed result batch was read, so batches were never
  acknowledged and were redelivered forever. **Anyone running a collector should upgrade.**
- Requests did not accept `application/problem+json`, so the problem document this client parses
  was never asked for.

## [0.15.0] - 2026-07-28

### Changed

- **Breaking:** generated portable template models use the catalog contract's canonical names,
  without the `_dto` suffix.

## [0.14.0] - 2026-07-23

### Added

- `EpistolaClientBuilder.api_key(...)` authenticates with `Authorization: ApiKey <key>`. The server
  still accepts the deprecated `X-API-Key`.

## [0.13.0] - 2026-07-22

### Added

- **Initial release:** a Python client generated with OpenAPI Generator (`python` / urllib3,
  pydantic v2 models), at feature parity with the Kotlin and .NET clients.
  - `ClientIdentity`: the required `User-Agent` and `X-EP-Node-Id` headers.
  - `JwtSigner`: self-signed RSA and EC P-256 JWT bearer authentication, minting a short-lived token
    per request.
  - `ProblemDetailException` and an opt-in handler for RFC 9457 errors, with `KnownProblemSlugs`
    generated from `x-problem-types`.
  - `EpistolaClientBuilder` and `EpistolaApiClient`, which add identity, JWT and problem handling
    to the generated `ApiClient`.
  - `ResultCollector`: constant-memory NDJSON result streaming with gzip (optionally lz4 and zstd),
    adaptive polling and murmur3 partition routing.
  - Client-side JSON Schema validation (`TemplateSchemaValidator`, `ValidatingGenerationApi`) and a
    generated `validate()` helper.
