# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

- **BREAKING (REST API):** Replaced the API's duplicate portable template-model DTO schemas with
  direct references to the catalog contract. Generated API models now use the canonical names
  `TemplateDocument`, `Node`, `Slot`, `ThemeRef`, `PageSettings`, `Margins`, `DocumentStyles`, and
  `BlockStylePreset` instead of their `*Dto` variants. Catalog schemas, validation behavior,
  package exports, and published coordinates are unchanged.
- Kept the generated Kotlin API client and server models strongly typed when consuming the
  catalog schemas: `modelVersion` remains integer-backed, and inherited theme references no
  longer incorrectly require override-only fields.
- Reorganized the REST API as the self-contained `contracts/api` domain, co-locating its authored
  specification, documentation, build tooling, generated clients, server stubs, and mock server.
  The bundled specification is now generated at `contracts/api/build/openapi.yaml`; published
  artifact names and coordinates are unchanged.
- Reorganized the portable catalog as the self-contained `contracts/catalog` domain, co-locating
  its schemas, registries, fixtures, implementation, documentation, scripts, and build metadata
  without changing published coordinates, exports, resource paths, or wire behavior.
- Added a small executable, language-neutral catalog conformance suite with actual valid and
  invalid catalog files plus exact expected reports, and distinguished those fixtures from the
  exhaustive finding-code registries and Kotlin unit tests.
- Defined `CatalogArchive.paths` as regular readable files only. Explicit ZIP directory entries
  remain safety-checked and count toward archive limits, but no longer appear as content paths.
- Hardened catalog publication by validating release tags and source branches, failing on
  Maven/NuGet publication errors, preflighting and reusing the exact tested npm tarball,
  verifying the public Maven/npm artifacts from clean consumers, and documenting the renamed
  npm package's one-time trusted-publisher bootstrap.
- Published the Kotlin KDoc as a non-empty Dokka API documentation JAR and added release-artifact
  checks for Maven coordinates, catalog classes, registries, schemas, fixtures, sources, npm
  metadata, and package exports.
- Expanded Kotlin API documentation for archive ownership and safety, migration outcomes,
  canonical fingerprints, validation contexts and reports, resource models, deterministic
  ordering, and the boundary between portable catalog behavior and Suite-specific policy.
- Preserved the editor's established stencil-reference semantics by treating an omitted `isDraft`
  property as non-draft during portable validation. Explicit non-boolean values remain invalid,
  and portable catalog validation still rejects explicit draft references.
- **BREAKING:** Documented the catalog aggregate's explicit pre-1.0 changes and
  migration actions, including artifact coordinates, npm exports, the strict
  version-4 wire gate, canonical rich text, complete stencil identities,
  semantic validation, and the five-level nesting limit.
- Added portable, version-pinned stencil composition to the catalog specification. Stencil
  resources may contain references to other published stencil versions; standalone and
  whole-catalog validation now enforce exact-version resolution, owner-aware direct and
  transitive recursion checks, cross-resource cycles, and the shared five-level depth limit.
  Standalone validation preserves Suite-style draft identities for authoring contexts, while
  whole-catalog validation rejects draft references at the portable publication boundary.
  Suite authoring support remains a separate consumer capability.
- Expanded new catalog fingerprints to V2 so publisher, compatibility, include, and portable
  resource-manifest metadata participate in content identity. Whole-catalog validation continues
  to accept legacy V1 hashes, and the existing `fingerprint(catalog)` API retains its V1 result.
  Existing stored fingerprints, callers, and installations therefore remain valid; new
  fingerprints can opt into the corrected semantics through `currentFingerprint(catalog)`.
- **Validation tightening:** Whole-catalog validation now rejects stencil resources with invalid
  parameter schemas, template documents whose `modelVersion` is not `1`, and template resource
  themes that explicitly resolve as missing. Existing installed data is not modified, but a
  previously accepted invalid catalog can fail when it is re-imported or explicitly revalidated.
- Aligned the optional cross-catalog theme key across the Kotlin, JSON Schema, and TypeScript
  `ThemeRefOverride` contracts, made pull-request CI compile and verify the npm package after type
  generation, and corrected the wire-version documentation to match the strict version-4 gate.
- Limited template stencil nesting to five instances per ancestor chain, with a stable
  `STENCIL_NESTING_DEPTH_EXCEEDED` finding, a shared JVM/npm limit, and authoritative boundary
  fixtures.
- Removed the implementation-specific npm `/generated/*` entry point, exposed the public theme,
  component, and style types from the `@epistola.app/epistola-catalog` package root, and added a
  package-boundary check that prevents the internal path from becoming public again.
- Published the same versioned catalog conformance fixtures in both Maven and npm artifacts from
  one language-neutral fixture tree.
- Exposed standalone portable parameter-schema validation, corrected nested template finding paths,
  and allowed non-recursive nested stencil instances in templates with direct, transitive,
  placeholder-fill, sibling, and deterministic regression coverage.
- Moved the rich-text reference schemas into the catalog artifact and made catalog example-data
  validation use the full JSON Schema 2020-12 engine and Suite-compatible date-time semantics.
- Enforced the single current catalog wire model: an older `schemaVersion` is rejected unless a
  future explicit migration is implemented, even when its JSON happens to bind to the current model.
- Kept registry-declared static component slots optional when a template does not use them,
  matching existing Suite stencil documents.
- Enforced one canonical ProseMirror document object for text-node content; historical string and
  bare-array representations are intentionally not accepted.
- Made the public template finding-code set executable so tests guarantee that versioned fixtures
  cover every stable validation code.
- Included JavaScript source maps in the packed npm catalog so consumers do not receive dangling
  source-map references.
- Fixed portable template property validation for Jackson 3 tree values, declared directional
  border, directional spacing, font-style, and width styles, aligned table style applicability
  with canonical catalogs, and declared the `pageheader` examples' `hideOnFirstPage` property in
  the component registry.
### Changed

- **The portable catalog replaces the model artifact boundary.** The canonical JVM coordinate is
  now `app.epistola.contract:epistola-catalog`, the npm package is
  `@epistola.app/epistola-catalog`, and registry classpath resources live under
  `META-INF/epistola-catalog`. This is a clean pre-1.0 rename: consumers must migrate their
  dependency coordinates and resource paths; no duplicate-class compatibility artifact is
  published.

### Added

- **Portable template validation.** `TemplateValidator` now returns deterministic reports with
  stable codes, severities, paths, and messages for graph integrity, registry-driven component and
  style rules, placeholders, stencil references, parameter schemas/bindings, expressions, themes,
  style presets, and page headers. `TemplateValidationContext` provides a product-neutral resource
  resolution boundary, and versioned fixture metadata covers every finding code.
- **Safe deterministic catalog archives.** Streaming archive reader/writer APIs now enforce
  normalized paths, duplicate/symlink/encryption rejection, compressed and expanded size limits,
  entry-count and expansion-ratio limits, stable metadata/path ordering, and portable binary
  content providers without exposing filesystem or mapper types. Versioned fixture metadata and
  executable cases cover every stable archive finding code.
- **Portable catalog migration and canonical fingerprints.** `CatalogSchemaMigrator` centralizes
  wire-version gating and current-model binding behind stream-based APIs, while
  `CatalogCanonicalizer` produces stable per-resource hashes and an aggregate fingerprint from
  canonical catalog content rather than ZIP metadata or entry layout. Versioned golden fixtures
  publish the authoritative current wire representation and expected hashes.
- **Whole-catalog validation.** `CatalogValidator` and `ResourceValidator` now compose archive
  safety, migration, manifest/detail binding, portable template validation, reference closure,
  resource-specific checks, example-data validation, SemVer metadata, and canonical fingerprints
  into deterministic product-neutral reports. The validation policy exposes only portable limits
  and dependency resolution; Suite persistence, authorization, conflicts, and renderer checks
  remain outside the artifact.

