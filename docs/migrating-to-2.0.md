# Migrating from contract 1.x to 2.0

Contract 2.0 replaces per-template versioning with **catalog releases**. Almost every request and
response body changes, so a 1.x client cannot talk to a 2.x server at all. This guide lists what
to change, by area. `CHANGELOG.md` has the summary; each client's own changelog (.NET, Python,
Node.js) lists the changes to its hand-written library.

Parts marked **alpha** (`x-experimental`) may still change in a minor 2.x release.

## 1. The model, 1.x → 2.0

| 1.x | 2.0 |
| --- | --- |
| A template version (draft → published → archived) | A **release** of the whole catalog: a `draft` (editable, no version) or `released` (immutable, with a `version`) |
| A variant draft, a stencil version, a contract draft | The resource's working copy in a draft release, marked **ready** before the release |
| Publishing a version | Releasing a draft (`releaseCatalogDraft`) and then **deploying** the release to an environment |
| Activation of a version per environment | **Deployment** of a release to an environment; an environment can serve several releases of one catalog |
| `versionId` in a generation request | `versionRange` (npm SemVer range) together with `environmentKey` |
| `upgradeCatalog` / `previewCatalogUpgrade` | `installCatalogRelease` / `listAvailableCatalogReleases`. Releases install side by side; nothing is upgraded in place |
| Authored and subscribed catalog types | No types. `CatalogDto.editable`, `readOnlyReason` and `source` |
| `catalogId` | `registry` + `namespace` + `catalogKey` |
| `id` / `slug` | `key` |

A catalog may have any number of drafts. Each one starts from a release (its **base**) and is
released on its own. Its version follows from what changed against the base, so a fix to an older
line is simply `1.4.1` released after `2.0.0`. Drafts are independent copies. A draft is **behind**
releases that came out after it started, and releasing it needs `acknowledgedBehind`.

## 2. Every request

- **Media type.** Send and accept `application/vnd.epistola.v2+json`, and
  `application/vnd.epistola.v2+ndjson` for result collection. A 2.x server answers the 1.x media
  type with 406 or 415.
- **Paths.** Parameters are keys: `{tenantKey}`, `{templateKey}`, `{variantKey}`, `{stencilKey}`,
  `{environmentKey}`, `{themeKey}`, `{codeListKey}`, `{fontKey}` and `{imageKey}`. A catalog is
  `…/catalogs/{registry}/{namespace}/{catalogKey}/…`, where `~` means the server's default
  registry.
  Catalog content lives under a release:
  `…/catalogs/{registry}/{namespace}/{catalogKey}/releases/{release}/templates/…`, where
  `{release}` is a `releaseId` or, once released, its version. Reads work on any release, writes
  only on a draft.
- **API keys** go in `Authorization: ApiKey <key>`. The `X-API-Key` header is gone.

## 3. Renamed and reshaped members

| 1.x | 2.0 |
| --- | --- |
| `slug`, `id` on resources; `CreateCodeListRequest.slug` | `key` (required on every read model) |
| `tenantId`, `templateId`, `variantId`, `environmentId`, `stencilId` | `tenantKey`, `templateKey`, `variantKey`, `environmentKey`, `stencilKey` |
| `catalogId` / `catalog` | `registry`, `namespace`, `catalogKey` |
| `AttributeDto.displayName`, `CodeListDto.displayName` | `name` |
| `VariantDto.title` | `name` (`UpdateVariantRequest.name` is no longer required) |
| `DocumentDto.id`, `DocumentGenerationJobDto.id`, `DocumentGenerationItemDto.id` | `documentId`, `requestId`, `itemId` |
| `draftLabel` | `releaseName` |
| `PageMeta.totalElements`, `totalCount` | `total` |
| `CollectMeta.count` | `resultCount` |
| `ConsumerDto.updatedAt` | `lastModified` (required) |
| `CatalogDto.releasedVersion` | `highestVersion` |
| `listImages?search=` | `listImages?q=` |
| Enum values `PENDING`, `IN_PROGRESS`, `SINGLE`, `API_KEY`, … | `pending`, `in-progress`, `single`, `api-key`, … (lowercase, kebab-case). `PageFormat` keeps `A4`, `Letter`, `Custom` |
| Template content `ThemeRef.themeId`, stencil node `stencilId`, image node `assetId` | `themeKey`, `stencilKey`, `imageKey` |

