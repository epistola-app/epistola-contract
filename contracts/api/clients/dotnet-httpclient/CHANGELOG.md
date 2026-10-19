# Changelog — Epistola .NET Client

All notable changes to the `Epistola.Contract.Client` package are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). This package's
version tracks the Epistola contract version (`info.version` in the OpenAPI spec), so it releases in
lockstep with the other generated artifacts. The repository-level [CHANGELOG](../../../../CHANGELOG.md)
records contract changes; this file records changes to the hand-written library only.

## [Unreleased]

### Added

- `ProblemDetailException.MissingDependencies` and `IsReleaseDependenciesMissingProblem` surface the
  `missingDependencies` member of a `release-dependencies-missing` problem (409 from
  `DeployCatalogVersion`): each dependency release that cannot be had, as a hand-written
  `Epistola.Client.Error.MissingDependency` (`Registry`, `Namespace`, `CatalogKey`,
  `ReleaseVersion`, `Reason`). `Reason` is the string the server sent, so a reason a later contract
  adds is not lost; `MissingDependency.Reasons` holds the one named today, `Unavailable`. A malformed entry is
  skipped on its own; an absent member is an empty list. The exception's constructor takes it as a
  new optional `missingDependencies` parameter, so existing call sites compile unchanged.
- `ProblemDetailException.UnreleasedDependencies` and `IsReleaseDependenciesUnreleasedProblem`
  surface the `unreleasedDependencies` member of a `release-dependencies-unreleased` problem (409
  from `ReleaseCatalogDraft`): each draft of another catalog the released draft's dependency
  declarations name, as a hand-written `Epistola.Client.Error.UnreleasedDependency` (`Registry`,
  `Namespace`, `CatalogKey`, `VersionId`, `DraftName`). A malformed entry is skipped on its own; an
  absent member is an empty list. The constructor takes it as a new optional
  `unreleasedDependencies` parameter.
- The generated `Validate()` extensions check a member that references a plain string schema, such
  as `CatalogDependencyRequirement.ReleaseVersion` (`ReleaseVersion`), as if its constraints were
  written inline. Before, every referenced member was skipped.

### Changed

- **BREAKING:** `EpistolaMediaTypeHandler.VendorJson` and the result collector send
  `application/vnd.epistola.v2+json` and `application/vnd.epistola.v2+ndjson`, the contract 2.x media
  types. A 2.x server refuses the 1.x ones.
- **BREAKING:** a resource's address is its `key` (epistola-app/epistola-contract#84), so the
  hand-written API follows the generated one: `ResultCollector.Builder().TenantId(…)` is
  `TenantKey(…)`; the result collector's `GenerationResult.TemplateId` / `VariantId` are
  `TemplateKey` / `VariantKey`, read from the stream's `templateKey` / `variantKey`;
  `ServerTemplateDataValidator`'s `variantId` / `environmentId` parameters are `variantKey` /
  `environmentKey`; and the `Validate` methods of `ITemplateDataValidator`,
  `TemplateSchemaValidator` and `ServerTemplateDataValidator` take `(tenantKey, registry,
  namespace, catalogKey, templateKey, data)`. `ValidatingGenerationApi`'s parameter is `tenantKey`.
  No deprecated aliases are kept.
- **BREAKING:** a catalog is identified by registry, namespace and key.
  `ITemplateDataValidator.Validate`, `TemplateSchemaValidator.Validate` and
  `ServerTemplateDataValidator.Validate` take `(tenantId, registry, namespace, catalogKey,
  templateId, data)` in place of `(tenantId, catalogId, templateId, data)`. The registry is the path segment: `~`
  (`ITemplateDataValidator.DefaultRegistry`) for the server's default registry, or a name in full
  (`acme.com`). The namespace is a plain slug. `ValidatingGenerationApi` hands a validator `~` for
  a request whose `registry` is unset and passes a named registry on as given. It no longer
  converts `host/name` namespaces to `host~name`, because neither form exists any more.