## [0.14.0] - 2026-07-23

### Added

- **Authorization-header API-key authentication.** The contract now accepts static API keys through
  `Authorization: ApiKey <key>` while retaining deprecated `X-API-Key` support for existing
  integrations. The Kotlin, .NET, and Python client helpers can set the new header, and
  `api-key-auth-disabled` is a canonical Problem Details type for deployments that disable API-key
  authentication.
- **Suite-backed API parity for contract and draft lifecycle actions.** The contract now documents
  data-contract draft/update/publish/list endpoints, variant draft create/publish/discard actions,
  and code-list entry hide/show toggles that already exist in Epistola Suite.
- **Catalog-scoped Assets API.** The suite's image/asset capabilities are now represented in the
  public contract with asset list, upload, content download, and delete endpoints.
- **Catalog and stencil apply actions.** Authored catalog release, subscribed catalog upgrade apply,
  and stencil upgrade apply actions are now documented as public API endpoints.

### Fixed

- **Kotlin server artifact manifests now expose `Implementation-Version`.** The
  `server-kotlin-springboot4` JAR now stamps the contract version into its manifest so Epistola
  Suite can read the generated server-stub version and expose it through `/api/ping` instead of
  reporting `apiVersion: "unknown"`.

### Changed

- **Operation authorization metadata now matches Epistola Suite permissions.** The OpenAPI
  extensions now use `x-required-permissions`, `x-required-platform-roles`, or
  `x-required-authentication` instead of the legacy `reader`/`editor`/`generator`/`manager`
  role labels, aligning the contract with Suite's `Permission`, `TenantRole`, and `PlatformRole`
  model.
- **GitHub Actions Node runtime compatibility.** CI workflows now use Node 24-compatible action
  majors for artifact transfer, mise setup, npm setup, and Docker image publishing to avoid the
  Node 20 deprecation warnings emitted by GitHub-hosted runners.
- **Renovate GitHub Actions grouping.** Renovate now groups GitHub Actions updates separately so
  runtime/deprecation fixes can be reviewed independently from application dependency updates.

## [0.13.0] - 2026-07-22

### Added

- **Shared agent release skill.** The release workflow now lives under `.agents/skills/release`
  as an agent-neutral source of truth, with Claude and Codex adapters pointing at it and
  `AGENTS.md` documenting shared skill discovery for future agents.
- **Editor component vocabulary in `epistola-model`.** The model artifact now ships the static editor
  component registry and style registry as a typed TypeScript facade, raw npm JSON exports, and Maven
  classpath resources, with a lint guard that validates component examples, child-type references, and
  style-key references. This makes `epistola-contract` the source for the editor model vocabulary
  instead of relying on suite-local registry dumps.
- **Editor model registry documentation.** The registry split is documented for TypeScript, JVM, and
  `epistola-suite` consumers, and model schema type generation now lives in a dedicated script instead
  of a long package script.
- **Stronger editor registry validation.** Component examples are now required by schema and the
  registry guard validates example node/slot references, child rules, style registry versioning, and
  default style keys.

### Breaking Changes

- **Editor component `parameters` metadata is now explicit.** The registry no longer uses
  `parameters: null` to mean dynamic per-node parameters. Dynamic components now declare
  `parameters: { "kind": "dynamic" }`; static parameter schemas use
  `parameters: { "kind": "static", "schema": { ... } }`; missing `parameters` means no parameter
  support.

### Fixed

- **Python client CI generation.** `client-python-urllib3/generate.sh` now runs its
  derived-source generator without asking `uv` to install the package first, avoiding the circular
  hatchling dynamic-version failure where `contract_version.py` had to exist before it could be
  generated.
- **Python client packaging checks.** Pull request CI now builds the Python wheel and source
  distribution after the test suite, so packaging regressions are caught before release or snapshot
  publishing.
- **Python snapshot version stamping.** The snapshot workflow now stamps the Python client to the
  same PEP 440 `dev` version before test installation as it uses before publishing, so TestPyPI
  snapshot runs no longer fail on `*-SNAPSHOT` package metadata.

### Changed

- **Python trusted-publisher staging.** The release documentation now records that PyPI/TestPyPI
  trusted publishers should temporarily be configured under Sander de Groot's personal PyPI account
  while the Epistola organization approval is pending.

## [0.12.0] - 2026-07-17

### Added