**Keys.** Every catalog resource (template, variant, stencil, theme, attribute, code list, font,
image, data example) has one rule: `^[a-z0-9]+(-[a-z0-9]+)*$`, 3–50 characters, with a leading
digit allowed. Before, each type had its own limits. Tenant keys are 3–63 characters.

**Fingerprints** name their algorithm (`v6:<hex>`), and a bare hex value is no longer a
fingerprint. **Versions** are always `version` and ranges always `versionRange`. A release number
may carry a patch label (`1.3.1-gemeente-x.1`), so code that stores or compares versions must
accept it.

## 4. Operations removed, and what replaces them

| Removed | Use instead |
| --- | --- |
| `listVersions`, `getVersion`, `updateVersion`, `publishVersion`, `archiveVersion` | Releases: `listCatalogReleases`, `createCatalogRelease`, `getCatalogRelease`, `updateCatalogRelease`, `deleteCatalogRelease`, `previewCatalogDraftRelease`, `releaseCatalogDraft` |
| `getVariantDraft`, `upsertVariantDraft`, `createVariantDraft`, `publishVariantDraft`, `discardVariantDraft` | `getVariantContent`, `updateVariantContent`, `markVariantReady`, `markTemplateReady` in a draft release |
| `listVariantActivations`, `removeVariantActivation`, `getActiveVersion` | `listEnvironmentDeployments`, `deployCatalogRelease`, `undeployCatalogRelease`, `listDeploymentHistory` |
| `listStencilVersions`, `createStencilVersion`, `getStencilVersion`, `updateStencilDraft`, `publishStencilVersion`, `archiveStencilVersion`, `getStencilVersionUsage`, `applyStencilUpgrade`, `previewStencilUpgrade` | `getStencilContent`, `updateStencilContent`, `markStencilReady`, `listStencilUsage` |
| `createContractDraft`, `updateContractDraft`, `publishContractDraft`, `listContractVersions` | `updateTemplate` (`dataModel`, `dataExamples`) in a draft. A breaking change is reported when the draft is released |
| `releaseCatalog` | `releaseCatalogDraft` |
| `upgradeCatalog`, `previewCatalogUpgrade` | `installCatalogRelease`, `listAvailableCatalogReleases` |

Other removals:

- `TemplateDto.schema`, along with `schema` on the create and update requests. Use `dataModel`,
  which is now always present (`{}` when unconstrained).
- `UpdateTemplateRequest.forceUpdate`.
- `uploadImage`'s `sensitive` field.
- `importCatalog`'s `catalogVersion`, `authoredMode` and `catalogType`.
- `CatalogType`, along with `catalogType` on attribute, code list, font and image DTOs.
- `DeployReleaseRequest`.
- The list `sort`/`direction` parameters that sorted by nothing. Only `listTemplates` sorts, by an
  enum.

## 5. Generating documents

This is the change most integrations need.

- **Request** (`generateDocument`, batch items, `previewDocument`, `validateTemplateData`):
  - `catalogId` becomes `registry` (optional), `namespace` and `catalogKey`.
  - `templateId` and `variantId` become `templateKey` and `variantKey`.
  - `environmentId` becomes `environmentKey`.
  - `versionId` is gone. Name a `versionRange` and/or an `environmentKey`; the highest matching
    release the environment serves is rendered.
  - With neither, the catalog's highest release is rendered, never unreleased changes.
  - In an environment that serves a draft, a request without a range renders that draft.
  - `previewDocument` can render a draft directly (`draft`: its `releaseId`).
