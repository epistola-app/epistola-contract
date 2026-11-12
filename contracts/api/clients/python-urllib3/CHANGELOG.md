# Changelog — Epistola Python Client

All notable changes to the `epistola-client` package are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). This package's
version tracks the Epistola contract version (`info.version` in the OpenAPI spec), so it releases in
lockstep with the other generated artifacts. The repository-level [CHANGELOG](../../../../CHANGELOG.md)
records contract changes; this file records changes to the hand-written library only.

## [Unreleased]

## [2.0.0] - 2026-11-12

### Fixed

- `ResultCollector.partition_for`, `is_my_partition` and `routing_key_to_me` answer as before the
  assignment is known when it names a `hash` other than `murmur3`, instead of routing with murmur3
  regardless and sending results to other nodes.
- An enum value this client does not know, from a newer 2.x server, no longer fails the
  response. A named enum reads it as an unknown member that keeps the value (`.value`, and equal
  to the received string), and an enum declared inline keeps the string.
- `GenerationResult` carries `release_id` and `release_name`, after `version`. The collector
  parsed result lines by hand and dropped both, so a consumer could not tell which draft rendered
  a document.
- `ResultCollector.collect_once()` raises a `ProblemDetailException` for an
  `application/problem+json` error response, and the generated `ApiException` with the status and
  body for any other non-2xx, as the client does for every other call (#30). It raised a bare
  `RuntimeError` that dropped the problem detail, because the collector reads the response itself
  and the client's problem handler never sees it.

### Added

- `ProblemDetailException.dependents`, `held_resources` and `is_catalog_draft_in_use_problem`
  surface a `catalog-draft-in-use` problem (409 from `delete_catalog_release`, which refuses to
  discard a draft something outside it relies on): the drafts of other catalogs whose dependency
  declarations name it, each a `CatalogDraftRef(registry, namespace, catalog_key, release_id,
  release_name)`, exported from `epistola_client`, and the resources only it holds that something
  outside it still uses, as `resourceType/resourceKey`. The constructor's `dependents=` and
  `held_resources=` parameters are optional. `environments`, `pinned_by` and
  `is_release_in_use_problem` stay, for `release-in-use`, which no operation answers yet.
- `KnownValidationCodes`, generated from the contract's `x-validation-codes` registry and exported
  from `epistola_client`: one constant per code that says why a field of a `validation-error`
  failed, and `ALL`. Compare a field error's `code` (`None` when the server sent none) against
  them, keeping a fallback branch.
- `ProblemDetailException.help`: the problem type's page with this request's context in its query
  (`?operation=getTemplate`), the contract's new optional base member `help`, or `None` when the
  server sent none. Switch on the type, not on this; open it for hints.
- `ProblemDetailException.required_permission`, `refused_tenant_key`, `required_platform_role`,
  `refusal_reason` and `is_forbidden_problem`: what a `forbidden` problem says was refused (a
  permission in a tenant, access to a tenant, a platform role, or a policy's reason), read from that
  problem only.
- `ProblemDetailException.behind` and `is_catalog_version_behind_problem` (a
  `catalog-version-behind` problem's versions to acknowledge), `environments`, `pinned_by` (each a
  `CatalogReleaseRef`) and `is_release_in_use_problem` (what a `release-in-use` problem says uses
  the release), and `tied_variants` and `is_ambiguous_variant_problem` (an `ambiguous-variant`
  problem's variants).
- `ProblemDetailException.missing_base_version`, `missing_base_fingerprint` and
  `is_merge_base_missing_problem` surface the `version` and `fingerprint` members of a
  `merge-base-missing` problem (409, from `merge_into_catalog_draft`, alpha): the release to install
  first, read from that problem only. The new constructor parameters are optional.
- `ProblemDetailException.unresolved_conflicts` and `is_merge_conflicts_unresolved_problem`
  surface the `unresolvedConflicts` member of a `merge-conflicts-unresolved` problem (409, from
  `merge_into_catalog_draft`, alpha): each conflict without a resolution, as
  `resourceType/resourceKey`, skipping entries that are not strings. The exception's new
  `unresolved_conflicts=` constructor parameter is optional.
- `ProblemDetailException.read_only_reason` and `is_catalog_read_only_problem` surface the
  `readOnlyReason` member of a `catalog-read-only` problem (409, from any write to a catalog that
  cannot be changed): `editing-disabled` or `supplied-by-epistola`, kept as the wire string; `None`
  when absent or not a string. The exception's new `read_only_reason=` constructor parameter is
  optional.
- `ProblemDetailException.missing_dependencies` and `is_release_dependencies_missing_problem`
  surface a `release-dependencies-missing` problem (409 from `deploy_catalog_release`): each dependency
  release that cannot be had, as a `MissingDependency(registry, namespace,
  catalog_key, version, reason)`. `reason` is the wire string, so a reason a later
  contract adds is kept rather than failing the parse. The member is `[]` when absent or
  malformed, and the exception's new `missing_dependencies=` constructor parameter is optional.
- `ProblemDetailException.unreleased_dependencies` and `is_release_dependencies_unreleased_problem`
  surface a `release-dependencies-unreleased` problem (409 from `release_catalog_draft`): each
  draft of another catalog the draft's dependency declarations name, as an
  `UnreleasedDependency(registry, namespace, catalog_key, release_id, release_name)`, exported from
  `epistola_client`. The member is `[]` when absent or malformed, and the exception's new
  `unreleased_dependencies=` constructor parameter is optional.
- `model_validation` also covers a model whose only constraint sits in a referenced plain string
  schema (`ReleaseVersion`), which pydantic already enforces on the generated field.

### Changed

- **BREAKING:** Requires Python 3.10 or later, `urllib3>=2.8.0`, `pyjwt[crypto]>=2.15.0` and
  `pydantic>=2.4.0`. The old minimums allowed versions with known vulnerabilities, and the first
  `urllib3` without them does not support Python 3.9, which is end of life.
- **BREAKING:** `GenerationResult.registry`, `namespace` and `catalog_key` are `str`, no longer
  `Optional[str]`: every collected result names its catalog. `version` stays `None` for a
  document rendered from a draft. `GenerationResult.status` carries the wire value as sent, now
  `completed` or `failed`.
- `MissingDependency.reason` carries the wire value as sent, now `unavailable`.
- The README no longer mentions the `X-API-Key` header, which the contract no longer defines;
  `.api_key(...)` sends `Authorization: ApiKey <key>`, as before.
- **BREAKING:** the HTTP client and the result collector send `application/vnd.epistola.v2+json` and
  `application/vnd.epistola.v2+ndjson`, the contract 2.x media types. A 2.x server refuses the 1.x
  ones.
- **BREAKING:** contract 2.0 addresses a resource by its `key` (#84), a catalog by registry,
  namespace and key, and renders a release rather than a template version. Against 1.4, the
  hand-written library changes as follows:
  - `TemplateDataValidator.validate`, `ServerTemplateDataValidator.validate` and
    `TemplateSchemaValidator.validate` take `(tenant_key, registry, namespace, catalog_key,
    template_key, data)` in place of `(tenant_id, catalog_id, template_id, data)`. `registry` is
    `~` for the server's default registry or a registry named in full (`acme.com`).
  - `ServerTemplateDataValidator` no longer takes a version id. `version_range=` (`1.4.0`,
    `^1.4.0`, sent as the request's `versionRange`) and `environment_key=` select the release;
    with neither, the catalog's highest release is checked. Its `variant_id=` and
    `environment_id=` are now `variant_key=` and `environment_key=`.
  - `ValidatingGenerationApi.generate_document` and `generate_document_batch` take `tenant_key`,
    and the pre-flight passes a request's `registry` (`~` when unset), `namespace` and
    `catalog_key` to the validator.
  - `ResultCollectorBuilder.tenant_id()` is now `tenant_key()`.
  - `GenerationResult.template_id` and `variant_id` are now `template_key` and `variant_key`, and
    `version_id` is now `version`, the release's version (`None` for a draft). It gains `registry`,
    `namespace` and `catalog_key`, naming the catalog release a document was generated from.
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