- **.NET (C#) client library (`Epistola.Contract.Client`).** A new `client-dotnet-httpclient/` module generates a full .NET 8 client from the same bundled OpenAPI spec using OpenAPI Generator (`csharp` / `HttpClient`), at full feature parity with the Kotlin client and consumable by any modern .NET project via NuGet. See [.NET client changelog](contracts/api/clients/dotnet-httpclient/CHANGELOG.md) for the client's feature list and ongoing history. Wired into the `Makefile` (`make build-dotnet`) and the build/snapshot/feature-snapshot/release workflows; releases publish to NuGet.org via OIDC trusted publishing (no stored API key, mirroring the npm OIDC publish), while snapshots and feature snapshots publish to GitHub Packages (NuGet.org has no transient snapshot feed). Each release also attaches a CycloneDX SBOM (`epistola-dotnet-client-sbom.json`) for the .NET client's dependency closure (`make sbom-dotnet` locally). Requires `dotnet` in `.mise.toml` and a NuGet.org trusted-publisher policy for the `release.yml` workflow.
- **`NodeDto.props` documents the stencil node's identity props** — `stencilId`, `catalogKey`, `version`, `isDraft` — alongside the previously documented parameter wiring (`parameterBindings`, `parameterSchemaSnapshot`, `paramsAlias`). Without these there was no documented way to state *which* stencil a `stencil` node embeds.
- **`pagefooter` page-decoration convention documented** in `TemplateDocumentDto`, next to the existing `pageheader` rules: renders at the bottom of every page, at most one per document, placed at the document root, with `height` and `hideOnFirstPage` props. Unlike `pageheader` (server-validated), the max-1 rule is enforced by the suite's editor only; the renderer uses the first `pagefooter` and ignores any others.

### Fixed

- **Template-model examples now match what the suite's renderer actually accepts** (#18). Every `TemplateDocumentDto` example previously showed a `text` node's `props.content` as a markdown-ish string (`"Invoice {{invoiceNumber}}"`); the renderer expects a rich-text document *object* (ProseMirror JSON: `doc` → `paragraph`/`heading` → `text` runs / inline `expression` nodes / `hardBreak`), so a document built from the old examples rendered nothing. The examples in `template-model.yaml`, `versions.yaml`, and `stencils.yaml` are rewritten to the real shape, and `NodeDto.props` now documents it. Two more example fixes in the same sweep: `image` nodes reference an uploaded asset via `assetId` (+ optional `catalogKey`), not a `src` URL, and `columns` nodes are sized with `columnSizes` (relative weights) + `gap`, not `columnCount`.

### Breaking Changes

- **Variant `title` is now required.** `CreateVariantRequest.title` and `UpdateVariantRequest.title` change from optional, nullable strings to **required, non-nullable** `string`s (`minLength: 1`, `maxLength: 100`). A client must now send a non-blank title when creating or updating a variant. This makes the contract factual: the server already rejects a missing or blank title with `400 problem+json` (field `title`). (epistola-suite #631)

### Changed

- **`VariantDto.title` and `VariantSummaryDto.title` are now required and non-nullable.** The server stores `template_variants.title` as `NOT NULL`, so every variant response carries a non-blank title (`minLength: 1`, `maxLength: 100`). Additive for consumers — a stronger guarantee, not a breaking change.

- **Python client library (`epistola-client`).** A new `client-python-urllib3/` module generates a full Python client from the same bundled OpenAPI spec using OpenAPI Generator (`python` / urllib3, pydantic v2 models), at full feature parity with the Kotlin and .NET clients and consumable by any Python 3.9+ project via pip. It follows the same three-part structure — stock generated code, hand-written glue (identity headers, self-signed JWT auth, RFC 9457 problem-detail handling, NDJSON result collection with murmur3 partition routing, client-side JSON-Schema validation), and build-time derived sources (`contract_version`, `known_problem_slugs`, `model_validation`) generated from the spec's `info.version`, `x-problem-types` registry, and schema constraints. See [Python client changelog](contracts/api/clients/python-urllib3/CHANGELOG.md) for the client's feature list and ongoing history. Wired into the `Makefile` (`make build-python`) and the build/snapshot/release workflows; releases publish to PyPI via OIDC trusted publishing (no stored token, mirroring the npm/NuGet OIDC publishes), while mainline snapshots publish to TestPyPI (also via OIDC — GitHub Packages has no PyPI registry and pypi.org is reserved for releases). Feature branches build/install locally (`make publish-local`) rather than publishing. Requires `python` and `uv` in `.mise.toml` and trusted-publisher policies on pypi.org (for `release.yml`) and test.pypi.org (for `snapshot.yml`).

## [0.11.0] - 2026-07-10

### Added

- **`sort` and `direction` query parameters on the paginated list endpoints.** Each paginated list `GET` (templates, catalogs, tenants, environments, themes, fonts, attributes, code lists, variants, versions, stencils, stencil versions, stencil usages) now accepts `sort` and `direction`, both defined as shared components in `spec/components/parameters/sorting.yaml`. `sort` (`#/Sort`) is a free-form `string`: the set of sortable fields differs per resource, so the server is the authority on which values it accepts and on the per-resource default when `sort` is omitted; an unsupported value is rejected with `400`. `direction` (`#/Direction`) is an `enum` of `asc`/`desc` defaulting to `desc` (uniform across every endpoint; it applies once a `sort` field is selected), and an unsupported value is likewise rejected with `400`. Each of these list `GET`s documents a `400` response. The non-paginated list endpoints (`listCodeListEntries`, which has its own fixed `sortOrder`/`code` ordering, and `listVariantActivations`, a bounded per-environment set), the document-generation paths (`listGenerationJobs`, `listDocuments`), and `listConsumers` (which does not sort server-side) are intentionally left unchanged. Additive and backward-compatible — existing callers keep the previous ordering, and the response shapes are unchanged.

## [0.10.0] - 2026-07-03

### Breaking Changes

- **Pagination metadata moved under a nested `page` object.** Every collection response now carries pagination under a shared `page` object (`spec/components/schemas/common.yaml#/PageMeta`: `number`, `size`, `totalElements`, `totalPages`), matching Spring's `PagedModel` serialization, and every list `GET` accepts the shared `page`/`size` query parameters. Previously only 4 of ~17 list responses were paginated, with the fields flat alongside `items`. The four already-paginated endpoints (tenants, consumers, generation jobs, documents) **break**: their top-level `page`/`size`/`totalElements`/`totalPages` fields move into the `page` object, and the page-index field is renamed `page` → `page.number` (the request query parameter stays `page`). The other ~13 list endpoints gain the `page` object additively. Bounded sub-resource lists (variant activations, stencil upgrade previews) stay unpaginated.

  _Migration:_ read `response.page.number` / `response.page.totalElements` / etc. instead of the top-level fields on the four affected list endpoints; no request-side change (query params are unchanged). New list endpoints follow the same nested shape.

### Added

- **Consistent, generated pagination across all list endpoints** — see the breaking-change note above. The shared `PageMeta` schema and `page`/`size` parameters (`spec/components/parameters/pagination.yaml`) are referenced by every list endpoint via a plain property `$ref`, so the envelope is defined once and generated models carry a shared `page: PageMeta` field.
- **Machine-readable problem-type registry (`x-problem-types`).** The canonical problem `type` slugs now live in a top-level `x-problem-types` extension in `epistola-api.yaml` (base URI + slug/status/schema/description per type), fixing live drift where the API description's own table was missing `data-model-validation-error` (422). The prose table in the API description is replaced by a pointer to [error registry](contracts/api/docs/error-types.md); a new `scripts/check-error-registry.sh` (run by `make lint` and CI) fails when the docs table and the spec registry disagree.
- **Client `KnownProblemSlugs` is now generated from the spec.** A new `generateProblemSlugs` Gradle task emits the constants from the bundled spec's `x-problem-types`, so the client can no longer drift from the registry; new `ProblemRegistryTest` guard tests in both Kotlin modules assert the remaining hand-written pieces (`TYPE_BASE`, the server's new `ProblemDetails.KnownSlugs` constants) agree with the spec.
- **`BadRequestError` reusable response.** The `bad-request` problem type now has a shared response component in `problem-responses.yaml` like the other seven types; the two operations that previously declared it inline (catalog import, code-list refresh) reference it.
- **Machine-readable security-scheme deprecation.** `apiKeyAuth` carries an `x-deprecated: true` vendor extension alongside its existing prose deprecation, so tooling can detect it (OpenAPI has no native `deprecated` flag for security schemes).
- **Media-type allowlist guard.** A new `scripts/check-media-types.sh` (run by `make lint` and CI) fails when a media type outside the intended set (`application/vnd.epistola.v1+json`, `+ndjson`, `problem+json`, `application/pdf`, `multipart/form-data`) appears in the spec — the closest enforceable substitute for the fact that OpenAPI cannot `$ref` a content-type key.

### Changed

- **Server no longer generates a `DataModelValidationProblemDetail` DTO.** The schema is now mapped to Spring's native `org.springframework.http.ProblemDetail` (like `ProblemDetail`/`ValidationProblemDetail` already were), matching what the hand-written `ProblemDetails.dataModelValidation(...)` helper actually returns; the orphaned allOf model is suppressed via `.openapi-generator-ignore`.
- **`oasdiff` now flattens `allOf` and shared parameters before diffing.** `make breaking` and the CI breaking-change check pass `--flatten-allof --flatten-params`, so the remaining `allOf` compositions (the problem-detail schemas) and path-level parameters are understood correctly instead of being misreported as removed properties.
- **Client-identity headers are no longer modeled as unused components.** The unreferenced `UserAgent`/`NodeId` parameter components were removed; the required `User-Agent` / `X-EP-Node-Id` headers remain documented normatively in the API description (the generated clients set them automatically) rather than added to all 86 operation signatures.
- **Version-agnostic `User-Agent` examples.** The stale `epistola-contract/0.3.0` example (the spec is at 0.9.0) is now `epistola-contract/x.y.z` in the API description, `consumers.yaml`, and both module READMEs, so it cannot drift again.
- **Pinned spec tooling.** `@redocly/cli` (2.36.0) and `@stoplight/prism-cli` (5.15.11) are now exact-pinned in `tools/package.json` with a committed `pnpm-lock.yaml`; the Makefile and all CI workflows use the pinned binaries instead of unpinned `npx @redocly/cli` (which pulled latest on every run). The CI breaking-change check now runs the mise-pinned `oasdiff` binary (same version as local `make breaking`) instead of an untagged `tufin/oasdiff` docker image, and all CI jobs use the mise-pinned Node 24 (previously Node 22 was hardcoded in two workflows and the mock-server image).
- **Single spec-version parser.** A new `scripts/spec-version.sh` is the one place that parses `info.version` out of `epistola-api.yaml`; the Makefile `release` target, the `calculate-version` action, and the docs/mock-server workflows all use it (previously the same grep/sed pipeline was copy-pasted in five places).
- **One shared Gradle version catalog.** The three Gradle builds now import a single root `gradle/libs.versions.toml` (module-local catalogs removed); shared versions (Kotlin, ktlint, kover, OpenAPI Generator, vanniktech maven-publish, Java toolchain) are declared once, and the deliberately divergent lines are explicit aliases (`spring-boot3`/`jackson2` for the client vs `spring-boot4`/`jackson3` for the server). The Kotlin `Regex` that derived the artifact version from the spec, previously duplicated in the client and server build files, moved to a shared `gradle/contract-version.gradle.kts`.
- **Fragile codegen post-processing now fails loudly.** The server's generated-`produces` rewrite throws (instead of warning) when an OpenAPI Generator upgrade changes the emitted string and the rewrite stops matching, and the client's `generateValidation` task throws if it produces no validators instead of silently writing nothing.

## [0.9.0] - 2026-07-03

### Added

- **`parameterSchema` on stencil-version API schemas.** `StencilVersionDto` (response) and the `CreateStencilRequest`, `CreateStencilVersionRequest`, and `UpdateStencilDraftRequest` request bodies now carry an optional `parameterSchema` object — the stencil version's typed input parameters as a JSON Schema (`{ type, properties, required }`), the same value carried on the catalog `StencilResource.parameterSchema` and stored on the stencil version. Optional and additive: omit or send null to declare no parameters. The `NodeDto.props` description now documents the consumer-side stencil-node prop keys (`parameterBindings`, `parameterSchemaSnapshot`, `paramsAlias`) that bind those parameters within a template.
- **`data-model-validation-error` problem type (422).** A new canonical problem `type` (`https://epistola.app/errors/data-model-validation-error`) documents semantic-validation failures where supplied data examples do not validate against a template's data model. It carries a `validationErrors` extension member (example name → failures) and is registered in [error registry](contracts/api/docs/error-types.md) with a reusable `UnprocessableEntityError` response component.
- **Typed error handling for `data-model-validation-error` in both modules.** The hand-written error helpers now cover the new problem type: the Kotlin client adds `KnownProblemSlugs.DATA_MODEL_VALIDATION_ERROR`, surfaces the per-example failures via `ProblemDetailException.validationErrors` (a `Map<String, List<DataModelValidationError>>`) and an `isDataModelValidationProblem` flag; the Kotlin server adds `ProblemDetails.dataModelValidation(...)` and a `VALIDATION_ERRORS_PROPERTY` constant to build the response.
- **`updateTemplate` now documents `409` and `422` responses.** `PATCH /tenants/{tenantId}/catalogs/{catalogId}/templates/{templateId}` can return `409` (`conflict`) when a backwards-incompatible data-model change is not confirmed, and `422` (`data-model-validation-error`) when data examples are incompatible with the data model.

### Changed

- **`UpdateTemplateRequest.forceUpdate` description corrected.** The flag confirms publishing a backwards-incompatible (breaking) data-model change (auto-upgrading dependent template versions); it does **not** bypass data-example validation — an incompatible example is still rejected with `422` even when `forceUpdate` is true. (Previously described as "save schema even if data examples don't match.")

## [0.8.0] - 2026-06-24

### Added

- **`StencilResource.parameterSchema: Map<String, Any?>?` (optional).** Carries the stencil version's typed input parameters (a JSON Schema object) through catalog export/import so a parametrised stencil keeps its schema on round-trip and templates binding to those parameters stay bound on the receiving side. Optional and additive — a ZIP from a stencil without declared parameters (or a pre-this-version exporter) simply omits it and an older consumer ignores it, so no catalog `schemaVersion` bump is required.

## [0.7.0] - 2026-06-05

### Added

- **Kotlin client: opt-in typed error handling.** A new `app.epistola.client.error` package adds `RestClient.Builder.installProblemDetailHandler()`, which parses `application/problem+json` responses and throws a typed `ProblemDetailException` (extends `RestClientResponseException`, so existing catch sites keep working). The exception exposes the problem `type`, `typeSlug`, `title`, `problemStatus`, `detail`, and field-level `errors`; switch on `typeSlug` (see `KnownProblemSlugs`). Non-problem error bodies still surface as plain `RestClientResponseException`.
- **Error-type registry.** A new [error registry](contracts/api/docs/error-types.md) documents the canonical problem `type` slugs (`validation-error`, `bad-request`, `unauthorized`, `forbidden`, `not-found`, `conflict`, `rate-limited`), their status codes, shapes, and meaning; the same table is summarized in the API description. Both module READMEs link to it.
- **`bad-request` problem type.** Application-level `400` responses that are not field-level validation failures (an invalid catalog ZIP, refreshing a non-URL-sourced code list) now declare the `https://epistola.app/errors/bad-request` `type` in their contract example, so clients can switch on it (`KnownProblemSlugs.BAD_REQUEST`) instead of seeing an undocumented `type`.

### Changed

- **Error responses now reference shared problem-response components per `type`.** Each error response points at a reusable component (`NotFoundError`, `ValidationFailedError`, `ConflictError`, `RateLimitedError`, plus the existing `UnauthorizedError`/`ForbiddenError`) carrying an example with the concrete `type` URI, so the contract states which problem type each operation returns. `429` responses now consistently advertise the `Retry-After` header.

- **Error responses now use RFC 9457 Problem Details.** Error response media types are `application/problem+json`; problem bodies include `type`, `title`, `status`, `detail`, and `instance`. The problem **`type` URI is the machine-readable discriminator** clients switch on (`https://epistola.app/errors/{slug}`, or `about:blank` for framework-level errors) — there is no separate `code` member. Validation errors use the `ValidationProblemDetail` shape with top-level `errors`. (RFC 9457 obsoletes RFC 7807.)
- **Generated Spring server stubs reuse Spring's native `org.springframework.http.ProblemDetail`** instead of a generated DTO — it serializes to `application/problem+json` via `ResponseEntityExceptionHandler` out of the box. The server module adds an opt-in `app.epistola.api.error.ProblemDetails` helper for building problem bodies (`type`/`errors`) consistent with the contract, and the README documents a reference `@RestControllerAdvice`.
- **Removed the deprecated pre-Problem-Details error schemas** (`ErrorResponse`, `ValidationErrorResponse`, `FieldError`); they were unreferenced after the Problem Details migration.
- **Problem `instance` values are documented as URI references.** Runtime responses may use relative `/api/...` paths with query strings, so the schema now uses `format: uri-reference` instead of requiring absolute URIs.

- **Generated Spring server stubs preserve the success media type for bodyless responses.** Post-generation normalization keeps `application/vnd.epistola.v1+json` alongside `application/problem+json` for generated mappings whose success response has no body.

## [0.6.0] - 2026-05-21

### Changed

- **`StencilResource.version: Int` is now required.** The exported wire format carries the published version number of each stencil so that templates pinning a specific version survive a catalog round-trip. No default value: ZIPs produced by pre-`0.6.0` exporters lack the field and must be re-exported before they can be imported. **BREAKING** for any consumer producing/consuming `StencilResource` directly.

## [0.5.3] - 2026-05-19

### Added

- **`importCatalog`: optional `authoredMode` form field (`MERGE` | `REPLACE`,
  default `MERGE`).** For a ZIP that targets an existing **AUTHORED** catalog,
  `MERGE` upserts the ZIP's resources and keeps local-only resources;
  `REPLACE` additionally deletes local-only resources (conflict-checked before
  any mutation). Release state is never changed. Ignored for a newly-created
  or SUBSCRIBED catalog. Brings the REST surface to parity with the web UI's
  Merge/Replace choice (it previously always merged).
- **`ImportCatalogResponse.aborted` (boolean, required).** `true` when a
  SUBSCRIBED-catalog ZIP upgrade was aborted (a resource install failed, so
  nothing was pruned and the installed-release pointers were not advanced —
  the catalog is unchanged and a re-import is a meaningful retry); `false`
  when the import finalized (any `failed` resources are permanent for that
  import and the catalog moved forward). Lets API clients distinguish
  retry-safe from escalate, which `{installed,updated,failed,total}` alone
  could not. Always `false` for AUTHORED imports.

## [0.5.2] - 2026-05-19

### Added

- **REST: `GET /tenants/{tenantId}/catalogs/{catalogId}/upgrade-preview`
  (`previewCatalogUpgrade`) + `CatalogUpgradeDiff` schema.** Read-only
  source-vs-source preview of upgrading a SUBSCRIBED catalog to its source's
  latest release: `previousVersion` / `newVersion` / `upgradeAvailable`,
  `added` / `removed` / `changed` / `unchanged` (each `"type/slug"`),
  `conflicts` (cross-catalog references that would block removals) and
  `blockedByConflicts`. The upgrade *action* is intentionally not exposed over
  REST — upgrades are applied through the UI (mirrors the release-action
  decision); this is read parity only. Spec version `0.5.1` → `0.5.2`.

## [0.5.1] - 2026-05-18

### Added

- **Catalog protocol: `ReleaseInfo.fingerprint`.** Optional lowercase hex
  SHA-256 of a catalog's canonical content (deterministic, order-independent,
  excludes volatile fields). Lets consumers detect that catalog content
  actually changed independently of the `version` label, enabling
  content-based upgrade/drift detection alongside author-controlled SemVer.
  Nullable — catalogs produced before fingerprinting read unchanged.