- **Attribute criteria** (`VariantSelectionAttribute`) are `{catalogKey, key, value, required}`.
  `catalogKey` is the dependency's alias, or omitted for the template's own catalog. The 1.x dotted
  `<catalog>.<key>` form and the tenant-wide lookup of a bare key are gone.
- **Results.** Jobs, documents and collected results report `registry`, `namespace`, `catalogKey`,
  `templateKey`, `variantKey`, `version` (null for a draft), `releaseId` and `releaseName`.
  `versionId` is gone.
- **Jobs.** `getGenerationJobStatus` returns the job and its counts, and items are listed with
  `listGenerationJobItems` (paged, filterable by `status`). An item no longer echoes its `data`.
  A batch holds at most 10000 items.
- **Documents.** To filter `listDocuments` by `templateKey`, you must also name `namespace` and
  `catalogKey`.

## 6. Errors

- **Every problem has a registered type** under `https://epistola.app/errors/`, and there are 30
  new ones. Switch on the type's slug and fall back to the status for a type you do not know: new
  types may come in minor versions. A generic `conflict` is now often a specific type, such as
  `catalog-read-only`, `catalog-version-behind`, `release-in-use`, `release-number-taken`,
  `resource-modified` or `catalog-draft-name-in-use`.
- **`forbidden` (403)** may say what was refused: `requiredPermission`, `tenantKey`,
  `requiredPlatformRole` or `reason`. Suite's own 403 types (`permission-denied`, `access-denied`,
  `platform-access-denied`) become `forbidden`.
- **A write to a catalog that cannot be changed** is 409 `catalog-read-only` with a
  `readOnlyReason`, no longer a plain `conflict`.
- **Validation errors** may carry a `code` per field (see `x-validation-codes`).
  `data-model-validation-error`'s `validationErrors` is keyed by data example `key`, not by name.
- **`help`** is an optional link to the type's page, carrying the request's context. Do not switch
  on it; `type` is the thing to switch on.

## 7. Authorization

- **Permissions.** `TEMPLATE_PUBLISH` and `STENCIL_PUBLISH` are replaced by `CATALOG_RELEASE` and
  `ENVIRONMENT_DEPLOY`. `TENANT_USERS` is now `CREDENTIALS_MANAGE`. `BACKUP_VIEW`, `BACKUP_CREATE`
  and `TENANT_RESTORE` are gone.
- **Roles.** `CONTENT_VIEWER` no longer grants `DOCUMENT_VIEW`. `DOCUMENT_GENERATOR` grants it, and
  the new `DOCUMENT_READER` grants it alone. The new platform role `INSTALLATION_ADMINISTRATOR`
  maintains the installation itself.
- **Importing** needs `CATALOG_MANAGE`, no longer `TEMPLATE_EDIT`.
- **Consumer permissions.** The `versions` scope and the templates `publish` level are gone, and
  marking ready is part of `write`. `catalogs` gains `release` and `environments` gains `deploy`
  (OAuth scopes `epistola:catalogs:release` and `epistola:environments:deploy`).
- The full vocabulary is machine-readable in `x-authorization` and documented in
  `contracts/api/docs/auth.md`.

## 8. Updates

- **Conditional updates.** Every update returns an `ETag` and accepts `If-Match`. A mismatch is 412
  `resource-modified`. Without `If-Match` the last write wins, as before.
- **Null never removes a value.** On every update, an omitted or null member keeps its value. To
  remove one:
  - send an empty string for text members (descriptions, `contact`);
  - send an empty object or empty array for presets;
  - for members with no empty form, set the explicit flag: `clearDefaultTheme`, `clearExpiresAt`
    or `clearCodeListBinding`.
- **Creates.** Every 201 carries `Location`, and so does the 202 of a generation request (pointing
  at the job). In the Jakarta client, read either header through the `…WithHttpInfo` siblings.

## 9. Limits added

These limits are new in 2.0 and would have been a tightening later:

- **Batches:** at most 10000 items.
- **Code lists:**
  - `sourceUrl` is HTTPS only, at most 2048 characters;
  - names are 1–100 characters and descriptions at most 2000;
  - an entry's `code` is 1–64 characters and its `label` 1–200;
  - a list holds at most 10000 entries, and `listCodeListEntries` is paged.
