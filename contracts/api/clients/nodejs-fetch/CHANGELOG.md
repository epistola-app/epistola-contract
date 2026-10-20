# Changelog — Epistola Node.js Client

All notable changes to the `@epistola.app/epistola-client` package are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). This package's
version tracks the Epistola contract version (`info.version` in the OpenAPI spec), so it releases in
lockstep with the other generated artifacts. The repository-level [CHANGELOG](../../../../CHANGELOG.md)
records contract changes; this file records changes to the hand-written library only.

## [Unreleased]

### Added

- `ProblemDetailException.missingDependencies` lists the releases a `release-dependencies-missing`
  problem (409, from `deployCatalogVersion`) reports cannot be had, each a `MissingDependency`
  (`registry`, `namespace`, `catalogKey`, `releaseVersion`, `reason`). `reason` is kept as the
  string the server sent, so a reason added later is not lost; entries missing a member are
  dropped, and the list is empty for any other problem. `isReleaseDependenciesMissingProblem` is
  true when it is not empty.
- `ProblemDetailException.unreleasedDependencies` lists the drafts of other catalogs a
  `release-dependencies-unreleased` problem (409, from `releaseCatalogDraft`) reports the released
  draft's dependency declarations name, each an `UnreleasedDependency` (`registry`, `namespace`,
  `catalogKey`, `versionId`, `draftName`); entries missing a member are dropped, and the list is
  empty for any other problem. `isReleaseDependenciesUnreleasedProblem` is true when it is not
  empty.
- The generated model validators check a member that references a plain string schema, such as
  `CatalogDependencyRequirement.releaseVersion` (`ReleaseVersion`), as if its constraints were
  written inline. Before, every referenced member was skipped.

### Changed

- **BREAKING:** a resource's address is its `key` (#84). The hand-written API follows the
  generated one: `TemplateDataValidator.validate`, `TemplateSchemaValidator.validate`,
  `ServerTemplateDataValidator.validate` and `SchemaCache.getOrLoad` / `evict` take
  `(tenantKey, registry, namespace, catalogKey, templateKey, …)`; `ServerValidationTarget` takes
  `variantKey` and `environmentKey` in place of `variantId` and `environmentId`; and
  `ResultCollector.builder().tenantId(…)` is now `.tenantKey(…)`. The generated request parameters
  and models carry the same renames (`tenantKey`, `templateKey`, …, and `key` in place of `slug` and
  of a create request's `id`).
- **BREAKING:** a catalog is addressed by registry, namespace and key. `TemplateDataValidator.validate`,
  `TemplateSchemaValidator.validate` and `ServerTemplateDataValidator.validate` take
  `(tenantId, registry, namespace, catalogKey, templateId, data)` in place of
  `(tenantId, catalogId, templateId, data)`, the registry in its path-segment form (`~` for the
  server's default registry, or named in full, such as `acme.com`) and the namespace a plain slug.
  `SchemaCache.getOrLoad` / `evict` are keyed the same way. `ValidatingGenerationApi` hands a
  pre-flighting validator the request's `registry`, or `~` when the request leaves it out or null.
- **BREAKING:** `ServerValidationTarget` takes a `versionRange` (a version range such as `1.4.0` or
  `^1.4.0`, sent as the request's `versionRange`) in place of `versionId`, matching contract 2.0's
  generation by release. It may be combined with `environmentKey`; without either, the catalog's
  latest release is checked.
- Results handed to a `ResultCollector` handler carry the template's catalog as `registry`,
  `namespace` and `catalogKey`, always set, next to `releaseVersion`, which is null for a result
  rendered from a draft.
- The README no longer mentions the `X-API-Key` header: the contract dropped it, and the client
  only ever sent `Authorization: ApiKey <key>`.

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