- **REST `CatalogDto`: `releasedVersion` and `fingerprint`.** Read-only
  exposure of a catalog's current version label (latest released SemVer for
  AUTHORED, installed version for SUBSCRIBED) and content fingerprint.

### Reserved

- **`DependencyRef` versioning (Phase 3, not yet implemented).** KDoc reserves
  a future optional `versionRange` on the catalog-scoped `DependencyRef`
  subtypes for catalog-level SemVer dependency constraints. No wire change
  yet; documented so consumers expect it.

## [0.5.0] - 2026-05-17

### Added

- **REST API: read-only font endpoints.** `GET /tenants/{tenantId}/catalogs/{catalogId}/fonts` (list) and `.../fonts/{fontSlug}` (get) — `FontDto` with `slug`/`name`/`kind`/`catalog`/`catalogType`/`readOnly`/`variants` (each face `{ weight, italic }`) + timestamps. Read-only by design, mirroring assets: font families and binaries are managed via the UI and catalog exchange, never created/updated/deleted over REST (write access deferred, may be revisited). New `spec/paths/fonts.yaml` + `spec/components/schemas/fonts.yaml`, wired into `epistola-api.yaml` with a `Fonts` tag. This documents the font REST surface the suite already ships (previously hand-written with no contract coverage).
- **Catalog protocol: `FontResource`, `FontVariantEntry`, `DependencyRef.Font`, and `FontRef`.** Catalogs can now distribute font families. A font family is a thin grouping over its font-face binaries; each face rides the catalog as an ordinary `AssetResource`, referenced from `FontResource.variants[].assetSlug` (the `FontResource` carries no binary). A face is identified by CSS-style numeric `weight` (1–1000) + `italic` (not a fixed four named variants); every face is a static binary (variable fonts are instanced into static faces at upload, never represented on the wire). Bundled system fonts are classpath-backed locally and never exported, so the wire format only ever describes catalog-authored (asset-backed) fonts. `FontRef { catalogKey?, slug }` mirrors `CodeListBindingRef` and is the shape stored under the `fontFamily` key in `documentStyles` / block-style presets / inline node styles. The discriminator `"font"` joins `theme`/`stencil`/`asset`/`codeList` in both `ResourceDetail` and `DependencyRef`. Manifest `schemaVersion` only needs bumping when a catalog actually declares fonts; existing catalogs read unchanged.