- **Attributes:** names are 1–100 characters, and `allowedValues` holds at most 1000.
  `description` is gone.
- **Variants:** descriptions are at most 2000 characters, and attribute values at most 255.
- **Themes:** names are 1–255 characters and descriptions at most 2000. `spacingUnit` is 0.5–16.
- **Version ranges:** at most 256 characters.
- **Variant selection:** a variant has at most 50 attributes, a generation or preview request at
  most 50 attribute criteria, and each value at most 255 characters.
- **Templates:** at most 100 data examples, each `name` at most 255 characters.
- **Drafts:** at most 50 dependencies and 100 declared presets, and `acknowledgedBehind` at most
  1000 versions.
- **Themes:** at most 100 block style presets.
- **Attributes:** each `allowedValues` entry at most 255 characters.
- **Images:** `mediaType` at most 255 characters.

## 10. Catalog archives (wire v8)

`x-epistola-catalog-contract.wireSchemaVersion` is 8. Archives from wire v7 and earlier still read
and are migrated. They are never tightened, and a v7 archive's fingerprint still verifies.

- **Identity.** A catalog is identified by `catalog.registry` (`local` or a DNS name) and
  `catalog.namespace`, both required. To migrate a v7 archive you must supply them
  (`CatalogMigrationOptions`, or `importCatalog`'s `registry` and `namespace`). They are never
  derived from a tenant or host.
- **Dependencies.** `catalogDependencies` declares each catalog depended on, with an `alias`, the
  pinned `releaseVersion` and its `releaseFingerprint`. Content names another catalog only by that
  alias, never by its own key.
- **Releases.** `release.version` is required, `MAJOR.MINOR.PATCH` with an optional patch label.
  A v7 pre-release such as `1.0.0-beta.1` is a finding, and is never stripped. A v8 release carries
  its `v6:` fingerprint, which covers content only: not the catalog's name, its presentation or
  release metadata.
- **Keys.** Every resource has a `key`, with the same rule as the API. The migration pads a key of
  1–2 characters with `0` (`nl` becomes `nl0`), truncates one over 50, and rewrites references to
  match.
- **Stencils** have no versions. A migrated v7 stencil keeps the scope v7 rendered it with
  (`readsTemplateData`, `passParameters`).
- **Paths** inside the archive (`detailUrl`, `contentUrl`) must be relative.
- **Carried releases.** An archive may carry the releases it pins, under `dependencies/`.
- **New, all optional:**
  - `tags` and producer `metadata` on every resource;
  - a variant `description`;
  - data example `key`s;
  - a theme's `defaultBlockStyles`;
  - the catalog's `defaultTheme` and `presets`;
  - quality profiles (alpha).

  The component registry no longer gives blocks a default `marginBottom`: a theme sets it.

The Kotlin and npm catalog libraries follow these renames (`CatalogSlugs` becomes `CatalogKeys`,
for example). `contracts/catalog/docs/catalog-compatibility.md` has the details.

## 11. Client libraries

- **Template data validation** goes through the server (`validateTemplateData`) behind a pluggable
  `TemplateDataValidator`. No client bundles a JSON Schema library any more. A failure's `path` is a
  JSON Pointer, and its `keyword` is a JSON Schema keyword.
- **Problem accessors** exist for every new problem member, each with an `is…Problem` flag, in
  each language's spelling.
- **Result collectors** type `registry`, `namespace` and `catalogKey` as non-null, and surface
  `releaseId` and `releaseName`.
- **Newer servers.** A later 2.x may add response members and enum values. The clients ignore an
  unknown member. The Kotlin, Jakarta and .NET enums read an unknown value as
  `unknown_default_open_api`, so an exhaustive `when` or `switch` over one needs a branch for it.
  The Python client keeps the value as received, and so does the Node.js client.
- See the .NET, Python and Node.js client changelogs for the per-language details.
