# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
The .NET, Python and Node.js clients keep their own changelogs for changes to their hand-written
libraries.

## [Unreleased]

## [1.4.0] - 2026-09-30

### Added

- Template data that breaks a template's data contract is now described field by field
  (epistola-suite#978). Preview answers it with a new `template-data-invalid` problem type (status
  400, schema `TemplateDataValidationProblemDetail`) whose `errors[]` points into the request body
  and whose new `missingFields` and `invalidFields` members give each field's JSON Pointer into
  `data` and its JSON Schema. `validateTemplateData` returns the same two fields, documents its 400
  response, and accepts optional `variantId`, `versionId` and `environmentId` to validate against
  the version that would be rendered. All new fields are optional.

### Changed

- Page headers and footers may appear any number of times, anywhere in a template's flow,
  including inside stencils, conditionals and loops (epistola-suite#1020). The validator no longer
  reports `PAGEHEADER_TOO_MANY`, `PAGEHEADER_ROOT_MISSING` or `PAGEHEADER_NOT_AT_ROOT` (the
  constants remain, deprecated) and adds `PAGEBAND_NESTED` for a header or footer nested in
  another. The component registry drops `maxInstancesPerDocument` from `pageheader` and
  `pagefooter`. The wire format is unchanged and every previously valid catalog stays valid, but a
  catalog with several footers should only be imported by a Suite that renders them: older Suites
  render the first footer on every page.

### Fixed

- The release workflow skipped its last two jobs on every release created on GitHub, so the
  published catalog was never verified against Maven Central and npm, and no release since v0.16.0
  has attached `openapi.yaml` and the .NET SBOM.

## [1.3.1] - 2026-09-21

### Changed

- API keys are the supported authentication method. `bearerAuth` and every Consumers API
  operation are now marked `x-experimental: true`, since Epistola Suite may not implement the
  consumer model JWT authentication relies on yet. `apiKeyAuth` no longer says "use JWT instead";
  its `x-deprecated: true` now deprecates only the `X-API-Key` header in favour of
  `Authorization: ApiKey <key>`. Nothing changes on the wire; the docs and every client's quick
  start now use an API key.

### Fixed

- 1.3.0 clients refused every response from a pre-1.3.0 server (#84): `slug` was added to eleven
  read models as required, and the Kotlin, .NET and Python clients rejected responses without it.
  `slug` is now optional on `AttributeDto`, `CatalogDto`, `EnvironmentDto`, `StencilDto`,
  `StencilSummaryDto`, `TemplateDto`, `TemplateSummaryDto`, `TenantDto`, `ThemeDto`, `VariantDto`
  and `VariantSummaryDto`; fall back to the deprecated `id` (`key` on `AttributeDto`) when it is
  absent. It becomes required again in 2.0.0, which removes `id` and `key`.
  - Source-breaking against the 1.3.0 models: Kotlin reads `slug` as `String?` and its constructor
    parameter moves last with a `null` default, so positional Java callers must reorder arguments.
    The Python and .NET properties become optional too.
  - A new conformance scenario, `older-server-response`, guards against this in every client.

## [1.3.0] - 2026-09-21

### Added

- The Node.js client, `@epistola.app/epistola-client`: TypeScript on the platform's `fetch`, at
  feature parity with the other clients.
- `slug` on every REST response that addresses a resource, deprecating the `id` it duplicates
  (`key` on `AttributeDto`) (#84). A **slug** is an address someone chooses; an **id** is one the
  system assigns, so `documentId`, `requestId`, version numbers and the like keep their names. Both
  properties carry the same value. `id`/`key`, request bodies and path parameter names change in
  2.0.0.
- `/images`, which addresses a catalog image by its slug and lists images only (#80).

### Changed

- **Catalog wire v7** (#86). Older archives migrate automatically; catalog fingerprints change
  once, while V1–V3 fingerprint verification is unchanged.
  - Binaries are identified by content: `AssetResource` becomes `ImageResource` with a
    `contentHash`, binaries are filed at `bin/<contentHash>`, and `contentUrl` is deprecated and
    optional. A font face carries its own binary and states `FontVariantEntry.mediaType`
    (`font/ttf` or `font/otf`). A v6 asset becomes an image that keeps its slug, so template
    references keep resolving.
  - Content hashes are verified (`CATALOG_ASSET_CONTENT_HASH_MISMATCH`); a v6 binary that cannot be
    resolved reports `CATALOG_ASSET_CONTENT_UNRESOLVED`.
  - An image dependency names its catalog, like every other dependency kind. A v6 image dependency
    without one reports `CATALOG_DEPENDENCY_UNQUALIFIED` rather than being guessed.
  - A template variant is addressed by `slug` instead of `id`.
  - The unused per-resource `compatibility` is removed; the catalog-level one remains.
  - Every resource slug is validated against its type's bounds, defined in `CatalogSlugs` and
    reported as `CATALOG_RESOURCE_SLUG_INVALID`: template, stencil, attribute, theme and catalog
    3–50 characters, code list 3–64, font 2–64, image 1–50 (leading digit allowed), variant 3–50.
    Exchange's publication gate now catches a slug a Suite would reject on install.
- `ImageDto.key` is `ImageDto.slug` and `{imageKey}` is `{imageSlug}`, matching the rest of the API.
  The images API had not shipped before this release.
- The TypeScript catalog surface renames `AssetResource` to `ImageResource`.

### Removed

- **Breaking, shipped in a minor by decision:** the asset operations `listAssets`, `uploadAsset`,
  `downloadAssetContent` and `deleteAsset`, with `AssetDto` and `AssetListResponse` (#86). Use
  `/images`. These addressed assets by UUID, so they could not name readable image slugs and mixed
  font-face binaries into the listing. We know of no caller, the Suite UI never used them, and a
  deprecation window would have served nobody.

### Fixed

- The Python client's binary downloads asked only for `application/problem+json`, never the PDF or
  image they return; it now accepts every media type an operation declares.

## [1.2.0] - 2026-09-03

### Added

- `app.epistola.contract:client-jakarta`, a Java client for Jakarta EE application servers
  (WildFly, Open Liberty, Payara, Quarkus) built on MicroProfile Rest Client. It has the same
  features as the Spring client and no runtime dependencies: every container-supplied API is
  `compileOnly`.
- `EpistolaClient` in the Kotlin client: one builder that assembles identity, JSON configuration,
  problem parsing and API-key or JWT authentication into a `RestClient`. It always installs the
  problem handler, can build several clients with different read timeouts, and uses
  `java.net.http.HttpClient`, which supports `PATCH`.
- `ProblemDetail.extensions` and `ProblemDetailException.extensions` in the Kotlin client, exposing
  problem-body members beyond the RFC 9457 base that were previously discarded.
- `x-client-identity` in the spec: a machine-readable form of the `X-EP-Node-Id` header and the
  `User-Agent` grammar.
- The contract constants both sides of the wire share — problem-type slugs, client identity,
  problem extension members and the versioned media types — are generated into every JVM module.
  Both clients expose `ContractMediaTypes`.
- A cross-client conformance suite (`make conformance`). A scripted server plays each scenario in
  `contracts/api/conformance/scenarios` against every client and judges the recorded requests. It
  covers identity headers, media types, both auth schemes, result collection and backoff,
  compression, query and body serialization, murmur3 routing and RFC 9457 parsing, can validate
  requests against the spec through Prism (`backend: prism`), and checks fixtures against the
  response schemas.

### Changed

- **Source-breaking (Kotlin):** enum constants keep the contract's spelling
  (`VersionDto.Status.DRAFT` becomes `.draft`), so enum query parameters are sent as declared.
- **Source-breaking:** `SchemaCache.getOrLoad` and `TtlSchemaCache.evict` (and their .NET and
  Python equivalents) take the catalog id.
- The Kotlin client depends on `spring-web` instead of `spring-boot-starter-web` (15 artifacts
  instead of 33) and exposes Spring and Jackson in `compile` scope. Applications that relied on it
  for the web starter must declare it themselves.
- The Kotlin client signs JWTs with plain `java.security` (RS256, ES256) instead of
  `nimbus-jose-jwt`.
- Wire-protocol logic shared by the JVM modules (partition routing, backoff, decompression,
  `User-Agent`, problem URIs, JWT signing, murmur3) lives in `contracts/api/protocol-java` and is
  compiled into each artifact rather than published. An application should use one Epistola
  artifact, not two.
- An incomplete `JwtSigner.builder()` throws `IllegalArgumentException` in both JVM clients.
- The Kotlin client detects result-stream compression from its leading bytes instead of
  `Content-Encoding`.
- The JVM conformance drivers build separately from the published clients, and the Kotlin client
  is a single-project Gradle build.

### Fixed

- Every client's `ResultCollector` could busy-loop after a burst of results or a server outage;
  the backoff is now floored at `minInterval`. **Anyone running a collector should upgrade.**
- `routingKeyToMe` returned keys that routed to other nodes.
- The template schema cache ignored the catalog, so two catalogs holding the same template id
  shared one schema.
- `partitionFor` divided by zero when the server reported no partitions.
- Kotlin client:
  - Binary operations (`downloadDocument`, `previewDocument`, `importCatalog`, asset content) always
    failed with `UnknownContentTypeException`. They now use Spring's `Resource`; a multipart
    `Resource` built from bytes needs a `filename`.
  - Enum query parameters were sent as the constant name (`direction=DESC`).
  - Generation requests sent `attributes: null`, which a spec-validating server rejects.
- Kotlin and .NET clients: partial updates sent `null` for every unset field, so a `PATCH` erased
  fields the caller never touched. Unset fields are now omitted.
- .NET client: result collection dropped the base path of a base URL without a trailing slash.
- Python client:
  - Only the first line of an uncompressed result batch was read, so batches were never
    acknowledged and redelivered forever. **Anyone running a Python collector should upgrade.**
  - Requests did not accept `application/problem+json`.

## [1.1.0] - 2026-08-20

### Added

- Catalog wire v6: optional qualified catalog attributes, exact-case keywords, and same-catalog
  icon and gallery images. v4 and v5 archives migrate to empty attributes and keywords.
- The V4 catalog fingerprint, covering v6 discovery metadata. Legacy V1–V3 verification is kept
  for v4 and v5 archives.
- Optional catalog-wide license metadata: a display name plus optional SPDX expression, URL and
  copyright text.
- Optional `TemplateResource.pdfaEnabled`, defaulting to `true` when absent.
- Inheritable `listItemSpacing` for text, rich-text block and data-list components (`sp`/`pt`,
  default `0.5sp`).
- Complete versioned JSON Schemas for the catalog manifest and resource details, generated
  TypeScript wire types, and shared Kotlin/TypeScript fixture checks that keep them in parity.
- Documented the planned 2.0 requirement that every data contract has at least one valid named
  example, with its migration policy. 1.x behaviour is unchanged.

### Changed

- `CatalogInfo` no longer offers JVM destructuring or legacy `copy` shims; consumers recompile.

## [1.0.1] - 2026-08-04

### Fixed

- Catalog fingerprinting of JSON Schemas with a property named `type` whose value is an object.

## [1.0.0] - 2026-07-30

- Declared the Epistola contract stable with its first major release.

## [0.16.1] - 2026-07-30

### Fixed

- Catalog v4→v5 migration of JSON Schemas with a property named `type` whose value is an object.

## [0.16.0] - 2026-07-29

### Added

- REUSE-compliant EUPL-1.2 licensing, with a CI check for unlicensed files.

### Changed

- **Breaking (catalog):** wire v5 replaces stencil `isDraft` flags with exact `draftVersion`
  provenance. v4 archives migrate with notices, and V3 fingerprints keep older releases verifiable.

### Fixed

- Component style validation expands style families like the editor does, so values such as
  `paddingTop` are accepted.

## [0.15.0] - 2026-07-28

### Added

- Portable template validation (`TemplateValidator`) with stable finding codes, and
  `TemplateValidationContext` for resolving resources.
- Safe, deterministic catalog archive reading and writing with size, entry-count and
  expansion-ratio limits.
- `CatalogSchemaMigrator` for wire-version gating and `CatalogCanonicalizer` for content-based
  fingerprints.
- Whole-catalog validation (`CatalogValidator`, `ResourceValidator`).
- Version-pinned stencil composition: stencils can reference other published stencil versions,
  with cycle detection and a five-level nesting limit (`STENCIL_NESTING_DEPTH_EXCEEDED`).
- V2 catalog fingerprints covering publisher, compatibility and manifest metadata, via
  `currentFingerprint(catalog)`. `fingerprint(catalog)` still returns V1, and V1 hashes still
  validate.
- A language-neutral catalog conformance suite, published in both the Maven and npm artifacts.
- Standalone parameter-schema validation.
- A non-empty Dokka documentation JAR and expanded KDoc.

### Changed

- **Breaking:** the catalog artifact is `app.epistola.contract:epistola-catalog` (npm
  `@epistola.app/epistola-catalog`), and registry resources live under `META-INF/epistola-catalog`.
  No compatibility artifact is published.
- **Breaking (REST API):** template-model schemas reference the catalog contract directly, so the
  generated models are `TemplateDocument`, `Node`, `Slot`, `ThemeRef`, `PageSettings`, `Margins`,
  `DocumentStyles` and `BlockStylePreset` instead of their `*Dto` variants.
- The server stubs reuse the catalog's Kotlin classes and expose `epistola-catalog` transitively.
- **Validation tightening:** whole-catalog validation rejects invalid stencil parameter schemas, a
  `modelVersion` other than `1`, and missing template themes. Installed data is untouched, but
  re-importing an invalid catalog fails.
- An older `schemaVersion` is rejected unless an explicit migration exists.
- Text-node content must be a ProseMirror document object.
- The npm package no longer exposes `/generated/*`; theme, component and style types are exported
  from the package root.
- Rich-text reference schemas moved into the catalog artifact, and example data is validated with
  a full JSON Schema 2020-12 engine.
- `CatalogArchive.paths` lists regular files only.
- The repository is organised as two self-contained domains, `contracts/api` and
  `contracts/catalog`. Published coordinates are unchanged.
- Catalog publishing verifies release tags, fails on publication errors, and checks the published
  artifacts from clean consumers.

### Fixed

- Omitted stencil `isDraft` is treated as non-draft, as the editor does.
- Optional static component slots, nested template finding paths, and non-recursive nested
  stencils.
- Template property validation with Jackson 3, several missing style declarations, and table style
  applicability.
- The optional cross-catalog theme key is consistent across Kotlin, JSON Schema and TypeScript.
- Generated Kotlin models keep `modelVersion` integer-typed, and inherited theme references no
  longer require override-only fields.
- The npm package includes its source maps.

## [0.14.0] - 2026-07-23

### Added

- API-key authentication through `Authorization: ApiKey <key>`. `X-API-Key` still works but is
  deprecated. New problem type `api-key-auth-disabled`.
- Endpoints that already existed in Epistola Suite: data-contract draft, update, publish and list;
  variant draft create, publish and discard; code-list entry hide and show; catalog release,
  subscribed catalog upgrade and stencil upgrade.
- Catalog-scoped asset list, upload, download and delete endpoints.

### Changed

- Operations declare `x-required-permissions`, `x-required-platform-roles` or
  `x-required-authentication`, matching Suite's permission model, instead of the old role labels.

### Fixed

- The server stubs JAR sets `Implementation-Version`, so Suite reports the contract version in
  `/api/ping`.

## [0.13.0] - 2026-07-22

### Added

- The Python client `epistola-client` (PyPI).
- The editor component and style registries ship in `epistola-model` as a typed TypeScript facade,
  raw JSON exports and Maven classpath resources, with validation of examples, child rules and style
  keys.

### Changed

- **Breaking:** editor component `parameters` are explicit: `{ "kind": "dynamic" }` or
  `{ "kind": "static", "schema": … }` instead of `null`; a missing `parameters` means none.

### Fixed

- Python client generation, packaging checks and snapshot versioning in CI.

## [0.12.0] - 2026-07-17

### Added

- The .NET client `Epistola.Contract.Client` (NuGet), with a CycloneDX SBOM attached to each
  release.
- `NodeDto.props` documents the stencil node's identity props (`stencilId`, `catalogKey`,
  `version`, `isDraft`).
- The `pagefooter` convention is documented next to `pageheader`.

### Changed

- **Breaking:** `CreateVariantRequest.title` and `UpdateVariantRequest.title` are required and
  non-blank, as the server already enforced.
- `VariantDto.title` and `VariantSummaryDto.title` are required and non-nullable.

### Fixed

- Template-model examples match what the renderer accepts: rich-text documents for text content,
  `assetId` for images, and `columnSizes` for columns (#18).

## [0.11.0] - 2026-07-10

### Added

- `sort` and `direction` (`asc`/`desc`, default `desc`) on the paginated list endpoints. The server
  decides which `sort` fields it accepts and rejects others with 400.

## [0.10.0] - 2026-07-03

### Added

- Pagination on every list endpoint, through shared `page`/`size` parameters and a `PageMeta`
  object.
- The machine-readable problem-type registry `x-problem-types`, with a check that keeps
  `docs/error-types.md` in line. The Kotlin `KnownProblemSlugs` is generated from it.
- A reusable `BadRequestError` response.
- `x-deprecated: true` on `apiKeyAuth`.
- A lint check that restricts the spec to its allowed media types.

### Changed

- **Breaking:** pagination fields move into a nested `page` object, and `page` becomes
  `page.number`, on the tenant, consumer, generation-job and document lists.
- `DataModelValidationProblemDetail` maps to Spring's `ProblemDetail` in the server stubs.
- `make breaking` flattens `allOf` and shared parameters before diffing.
- Spec tooling (Redocly, Prism, oasdiff, Node) is pinned, and Gradle builds share one version
  catalog.

## [0.9.0] - 2026-07-03

### Added

- Optional `parameterSchema` on stencil versions and their create and update requests.
- The `data-model-validation-error` problem type (422), with typed handling in the Kotlin client
  (`isDataModelValidationProblem`, `validationErrors`) and a builder in the server stubs.
- `updateTemplate` documents its 409 and 422 responses.

### Fixed

- `UpdateTemplateRequest.forceUpdate` is described correctly: it confirms a breaking data-model
  change and does not bypass example validation.

## [0.8.0] - 2026-06-24

### Added

- Optional `StencilResource.parameterSchema`, so a stencil's parameters survive a catalog
  round-trip.

## [0.7.0] - 2026-06-05

### Added

- Opt-in typed error handling in the Kotlin client: `installProblemDetailHandler()` throws a
  `ProblemDetailException` with the problem type, slug and field errors.
- The error-type registry (`docs/error-types.md`) and the `bad-request` problem type.

### Changed

- Errors are RFC 9457 Problem Details (`application/problem+json`), discriminated by their `type`
  URI. Each error response references a shared component per problem type, and 429 responses
  advertise `Retry-After`.
- The server stubs use Spring's `ProblemDetail`, with an opt-in `ProblemDetails` helper.
- Problem `instance` is a URI reference.

### Removed

- The pre-Problem-Details schemas `ErrorResponse`, `ValidationErrorResponse` and `FieldError`.

### Fixed

- Server stubs keep the success media type on responses without a body.

## [0.6.0] - 2026-05-21

### Changed

- **Breaking (catalog):** `StencilResource.version` is required. Archives from older exporters must
  be re-exported.

## [0.5.3] - 2026-05-19

### Added

- `importCatalog` takes an optional `authoredMode` (`MERGE`, the default, or `REPLACE`, which also
  deletes resources missing from the archive) for authored catalogs.
- `ImportCatalogResponse.aborted`, telling a retryable aborted upgrade apart from a finished import.

## [0.5.2] - 2026-05-19

### Added

- `previewCatalogUpgrade`, a read-only preview of upgrading a subscribed catalog.

## [0.5.1] - 2026-05-18

### Added

- Optional `ReleaseInfo.fingerprint`, a SHA-256 of a catalog's canonical content.
- `CatalogDto.releasedVersion` and `CatalogDto.fingerprint`.

## [0.5.0] - 2026-05-17

### Added

- Read-only font endpoints (list and get).
- Catalogs can distribute font families: `FontResource`, `FontVariantEntry`, `DependencyRef.Font`
  and `FontRef`.

## [0.4.0] - 2026-05-12

### Added

- Code-list endpoints (CRUD, refresh from source, list entries), and catalogs can distribute code
  lists (`CodeListResource`, `DependencyRef.CodeList`).
- Attributes can be bound to a code list in the same or another catalog, and `AttributeDto` gains
  `catalog`, `displayName`, `allowedValues`, `codeListBinding`, `catalogType` and `readOnly`.
- Optional `catalog` on `VariantSelectionAttribute`.

## [0.3.0] - 2026-05-05

### Added

- Consumer onboarding: self-registration or OAuth auto-registration, then admin approval of tenants,
  roles and expiry, all managed in Epistola rather than in JWT claims.
- Self-signed JWT authentication, with `JwtSigner` in the client and `ConsumerResolver` in the
  server stubs.
- `POST /ping` for health and metadata exchange.
- Required client identity headers, `User-Agent` and `X-EP-Node-Id`, with `ClientIdentity` in the
  client and `ClientInfo` in the server stubs.
- Result collection: `POST /tenants/{tenantId}/generation/collect` streams results as compressed
  NDJSON with node affinity and failover. The client's `ResultCollector` adds `kick()` and a
  configurable backoff.

### Changed

- `ConsumerDto.authMethod` is `oauth`, `self-signed-jwt` or `api-key`.
- `make release` writes the full release version into `info.version`.

## [0.2.7] - 2026-05-05

### Changed

- `Margins` fields and `PageSettings.margins` are optional in `epistola-model`.
- Multi-arch mock server images build on native runners.

## [0.2.6] - 2026-05-01

### Changed

- `MarginsDto` sides are optional.

### Fixed

- The docs are published under the full release version.

## [0.2.5] - 2026-04-21

### Added

- Optional `TemplateResource.themeCatalogKey` for cross-catalog theme references.

## [0.2.4] - 2026-04-21

### Added

- `TemplateResource.themeId`.

### Fixed

- The docs deploy after releases.

## [0.2.0] - 2026-04-16

This entry also covers the 0.1.x releases, which were not recorded separately.

### Added

- Catalogs: list, and import from a self-contained ZIP. The shared `epistola-model` module
  (Maven and npm) defines the catalog manifest and resource types.
- Stencils: CRUD, versions with publish and archive, usage lookup, and upgrade preview. Templates
  embed them through a dedicated `stencil` node type.
- `POST /tenants/{tenantId}/documents/preview`: a rate-limited synchronous PDF preview.
- Generate and preview requests fall back to the latest published version when neither
  `versionId` nor `environmentId` is given.
- Client-side JSON Schema validation of generation requests (`ValidatingGenerationApi`, pluggable
  `SchemaCache`) and generated `.validate()` extensions for constrained models.
- Theme `spacingUnit`, and `PageSettingsDto.backgroundColor`.
- Typed template-model schemas (`TemplateDocumentDto`, `NodeDto`, `SlotDto`, `ThemeRefDto`,
  `BlockStylePresetDto`).
- Authentication (OAuth 2.0 client credentials, or an API key), role-based access control and
  tenant authorization, with 401 and 403 responses.
- Template data validation (`POST …/templates/{templateId}/validate`).
- Development tooling: `make breaking`, `make mock`, a published Prism mock server image with
  deterministic example responses, and versioned API docs on GitHub Pages.
- Design documents for consumer registration and the event system.

### Changed

- **Breaking:** catalog-scoped endpoints are nested under `/tenants/{tenantId}/catalogs/{catalogId}`,
  and generate and preview requests require `catalogId`.
- **Breaking:** `templateModel` is a typed `TemplateDocumentDto`, `DocumentStylesDto` is an open
  object, and `blockStylePresets` values are `BlockStylePresetDto`.
- **Breaking:** paths have no `/v1` prefix; the version is in the media type.
- **Breaking:** the server module is `server-kotlin-springboot4`, on Spring Boot 4 and Jackson 3.
- **Breaking:** publishing moved to the Sonatype Central Portal.
- The repository became contract-first: the OpenAPI spec is the source of truth and code is
  generated at build time. The Kotlin client uses Spring RestClient instead of Ktor.
- Releases are created as GitHub Releases, with one `vX.Y.Z` tag for all artifacts, and publish only
  after every module builds.

### Removed

- The bulk template import endpoint, superseded by catalog import.

### Fixed

- `MarginsDto` is documented in millimetres, not pixels.
- The mock server image receives the release version and pulls on older Docker clients.