## [0.4.0] - 2026-05-12

### Added

- **Catalog protocol: `CodeListResource`, `DependencyRef.CodeList`, and `AttributeResource.codeListBinding`.** Catalogs can now distribute code lists alongside attributes, and an attribute can reference a code list either inside its own catalog (`codeListBinding.catalogKey == null`) or in another catalog of the same tenant (`codeListBinding.catalogKey = "system"`). Manifest `schemaVersion` bumps to `3` only when these features are used; older v2 catalogs read unchanged. The discriminator `"codeList"` joins `theme`/`stencil`/`asset` in `DependencyRef`.
- **REST API: code-list CRUD endpoints.** New surface at `/tenants/{tenantId}/catalogs/{catalogId}/code-lists/...` — list, get, create, update, delete, refresh-from-source, and list-entries. SUBSCRIBED-catalog code lists are flagged `readOnly: true` and return 409 on writes. See `spec/paths/code-lists.yaml` and `spec/components/schemas/code-lists.yaml`.
- **REST API: `AttributeDto` grows catalog + read-only + constraint fields.** Now carries `catalog`, `displayName`, `allowedValues`, `codeListBinding`, `catalogType` (AUTHORED/SUBSCRIBED), and `readOnly`. `CreateAttributeRequest` + `UpdateAttributeRequest` accept the same constraint shapes (inline values / code-list binding) that the UI already supports. PATCH/DELETE on SUBSCRIBED-catalog attributes return 409.
- **REST API: `VariantSelectionAttribute` carries optional `catalog`.** Lets clients write `{ catalog: "system", key: "locale", value: "en-US" }` rather than relying on the dotted-form (`"system.locale"`) or bare-slug (legacy, tenant-wide lookup) fallbacks. All three forms remain supported; the explicit `catalog` field is the recommended shape.

## [0.3.0] - 2026-05-05

### Added
- **`ResultCollector.kick()` + tunable backoff** — public API on the polling client. Producer-side hint that a result is expected soon: when the collector has backed off into idle mode, `kick()` resets the next-poll wait to `kickInterval` (new builder field, default 3s) instead of waiting out the full backoff. Threshold-guarded so a kick during active polling is a no-op. Implementation replaces `Thread.sleep` with a wakeable `LinkedBlockingQueue` so `kick()` can interrupt an in-progress wait. Also adds `backoffMultiplier` (new builder field, default 3.0; previously hard-coded to 2.0) — gives the sequence 1s → 3s → 9s → 27s → 30s (capped at `maxInterval`), reaching idle faster which reduces poll volume now that the kick is the safety net for fast resumption. Both fields are backward-compatible additions; existing callers keep working.
- **Consumer onboarding** — Full consumer lifecycle with two registration paths: self-service via `POST /consumers/register` (with public key for self-signed JWT auth) or auto-registration from OAuth. Admin approval (`POST /consumers/{id}/approve`) sets allowed tenants, roles, and optional expiry. Includes reject, update, delete, and public key rotation endpoints.
- **Self-signed JWT authentication** — Applications without an IdP can authenticate by signing short-lived JWTs with a registered private key. Includes replay protection via `jti` nonce and `exp` claims.
- **Permissions managed in Epistola** — Allowed tenants, roles, and expiry are set in the consumer record, not JWT claims. Single source of truth for authorization.
- **Ping metadata** — Extend `POST /ping` request body with optional `name`, `description`, and `contact` fields for application self-description.
- **JwtSigner (client)** — Utility for creating and signing short-lived JWTs for self-signed JWT authentication. Builder pattern with RSA/EC key support and a Spring `ClientHttpRequestInterceptor` for automatic Bearer token injection.
- **Generation result collection** — `POST /tenants/{tenantId}/generation/collect` streams completed/failed generation results as compressed NDJSON. Node-affinity with failover: results go to the node that requested them first, orphaned results from dead nodes are redistributed to active nodes. Supports compression negotiation (lz4, zstd, gzip) and adaptive polling via `hasMore` flag.
- **ConsumerResolver (server)** — Extracts consumer identity from JWT claims (`client_id`, `azp`, or `iss`). Works for both OAuth and self-signed JWT consumers.
- **Ping/Pong endpoint** — `POST /ping` for bidirectional health checking and metadata exchange. Unauthenticated requests receive basic health status; authenticated requests also get server version, API spec version, and node identity.
- **Client identity headers** — two required headers on all requests: `User-Agent` (must start with `epistola-contract/{version}`, additional product tokens for the software stack) and `X-EP-Node-Id` (pod name, container ID, or hostname).
- **ClientIdentity (client)** — builder class for managing `User-Agent` and `X-EP-Node-Id` headers with key/value product registration. Creates a `ClientHttpRequestInterceptor` for Spring RestClient. Contract version is baked in automatically at build time.
- **ClientInfo (server)** — parser for extracting client identity from incoming requests. Provides `contractVersion`, `nodeId`, and `productVersion(name)` for easy access to any product in the software stack.