- **BREAKING:** the result collector's `GenerationResult` gained `Registry` (null for results from
  before contract 2.0), before `Namespace`. `GenerationResult` is a positional record, so code
  constructing it positionally must add the argument.

- The reference in-process validator to copy, `NJsonSchemaTemplateDataValidator` under
  `test/Epistola.Client.Tests/Validation/Schema/Local/`, takes the catalog version whose template
  schema it holds data to, because a template is now read from a catalog version. The shipped
  validators are unchanged: `validateTemplateData` is not addressed by version.
- **BREAKING:** `ServerTemplateDataValidator`'s `releaseVersion` parameter is now `versionRange`,
  a version range sent as the request's `versionRange`; it may be combined with `environmentKey`.
- The result collector's `GenerationResult` gained `Namespace` and `CatalogKey` (null for results
  from before contract 2.0), next to `ReleaseVersion`. `GenerationResult` is a positional record, so
  code constructing it positionally must add the two arguments.
- The derived-source generator names a constrained `version` property `VarVersion`, as the
  OpenAPI generator does, so `Validate()` compiles for models that carry one.

- **BREAKING:** the server-side template data validator takes a `releaseVersion` (a string) in
  place of `versionId`, matching contract 2.0's generation by release; without it or an
  environment, the catalog's latest release is checked. The result collector reports
  `releaseVersion` in place of `versionId`.

- **Template data is validated by the server, not by a bundled library.** `TemplateSchemaValidator`
  and `ValidatingGenerationApi` now call `validateTemplateData` through a pluggable
  `ITemplateDataValidator`, whose only shipped implementation is `ServerTemplateDataValidator`.
  **The `NJsonSchema` package reference is gone**, so installing this client no longer pulls in a
  schema library. The verdict is the server's, which is the one that decides what renders.
- **`ValidationError.Path` is now a JSON Pointer** (`/customer/email`) rather than NJsonSchema's
  `#/customer.name`, and **`Keyword` is a JSON Schema keyword** (`type`, `required`, `minLength`)
  rather than NJsonSchema's `ValidationErrorKind` name (`StringExpected`, `PropertyRequired`). A
  batch item's errors are prefixed `items[0]/customer/email`. Code that matched on either value
  must be updated; both formats are now pinned on `ITemplateDataValidator`, so every implementation
  reports them the same way.
- `ValidatingGenerationApi` no longer pre-validates when the validator asks the server
  (`PreflightsGeneration` is `false`), since the server checks the same data on submit; it
  translates the resulting `template-data-invalid` problem into `TemplateDataValidationException`
  instead. One request instead of two, or instead of one per batch item. A validator that answers
  in-process still pre-flights, batch aggregation included.
- `ProblemDetailException` gained `MissingFields`, `InvalidFields` and `IsTemplateDataProblem`, and
  `ProblemDetailHandler.ParseProblem` reads the two arrays contract 1.4.0 added; it had been
  discarding them. The new constructor parameters are optional, so existing call sites compile
  unchanged.
- `TemplateSchemaValidator` and `ValidatingGenerationApi` take either an `ITemplatesApi` or an
  `ITemplateDataValidator`. The `ISchemaCache?` parameter is gone.

### Removed

- `ISchemaCache` and `TtlSchemaCache`. They were typed on NJsonSchema's `JsonSchema`, so they could
  not survive dropping the library. Caching a fetched schema is an in-process validator's concern;
  the reference adapter under `test/Epistola.Client.Tests/Validation/Schema/Local/` shows one.

## [1.4.0] - 2026-09-30

### Changed

- Raised the package minimums, which NuGet installs by default: System.IdentityModel.Tokens.Jwt
  8.23.0, Polly 8.8.0, NJsonSchema 11.6.1, Newtonsoft.Json 13.0.4 and JsonSubTypes 2.1.0.
- Deprecated model properties are marked `[Obsolete]`, which newly includes
  `UpgradeCatalogRequest.IncludeNewSlugs`. Projects that treat warnings as errors and set it will
  see CS0612.

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