### Changed
- **API version bumped to 0.3.0** — new System endpoint group for ping/pong, client identity headers
- **Auth model expanded** — `ConsumerDto.authMethod` is now an enum of `[oauth, self-signed-jwt, api-key]`. `oauth` and `self-signed-jwt` are the registration paths exposed by the new Consumer Management API; `api-key` covers the existing long-lived `X-API-Key` model (provisioned out of band by tenant managers, not a self-service flow). All authorization (tenants, roles, granted permissions) is managed in Epistola's consumer record across all three auth methods, not via JWT claims. The contract surface for `X-API-Key` is unchanged and continues to work; suite-side implementation of the JWT paths is a follow-on.
- **Release process** — `make release` now updates `info.version` in `epistola-api.yaml` to the full release version before creating the GitHub Release, ensuring the spec always reflects the exact artifact version

## [0.2.7] - 2026-05-05

### Changed
- **`epistola-model` Margins fields optional** — the `Margins` JSON Schema in `epistola-model` no longer requires `top`, `right`, `bottom`, and `left` (removed from `required`). Generated Kotlin (`Long? = null`) and TypeScript (`?: number`) types now allow these fields to be omitted, matching the relaxed `MarginsDto` contract introduced in v0.2.6. Wire format still rejects explicit `null` — fields must either be omitted or be a non-negative integer.
- **`epistola-model` PageSettings.margins optional** — the `PageSettings` JSON Schema no longer requires `margins`. Generated TypeScript declares `margins?: Margins`; the manually-defined Kotlin `PageSettings` now uses `val margins: Margins? = null` instead of defaulting to `Margins(20, 20, 20, 20)`, so callers can distinguish "no margins specified" (cascade) from explicit margins. The OpenAPI `PageSettingsDto.margins` was already optional; this brings the JSON Schema in line.
- **Mock server CI** — multi-arch Docker builds now run on native runners (`ubuntu-latest` for amd64, `ubuntu-24.04-arm` for arm64) with a manifest-merge step, replacing the QEMU-emulated single-job build. This eliminates ~30 min of arm64 emulation time per release.

## [0.2.6] - 2026-05-01

### Changed
- **MarginsDto** — `top`, `right`, `bottom`, and `left` are no longer required, matching the relaxed contract in `@epistola.app/epistola-model`. Clients may now send a partial margins object (e.g. `{ "top": 40 }`); `minimum: 0` still applies when a value is provided.

### Fixed
- **Docs version** — docs workflow now uses the actual release tag version (e.g., 0.2.5) instead of only major.minor from the API spec

## [0.2.5] - 2026-04-21

### Added
- **themeCatalogKey on TemplateResource** — added optional `themeCatalogKey` field to indicate which catalog a template's theme belongs to, enabling cross-catalog theme references in exports

## [0.2.4] - 2026-04-21

### Added
- **themeId on TemplateResource** — added `themeId` field to link templates to catalog themes

### Fixed
- **GitHub Pages deployment** — docs workflow was skipped for releases because `workflow_run` branch filter didn't match tag-based release runs; removed the branch filter so docs deploy triggers on any successful release

## [0.2.0] - 2026-04-16

### Added
- **Catalogs API** — `GET /tenants/{tenantId}/catalogs` lists all catalogs. `POST /tenants/{tenantId}/catalogs/import` imports a self-contained ZIP archive.
- **Catalog protocol** — shared `epistola-model` module (renamed from `editor-model`) with `CatalogManifest`, `ResourceDetail`, `DependencyRef` types for catalog exchange. Published as both Maven (`app.epistola.contract:epistola-model`) and npm (`@epistola.app/epistola-model`).
- **Stencils API** — full CRUD for reusable template components (stencils) with versioned content
  - `GET/POST /tenants/{tenantId}/stencils` — list and create stencils
  - `GET/PATCH/DELETE /tenants/{tenantId}/stencils/{stencilId}` — manage individual stencils
  - `GET/POST /tenants/{tenantId}/stencils/{stencilId}/versions` — list and create stencil versions
  - `GET/PATCH .../versions/{versionId}` — get and update draft versions
  - `POST .../versions/{versionId}/publish` — publish with no-nesting validation
  - `POST .../versions/{versionId}/archive` — archive published versions
  - `GET .../versions/{versionId}/usage` — find templates using a stencil version
  - `POST .../versions/{versionId}/upgrade-preview` — before/after diff for bulk upgrades
- **Stencil component type** — stencil instances in templates use a dedicated `stencil` node type with `stencilId` and `version` in props, rather than a generic reference on all nodes
- **Version fallback** — `versionId` and `environmentId` are both optional in generate/preview requests. When neither is specified, the latest published version is used.

### Removed
- **Template import endpoint** `POST /tenants/{tenantId}/catalogs/{catalogId}/templates/import` — superseded by catalog import (`POST /tenants/{tenantId}/catalogs/import`). Related schemas (`ImportTemplatesRequest`, `ImportTemplateDto`, `ImportVariantDto`, `ImportTemplatesResponse`) removed.

### Changed
- **BREAKING: All catalog-scoped paths now include `{catalogId}`** — endpoints for templates, themes, stencils, attributes, and variants are nested under `/tenants/{tenantId}/catalogs/{catalogId}/...`. Generation and preview requests require a `catalogId` field.
- **Release trigger changed from `[release]` commit to GitHub Release** — releases are now triggered by creating a GitHub Release (`gh release create vX.Y.Z` or `make release`) instead of pushing a commit containing `[release]` to `main`
  - `make release` now auto-calculates the next patch version and creates a GitHub Release directly (no more empty marker commits)
  - Snapshot workflow no longer needs to check for `[release]` commits — all pushes to `main` publish snapshots
  - Release branches (`release/**`) continue to auto-release on push
  - Aligns release approach with epistola-suite

### Added
- **Document preview endpoint** `POST /tenants/{tenantId}/documents/preview`
  - Synchronous endpoint that returns a PDF directly in the response body
  - For preview purposes only — not PDF/A compliant, rate-limited, no latency/throughput guarantees
  - Documents are not stored; use the async generation endpoint for production use
  - New `PreviewDocumentRequest` schema (same as generation request without `filename`/`correlationId`)
  - Returns `429 Too Many Requests` when rate limit is exceeded
- **Client-side JSON Schema validation** for document generation requests
  - `TemplateSchemaValidator` fetches the template's JSON Schema from the server, caches it, and validates the `data` field locally before submission
  - `ValidatingGenerationApi` wraps `GenerationApi` to transparently validate on `generateDocument` and `generateDocumentBatch` calls
  - `SchemaCache` fun interface with pluggable caching; default `TtlSchemaCache` uses ConcurrentHashMap with configurable TTL (5 min default)
  - Auto-detects JSON Schema draft version from `$schema` keyword (supports Draft 4/6/7/2019-09/2020-12)
  - Batch validation collects all errors across all items into a single `TemplateDataValidationException`
  - Optional dependency: `com.networknt:json-schema-validator:1.5.7` (consumers add it only if using validation)
- **Theme `spacingUnit` property** — `ThemeDto`, `CreateThemeRequest`, and `UpdateThemeRequest` now include an optional `spacingUnit` field (number, 1-16). This is the base spacing unit in points for the sp spacing scale system. Null means default (4pt).

### Changed
- **CI/CD simplification** — extracted 3 composite actions to eliminate duplication across workflows
  - `setup-build-tools`: unified tool setup via mise (Java, Gradle, Node, pnpm) with optional npm dependency installation
  - `bundle-spec`: OpenAPI validation, bundling, optional version injection, and artifact upload
  - `calculate-version`: version calculation for release, snapshot, and feature-snapshot modes
- **All workflows refactored** to use composite actions instead of duplicated inline steps
  - Removed hardcoded Node 22 references (now uses Node 24 from `.mise.toml` via mise)
  - Removed manual `npm install -g pnpm`, `setup-node@v4`, and `pnpm/action-setup@v4` in favor of mise
- **Feature snapshot workflow** now validates the OpenAPI spec, uses matrix strategy for parallel builds, and publishes epistola-model to GitHub Packages
- **Mock server workflow** now reuses bundled spec artifact from caller workflow instead of re-bundling
- **Client version catalog aligned** with server — Kotlin `2.3.0` → `2.3.10`, OpenAPI Generator `7.13.0` → `7.19.0`

### Fixed
- Release workflow npm publish now correctly installs pnpm and npm dependencies before publishing (was missing `pnpm install`, would fail at runtime)
- Release workflow now creates the git tag **before** publishing to Maven Central/npm, preventing a stuck-version loop where a partial publish failure leaves no tag, causing the next release attempt to retry the same version and fail with "already exists"

### Changed
- **Consolidated GitHub releases** — releases now create a single unified release per version (e.g. `v0.1.3`) instead of two separate per-module releases (e.g. `client-spring3-restclient-v0.1.3` and `server-kotlin-springboot4-v0.1.3`)
  - Release tag format changed from `{artifact_id}-v{version}` to `v{version}`
  - Version calculation scans both new unified tags and legacy module-prefixed tags for backwards compatibility

### Changed
- **Snapshot workflow restructured** to match release workflow pattern
  - Replaced sequential `build-all` job with parallel matrix-based `build` job (client and server build concurrently)
  - Consolidated `publish-client` and `publish-server` into a single matrix-based `publish` job
  - Build jobs now skip when no relevant files changed (previously always built both modules)
  - `detect-changes` job now skips on `[release]` commits (previously ran unnecessarily)
  - `mock-server` job no longer waits for Gradle builds (only needs bundled spec)

### Fixed
- CLAUDE.md `security-defined` validation rule documented as "Disabled" but was actually set to `error` in `redocly.yaml`
- Version injection `sed` command in release and snapshot workflows replaced all `version:` lines in bundled spec, corrupting schema property definitions (now only replaces the first match: `info.version`)

### Added
- **Trunk-based release flow** — releases are triggered by including `[release]` in a commit message on `main`
- **`make release`** convenience target that creates a `[release]` marker commit with safety checks (must be on `main`, clean working tree)
- Snapshot publishing automatically skips when a `[release]` commit is pushed (prevents duplicate artifacts)
- Release branches (`release/X.Y`) are still supported for hotfixing older versions — any push to a release branch triggers a release

### Removed
- `version-bump.yml` workflow (was for release-branch model)
- `make cut-release` target (replaced by `make release`)

### Added
- **Bulk template import endpoint** `POST /tenants/{tenantId}/templates/import`
  - Create-or-update semantics for idempotent template synchronization
  - Supports full template definition: metadata, dataModel, dataExamples, templateModel, variants
  - Per-variant templateModel override (falls back to top-level templateModel)
  - Automatic publishing to specified environments after import
  - Per-template result status: `created`, `updated`, `unchanged`, `failed`
  - New schemas: `ImportTemplatesRequest`, `ImportTemplateDto`, `ImportVariantDto`, `ImportTemplatesResponse`, `ImportTemplateResultDto`

### Added
- **Template model schema types** for the node/slot graph model (`spec/components/schemas/template-model.yaml`)
  - `TemplateDocumentDto`: root document with modelVersion, root, nodes, slots, themeRef, and optional overrides
  - `NodeDto`: graph node with id, type, slots, styles (open), stylePreset, and props (open)
  - `SlotDto`: graph slot with id, nodeId, name, and children
  - `ThemeRefDto`: theme reference with type enum (`inherit` / `override`) and optional themeId
  - `BlockStylePresetDto`: structured preset with label, styles (open), and optional applicableTo
- `PageSettingsDto.backgroundColor` property for page background color

### Changed
- **BREAKING**: `VersionDto.templateModel` and `UpdateDraftRequest.templateModel` changed from bare `type: object` to `TemplateDocumentDto`
  - Server stubs: `ObjectNode` → `TemplateDocumentDto`
  - Client: `Any?` → `TemplateDocumentDto`
  - Wire format remains compatible — same JSON, now properly described
  - All examples updated from old block-based model to node/slot graph format
- **BREAKING**: `DocumentStylesDto` changed from explicit properties to an open object
  - Matches `template-shared.schema.json#DocumentStyles` where available properties are driven by the style registry
  - Server stubs: typed data class → `Map`/`ObjectNode`
  - Client: typed data class → `Any`
- **BREAKING**: `blockStylePresets` in `ThemeDto`, `CreateThemeRequest`, and `UpdateThemeRequest` changed
  from unstructured `additionalProperties: type: object` to `additionalProperties: $ref: BlockStylePresetDto`
  - Each preset now has `label` (required), `styles` (required), and `applicableTo` (optional)
  - Server/client: `Map<String, Any>` → `Map<String, BlockStylePresetDto>`

### Fixed
- `MarginsDto` description corrected from "pixels" to "millimeters" matching the source of truth
  - Added `required` constraint on all four sides and `minimum: 0` validation

### Added
- Consumer registration design document (`docs/consumer_registration.md`) covering:
  - Consumer registry for tracking which systems consume the Epistola API (platform-level CRUD)
  - Template dependency declaration per tenant for impact analysis
  - 409 Conflict responses on delete when dependent consumers exist
  - Attribution via `DocumentDto.createdBy` population
  - Integration with the event system actor model
  - Phased implementation recommendation (registry, dependencies, events)

### Fixed
- Fix mock server Docker image pull failure ("manifest unknown") by disabling provenance attestations,
  which forced OCI-only manifest format incompatible with older Docker clients
- Prevent half-releases by separating build and publish phases in CI workflows
  - Release and snapshot workflows now build and test all modules first
  - Publishing only starts after all builds succeed
  - Previously, modules built and published independently — if one succeeded and the other failed,
    only one artifact would be published to Maven Central

### Added
- Event system design document (`docs/event_system.md`) exploring five delivery mechanisms:
  Long Polling, SSE, Webhooks, Polling with Event Log, and gRPC Hybrid (notification
  channel + REST event log). Recommends Polling with Event Log for contract-first
  compatibility, with two upgrade paths: Long Polling (primary) and gRPC Hybrid (future).
- Client-side validation extension functions generated from OpenAPI schema constraints
  - `.validate()` extension on all model classes that have constrained properties (25 models)
  - Enforces `pattern`, `minLength`/`maxLength`, `minimum`/`maximum`, and `minItems` constraints
  - Nullable fields use safe `?.let` pattern; null values skip validation
  - Returns `this` for fluent chaining (e.g., `createTenantRequest.validate()`)
  - Generated into `build/generated-validation/` (not committed) alongside the OpenAPI-generated client code
- OpenAPI examples for consistent, deterministic mock server responses
  - Schema-level examples on all response/DTO types (used by Prism for reliable static responses)
  - Property-level examples on all properties (used by documentation renderers like Redoc)
  - Examples follow a coherent "Epistola story" narrative (Acme Corp tenant, invoice template, English variant)
  - All IDs, timestamps, and references are cross-consistent across schemas
  - List responses include realistic multi-item arrays (e.g., production + staging environments)

### Changed
- Switched Prism mock server from dynamic (`-d`) to static mode
  - Responses are now deterministic and consistent across repeated requests
  - Mock data is derived from schema examples instead of randomly generated

### Fixed
- Mock server Docker image now receives the correct auto-incremented version during releases
  - Previously used static spec version from `epistola-api.yaml` (e.g., always `0.1.0`)
  - Now uses the same centralized version calculation as Maven artifacts (e.g., `0.1.2`)
- Centralized release version calculation in `validate-and-bundle` job
  - All artifacts (client, server, mock server) now share the same version per release
  - Eliminates potential version drift between independently calculated artifact versions

### Changed
- Enabled automatic release to Maven Central (no more manual "Publish" click in Sonatype Central Portal)

### Added
- **Dual authentication support** for system-to-system communication
  - OAuth 2.0 Client Credentials flow with JWT (recommended)
  - API Key authentication via `X-API-Key` header (fallback)
- **Role-based access control** with five independent roles (can be combined)
  - `reader`: Read-only access to resources within allowed tenants
  - `editor`: Create and update resources within allowed tenants
  - `generator`: Submit document generation jobs
  - `manager`: Delete resources and cancel jobs within allowed tenants
  - `tenant_control`: Manage tenants (list all, create, update, delete)
- **Tenant authorization** via JWT claims (`allowed_tenants`)
- **Security schemes** in OpenAPI spec: `bearerAuth` (JWT) and `apiKeyAuth`
- **401/403 error responses** for authentication/authorization failures
- **`x-required-roles`** extension on all endpoints documenting permission requirements
- Authentication documentation at `docs/auth.md`
- Template data validation endpoint `POST /tenants/{tenantId}/templates/{templateId}/validate`
  - Pre-flight validation of input data against template JSON Schema
  - Returns validation result with detailed error information (path, message, keyword)
  - Enables faster feedback before batch submission without rendering overhead

### Changed
- **BREAKING**: Removed `/v1` prefix from all URL paths
  - API versioning is handled via `Accept` header (`application/vnd.epistola.v1+json`)
  - Paths now start with `/tenants` instead of `/v1/tenants`
- Standardized version handling across all workflows to use `-Pversion=` consistently
  - Release workflow now passes full version (e.g., `1.0.3`) instead of patch version
  - Snapshot workflow centralizes version calculation in spec-validation job
- Mock server is now automatically published as part of release and snapshot workflows
  - Releases publish with spec version tag (e.g., `1.0.0`) and `latest`
  - Snapshots publish with snapshot version tag (e.g., `1.0-SNAPSHOT`) and `latest`
- Removed redundant `build-summary` job from build workflow (use GitHub's native required checks)

### Added
- OpenAPI development tooling
  - `make breaking` - Check for breaking API changes against main branch using oasdiff
  - `make mock` - Start Prism mock server for API testing on http://localhost:4010
  - `make validate-impl` - Validate implementation against OpenAPI spec using Prism proxy
  - CI workflow for automatic breaking change detection on PRs modifying the spec
- Mock server Docker image published to GitHub Container Registry (ghcr.io)
  - Based on Stoplight Prism with bundled OpenAPI spec
  - Automatically released with snapshots and releases, also available via manual workflow dispatch
- Claude skill for OpenAPI spec maintenance (`.claude/skills/openapi.md`)
  - Guidance for file structure navigation
  - Patterns for adding endpoints and schemas
  - REST best practices and versioning guidelines
- GitHub Pages API documentation with Redoc
  - Multi-version support with version selector
  - Landing page showing all available versions
  - Automatic deployment after successful releases to Maven Central
  - Manual deployment via workflow dispatch

### Changed
- **BREAKING**: Renamed server module from `epistola-server-kotlin` to `server-kotlin-springboot4`
  - Artifact ID changed from `server-spring-boot4` to `server-kotlin-springboot4`
  - Removed submodule structure (flattened to single module)
- Upgraded vanniktech/gradle-maven-publish-plugin from 0.30.0 to 0.36.0 for snapshot support
  - Plugin API changed: removed `SonatypeHost` enum (Central Portal is now default)
- **BREAKING**: Migrated Maven publishing from OSSRH (s01.oss.sonatype.org) to Sonatype Central Portal
  - Replaced manual `maven-publish` and `signing` plugins with vanniktech/gradle-maven-publish-plugin 0.36.0
  - Publishing now uses `publishToMavenCentral` command instead of `publish`
  - GPG signing now uses in-memory keys instead of requiring GPG binary
  - **User action required**: Generate new Central Portal token at https://central.sonatype.com/account
- **BREAKING**: Server module now targets Spring Boot 4.x with Jackson 3
  - Updated Spring Boot from 3.5.1 to 4.0.2
  - Updated OpenAPI Generator from 7.13.0 to 7.19.0
  - Added Jackson 3 module (`tools.jackson.module:jackson-module-kotlin`)
  - Added `useJakartaEe` configuration option

### Added
- Manual workflow dispatch for snapshot publishing with option to skip change detection
- Maven Central publishing configuration for both modules
  - Signing plugin with GPG support
  - Complete POM metadata (name, description, license, developers, SCM)
  - Sources and Javadoc JAR generation
  - OSSRH repository configuration
- GitHub Actions release workflow (`release.yml`)
  - Manual dispatch with module selection
  - Automatic patch version calculation from git tags
  - GitHub release creation with Maven coordinates
- Automatic version reading from OpenAPI spec
  - API version (major.minor) read from `epistola-api.yaml`
  - Patch version calculated from existing git tags
  - Local builds use version `X.Y.0` (not for release)

### Changed
- Moved `epistola-api.yaml` to repository root for easier access
- Moved `redocly.yaml` to repository root

### Changed
- **BREAKING**: Transformed repository into contract-first architecture
- Renamed repository concept from `epistola-api-clients` to `epistola-contract`
- OpenAPI specification is now the source of truth in `spec/` directory
- Generated code is no longer committed - built fresh from spec during each build
- Renamed Kotlin client module to `client-kotlin-spring-restclient`
- Kotlin client now uses Spring RestClient (Spring Boot 3.2+) instead of Ktor
- Updated Java toolchain from 25 to 21 for broader compatibility
- GitHub Actions workflow now runs spec validation, client, and server builds in parallel

### Added
- OpenAPI specification in `spec/` directory (copied from epistola-suite)
- `client-kotlin-spring-restclient` module with OpenAPI Generator configuration
  - Spring RestClient for HTTP communication
  - Jackson for JSON serialization
  - Java 8 date/time handling
- `server-kotlin-springboot4` module for Spring server stubs
  - Interface-only generation for clean implementations
  - Spring Boot 4.x compatible (Jackson 3)
  - Bean validation annotations
- Redocly configuration for spec validation (`redocly.yaml`)
- Spec validation job in CI pipeline
- Maven publishing configuration for both modules

### Removed
- Placeholder EpistolaClient class (replaced by generated code)
- Placeholder test class (replaced by generated tests)
