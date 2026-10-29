# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
The .NET, Python and Node.js clients keep their own changelogs for changes to their hand-written
libraries.

## [Unreleased]

### Added

- **A template can be created with its data contract** (`createTemplate`). `CreateTemplateRequest`
  takes optional `dataModel` and `dataExamples`, as `updateTemplate` does, so a template need not
  exist without them. Omitted or null, the template gets the empty schema `{}`, which any data
  satisfies, and no examples. Examples that do not satisfy the data model are refused with 422
  `data-model-validation-error`, which `createTemplate` now lists. Epistola Suite may not implement
  it yet. Conformance scenarios `create-template` and `create-template-without-contract` (driver
  action `create-template`) hold all five clients to sending the contract and examples as written,
  and nothing for them when absent, and to reading the created template and its `Location` back,
  an empty `{}` data model as one rather than none.

- **An image accepts `keepWithNext`** (#58): the `image` component lists it in its
  `applicableStyles`, so an image can be kept on the page of the block after it, and the validator
  no longer warns `TEMPLATE_STYLE_NOT_APPLICABLE` for it. `keepTogether` is not added: an image is
  one unbreakable block already.

- **Every catalog resource carries producer `metadata`, and no hash covers it** (wire v8).
  `CatalogResource.metadata` is common to every type: producer-specific JSON objects keyed by a
  reverse-DNS namespace the producer owns, one to sixteen namespaces, at most 8 KiB serialized
  (`CatalogMetadata`; `CATALOG_RESOURCE_METADATA_INVALID`, `CATALOG_RESOURCE_METADATA_TOO_LARGE`),
  omitted when empty. Every reader keeps it member for member. Metadata, at resource or release
  level, is what a producer says about the content, not the content: no fingerprint of any algorithm,
  catalog or per-resource, covers it, so two resources or releases that differ only in metadata have
  the same fingerprint. The rendering-defaults version a release was cut with stays release metadata
  (#96): another generator may render the same catalog with defaults of its own.

  **Breaking (wire v8, alpha): `VariantEntry.metadata` is removed**, with its codes
  `CATALOG_VARIANT_METADATA_INVALID` and `CATALOG_VARIANT_METADATA_TOO_LARGE` (now the resource
  codes) and the npm constants `CATALOG_VARIANT_METADATA_NAMESPACE_PATTERN` and
  `MAX_CATALOG_VARIANT_METADATA_*` (now `CATALOG_METADATA_NAMESPACE_PATTERN` and
  `MAX_CATALOG_METADATA_*`); Kotlin's `CatalogVariantMetadata` is now `CatalogMetadata`. A wire-v8
  archive that still carries metadata on a variant has it folded into its template's when read, a
  variant's `{"<ns>": V}` becoming the template's `{"<ns>": {"variants": {"<variant key>": V}}}`
  (notice `CATALOG_VARIANT_METADATA_FOLDED`; `CATALOG_VARIANT_METADATA_UNFOLDABLE` when that would
  overwrite a value). Because variant metadata was covered by V5 and V6 and metadata no longer is, a
  release that carried non-empty variant metadata has a different V5 and V6 fingerprint than before;
  the wire-v8 golden fingerprints move accordingly.

- **A conditional has an optional `else` branch** (#97). The `conditional` component declares a
  second static slot, `else`, beside `body`: when the condition holds the body renders, and
  otherwise the `else` slot does, so one boolean no longer needs two mirrored conditionals. The
  slot is optional, so every existing document stays valid; `inverse` still flips the condition
  first, which swaps the two branches. A four-way choice nests conditionals; a `choose`/`when`
  component can follow if one is needed. The registry gains a `with-else` example. Epistola Suite
  may not render the `else` slot yet.

- **Every catalog resource carries `tags`** (wire v8). `CatalogResource.tags` is common to every
  type, so a consumer can tag and filter templates, themes, attributes, images, code lists and fonts
  as it already did stencils. A type that had no tags leaves them out of its JSON when there are
  none (`NON_EMPTY`), so no V5 or V6 fingerprint and no content hash moves; the legacy algorithms
  (V1 to V4) leave them out too (`CatalogV8Additions`). A stencil writes its tags as before, an empty
  list included, so its fingerprints stay put. The v8 resource schema gains an optional `tags` on
  each type, the same shape as a stencil's; a reader on `2.0.0-alpha.1` refuses a resource that
  carries them on another type, which wire v8, alpha, allows itself.

- **A template's variant carries a `description`** (wire v8): plain text for the variant's authors,
  optional, at most 2000 characters. Left out when there is none (`NON_EMPTY`), so no V5 or V6
  fingerprint and no content hash moves; the legacy algorithms (V1 to V4) leave it out too
  (`CatalogV8Additions`). A reader on `2.0.0-alpha.1` refuses a variant that carries one, which wire
  v8, alpha, allows itself.

- **A release carries its content hash, and is found by it** (contract ADR 0003, amendment of
  2026-10-22; Epistola Suite ADR 0034). New identifier `ContentHash`, the V6 fingerprint (`v6:` and
  64 lowercase hex digits), which identifies a release whatever its name or number.
  `CatalogReleaseDto.contentHash` beside `fingerprint` (the recorded one, any algorithm), so also
  in `listCatalogReleases`, `getCatalogRelease` and the release `releaseCatalogDraft` returns: the
  same as `fingerprint` for a release made under V6, computed from the content for one recorded
  with an earlier algorithm; null for a draft, for a release recorded before the server computed
  content hashes until it has, and for one whose retained content no longer reproduces its
  fingerprint. `CatalogDto.contentHash` (the highest release's), `RegistryReleaseDto.contentHash`
  (what the registry records, a label), `CatalogMergeBaseRef.contentHash` (lineage is by hash; in
  `missingBase` and a registry release's `base`) and, for pins, `CatalogDependencyDto.contentHash`
  and `ReleaseDependencyDto.contentHash`, all optional and nullable. `unchangedContent` now says the
  released content is another release's by content hash. **Finding a release by its content** is a
  filter, `listCatalogReleases?contentHash=` (anything but a `ContentHash` is 400), rather than a
  path: several numbers can name one content (a release whose changes restore an earlier one, one
  received again under another number), so the answer is a list, empty rather than 404, with the
  `status` filter and paging the list already has, and `…/releases/{release}` keeps addressing one
  release by id or number. The clients' validation tests hold `contentHash` to `v6:`. Conformance:
  `list-catalog-releases` sends `config.contentHash` and reports `contentHashes`;
  `get-catalog-release` reports `contentHash` and `dependencyContentHashes`;
  `release-catalog-draft` reports `contentHash` and `unchangedContent`; dependency reports carry
  `contentHashes`; `list-registry-releases` reports `contentHashes` and `baseContentHashes`;
  `preview-catalog-draft-merge` reports `missingBaseContentHash`; new scenario
  `list-catalog-releases-by-content-hash`.

- **Resolving a merge conflict with merged content (alpha, `x-experimental`, ADR 0005).** So that a
  person, an assistant or a tool can combine both sides of a conflict:
  `getCatalogDraftMergeSides` (`GET
  …/catalogs/{registry}/{namespace}/{catalogKey}/releases/{release}/merge-sides?version=&entry=`,
  `CATALOG_VIEW`) returns one listed entry's content on each side, `{entry, mergeBase, base, draft,
  release, token}`, each side a free-form JSON object in the portable catalog format
  (`CatalogMergeEntryContent`: a resource as a resource detail carries it, `{"defaultTheme": …}`,
  `{"presets": […]}`, a dependency declaration) or null; 404 when the merge does not list the
  entry, 409 `merge-base-missing` (`MergeSidesConflictError`) without the merge base.
  `MergeIntoCatalogDraftRequest.mergedContent` (optional, `type/key` to that content) resolves a
  conflict to the content given; it is written into the draft as given, not marked ready, so it is
  reviewed before the draft is released, and listed in the response's new optional `merged`.
  Invalid content is 400 `validation-error` (`field` `mergedContent.<type/key>…`), a result with an
  unresolved reference 409 `merge-refused`, content for an entry that is not a conflict 422
  `merge-not-applicable`. `resolutions` keeps its type, a map of `ours`/`theirs` strings: a `oneOf`
  of a string and an object there generated a type holding only the content in the Kotlin and
  Jakarta clients (and the Kotlin server stubs), so no side could be sent. A null member and an
  absent one mean the same in merged content. Jakarta's `GeneratedApiContractTest` and the Node
  client's operation test name the operation. Conformance: a driver action
  `get-catalog-draft-merge-sides` in all five clients, `merged` reported by
  `merge-into-catalog-draft`, which sends `config.mergedContent`, and scenarios
  `get-catalog-draft-merge-sides`, `merge-into-catalog-draft-merged-content` and
  `merge-into-catalog-draft-merged-content-invalid`.

- **A merge preview says what goes together (alpha, `x-experimental`, ADR 0005).**
  `CatalogDraftMergeResource.needs` (optional array of `resourceType/resourceKey`): the other
  entries of the preview the entry's value refers to, so a choice is made with them in view (a
  template needs a stencil or theme it uses; `catalog/defaultTheme` needs
  `catalogDependency/shared` when the theme is in catalog `shared`). Empty or absent when none.
  Conformance: `preview-catalog-draft-merge` carries it, and every driver reports `resourceNeeds`.

- **Release registries (alpha, `x-experimental`, ADR 0008).** One way to move releases between
  installations, whatever carries them: an Epistola Exchange (`exchange`) or a container registry
  (`container`). `listReleaseRegistries` (`GET /tenants/{tenantKey}/release-registries`, `{items:
  [{name, kind}]}`, no credentials), `listRegistryReleases` (`GET
  …/release-registries/{name}/catalogs/{namespace}/{catalogKey}/releases`, `version`,
  `fingerprint`, `releaseId`, `base`, highest first, best effort), `installRegistryRelease` (`POST
  …/releases/{version}/install`, installed as `importCatalog` installs an archive, with its response
  and refusals; a new catalog gets the registry as its source) and `publishReleaseToRegistry`
  (`POST …/catalogs/{registry}/{namespace}/{catalogKey}/releases/{release}/publish` `{to}`, status
  `published`, `already-published` or `queued`, and the registry's `digest`). A release registry is
  named by the installation (`ReleaseRegistryName`) and is not the `{registry}` of a catalog's name.
  New problem types 404 `release-registry-not-found`, 409 `release-number-taken` (the registry has
  this version with other content) and 409 `namespace-not-granted` (the Exchange does not grant the
  catalog's namespace); a draft is 409 `conflict`, a registry that cannot be used
  `catalog-source-unavailable`. Permissions `CATALOG_VIEW`, `CATALOG_MANAGE` and `CATALOG_PUBLISH`
  (now in the role table, granted by `CONTENT_PUBLISHER`). Server `ProblemDetails.KnownSlugs` and
  every client's registry test name the slugs; Jakarta's `GeneratedApiContractTest` holds the
  release-registry paths to `name`, `namespace`, `catalogKey`. Conformance: four driver actions in
  all five clients and scenarios `list-release-registries`, `list-registry-releases`,
  `install-registry-release`, `publish-release-to-registry`, `…-already-published` and
  `…-number-taken`.
- **Problem type `catalog-rendering-defaults-too-new`** (400). A release received from elsewhere
  (`importCatalog`, `installCatalogRelease`) whose `release.metadata` names rendering defaults the
  installation does not have, newer or unknown, is refused before anything is written: rendering it
  with other defaults would change its documents, so Epistola must be upgraded to receive it.
  `CatalogRenderingDefaultsTooNewProblemDetail` adds `renderingDefaultsVersion` (a string, as the
  release names it) and `supportedVersion` (an integer, the newest the installation has). Both
  operations' 400 responses name it (`CatalogImportBadRequestError`,
  `CatalogInstallBadRequestError`). Registered in `x-problem-types` and `error-types.md`; server
  `ProblemDetails.catalogRenderingDefaultsTooNew` and `KnownSlugs.CATALOG_RENDERING_DEFAULTS_TOO_NEW`;
  every client's `KnownProblemSlugs` carries it, and the Kotlin and Node.js clients read the
  members from `extensions` (no typed accessors).
- **A merge merges a release's catalog settings (alpha, `x-experimental`, ADR 0005).** Besides
  resources, `previewCatalogDraftMerge` and `mergeIntoCatalogDraft` classify and merge the release's
  default theme, presets and dependency declarations, one entry each, with `resourceType`
  `catalog` and `resourceKey` `defaultTheme`, `presets` or `dependencies`; a conflict on one is
  resolved like a resource's (`resolutions`, `catalog/defaultTheme`), and `taken` and `kept` list
  them the same way. The catalog's name, description and other catalog-wide details are not a
  release's and are not merged. `CatalogDraftMergeResource.resourceKey` is therefore no longer held
  to the resource-key rule (`ResourceKey`): a string of 1 to 50 characters, a resource's key or a
  setting's (`defaultTheme` is camel case), since a client validating the old pattern refused every
  preview with a setting in it; `resourceType` stays a string, its values now documented. ADR 0005
  is amended (it said the draft's settings stay its own). Conformance:
  `preview-catalog-draft-merge` and `merge-into-catalog-draft` carry setting entries.

- **A complete per-resource fingerprint** (catalog library).
  `CatalogCanonicalizer.completePerResourceFingerprints` maps every `type/key` resource to a hash of
  its complete wire-v8 content, as V5 covers it: the per-resource counterpart of
  `currentFingerprint`, under the v8 member names, covering a template's `description`, a variant's
  `metadata` and a data example's `key`, an image's bytes included, under its own domain prefix
  (`resource sha256-v5`). It is what a consumer should use to tell resources apart when merging or
  diffing; `perResourceFingerprints` (V1) and `currentPerResourceFingerprints` (V3) leave those
  members out, so a change to only them did not show, and keep their values. ADR 0005 now names it
  as what the merge compares. The published `fingerprints.json` pins it for the wire-v8 golden
  catalog (`completeWireV8ResourceFingerprints`). Kotlin only: the npm package exports no
  fingerprint functions.
- **A missing merge base (alpha, `x-experimental`, ADR 0005).** A merge compares against the merge
  base, the closest release both the draft and the release descend from, so it needs that release
  installed. `previewCatalogDraftMerge` still answers without it: `CatalogDraftMergePreview` gains an
  optional `missingBase` (`CatalogMergeBaseRef`: `version`, `fingerprint`) naming that release, and
  classifies nothing. `mergeIntoCatalogDraft` is refused with the new 409 `merge-base-missing`
  (`MergeBaseMissingProblemDetail`, members `version` and `fingerprint` of the release to install
  first; server `ProblemDetails.mergeBaseMissing`). Every client's problem parser surfaces them as
  `missingBaseVersion`/`missingBaseFingerprint` (`MissingBaseVersion`/`MissingBaseFingerprint` in
  .NET, `missing_base_version`/`missing_base_fingerprint` in Python) and `isMergeBaseMissingProblem`,
  read from that problem only, since other problems use `version` for other things. Conformance:
  the drivers report the preview's `missingBase` and the problem's `problemMissingBase` in all five
  clients; scenarios `merge-into-catalog-draft-base-missing` and
  `preview-catalog-draft-merge-base-missing`.
- **A merge preview's token (alpha, `x-experimental`, ADR 0005).** `CatalogDraftMergePreview` gains
  a required `token` (an opaque string, at most 128 characters) that identifies what the preview
  was computed from: the draft, the release and the merge base. `MergeIntoCatalogDraftRequest`
  gains an optional `expectedToken`: when given and the draft or the release changed since that
  preview, the merge is refused with the new 409 `merge-preview-outdated` ("The draft or the
  release changed since the preview; preview again") and nothing changes. Server
  `ProblemDetails.KnownSlugs.MERGE_PREVIEW_OUTDATED`. The conformance action
  `preview-catalog-draft-merge` reports `token` and `merge-into-catalog-draft` sends
  `expectedToken` in all five drivers; scenario `merge-into-catalog-draft-preview-outdated`, and
  `merge-into-catalog-draft` checks that no token goes out when none is given.
- **A release names its merge base** (catalog wire v8, changed in place: v8 is unreleased; ADR
  0005, "The merge base travels with the release"). `release.base` (`ReleaseInfo.base`,
  `ReleaseBase`, also a TypeScript type) names the release this one was made from by identity only:
  its `version` and its qualified `fingerprint` (`ReleaseBase.FINGERPRINT_PATTERN`,
  `isValidFingerprint`: any known algorithm, as a catalog dependency pins a release). A receiver
  follows these links to the closest release both lines share; a merge needs that release
  installed and checks it by fingerprint. Optional, omitted when absent (a first release, or a base
  the producer does not know), and not covered by any fingerprint, so every published fingerprint
  stays valid. New finding, from the validator and the migrator's wire check:
  `CATALOG_RELEASE_BASE_INVALID`. ADR 0005 now names `currentPerResourceFingerprints`, the
  per-resource form of V3, as what the merge compares.
- **Merging a release into a draft (alpha, `x-experimental`)**, per ADR 0005, now Accepted (alpha).
  `previewCatalogDraftMerge` (`GET …/releases/{release}/merge-preview?version=`) returns
  `CatalogDraftMergePreview`: the `draft`, its `base`, the `release` merged in, `alreadyMerged`, and
  per resource a side changed (`CatalogDraftMergeResource`: `resourceType`, `resourceKey`, `name`,
  `outcome` `ours`/`theirs`/`conflict`, `draftChange` and `releaseChange` `added`/`modified`/
  `removed` or null), with the rest counted in `unchanged`. `mergeIntoCatalogDraft`
  (`POST …/releases/{release}/merge`, `MergeIntoCatalogDraftRequest` `{release, resolutions}`,
  resolutions `resourceType/resourceKey` to `ours` or `theirs`) returns
  `MergeIntoCatalogDraftResponse` `{release, taken, removed, kept}`. The release is a query
  parameter `version` in the preview because `{release}` in the path names the draft. The draft
  then contains the release and is not behind it (`CatalogReleaseDto.behind` leaves merged releases
  out); its base does not move. New problem types: 409 `merge-conflicts-unresolved`
  (`MergeConflictsUnresolvedProblemDetail`, whose `unresolvedConflicts` every client's problem
  parser surfaces; server `ProblemDetails.mergeConflictsUnresolved`), 409 `merge-refused` (the
  release removes a resource the draft still uses) and 422 `merge-not-applicable` (the draft
  already contains the release, or a resolution names a non-conflict); a read-only catalog is 409
  `catalog-read-only`. Conformance actions `preview-catalog-draft-merge` and
  `merge-into-catalog-draft` in all five drivers, with scenarios `preview-catalog-draft-merge`,
  `merge-into-catalog-draft`, `merge-into-catalog-draft-conflicts-unresolved` and
  `merge-into-catalog-draft-read-only`.
- **A release carries its id and producer metadata** (catalog wire v8, changed in place: v8 is
  unreleased). `release.releaseId` (`ReleaseInfo.releaseId`) is the release's id, by the API's
  `CatalogReleaseId` rule (`CatalogReleaseIds`; TypeScript `CATALOG_RELEASE_ID_PATTERN`,
  `MIN_CATALOG_RELEASE_ID_LENGTH`, `MAX_CATALOG_RELEASE_ID_LENGTH`), unique among the releases of
  the catalog's name and kept when the release moves to another installation.
  `release.metadata` (`ReleaseInfo.metadata`) holds producer-specific facts under a reverse-DNS
  namespace per producer, by the rules of a variant's `metadata`: Epistola Suite records its
  `renderingDefaultsVersion` there. Consumers keep every namespace, unknown ones included, member
  for member. Both are optional, omitted when absent, and not covered by the fingerprint, so every
  published fingerprint stays valid. New findings `CATALOG_RELEASE_ID_INVALID` (validator and the
  migrator's wire check), `CATALOG_RELEASE_METADATA_INVALID` and `CATALOG_RELEASE_METADATA_TOO_LARGE`.
- **A catalog's default theme and declared presets** (ADR 0007). The catalog manifest's `CatalogInfo`
  gains two optional wire-v8 members, added to v8 because 2.0 is unreleased: `defaultTheme`
  (`CatalogThemeRef`: `key`, and `catalogKey`, `namespace`, `registry` qualified like every other
  cross-catalog reference), the theme a template that names none renders with; and `presets`
  (`CatalogPresetDeclaration`: `key`, `name`, `description`), the block style presets the catalog's
  content may use, keys unique (`CatalogPresets`, exported to TypeScript as
  `CATALOG_PRESET_KEY_PATTERN` and the `MAX_CATALOG_PRESET_*` limits). Both are omitted when absent,
  and V5 covers them only when present, so every published fingerprint stays valid; presets are
  canonicalized by key. The validator resolves the default theme in the catalog or a declared
  dependency and checks the presets (`CATALOG_PRESET_KEY_INVALID`, `CATALOG_PRESET_DUPLICATE`,
  `CATALOG_PRESET_NAME_INVALID`, `CATALOG_PRESET_DESCRIPTION_INVALID`); the migrator's wire check
  applies the preset rules; archives from wire v7 and earlier carry neither. In the API,
  `CatalogReleaseDto` gains `defaultTheme` (`CatalogThemeReference`) and `presets`
  (`CatalogPresetDto`), and `updateCatalogRelease` also sets them on a draft: an omitted member keeps its value,
  null (or an empty preset array) clears it.
- **Optimistic locking with `ETag` and `If-Match`, on every update.** Templates, variants, themes,
  stencils, code lists, attributes, releases, environments, tenants and consumers carry a
  revision, returned as a strong `ETag` response header (a quoted decimal integer, e.g. `"7"`) by
  `getTemplate`, `getVariant`, `getVariantContent`, `getTheme`, `getStencil`, `getStencilContent`,
  `getCodeList`, `getAttribute`, `getCatalogRelease`, `listCatalogDependencies`, `getEnvironment`,
  `getTenant` and `getConsumer`, and on the 200 of a successful update. `updateTemplate`,
  `updateVariant`, `updateVariantContent`, `setDefaultVariant` (the `ETag` of the variant being
  made default), `updateTheme`, `updateStencil`, `updateStencilContent`, `updateCodeList`,
  `updateCodeListEntryHidden` (the code list's `ETag`), `updateAttribute`, `updateCatalogRelease`,
  `updateCatalogDependencies` (both the release's `ETag`, which covers its name, default theme,
  presets and dependency declarations), `updateEnvironment` (its name and production mark),
  `updateTenant` and `updateConsumer` take an optional `If-Match` header:
  when it no longer matches, the update is refused with 412 `resource-modified` (a new problem type,
  `ResourceModifiedError`) and nothing changes. Without `If-Match` an update applies as before, last
  write wins. A template's `ETag` covers its own properties; a variant's covers its title,
  attributes and template model, whether read through `getVariant` or `getVariantContent`. New
  reusable components: the `IfMatch` parameter and the `ETag` response header. Conformance
  scenarios `update-tenant`, `update-environment-if-match`, `update-consumer-resource-modified`,
  `update-catalog-release`, `catalog-dependencies-update-resource-modified` and
  `update-code-list-entry-hidden`, with the new driver actions `update-tenant` and
  `update-code-list-entry-hidden`.
- **`Location` on every create.** Every 201 (`createTenant`, `createEnvironment`,
  `registerConsumer`, `createTemplate`, `createVariant`, `createStencil`, `createTheme`,
  `createAttribute`, `createCodeList`, `uploadImage`, `createCatalogRelease`) carries the created
  resource's address in `Location`, and the 202 of `generateDocument` and
  `generateDocumentBatch` the job's (`getGenerationJobStatus`). New reusable component: the
  `Location` response header. The `create-catalog-release`, `create-environment` and
  `generate-document` driver actions report the header as received.
- **Releasing takes along the drafts it uses** (ADR 0002). In a draft, a dependency declaration
  names either an exact release or a draft of the other catalog: `CatalogDependencyRequirement` and
  `CatalogDependencyDto` gain a nullable `releaseId`, exactly one of it and `version` (400
  otherwise); the draft then renders with that draft, live. A release never pins a draft, so catalog
  archives are unchanged. Releasing a draft whose declarations name drafts is refused with 409
  `release-dependencies-unreleased` (a new problem type, `ReleaseDependenciesUnreleasedError`), whose
  `unreleasedDependencies` member lists each such draft, directly or through those drafts'
  declarations (`UnreleasedCatalogDependency`: `registry`, `namespace`, `catalogKey`, `releaseId`,
  `releaseName`). `ReleaseCatalogDraftRequest.releaseDependencies` (default false) releases them first,
  recursively, each as the version its own changes need, in the same transaction, all or nothing (one that is
  refused, for instance behind without acknowledgement, releases nothing), and the new release pins
  them as released; `ReleaseCatalogDraftResponse.releasedDependencies`
  (`ReleasedDependencyDto`: the `releaseId` and the `version` it was released as) lists them. A
  draft released on its own keeps its `releaseId`, and declarations naming it then name its version; one that a declaration names cannot be discarded. Every
  client's problem parser surfaces `unreleasedDependencies`; the server stubs add
  `ProblemDetails.releaseDependenciesUnreleased`. Conformance scenarios
  `catalog-dependencies-update-draft`, `release-catalog-draft-with-dependencies` and
  `release-catalog-draft-dependencies-unreleased`.
- **An environment serves releases, a draft included, and a production mark** (ADR 0002,
  ADR 0003 "Deploying"). A deployment names a release by `releaseId` or version:
  `deployCatalogRelease` and `undeployCatalogRelease` (`…/deployments/{registry}/{namespace}/{catalogKey}/{release}`,
  no request body). A draft can be deployed, which is how it is tried: it renders live, with no
  version, and an environment serves at most one draft per catalog (deploying another replaces
  it, logged as undeployed). A generation or preview request through the environment that names
  no `versionRange` renders the deployed draft if there is one, else the highest served release; one that
  names a range or an exact version renders the highest served release that satisfies it, never a
  draft. Rollback is undeploying the newer release. Releasing a deployed draft keeps its
  `releaseId`, so the environment then serves it as released, an explicit deployment logged as
  `released`; discarding it ends the deployment, logged as `undeployed`. A deployed draft's pinned
  dependency releases are used implicitly, a declaration naming another catalog's draft renders
  that draft live, and `includeDependencies` deploys pinned releases only. `EnvironmentDto` gains
  the required `production`, settable on create and update: a production environment serves only
  releases. Deploying a draft to one, or marking production an environment that serves a draft, is
  409 `environment-deployment-refused` (a new problem type). `DeploymentDto` and
  `DeploymentEventDto` carry the required `releaseId`, `status` (`CatalogReleaseStatus`: `draft`
  or `released`) and `releaseName` (the release's name, null only for an installed release, which
  has none), with a nullable `version` (null for a draft); the history's `action` is `deployed`,
  `undeployed` or `released`. Documents, generation items and collected results rendered from a
  draft carry `releaseName` (its name at render time) with a null `version`. Every client's problem-type helpers know the new slug.
  Conformance scenarios `deploy-catalog-draft`, `deploy-catalog-draft-refused`,
  `list-environment-deployments-with-draft`, `undeploy-catalog-draft` and
  `update-environment-production-refused`.
- **A catalog archive can carry the releases it pins** (wire v8). Beside `catalog.json`, an
  archive may contain `dependencies/{registry}/{namespace}/{catalogKey}/{releaseVersion}.zip`, an
  ordinary catalog archive of a release its `catalogDependencies` pin, found from the pin alone: no
  manifest member names it, so the manifest and the V5 fingerprint (which never covers
  `dependencies/`) are identical whether the release is exported thin or with its dependencies.
  Every release reachable through the pins, a dependency's own pins included, lives once in the
  outer archive's `dependencies/`; carried archives are thin. Each carried release must name the
  catalog and number of its path and match every reaching pin's `releaseFingerprint`, or the whole
  archive is refused. A pin without a carried archive is resolved by the installation (ADR 0003).
  New reader findings: `CATALOG_ARCHIVE_DEPENDENCY_PATH_INVALID`, `_COUNT_EXCEEDED`, `_NESTED`
  (a carried archive with its own `dependencies/`), `_IDENTITY_MISMATCH`, `_FINGERPRINT_MISMATCH`,
  `_UNREFERENCED` (no pin reaches it) and `_CONFLICT` (two pins name different releases of one
  catalog). Each carried archive is held to the per-archive limits; `CatalogArchivePolicy` adds
  `maxDependencies` (50), `maxTotalCompressedBytes` (100 MiB) and `maxTotalExpandedBytes`
  (200 MiB). `CatalogArchive.dependencies` lists the carried releases in install order (each
  before every release that pins it, `EmbeddedCatalogRelease`), `CatalogArchiveWriter` writes them,
  and `CatalogValidator` checks them; the path rule is `CatalogDependencyArchivePaths` (Kotlin) and
  `CATALOG_DEPENDENCY_ARCHIVE_PATH_PATTERN`, `parseCatalogDependencyArchivePath` and
  `catalogDependencyArchivePath` (npm). Golden fixtures in `fixtures/v1/carried-releases/`. Wire v5
  to v7 archives never carry dependencies; their rules are unchanged. `importCatalog` installs the
  carried releases the tenant lacks first, side by side with its other releases, and reports them
  (`installedDependencies`, `presentDependencies`); one it has with the same version but a
  different fingerprint refuses the import with 409, and a pin it can neither find in the archive
  nor have refuses it with 409 `release-dependencies-missing`. The API has no export operation, so
  exporting with dependencies is a producer's choice.
- **Deployments report how much they are used**, so an operator can see which deployed releases
  nobody uses any more before undeploying them. `DeploymentDto` (from `listEnvironmentDeployments`
  and `deployCatalogRelease`) gains the required `lastUsedAt` (nullable `date-time`: null when no
  request has used the release since it was deployed) and `documentsRequested` (`int64`, at least
  0). A generation request is counted when it names the environment and is accepted and rendered
  with that deployed release; requests naming no environment are not. Both are updated within
  about a minute and reset when the release is undeployed; a deployed draft that is released keeps
  its counts.
- **`deployCatalogRelease` can replace what an environment serves of a catalog.** The optional
  query parameter `replace` (default false) also stops serving the catalog's other releases in that
  environment, its deployed draft included, in the same transaction, each logged as undeployed.
  Without it, a release is still added beside the others.

- **Stencils by reference, with lexical scope (ADR 0006), catalog side.** A stencil declares
  `readsTemplateData` (wire v8, default `false`, left out of the JSON when false and not covered by
  the legacy fingerprint algorithms) when its content also sees the using template's data. The
  template validator accepts `readsTemplateData` on a stencil node, and `passParameters` and
  `parametersAs` on a placeholder (a placeholder that passes the stencil's parameters to its fill,
  under a name following the `paramsAlias` rules). `CatalogDocumentMigration` migrates one stored
  template document or style map from wire v7 to v8 exactly as the archive migration does inside a
  resource, so a producer's store and an archive migrate alike.

- **Releases: a catalog has any number of drafts, each released on its own** (ADR 0002). A
  catalog consists of releases, one object whether it is a draft or released: each has a `status`,
  `draft` (a draft release, or draft: editable, with no version) or `released` (immutable, with a
  `version`, its number). `version` is only ever that number, and `versionRange` a range of them. A
  release is addressed as `…/catalogs/{registry}/{namespace}/{catalogKey}/releases/{release}`,
  where `{release}` (`CatalogReleaseRef`) is its `releaseId` or, once released, its version, which
  address the same release. Every release has a `releaseId` (`CatalogReleaseId`: opaque,
  server-assigned, unique in its catalog, `^[a-z0-9]+(-[a-z0-9]+)*$`, 4–36 characters, never a dot,
  so never mistaken for a version; a short random id such as `k7m2x9qa` is recommended, a UUID
  fits), assigned when it is started as a draft and kept when it is released; a release installed
  by import or from its source gets one too. An AUTHORED catalog has any number of drafts, all
  alike: each has a `name` unique among the catalog's drafts and a fixed `base`, the release it
  started from (null when there was none). A release keeps its `releaseId` and its `name` when it
  is released; an installed release has no name. `listCatalogReleases` (drafts included, filtered
  by `status`, `CatalogReleaseListResponse`), `createCatalogRelease` (always a draft,
  `CreateCatalogReleaseRequest` `{name, from?}`: from a version or another release's `releaseId`,
  by default the highest release), `getCatalogRelease` (`CatalogReleaseDto` with the required
  `releaseId`, `status`, `name`, `createdFrom`, and a `version` that is null for a draft),
  `updateCatalogRelease` (`UpdateCatalogReleaseRequest`: a draft's name, default theme and
  presets; a released release, its name included, is refused with 409), `deleteCatalogRelease`
  (discards a draft; a released release is refused with 409), `previewCatalogDraftRelease`
  (`CatalogDraftReleasePreview`: the `base`, the kind of change against it and why, the version
  the changes need, `needed`, and the version the draft gets, `suggested`) and
  `releaseCatalogDraft`, which releases any draft. Its version follows from the kind of change
  against the draft's base (patch, minor, major); when that version is taken, the next free one of
  the same kind. The author may give a higher `version` (optional); one lower than the changes
  need, not above the base, or taken is refused with 400. From `1.4.0` while `1.5.0` and `2.0.0`
  exist, a fix becomes `1.4.1` and a breaking change `3.0.0`, so an older line is fixed by an
  ordinary release. The draft becomes released at that version, immutable, and keeps its
  `releaseId` and its name: it is addressed by either, and a write through either is refused with
  409. `ReleaseCatalogDraftResponse.release` is the released release. A catalog's drafts are
  listed only by `listCatalogReleases` (`?status=draft`); `CatalogDto` does not list them. Preview renders a draft
  named as `draft` (its `releaseId`). "Publish" keeps its one meaning, making a catalog available in
  a registry; releasing is not publishing, and a draft is never published.
- **The changes of a release, and deployments.** A released release also carries the releases of
  other catalogs it renders with (`dependencies`), and every release lists the environments that
  serve it (`deployedTo`). `getCatalogChanges` lists what is new, modified, ready or removed since the
  release's base and whether releasing it would be accepted;
  `markCatalogReady` marks everything changed ready in one action. An environment can
  serve several releases of one catalog: `listEnvironmentDeployments`, `deployCatalogRelease` and
  `undeployCatalogRelease` (both addressed by the release, with no request body), and
  `listDeploymentHistory` with every change to what the environment serves.
  `undeployCatalogRelease` removes an explicit deployment only, and is never refused because another
  release the environment serves renders with it: that release keeps rendering it, implicitly. A
  release the environment uses only implicitly cannot be undeployed (404) (ADR 0003).

- **A template has a `description`** (#97): optional plain-text notes for its authors, at most
  2000 characters, not rendered. On `TemplateDto`, `TemplateSummaryDto`, `CreateTemplateRequest`,
  `UpdateTemplateRequest` (omit to keep, null to clear), and in wire v8 as
  `TemplateResource.description`. The manifest entry's `description` must equal it when both are
  set (`CATALOG_RESOURCE_DESCRIPTION_MISMATCH`). The `list-templates` conformance action reports
  `templateDescriptions`.

- **Wire v8: producer metadata on each template variant** (#96). `VariantEntry.metadata` maps a
  reverse-DNS namespace owned by its producer (`app.epistola.suite`) to a JSON object the contract
  does not interpret: at most 16 namespaces and 8 KiB per variant
  (`CATALOG_VARIANT_METADATA_INVALID`, `CATALOG_VARIANT_METADATA_TOO_LARGE`). It is preserved
  verbatim, a consumer keeps namespaces it does not know, and V5 fingerprints cover it. Epistola
  Suite will carry each variant's rendering-defaults version there; it may not do so yet.

- **Data examples have a `key`.** Wire v8 `dataExamples[]` is `{key, name, data}`, the key unique
  within its template (`CATALOG_TEMPLATE_EXAMPLE_KEY_INVALID`, `_KEY_DUPLICATE`), matching the API's
  `DataExampleDto.key`, which now has the same pattern and limits (`^[a-z0-9]+(?:-[a-z0-9]+)*$`,
  1–64). The v7→v8 migration derives a key from each example's name
  (`CATALOG_DATA_EXAMPLE_KEY_ASSIGNED`); the published `data-example-keys.json` fixture lists the
  derivation cases.

- **Deploying uses a release's dependency releases implicitly** (ADR 0003). A release renders the
  releases of other catalogs it is pinned to from the tenant's copy, so deploying it makes the
  environment use them without deploying them. A dependency release that cannot be had refuses the
  deployment with the new problem type `release-dependencies-missing` (409), whose
  `missingDependencies` member lists each release (`MissingReleaseDependency`) and its `reason`
  (`MissingReleaseDependencyReason`, whose one value is `unavailable`). The new `deployCatalogRelease`
  query parameter `includeDependencies` (default false) also
  deploys them explicitly, all or nothing. Every client's problem parser surfaces
  `missingDependencies`; the server stubs add `ProblemDetails.releaseDependenciesMissing`.

- **Catalog dependencies pin an exact release.** A catalog declares each catalog its resources
  reference, once, with the exact release of it that the catalog renders with (`version`):
  `listCatalogDependencies` and `updateCatalogDependencies` (`CatalogDependencyRequirement`,
  `CatalogDependencyDto`). A declaration names no range; ranges (such as `^1.0.0`, any non-breaking
  1.x) may come in a later 2.x, as an addition. References between catalogs carry no version; they
  render with the release their catalog's declaration pins, so a release renders with exactly the
  releases its dependencies pin. A release is refused when a resource references an undeclared
  catalog, a declaration pins no release or one the tenant does not have, or two paths pin
  different releases of one catalog. `CatalogChangesDto` reports `dependenciesChanged`.

### Changed

- **The V6 fingerprint leaves out where a binary sits, and a release's algorithm is fixed by its wire
  version** (catalog, wire v8). V6 covered an image's and a font face's `contentUrl`, a location,
  not content: the same bytes at `./assets/logo.png` (an archive migrated from wire v6) and at
  `bin/<hash>` fingerprinted differently. V6 now covers the bytes by `contentHash` only; V5 keeps
  covering the path, so its published values do not move. The validator required a v8 release's
  fingerprint to name the library's newest algorithm, so a later algorithm would have invalidated
  every v8 release; it now requires the one the wire version fixes,
  `CatalogCanonicalizer.releaseFingerprintVersion` (`v6` for wire v8). Wire versions and
  fingerprint algorithms are numbered separately.
- **A catalog never names itself by its own key** (catalog, wire v8, BREAKING). Content may name the
  catalog itself only by naming no alias. Wire v8 allowed the catalog's own key as well, which made
  a release depend on its name, which the V6 fingerprint deliberately leaves out: the same release
  mirrored or renamed (ADR 0003) verified as itself but named a catalog it does not declare, or,
  renamed to an existing alias, another catalog. The validator now reports the own key as an alias
  the catalog does not declare (`CATALOG_DEPENDENCY_CATALOG_UNDECLARED`, saying it is the own key),
  in content and in the manifest's `defaultTheme`. The v7-to-v8 migration drops it, with a
  `CATALOG_REFERENCE_ALIASED` notice, from every reference site, image nodes and a variant's
  attribute keys (`letters.tone` becomes `tone`); a v7 archive's legacy fingerprint still verifies,
  restored from the raw documents. A migrated release re-exported without its raw v7 documents no
  longer reproduces its V4 value where its content named itself by key, as with the stencil
  versions wire v8 removes, and is released under the current algorithm. The golden v7-to-v8
  migration fixture now migrates its template within its own catalog, and shows the drop.
- **A native wire-v8 document is held to the published v8 schemas before it binds** (catalog).
  Binding alone is lenient: Jackson dropped a member the model did not know and a model default
  filled one the schema requires, so a v8 document with a misspelled member passed, lost it, and
  fingerprinted as if it were not there. `CatalogSchemaMigrator` now checks a document that
  declares wire v8 against `catalog-manifest-v8` and `resource-detail-v8`, each resource against the
  definition of the type it declares, and reports each unknown, missing or ill-typed member as
  `CATALOG_SCHEMA_UNKNOWN` at its path. An explicit `null` counts as leaving the member out, so
  archives earlier writers wrote still read; a document migrated from an older wire version is not
  held to the newer schemas. `CatalogArchiveWriter` now leaves null properties out, so what it
  writes passes the published schemas (it wrote `null` for optional template-document members,
  which the schemas reject), except a dependency's `releaseVersion` and `releaseFingerprint`, which
  the wire requires even when null. Fingerprints are computed over the bound model and do not
  move. Wire v8 is also frozen: `resource-detail-v8` references new copies
  `template-document-v8`, `template-shared-v8` and `theme-v8` rather than the live schemas, and a
  test holds the copies equal to the live ones while v8 is current, so a later change to a template
  document cannot change what wire v8 accepts.
- **Every problem a caller can act on has its own type, and a server answers only with registered
  types.** (BREAKING) The registry gains `catalog-version-behind` (409, with `behind`, the versions
  to name in `acknowledgedBehind`; it was `conflict`), `release-in-use` (409, with `environments`
  and `pinnedBy`, from `deleteCatalogRelease`), `catalog-draft-name-in-use` (409, from
  `createCatalogRelease` and `updateCatalogRelease`), `ambiguous-variant` (409, with
  `tiedVariants`) and `no-matching-variant` (404), both from `generateDocument`,
  `generateDocumentBatch` and `previewDocument`, which now declare the 409,
  `generation-job-not-cancellable` (409, from `cancelGenerationJob`), `catalog-schema-too-new`,
  `catalog-schema-too-old` and `catalog-schema-unknown` (400, from `importCatalog` and
  `installCatalogRelease`), and `operation-not-implemented` (501, from any operation a server does
  not implement yet). The contract now says that a server answers only with registered types:
  anything without a type of its own is the generic type of its status, so a missing resource is
  `not-found` whichever it is, and a refused authorization `forbidden`. A client handles a type it
  does not know by its status, so a type added in a minor version breaks nothing. The server stubs
  build each new problem with members (`catalogVersionBehind`, `releaseInUse`,
  `ambiguousVariant`), and all five clients surface the members (`behind`, `environments`,
  `pinnedBy`, `tiedVariants`) with an `is…Problem` flag each. Epistola Suite may not implement them
  yet: it sends many types of its own. Conformance scenarios `release-catalog-draft-version-behind`,
  `delete-catalog-release-in-use` and `generate-document-ambiguous-variant` hold all five clients to
  the members.
- **One name for a resource's human-readable name, and one for a reference to another object.**
  (BREAKING) A resource's own human-readable name is `name` everywhere; a member naming another
  object carries that object's type (`releaseName`, `releaseId`, `requestId`).
  - `AttributeDto`, `CodeListDto` and their create and update requests: `displayName` is `name`,
    as catalog wire v8 already called it.
  - `VariantDto`, `VariantSummaryDto`, `CreateVariantRequest` and `UpdateVariantRequest`: `title`
    is `name`. Catalog wire v8 renames it too (`VariantEntry.name`, still unreleased, changed in
    place): the v7-to-v8 migration renames `title`, and a v8 document that still has it is not wire
    v8. V1 to V5 fingerprints keep covering it as `title`, so their values do not move; the V6 value
    of a catalog whose variants have names does. `UpdateVariantRequest` is a partial update like
    every other: `name` is no longer required, and an omitted member keeps its value.
  - `GenerationResult`, `DocumentGenerationItemDto` and `DocumentDto`: `draftLabel` is
    `releaseName`, as deployments and their history call it, and names a released release too, which
    keeps the name it had as a draft.
  - `DocumentGenerationJobDto.id` is `requestId`, as `GenerationJobResponse` calls it;
    `DocumentDto.id` is `documentId`; `DocumentGenerationItemDto.id` is `itemId`, beside its
    `documentId`.
  - `listImages` filters by `q`, as every other list does, not `search`.

  Epistola Suite may not implement them yet.
- **Install and import name each dependency release in full.** (BREAKING)
  `InstallCatalogReleaseResponse` and `ImportCatalogResponse` list `installedDependencies` and
  `presentDependencies` as `CatalogReleaseRefDto` objects (`registry`, `namespace`,
  `catalogKey`, `version`, `releaseId`), not as `catalogKey vVERSION` strings. A catalog's key
  alone is not unique, so two `shared` catalogs in different namespaces could not be told apart,
  and a client had to parse a label to read a version. Epistola Suite may not implement it yet.
  Conformance scenario `install-catalog-release` requires every member.
- **A template's `dataModel` is always present.** (BREAKING) `TemplateDto.dataModel` is required:
  a template without constraints has the empty schema `{}`, so a consumer needs no null check. On
  `CreateTemplateRequest` and `UpdateTemplateRequest` it is omitted, not null, to leave it to the
  server. Each client's worked in-process validator treats `{}` as claiming nothing. Epistola Suite
  may not implement it yet.
- **`validationErrors` is keyed by each data example's `key`.** (BREAKING) The
  `data-model-validation-error` problem mapped each example's display `name` to its failures; a
  name need not be unique, so failures could merge. Epistola Suite may not implement it yet.
- **Breaking: a theme owns the default block spacing** (#59). The component registry no longer
  gives `text`, `richTextVariable`, `container`, `columns`, `table`, `datatable`, `qrcode` and
  `image` a `marginBottom: 1.5sp` default, which a theme's `spacingUnit` magnified (24pt at 16pt).
  A theme sets the baseline in the new optional `ThemeResource.defaultBlockStyles` (wire v8; also
  on the REST theme DTOs): one style map for every listed block, margin keys only, below the
  document styles, a preset and a node's own styles, and never inherited. Without one the renderer
  supplies its own fallback; the contract fixes no value. The `separator` keeps its own margins and
  lists keep `listItemSpacing`. A renderer may keep the former defaults for releases cut before it
  adopted the rule. V5 and V6 cover the new member; V1 to V4 leave it out, so no published
  fingerprint moves. `TemplateValidator.validateThemeStyles` takes `defaultBlockStyles` and warns
  for a key that is not a margin. See `catalog-compatibility.md`.

- **BREAKING (API 2.0, alpha release registries, changed in place): a container registry keeps a
  release under its full name, and the release-registry paths name the authority** (contract ADR
  0008, amendment of 2026-10-22). The layout is now the contract's: `CatalogContainerLayout`
  (Kotlin) and `catalogContainerRepository`, `CATALOG_CONTAINER_*` (npm), documented in
  `catalog-archive.md` ("In a Container Registry"): repository
  `<prefix>/<authority>/<namespace>/<catalogKey>`, the release number as the tag (every valid
  release number is a valid OCI tag), resolved to a digest once and fetched by digest, the media
  types and annotations Epistola Suite writes, plus `app.epistola.catalog.contentHash`. API:
  `listRegistryReleases` and `installRegistryRelease` are now
  `…/release-registries/{name}/catalogs/{registry}/{namespace}/{catalogKey}/releases[/{version}/install]`,
  since namespace and key cannot tell apart two catalogs that differ only in their authority, and
  the segment could not be added after 2.0 without a break. `registry` now always means the
  authority: `RegistryReleaseListResponse` names the release registry `releaseRegistry` and the
  catalog's authority `registry`; `ReleasePublicationDto.registry` is renamed `releaseRegistry`.
  Jakarta's `GeneratedApiContractTest` checks the new path order. Conformance:
  `list-registry-releases` and `install-registry-release` send `config.registry` and report
  `releaseRegistry`; `publish-release-to-registry` reports `releaseRegistry`.
- **The `local` authority** (contract ADR 0003, amendment of 2026-10-22; Epistola Suite ADR 0034).
  A registry is `local` or a DNS name with at least one dot: `local` names catalogs that never leave
  the organisation (`local/acme-hr/letters`), valid within its own installations and refused by an
  Exchange (`namespace-not-granted` on `publishReleaseToRegistry`). It is the only registry without
  a dot; `localhost`, `Local` and `locals` stay invalid. Catalog wire v8 (unreleased, changed in
  place): `CatalogNamespaces.REGISTRY_PATTERN`, new `LOCAL_REGISTRY` and `isLocalRegistry`;
  `CATALOG_REGISTRY_PATTERN`, new `CATALOG_LOCAL_REGISTRY`; the manifest schema's `CatalogRegistry`;
  a carried release's path (`CatalogDependencyArchivePaths.PATTERN`,
  `CATALOG_DEPENDENCY_ARCHIVE_PATH_PATTERN`, the carried-release path cases). API: `Registry` and
  `RegistryOrDefault` accept it. Wire v7 keeps its rule. The clients' validation tests and the
  catalog's tests hold the boundary; conformance scenario `get-local-catalog-release`.
- **BREAKING (API 2.0, unreleased, changed in place): a draft's changes include its catalog-level
  content, and releasing needs a change** (Epistola Suite ADR 0034). `CatalogChangesDto` gains
  `catalogChanges` (required): what the content hash covers outside the resources, each as a new
  `CatalogLevelChangeDto` `{type, key, change}` named as merge entries are, `catalogDependency/<alias>`
  for a dependency declaration added, removed or changed (re-pinned, a draft named instead, or
  another catalog under the alias) and `catalog/defaultTheme`, `catalog/presets` and
  `catalog/license` for those settings, `change` one of `added`, `modified`, `removed`; never the
  catalog's presentation. `ResourceChangeDto.mergedForReview` (optional, default false) marks a
  resource holding merged content (`mergedContent`, alpha) no one has marked ready.
  `dependenciesChanged` is now defined as `catalogChanges` listing a `catalogDependency` entry.
  `releasable` and `releaseCatalogDraft` say the same thing: a draft is releasable only when
  something changed against its base (a resource or catalog-level content; without a base, it has
  any), and releasing one with no change is refused with 409 `conflict`; `releasable` also lists
  merged content under review among what keeps a draft from release, and says what it does not
  cover (acknowledging `behind`). Conformance: `get-catalog-changes` and `mark-catalog-ready`
  report `mergedForReview` and `catalogChanges`, and their scenarios carry both.
- **BREAKING (API 2.0, unreleased, changed in place): the API names another catalog's resource by
  alias** (contract ADR 0003, amendment of 2026-10-22; Epistola Suite ADR 0034). A reference from
  one catalog's release into another names that catalog as the release's content does, by the
  alias its dependency declarations give it, in the member `catalogKey`, never by registry or
  namespace: `CatalogThemeReference` is `{key, catalogKey}` (`namespace` and `registry` removed),
  an attribute's `codeListBinding` is `{catalogKey, key}` in `AttributeDto` (`catalogKey` null for
  the attribute's own catalog, no longer required to be set) and in `CreateAttributeRequest` and
  `UpdateAttributeRequest` (`catalogKey` optional; `registry` and `namespace` removed), and
  `VariantSelectionAttribute` is `{catalogKey, key, value, required}`, its alias resolved through
  the release that renders. Omitted or null, or the catalog's own key, is the catalog itself; an
  alias the release does not declare is refused with 400 (in variant selection it names no
  attribute). New identifier `CatalogAlias` (the catalog key rule, 3 to 50 characters; a catalog
  test holds it to `CatalogAliases`). Dependency declarations carry the alias:
  `CatalogDependencyRequirement.alias` (optional, by default the catalog's key; unique among the
  declarations and never the draft's own key, else 400), `CatalogDependencyDto.alias` (required)
  and `ReleaseDependencyDto.alias` (required, null for a catalog the release renders with only
  through another dependency); the declaration is where a client finds the full name. Merge
  entries of declarations are `catalogDependency/<alias>`, and their content
  (`CatalogMergeEntryContent`) has `alias`. The "Catalog identity" section says so. Clients' tests
  build criteria and declarations with aliases. Conformance: `generate-document` sends no
  `registry` or `namespace` in a criterion, `update-catalog-dependencies` sends an entry's `alias`,
  dependency reports carry `aliases` and `get-catalog-release` reports `dependencyAliases`,
  `update-attribute` sends `config.codeListBinding` and reports the binding as `catalogKey/key`;
  scenario `update-attribute-bind-code-list-by-alias` is new, and the theme, dependency, release
  and attribute-criterion scenarios name catalogs by alias.
- **Patch release numbers in the API** (contract ADR 0003, amendment of 2026-10-22; Epistola Suite
  ADR 0034). `ReleaseVersion` is the catalog archive's release number rule,
  `CatalogReleaseVersions.PATTERN`: `MAJOR.MINOR.PATCH`, or a patch release
  `MAJOR.MINOR.PATCH-<namespace>.<N>` (`1.3.1-gemeente-x.1`), at most 128 characters, and
  `CatalogReleaseRef` joins it with the release id rule, so every member called `version`, every
  `{release}` and `{version}` path parameter and every query parameter that names a release accepts
  a patch release. A patch release sorts below its core and above every release below that
  (`1.3.0` < `1.3.1-gemeente-x.1` < `1.3.1`), "highest" means by that order, and a version range
  matches one as npm matches a pre-release: only when a comparator names a pre-release of the same
  `MAJOR.MINOR.PATCH` (`^1.3.0` never does); a `versionRange` may now be 256 characters (was 100),
  so an exact release number of any valid length fits. `releaseCatalogDraft` takes one only where the tenant
  may issue it (a namespace it holds, for a catalog whose namespace it does not hold), else 400;
  which namespaces it holds is the installation's to know. A client that stores or compares
  versions must accept the label. `CatalogReleaseVersionsTest` now holds `ReleaseVersion` to
  `PATTERN` and `CatalogReleaseRef` to the two rules joined. The clients' validation tests accept
  patch numbers and refuse `-<label>.0`, a leading zero, an uppercase label and a label without a
  number. Conformance: scenarios `get-patch-release` (a patch release in the path and in the body,
  a pinned dependency's version too) and `release-catalog-draft-patch-release`.
- **Catalog wire v8 (unreleased, changed in place): patch release numbers** (contract ADR 0003,
  amendment of 2026-10-22). A release number is `MAJOR.MINOR.PATCH`, as the holder of the catalog's
  namespace issues it, or `MAJOR.MINOR.PATCH-<namespace>.<N>` for a patch release by someone who
  does not hold it, labelled with a namespace they hold and a sequence number from 1
  (`1.3.1-gemeente-x.1`, which sorts above `1.3.0` and below the holder's `1.3.1`), at most 128
  characters (`CatalogReleaseVersions.MAX_LENGTH`, `MAX_CATALOG_RELEASE_VERSION_LENGTH`, the schemas'
  `maxLength`), so that every valid namespace, up to 63 characters, can label one; the API's
  `ReleaseVersion` and `CatalogReleaseRef` get the same `maxLength`, 128, keeping their patterns. The rule
  applies to `release.version`, a dependency's `releaseVersion`, `release.base.version` and a carried
  release's path (`CatalogDependencyArchivePaths.PATTERN`, `CATALOG_DEPENDENCY_ARCHIVE_PATH_PATTERN`). No other
  pre-release or build part. `CatalogReleaseVersions` gains `CORE_PATTERN`, `isCore` and
  `patchNamespace`; npm gains `CATALOG_RELEASE_VERSION_CORE_PATTERN`. The format checks the shape
  only: who holds a namespace is the installation's and the registry's to know. A consumer that
  stores or reports release numbers must accept the label. The v7-to-v8 migration still refuses a v7
  pre-release (`1.0.0-beta.1`) rather than reading it as a patch release. The API follows (see the
  entry on patch release numbers in the API).
- **BREAKING (catalog wire v8, unreleased, changed in place): a release is identified by its
  content, a V6 fingerprint** (contract ADR 0003, amendment of 2026-10-22; Epistola Suite ADR 0034).
  `CatalogFingerprintVersion.V6` is the current algorithm (`currentFingerprint`,
  `CURRENT_FINGERPRINT_VERSION`), and a v8 `release.fingerprint` must name it (`v6:<hex>`; a `v5`
  one is now `CATALOG_RELEASE_FINGERPRINT_INVALID`). V6 covers every resource as V5 does (references
  to other catalogs by alias), the resource index, the content settings `defaultTheme`, `presets` and
  `license`, the resource dependencies by alias, each catalog dependency as its alias and pinned
  `releaseFingerprint`, and `compatibility` (the Epistola versions the content needs). It does not
  cover the catalog's name (registry, namespace, key), a dependency's name or `releaseVersion`, the
  release block, the catalog's presentation (`name`, `description`, `keywords`, `presentation`,
  `attributes`, `publisher`, `includes`), or the wire version (`schemaVersion`): catalogs that differ
  only there have the same V6 fingerprint, so a release under another name, from any registry,
  mirror or file, verifies as itself, presentation can change without a release (the most recently
  received wins), and a wire version that only re-encodes content keeps every V6 value (the
  canonical form under the algorithm's domain prefix fixes the encoding). V1 to V5 stay
  verifiable with exactly the values they had; a pin or `release.base` may name any known
  algorithm. `release.base.fingerprint` is the base's content hash, and the schema and model say
  which catalog members are the name, which content and which presentation. A catalog archive's
  name, `release.version` and `release.fingerprint` are documented as its binding.
  `fingerprints.json` pins V6 (`currentCatalogFingerprint`, `catalogFingerprints.V6`,
  `currentWireV8CatalogFingerprint`, `wireV8CatalogFingerprints.V6`); the `carried-releases`
  fixtures are fingerprinted and pinned with V6. `completePerResourceFingerprints` is the
  per-resource form of V5 and V6 alike, with its values unchanged. The API's fingerprint
  descriptions and examples say `v6:` (the pattern already accepted it).
- **BREAKING (catalog wire v8, unreleased, changed in place): content names another catalog by an
  alias** (contract ADR 0003, amendment of 2026-10-22; Epistola Suite ADR 0034). A
  `catalogDependencies` entry gains a required `alias` (the catalog key rule, `CatalogAliases`,
  `CATALOG_ALIAS_PATTERN`; unique in the list, never the catalog's own key, by default the
  depended-on catalog's key), and every reference into another catalog names that alias and a
  resource key: a resource dependency's `catalogKey`, `catalog.defaultTheme.catalogKey`, a template's
  `themeCatalogKey`, a theme override's, stencil or image node's, `fontFamily` reference's and
  code-list binding's `catalogKey`, and a variant attribute key's qualifier (`system.locale`). The
  `registry` and `namespace` that references gained earlier in v8 (and `themeRegistry` /
  `themeNamespace`) are removed again: they are the declaration's, so renaming or mirroring a
  dependency changes nothing in the content. `registry` and `namespace` stay on `catalog` and each
  `catalogDependencies` entry.
  - Validator: every alias a reference names must be declared (`CATALOG_DEPENDENCY_CATALOG_UNDECLARED`,
    now also at each reference inside content, image nodes and variant attribute qualifiers
    included); new `CATALOG_DEPENDENCY_ALIAS_INVALID` and `CATALOG_DEPENDENCY_ALIAS_DUPLICATE`. A
    `CatalogDependencyResolver` receives the declared catalog's key, namespace and registry, never an
    alias. `ResourceValidationContext` takes `catalogDependencies` in place of `namespace` and
    `registry`.
  - Migrator: the wire check requires an alias (`CATALOG_DEPENDENCY_ALIAS_INVALID`) and refuses a
    `registry`, `namespace`, `themeRegistry` or `themeNamespace` left in a v8 reference
    (`CATALOG_SCHEMA_UNKNOWN`). The v7-to-v8 migration declares each other catalog a v7 archive names
    under its key as the alias, so references keep their values; the archive reader also declares
    those only the content names. A reference qualified with a registry or namespace (the schema
    shared with v5 to v7 allowed one in a theme reference) is mapped to the alias
    (`CATALOG_REFERENCE_ALIASED`), or is `CATALOG_REFERENCE_ALIAS_UNRESOLVED` when it names a catalog
    in another namespace. `CATALOG_DEPENDENCY_REGISTRY_ASSIGNED` and
    `CATALOG_DEPENDENCY_NAMESPACE_ASSIGNED` are no longer emitted and removed.
  - Kotlin: `CatalogDependency.alias` (defaults to `catalogKey`, so stored manifests bind);
    `registry`/`namespace` removed from `DependencyRef`, `CatalogThemeRef`, `ThemeRefOverride`,
    `CodeListBindingRef` and `FontRef`, `themeRegistry`/`themeNamespace` from `TemplateResource`, and
    `currentNamespace`/`currentRegistry` from `TemplateValidationContext` (and from `forStencil`);
    the model ignores the removed members when binding. TypeScript types follow.
  - Fingerprints: V5 takes a resource dependency's registry and namespace from the declaration its
    alias names, so the V5 values published for the golden catalogs, and a manifest stored before
    aliases, are unchanged (pinned in `fingerprints.json` as `wireV8CatalogFingerprints`).
  - API: the API embeds the catalog's `TemplateDocument`, so its `ThemeRef` loses `namespace` and
    `registry` in every client (`normalize-bundle.mjs`), and "Catalog identity" in the API
    description says a reference inside content names an alias; a dependency declared through the
    API has its catalog key as its alias. The API's own DTOs (`CatalogThemeReference`,
    `CatalogDependencyRequirement`/`Dto`, an attribute's code-list binding) are unchanged.
  - Fixtures: `wire-v8`, `migrations/v7-to-v8`, `carried-releases` and the stencil composition
    fixture (`STENCIL_DEFINITION_REFERENCES_SAME_KEY_IN_ANOTHER_CATALOG`) follow.
- **One merge entry per catalog dependency (alpha, `x-experimental`, ADR 0005).** The single
  `catalog/dependencies` entry of `previewCatalogDraftMerge` and `mergeIntoCatalogDraft` is now one
  entry per catalog the release uses: `resourceType` `catalogDependency`, `resourceKey` the
  catalog's key (registry and namespace are in the declaration; a full name does not fit the
  50-character key), resolved and reported as `catalogDependency/<catalogKey>`. Adding, removing or
  re-pinning a dependency is its own change; only both sides changing the same catalog differently
  is a conflict. A dependency the release no longer declares is listed in `removed`; one nothing
  uses any more is kept, as editing keeps one. Conformance: `preview-catalog-draft-merge` and
  `merge-into-catalog-draft` carry such entries.
- **`merge-refused` covers every unresolved reference (alpha).** Besides a release removing a
  resource the draft still uses, a merge is refused when its result would hold a reference that
  does not resolve: within the catalog, or into another catalog through a declared dependency,
  checked against the release it pins (the catalog's default theme included), or into a catalog the
  result does not declare. The problem's `detail` names what is missing and what to choose.
  Registry text, `error-types.md`, `MergeConflictError`, both operations and ADR 0005 updated.
  Conformance: new scenario `merge-into-catalog-draft-refused`.
- **`release-number-taken` on receiving a release too.** `importCatalog`, `installCatalogRelease`
  and `installRegistryRelease` now refuse a release whose number the tenant has with another
  fingerprint (its own, or one it pins), or whose release id another release or a draft of the
  catalog has, with 409 `release-number-taken` instead of `conflict`: one number and one id name
  one release. Other conflicts stay `conflict`. Their 409 response (`ReleaseDependenciesMissingError`)
  declares it with an example; registry text, `error-types.md`, the client READMEs and ADR 0008
  updated. A client switching on `conflict` for these cases must also handle `release-number-taken`.
  Conformance: `import-catalog-fingerprint-conflict` now expects it, and new scenarios
  `install-catalog-release-number-taken` and `install-registry-release-number-taken`.

- **ADR 0008 is amended (2026-10-22): release registries for content-addressed releases**
  (Epistola Suite ADR 0034). A container registry keeps a release at
  `<prefix>/<authority>/<namespace>/<key>:<version>`, the authority (the `registry` of the
  catalog's name) included, so catalogs that differ only in it can share one registry; the tag is a
  binding's number, and a release resolves by digest and verifies itself by its content hash,
  which decides receiving. Mirroring copies releases and their bindings unchanged and needs no
  namespace; publishing a received release is mirroring it, and the Exchange, which hosts names,
  stays the holder's. A catalog's source is per tenant and changeable, which changes where new
  releases come from, never what they are (`CatalogDto.source` says so). The release-registry
  paths keep addressing a catalog by namespace and key; naming the authority there, installing by
  content hash, how a binding travels and an operation to change a source are open questions. ADR
  0003's consequences now list what the API did the same day and what is still to come.
- **ADR 0003 is amended (2026-10-22): releases are content-addressed; names point at them**
  (Epistola Suite ADR 0034, phase 1 for the catalog format). The `registry` of a catalog's name is
  its authority, a domain, never a location; namespaces are flat; a release is identified by its
  content (V6); an archive's name, number and fingerprint are a binding, issued by the holder of the
  namespace, and anyone else issues only patch releases labelled with a namespace they hold;
  catalogs refer to each other by alias; lineage is by hash; presentation is not content. It lists
  the API follow-ups (addressing releases by hash, bindings, aliases and patch numbers in the API,
  namespace grants, the Exchange's domain verification). **ADR 0005 is amended** alike: the merge
  base is found by its content hash, a catalog used is a merge entry by its alias, and merge
  parents stay producer metadata.
- **ADR 0001 (hotfixing) is superseded** by Epistola Suite's ADR 0032 and contract ADR 0005: with
  no catalog types, any installation that allows editing fixes a release the ordinary way and the
  fix comes back as a merge; numbers are checked where releases meet. The `+hotfix.N` grammar it
  proposed is not added. **ADR 0003 is amended (2026-10-21)**: a catalog's name must not change
  when its releases move between installations, so registry and namespace are explicit, chosen when
  the catalog is created, and never derived from a tenant key or an installation's host; its
  "type is chosen once" section is withdrawn.
- **BREAKING: catalogs have no type; whether one can be changed is the installation's.** Following
  Epistola Suite's ADR 0032, every catalog has releases and drafts, wherever its releases come
  from. `CatalogType` (`authored`, `subscribed`) and `CatalogDto.type` are removed, and so is the
  `catalogType` member of `AttributeDto`, `CodeListDto`, `FontDto` and `ImageDto`.
  `CatalogDto` gains `editable` (required: whether the catalog can be changed in this
  installation), `readOnlyReason` (the values of `CatalogReadOnlyReason`, null or absent exactly
  when `editable` is true: `editing-disabled`, the installation only receives and runs releases,
  or `supplied-by-epistola`, the server supplies the catalog and replaces it on upgrade) and
  `source` (required, nullable: a URI saying where its releases come from, `exchange:…`, `oci:…`,
  `https://…`, `classpath:…`; null when releases only arrive by import or are made here). The resource DTOs keep
  `readOnly`, now defined by the catalog's `editable`. `highestVersion` is the highest release
  wherever it was made. `importCatalog` loses its `catalogType` form field: an import installs the
  release beside the catalog's others, and a catalog it creates is a catalog like any other; the
  `import-catalog-type-conflict` conformance scenario is gone with it. An archive from before wire
  v8 is imported only when the request names its `registry` and `namespace`; neither is derived
  from the tenant key or the installation. `listAvailableCatalogReleases` and
  `installCatalogRelease` need a source (`CatalogDto.source`) rather than a subscribed catalog,
  and work whether or not the catalog can be changed, as imports do.
- **BREAKING: a write to a catalog that cannot be changed is 409 `catalog-read-only`** (a new
  problem type, `CatalogReadOnlyError`, schema `CatalogReadOnlyProblemDetail`), whose
  `readOnlyReason` member says why. Every write to a catalog's content or drafts declares it:
  creating, updating and deleting templates, variants, stencils, themes, attributes, code lists
  and images, marking them ready, the draft operations (`createCatalogRelease`,
  `updateCatalogRelease`, `deleteCatalogRelease`, `updateCatalogDependencies`,
  `markCatalogReady`) and `releaseCatalogDraft`. Before, such a write was a plain 409 `conflict`.
  Every client's problem parser surfaces `readOnlyReason` (Kotlin and Node.js `readOnlyReason`,
  Jakarta `getReadOnlyReason()`, .NET `ReadOnlyReason`, Python `read_only_reason`, each with an
  `is…CatalogReadOnlyProblem` flag); the server stubs add `ProblemDetails.catalogReadOnly`.
- **A catalog carries its font faces as resources of its release, the bundled `system` catalog
  included**: the family, each face's bytes in the archive and both inside the fingerprint, as for
  images. The catalog model's documentation said bundled system fonts were never exported; nothing
  in the model or the validator refused them, so only the documentation changes.
- **BREAKING: an attribute criterion names its attribute one way.** `VariantSelectionAttribute`
  (the `attributes` of a generation, batch item or preview request) names the attribute by its key
  and the catalog that defines it, each in a member of its own: `key` is the attribute's resource
  key (`ResourceKey`, 3–50 characters), and `catalogKey`, `namespace` and `registry` name its
  catalog. Without `catalogKey` the attribute is the template's own catalog's; with it, the catalog
  must be the template's or one it declares as a dependency, and an omitted `namespace` or
  `registry` is the template's catalog's (`namespace` only with `catalogKey`, `registry` only with
  `namespace`). In 1.4 `key` also took a dotted `<catalog>.<key>`, and a bare key without a catalog
  was looked up tenant-wide, picking one catalog's attribute when several had it; both forms are
  removed, and a dotted key is refused by the key's pattern. Conformance scenario
  `spec-generate-document-attributes` sends a qualified criterion and a bare one; the
  `generate-document` driver action sends `config.attributes` in all five clients.

- **Every key and number rule is defined once, by name.** The OpenAPI document names its rules in
  `components/schemas/identifiers.yaml` and references them wherever a path, a parameter or a
  schema takes one, instead of repeating the pattern inline (about 280 copies): `TenantKey`,
  `Namespace`, `CatalogKey`, `EnvironmentKey`, `ConsumerId`, `ResourceKey` (a leading digit
  allowed, because resource keys used to be UUIDs), `PresetKey`, `Registry`, `RegistryOrDefault`
  (a registry or `~`, in paths and queries), `ReleaseVersion`, `CatalogReleaseId`,
  `CatalogReleaseRef` (a `releaseId` or a version, the `{release}` path form) and
  `Fingerprint`. Each has a description, which the rendered API documentation shows. No pattern or
  length changed, and the generated clients and server stubs are unchanged apart from
  documentation: every member stays a plain string. A nullable member references its rule as
  `oneOf: [{$ref}, {type: 'null'}]`, which every client's generated model validation now reads as
  the rule's constraints, null allowed. In a `multipart/form-data` body (`importCatalog`), where the
  Node.js and Python generators would take a reference for a model, the bundle inlines the rule.

- **A document, a job item and a collected result always name their release.**
  `releaseId` (`DocumentDto`, `DocumentGenerationItemDto`, `GenerationResult`) is the id of the
  release rendered, a released one's as well as a draft's; it is null only for a document made
  before 2.0. `version` is null for a draft.

- **BREAKING: every API enum value is lowercase, kebab-case when it has several words.** Generation
  job and item `status` (`pending`, `in-progress`, `completed`, `failed`, `cancelled`, and the
  `listGenerationJobs` `status` filter), `jobType` (`single`, `batch`), `GenerationResult.status`
  (`completed`, `failed`), code-list `sourceType` (`inline`, `url`) and `authType` (`none`,
  `api-key`, `bearer`), `MissingReleaseDependencyReason` (`unavailable`), `PongResponse.status`
  (`up`, `degraded`) and the catalog type (`authored`, `subscribed`), which is one shared schema,
  `CatalogType`, instead of six inlined copies (`CatalogDto.type`, the `catalogType` of
  `AttributeDto`, `CodeListDto`, `FontDto` and `ImageDto`, and `importCatalog`'s `catalogType`
  field). `PageFormat` (`A4`, `Letter`, `Custom`) keeps its values: it belongs to the catalog wire
  format. Every client's result collector, problem parser and driver read the new values; the
  clients' missing-dependency reason constant is `unavailable`. The Kotlin client's generated
  `ApiClient` sends an enum multipart part (`importCatalog`'s `catalogType`, now the top-level
  `CatalogType`) as its plain value: Spring wrote it as a quoted JSON string, which the
  `import-catalog` scenarios caught.
- **BREAKING (client libraries): a collected result always names its catalog.** The hand-written
  `GenerationResult` of every result collector types `registry`, `namespace` and `catalogKey` as
  non-null, as the contract now requires them; `version` is null for a draft.
- **BREAKING: a count is `total`, a last change `lastModified`.** `PageMeta.totalElements`,
  `GenerationJobResponse.totalCount` and `DocumentGenerationJobDto.totalCount` are `total`;
  `CollectMeta.count`, the number of result lines in one collect response, is `resultCount`;
  `ConsumerDto.updatedAt` is `lastModified`, now required and never null like every other
  resource's: it equals `createdAt` at registration and moves with every change to the consumer.
  The `update-consumer` conformance action reports the `lastModified` it parsed.

- **The API description is rewritten for a newcomer**: what Epistola is, a glossary, a getting
  started path (API key, headers, find a template, generate, poll the job, download; collecting
  results as the high-volume path), then the catalog model, authentication and errors. The spec
  refers to no ADR and no repository file: each rule is stated where it applies, and the guides are
  linked by absolute URL. Tag descriptions say what each group covers, and the Epistola Suite
  support hedge is stated once ("Experimental parts and server support").

- **`stylePreset` names an available preset** (ADR 0007). The template document schema describes a
  block's `stylePreset` as one of the catalog's available presets (its declared `presets` plus those
  of the catalogs it depends on), falling back to the presets its themes style while it declares
  none.
- **`updateStencilContent` keeps the parameter schema when `parameterSchema` is left out.**
  Omitted or null used to clear it, so a client saving only the content silently removed the
  parameters every template using the stencil binds. Now a schema replaces it, and a schema that
  declares no parameters (`{}`) removes it. `createStencil` treats such an empty schema as none too.
- **BREAKING: the media type is `application/vnd.epistola.v2+json`** (and
  `application/vnd.epistola.v2+ndjson` for result collection), in `Accept` and `Content-Type`. The
  media type carries the API's major version, and contract 2.0 changes most request and response
  bodies, so a 2.x server refuses the 1.x media type with 406 or 415. A 1.x client against a 2.x
  server now fails on its first request, instead of having `releaseVersion` or `versionId` ignored
  and being served a release it did not ask for. Every client sends the new media type; the
  `media-types`, `error-accept` and request-shape conformance scenarios hold all five to it.

- **A variant attribute key may name another catalog's attribute.** Wire v8 gave variant
  attribute keys the resource key rule, which refused the qualified form `<catalogKey>.<key>`
  (`system.locale`) that names an attribute defined in another catalog, so a template using the
  system catalog's `locale` could not be validated. The key is now an attribute key, optionally
  qualified by a catalog key, each part by its own rule (`CatalogKeys.isValidAttributeKey`,
  `ATTRIBUTE_KEY_PATTERN`, at most 101 characters in the schema; on npm
  `CATALOG_ATTRIBUTE_KEY_PATTERN` and `MAX_CATALOG_ATTRIBUTE_KEY_LENGTH`). The v7-to-v8 migration pads the
  attribute part of a qualified key, as it padded that attribute's own key.
- **Stencil nesting is checked lexically (ADR 0006).** The template validator no longer counts a
  placeholder's fill as inside the stencil that owns the placeholder: the fill is written outside
  it. So a fill may hold an instance of the stencil it fills, and instances written in fills of
  fills do not add up to the nesting limit. Inside a stencil's own content the stencil stays the
  ancestor of every fill, so a stencil that holds itself in a fill is still refused.

- **Template styles (#97), additive to the registries; Epistola Suite may not render them yet.**
  `minHeight` (a unit, on `container`) and `verticalAlign` (`top` / `middle` / `bottom`, on
  datatable columns and the two style maps below). New component style maps (`styleMaps` in the
  component manifest): `cellStyles` on `table`, keyed `<row>-<col>` from 0 and checked against
  `rows` × `columns`, and `columnStyles` on `columns`, one entry per `columnSizes` entry; both
  accept `backgroundColor`, `padding*`, `border*` and `verticalAlign`. A style property can be
  opt-in (`includedInAll: false`), so `applicableStyles: "all"` does not reach it; `container` now
  lists its styles explicitly. Style keys that are unknown or not applicable
  (`TEMPLATE_STYLE_UNKNOWN`, `TEMPLATE_STYLE_NOT_APPLICABLE`) are **warnings** now instead of
  errors, and are also checked in `documentStylesOverride`, theme `documentStyles` and block
  presets, so a typo is reported without refusing the document. `template-validation.json` gains
  a `warningCases` section.

- **BREAKING: one key rule for every catalog resource.** The key of a template, variant, stencil,
  theme, attribute, code list, font, image or data example, and every reference to one, is
  `^[a-z0-9]+(-[a-z0-9]+)*$`, 3–50 characters, a leading digit allowed (migrated images keep their
  UUID keys), in the API and in wire v8 alike: in path parameters and in every body, including the
  `variantKey` of a generation, preview or validation request and of a `GenerationResult`, and the
  code list `key` of an attribute's `codeListBinding`, and the `key` of an attribute criterion
  (`VariantSelectionAttribute`, see below). A preset declared by a
  catalog (`CatalogPresetDto.key`) is not a resource and keeps the wire's preset rule (1–50, a
  letter first). Every `tenantKey` is 3–63 characters and every `catalogKey`, including
  `ImportCatalogResponse.catalogKey` and the template model's `ThemeRef.catalogKey`, follows the
  catalog key rule. Conformance scenario `spec-generate-document-variant-key` generates with a
  variant key that starts with a digit. Before, the API had a rule per type (themes at most
  20 characters, fonts from 2, code lists and attributes from 1, images and archive keys up to 64)
  and wire v7 allowed 1–64, so a valid archive could hold resources the API could not address.
  The catalog's own key keeps 3–50, letter first; tenant and environment keys are unchanged. The
  v7→v8 migration pads a 1- or 2-character key with `0` (`nl` → `nl0`,
  `CATALOG_RESOURCE_KEY_PADDED`) and truncates one over 50 characters
  (`CATALOG_RESOURCE_KEY_TRUNCATED`), and rewrites every reference to it the same way, so
  catalogs migrated separately still agree; a renamed key that collides with an existing one is
  refused, never disambiguated. Epistola Suite must apply the same rule to the keys it stores.

- **Catalog drafts are independent copies; nothing is merged** (ADR 0002). Releasing a draft
  changes no other draft. A draft is **behind** each release that, relative to the version it would
  be released as, has a version above its base and below that one, or was released after the draft
  was started with a version below that one (other than its base): a draft on `2.0.0` is behind
  a fix `1.4.1` released later; a fix `1.4.1` from `1.4.0` is behind nothing.
  `CatalogReleaseDto.behind` lists them against the version the preview gives (`BehindReleaseDto`,
  with each release's `added` / `modified` / `removed` resources, `ReleasedChangeDto`), and
  `ReleaseCatalogDraftResponse.draftsBehind` names the drafts a release puts behind. Releasing a
  draft that is behind is refused with 409 unless `acknowledgedBehind` names every one of them, for
  the version it is released as, so a fix can be left out, but never unnoticed. Conformance scenarios
  `get-draft-behind` and `release-catalog-draft-behind`. A merge on request is proposed in ADR 0005,
  outside 2.0.0.
- **BREAKING: a resource's address is its `key`** (#84). The rule: a `key` is a resource's public
  address within its parent; an `id` is a generated identifier that is not an address
  (`documentId`, `requestId`, `batchId`, `correlationId`, `consumerId`, node and slot
  ids).
  - Read models: `slug` → `key` on every DTO that had one (templates, variants, stencils, themes,
    environments, tenants, attributes, code lists, fonts, images, import and catalog results,
    resource and incoming changes). Create requests: `id` (and `CreateCodeListRequest.slug`) →
    `key`, as is `DataExampleDto.id`.
  - References: `tenantId` → `tenantKey`, `templateId` → `templateKey`, `variantId` →
    `variantKey`, `environmentId` → `environmentKey` and `stencilId` → `stencilKey`, in every body
    and in the result-collection stream (`GenerationResult`).
  - Path and query parameters: `{tenantKey}`, `{templateKey}`, `{variantKey}`, `{stencilKey}`,
    `{environmentKey}`, `{themeKey}`, `{codeListKey}`, `{fontKey}`, `{imageKey}`. URLs are
    unchanged; the generated method arguments are renamed in every client.
  - The conformance scenario `template-listing-by-slug` is now `template-listing-by-key`, and the
    driver config and reports use the new names.
  - Template content: `ThemeRef.themeId` → `themeKey`, and in the component registry the stencil
    node prop `stencilId` → `stencilKey` and the image node prop `assetId` → `imageKey`. Stored
    documents with the old names no longer validate; the catalog migration rewrites archives, and
    Epistola Suite has to migrate its stored documents.
  - Wire v8 (changed in place): `catalog.key`, and `key` on every resource entry, resource detail,
    variant entry, resource dependency and code-list binding; `TemplateResource.themeKey`;
    `CatalogPresentation.iconImageKey` and `imageKeys` (was `imageAssetSlugs`);
    `AttributeAssignment.catalogKey` (was `catalog`); a style's `fontFamily` reference names its font
    by `key` (was `slug`). `CatalogV7ToV8Migration` renames all of them, in template
    content too, without notices; a v8 archive using an old name is refused
    (`CATALOG_SCHEMA_UNKNOWN`). The Kotlin model renames the properties and keeps the old JSON
    names as `@JsonAlias`, so stored manifests still bind. V5 fingerprints change (V5 is
    unreleased); V1–V4 reproduce their published values. Wire v5–v7 resolve template content
    through a frozen `template-document-v7.schema.json`, so released versions are not tightened.
  - Kotlin API: `CatalogSlugs` → `CatalogKeys`, `CatalogResourceReference.slug` → `key`,
    `TemplateValidationContext.forStencil(stencilKey =)`; finding codes
    `CATALOG_RESOURCE_SLUG_MISMATCH` / `_SLUG_INVALID` → `CATALOG_RESOURCE_KEY_MISMATCH` /
    `_KEY_INVALID`, and `CATALOG_TEMPLATE_VARIANT_ID_DUPLICATE` →
    `CATALOG_TEMPLATE_VARIANT_KEY_DUPLICATE`.

- **BREAKING: wire v8 paths into the catalog are relative** (#95). `ResourceEntry.detailUrl`,
  `ImageResource.contentUrl` and a font face's `contentUrl` must be a relative path below the
  manifest: no scheme, no leading `/`, no backslash, no empty, `.` or `..` segment, and no
  percent-encoded `.`, `/` or `\`. The migrator's wire check and the validator report
  `CATALOG_MANIFEST_DETAIL_URL_INVALID` and `CATALOG_ASSET_PATH_INVALID` for v8 input; older wire
  versions keep their rules. The rule is published as `CatalogArchivePaths` (Kotlin) and
  `CATALOG_ARCHIVE_PATH_PATTERN` (npm). The archive reader never resolved a path outside the
  archive, at any version; a test now pins that.

- **BREAKING: an API fingerprint names its algorithm.** `CatalogDto.fingerprint` and
  `CatalogReleaseDto.fingerprint` are `v5:<hex>`, the value in the release's catalog archive
  (pattern `^v[1-9][0-9]*:[0-9a-f]{64}$`); a bare hex value is not a fingerprint any more.

- **BREAKING: portable catalog wire v8.** A catalog archive identifies its catalog by registry,
  namespace and key (ADR 0003) and versions catalogs as a whole;
  `x-epistola-catalog-contract.wireSchemaVersion` is 8.
  - `catalog.registry` (a DNS name, always in full) and `catalog.namespace` (a slug) are required.
    New manifest member `catalogDependencies` declares each catalog depended on once, with the
    exact `releaseVersion` it pins (the release the catalog renders with and was cut with; no range),
    and that release's `releaseFingerprint`, so a copy fetched through any mirror can be checked (both
    null only for archives migrated from v7). References into another catalog name it by the alias
    its declaration gives it (see "content names another catalog by an alias" above, which replaced
    the registry and namespace this entry first added to references).
  - Stencils carry no version: `StencilResource.version` is removed from the wire and the Kotlin
    model, and a stencil node no longer allows `version` or `draftVersion`.
  - `CatalogV7ToV8Migration` takes the registry and namespace from the caller
    (`CatalogMigrationOptions(namespace, registry)`, accepted by `CatalogSchemaMigrator.migrateManifest`,
    `CatalogArchiveReader.read` and `CatalogValidationPolicy`); without them a v7 archive is refused
    with `CATALOG_REGISTRY_MISSING` / `CATALOG_NAMESPACE_MISSING`. It declares each referenced
    catalog in them, under its key as the alias, pinning no release (`releaseVersion` and
    `releaseFingerprint` null, for the author to pin), and removes stencil versions, each with a
    notice.
  - Fingerprints: V5 (now `currentFingerprint`) also covers the registry, namespace and declared
    dependencies, including their release fingerprints. v6 and v7 archives keep verifying against their V4 fingerprint; re-exporting
    replaces it with V5.
  - Every fingerprint names its algorithm: `release.fingerprint` and `releaseFingerprint` are
    `v5:<lowercase SHA-256 hex>` (pattern `^v[1-9][0-9]*:[0-9a-f]{64}$`), and a dependency's
    `releaseFingerprint` is copied from that release's own `release.fingerprint`. A v8 release must
    name `v5` (`CATALOG_RELEASE_FINGERPRINT_INVALID` otherwise, even when an older algorithm
    matches, because it covers less), and a verifier checks exactly the algorithm named. New Kotlin
    API: `QualifiedCatalogFingerprint` (`parse`, `PATTERN`), `CatalogFingerprintVersion.label`,
    and `CatalogCanonicalizer.currentQualifiedFingerprint`, `qualifiedFingerprint`,
    `matchesQualifiedFingerprint` and `CURRENT_FINGERPRINT_VERSION`. `currentFingerprint` still
    returns bare hex.
  - New validator codes `CATALOG_REGISTRY_INVALID`, `CATALOG_NAMESPACE_INVALID`,
    `CATALOG_DEPENDENCY_CATALOG_FINGERPRINT_INVALID`, `CATALOG_DEPENDENCY_CATALOG_UNDECLARED`,
    `CATALOG_DEPENDENCY_CATALOG_DUPLICATE`, `CATALOG_DEPENDENCY_CATALOG_SELF` and
    `CATALOG_DEPENDENCY_CATALOG_VERSION_INVALID` (a pinned `releaseVersion` that is not a release
    number). `CatalogNamespaces` (Kotlin) and the `CATALOG_REGISTRY_*`, `CATALOG_NAMESPACE_*` and
    `CATALOG_RELEASE_FINGERPRINT_PATTERN` constants (npm) publish the rules.
  - A release number is the API's `ReleaseVersion`: `release.version` and a pinned `releaseVersion`
    are `MAJOR.MINOR.PATCH` without leading zeros, pre-release or build part, at most 128 characters
    (wire v7 and earlier accepted full SemVer, `1.0.0-beta.1`). The validator and the migrator's
    wire check report `CATALOG_RELEASE_VERSION_INVALID` / `CATALOG_DEPENDENCY_CATALOG_VERSION_INVALID`;
    migrating a v7 (or earlier) release numbered with a pre-release or build part is a finding,
    never stripped. `CatalogReleaseVersions` (Kotlin) and `CATALOG_RELEASE_VERSION_PATTERN` /
    `MAX_CATALOG_RELEASE_VERSION_LENGTH` (npm) publish the rule; a test holds it to the API's
    `ReleaseVersion`.
  - Kotlin API: `StencilResource.version`, `CatalogResourceReference.version`/`draftVersion`,
    `TemplateValidationContext.allowDraftStencilReferences` and the `version` parameter of
    `TemplateValidationContext.forStencil` are removed; `CatalogInfo.registry` and `namespace`,
    `CatalogManifest.catalogDependencies` and `registry` and `namespace` on
    `CatalogResourceReference` are added (nullable in the model so manifests stored under v7 still
    bind). `templateModelVersion` stays 1.

- **BREAKING: catalog content is edited and read per release, not in one working copy.**
  Every path to a catalog's templates, variants, stencils, themes, attributes, code lists, fonts and
  images, and its `dependencies`, `changes` and `mark-ready`, gains `/releases/{release}`
  after the catalog. Reads work on any release; writes only on a draft, and are refused with 409 on
  a released release. `validateTemplateData` stays on the catalog, as it selects a release like
  generation does. `releaseCatalog` is replaced by the release operations (`releaseCatalogDraft`).
  An import never touches a draft (see "an import installs a release" below).

- **BREAKING: an import installs a release, never a draft.** `importCatalog` installs the release
  the archive carries, at its version, as an immutable release of the catalog it names (registry,
  namespace and key), side by side with the releases the tenant already has, as installing from an
  exchange does (ADR 0003). Nothing is merged, and no draft is created or changed. The same version
  with the same fingerprint is already present and changes nothing; the same version with a different
  fingerprint is refused with 409 `conflict`, as for the dependency releases it carries. An archive without a
  release is not a valid archive at any wire version (`release.version` is required) and is refused
  with 400 `bad-request`. A catalog the import creates holds the release and no draft; an author
  starts one with `createCatalogRelease` `from` the release. The request loses `catalogVersion` and
  `authoredMode`. `catalogType` loses its default (AUTHORED): the import that creates a catalog must
  give it, or is refused with 400 `bad-request`. A catalog the tenant has keeps its type, and an
  import never changes it: `catalogType` may be omitted or given as the catalog's type, and given as
  the other type the import is refused with 409 `conflict`, installing nothing. An import into an
  AUTHORED catalog adds the release beside its others. Changing a catalog's type is not part of 2.0
  (ADR 0003). `ImportCatalogResponse`
  reports the release, `version` (required), and `newlyInstalled` (required, false when it
  was already present), and loses `catalogVersion`, the per-resource counts `installed`, `updated`
  and `failed`, and `aborted`: installing a release is all or nothing. `total` stays. The operation
  requires `CATALOG_MANAGE`, as `installCatalogRelease` does, instead of `TEMPLATE_EDIT`: installing
  a release is not editing templates.

- **BREAKING: nothing is upgraded; a subscribed catalog lists and installs the releases its source
  offers, and the 1.x reconciliation is gone.** Releases are immutable and installed side by side
  (ADR 0003), and deployments choose which is used, so the upgrade operations are replaced by what
  they do:
  - `upgradeCatalog` (`POST …/{catalogKey}/upgrade`, with an `UpgradeCatalogRequest` body) becomes
    `installCatalogRelease`, `POST
    …/catalogs/{registry}/{namespace}/{catalogKey}/available-releases/{version}/install`, with
    no query and no body. It installs the release named in the path, fetched from the catalog's
    source, exactly as `importCatalog` installs the one an archive carries, with the releases it pins
    installed first; there is no "highest" default. Nothing is updated in place and nothing is
    removed; deploying decides which release an environment serves. The same version with the same
    fingerprint is already present (`newlyInstalled: false`); with a different fingerprint the
    install is refused with 409 `conflict`; a pin that cannot be had refuses it with 409
    `release-dependencies-missing`; a catalog that is not SUBSCRIBED is refused with 409 `conflict`,
    and a version the source does not offer with 404. `UpgradeCatalogResponse` becomes
    `InstallCatalogReleaseResponse` and reports the release as `ImportCatalogResponse` does:
    `registry`, `namespace`, `catalogKey`, `version`, `newlyInstalled`, `total`,
    `installedDependencies` and `presentDependencies`; it loses `previousVersion`, `newVersion`,
    `installResults`, `removedResources` and `aborted`. It stays a schema of its own: it has no
    `catalogName`, and its dependency lists name releases fetched from their sources.
  - `previewCatalogUpgrade` (`GET …/{catalogKey}/upgrade-preview`, `CatalogUpgradeDiff`) becomes
    `listAvailableCatalogReleases`, `GET …/catalogs/{registry}/{namespace}/{catalogKey}/available-releases`,
    returning `AvailableCatalogReleases`: every release the source offers that the tenant has not
    installed, lowest first, not only those above the highest installed, so a fix `1.4.1` is listed
    while `2.0.0` is installed (`available`, `AvailableReleaseDto`). Each names the release it is
    compared to (`comparedTo`, required and nullable: the closest installed release whose version
    is below it, null when there is none), the resources it added, modified or removed against `comparedTo`
    (`ReleasedChangeDto`; all added when `comparedTo` is null), and the releases it pins, each with
    whether the tenant has it (`AvailableReleaseDependencyDto.present`). `CatalogUpgradeDiff` goes,
    with `previousVersion`, `newVersion`, `upgradeAvailable`, the `added` / `removed` / `changed` /
    `unchanged` buckets, `conflicts` and `blockedByConflicts`. A catalog that is not SUBSCRIBED is
    refused with 409 here too.
  - Both need the catalog's source (its registry, through its mirrors). When it cannot be used they
    fail with `catalog-source-unavailable` (a new problem type, registered as 503) and install
    nothing: **503** (`CatalogSourceUnavailableError`, possibly with `Retry-After`) when the source
    could not be reached or did not answer in time, **502** (`CatalogSourceBadGatewayError`) when it
    answered with an error or with something that is not a valid answer. Every client surfaces it
    as a problem, 502 or 503, like any other (`KnownProblemSlugs.CATALOG_SOURCE_UNAVAILABLE`); the
    server stubs add `ProblemDetails.catalogSourceUnavailable`.
  - The permissions are unchanged: `CATALOG_MANAGE` to install, `CATALOG_VIEW` to list. Conformance
    scenarios `install-catalog-release`, `install-catalog-release-already-present`,
    `install-catalog-release-dependencies-missing`, `install-catalog-release-source-unavailable`,
    `list-available-catalog-releases` and `list-available-catalog-releases-source-unavailable`.

- **BREAKING: a catalog is identified by its registry, its namespace and its key** (ADR 0003).
  Every catalog-scoped path is `/tenants/{tenantKey}/catalogs/{registry}/{namespace}/{catalogKey}/…`
  instead of `…/catalogs/{catalogId}/…`. The registry is the DNS name of the registry the catalog
  is published on (`exchange.epistola.app`, `registry.gemeente-x.nl`); `~` in a path means the
  server's configured default registry, which may also be named in full. The namespace is a slug
  within the registry. Requests may omit the registry (the default); responses always name it in
  full. Every DTO that named a catalog (`catalogId`, `catalog`, or a catalog's `slug`)
  now carries `registry`, `namespace` and `catalogKey`: the generation, preview and batch requests,
  `VariantSelectionAttribute`, `CatalogDto`, `ReleaseDependencyDto`, `AvailableCatalogReleases`,
  `InstallCatalogReleaseResponse`,
  `DeploymentDto`, `DeploymentEventDto`, `AttributeDto` (and its `codeListBinding`), `CodeListDto`,
  `FontDto`, `ImageDto` and `ImportCatalogResponse`. Generated documents, generation items and
  collected results (`DocumentDto`, `DocumentGenerationItemDto`, `GenerationResult`) add the
  required `registry`, `namespace` and `catalogKey` next to `version`.
  `importCatalog` takes an optional `registry` and `namespace`. The client libraries' template data
  validators take `registry` before `namespace` (`DEFAULT_REGISTRY = "~"`).

- **BREAKING: one schema for a version, `ReleaseVersion`.** Wherever the contract names one
  exact version of a release, it is a SemVer core version without leading zeros
  (`^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$`), at most 128 characters: the
  release request (`version`, `acknowledgedBehind`), a dependency declaration, and every
  response member that names a version (`CatalogDto.highestVersion`, which replaces
  `releasedVersion` and is the version of the catalog's highest release, `CatalogReleaseDto.version` and
  `base`, `BehindReleaseDto.version`, `CatalogChangesDto.base`,
  `CatalogDraftReleasePreview.base`, `needed` and `suggested`, `ReleasedDependencyDto`,
  `ReleaseDependencyDto`, `CatalogDependencyDto`, `AvailableReleaseDto.version` and `comparedTo`,
  `AvailableReleaseDependencyDto.version`, `InstallCatalogReleaseResponse.version` and
  the `installCatalogRelease` `version` path parameter, deployments, deployment history, missing
  dependencies, jobs, documents and collected results, `ImportCatalogResponse.version`).
  `CreateCatalogReleaseRequest.from` and the `{release}` path parameter take the same version or a
  `releaseId`. A version is always named `version`, and every member named `version` is one; a
  range is always `versionRange`; version ranges are not versions and keep their own schema.
  Every client still sees these members as plain strings, and each client's generated model
  validation checks a member that references `ReleaseVersion` as if its constraints were inline.

- **BREAKING: a release is selected by a version range, together with an environment.** The
  generation, preview, batch and validation requests take `versionRange`:
  a range in the npm SemVer range grammar, which a server accepts in full (an exact version, `^`,
  `~`, comparators, a hyphen range, a `||` union). It combines with `environmentKey`, which narrows the choice to the releases that environment
  serves; the highest release that satisfies the range is rendered. `DeployReleaseRequest` and
  `DeploymentEventDto.previousReleaseVersion` are removed.

- **BREAKING: release and deploy replace publish.** `releaseCatalog` requires `CATALOG_RELEASE` and
  refuses while any resource is `new` or `modified`. Consumer permissions drop the `versions` scope
  and the templates `publish` level; marking ready is part of `write`, `catalogs` gains `release`
  and `environments` gains `deploy` (OAuth scopes `epistola:catalogs:release`,
  `epistola:environments:deploy`). Deleting an environment that still serves a release is refused.

- **BREAKING: stencil versions are gone; a stencil has a working copy that is marked ready.** The
  stencil version operations (`listStencilVersions`, `createStencilVersion`, `getStencilVersion`,
  `updateStencilDraft`, `publishStencilVersion`, `archiveStencilVersion`, `getStencilVersionUsage`)
  and the per-template upgrade operations (`applyStencilUpgrade`, `previewStencilUpgrade`) are
  removed, with their schemas. `getStencilContent` and `updateStencilContent` read and replace the
  working copy; `markStencilReady` marks it reviewed and moves nothing (`notFitting` names the
  instances that do not fit it, which block the release, ADR 0006); `listStencilUsage` lists the
  template variants and stencils that use it, and whether each fits (`fits`, `problems`). `StencilDto` and `StencilSummaryDto` report a `status`
  in place of `versions` and `latestPublishedVersion`. The stencil node's representation inside a
  template model is unchanged here: it belongs to the portable catalog format and changes there.

- **BREAKING: the data contract is part of the template's working copy.** The contract draft,
  publish and version history operations (`createContractDraft`, `updateContractDraft`,
  `publishContractDraft`, `listContractVersions`) and the `Contracts` tag are removed, with their
  schemas. `updateTemplate` edits the data model and examples in the working copy; marking the
  template ready covers its contract, and a change that breaks callers is reported when the catalog
  is released.

- **BREAKING: template versions and activations are gone; a variant has a working copy.** The
  `listVersions`, `getVersion`, `updateVersion`, `publishVersion` and `archiveVersion` operations
  and the `Versions` tag are removed, with `VersionDto`, `VersionSummaryDto`, `UpdateDraftRequest`,
  `PublishVersionRequest` and `VersionListResponse`. So are the per-variant activations
  (`listVariantActivations`, `removeVariantActivation`, `getActiveVersion`, `ActivationDto`,
  `ActivationListResponse`) and the variant draft operations (`getVariantDraft`,
  `upsertVariantDraft`, `createVariantDraft`, `publishVariantDraft`, `discardVariantDraft`). In
  their place, `getVariantContent` and `updateVariantContent` read and replace a variant's working
  copy, and `markVariantReady` and `markTemplateReady` mark it reviewed for the next catalog
  release. `VariantDto` and `VariantSummaryDto` report a `status` (`ResourceStatus`: `new`,
  `modified`, `ready`, `released`) in place of `hasDraft` and `publishedVersions`.

- **BREAKING: generation renders a catalog release, never a template version.** `versionId` is gone
  from `GenerateDocumentRequest`, `BatchGenerationItem`, `PreviewDocumentRequest` and
  `ValidateTemplateDataRequest`. A request selects the release with `versionRange` and `environmentKey` (see
  "a release is selected by a version range"); with neither, the catalog's latest release is
  rendered, never unreleased changes. Preview can render a draft instead (`draft`: its
  `releaseId`). Jobs, documents and collected results
  report `version` in place of the template version's `versionId`; it is null for a
  draft rendered through an environment. They name the release rendered by its `releaseId`
  (`CatalogReleaseId`), never a template version: a document rendered from a draft carries the
  draft's.

- **The clients no longer bundle a JSON Schema library.** Every client validated template data
  itself, each with a different library and under a different dependency policy — networknt 1.5.9
  `compileOnly` in the two JVM clients, NJsonSchema 11.6.1 as a hard reference in .NET,
  `jsonschema` as a hard dependency in Python, ajv as an optional peer in Node.js. On the JVM the
  dependency was never published at all, so `TemplateSchemaValidator` threw
  `NoClassDefFoundError` for any consumer who did not already happen to have a 1.x copy on the
  classpath; it worked only in the clients' own tests. Validation now goes through a pluggable
  `TemplateDataValidator` (`ITemplateDataValidator` in .NET, a `Protocol` in Python) whose one
  shipped implementation asks the server's `validateTemplateData`; it takes the release to check
  against as `versionRange` (`version_range` in Python, a `versionRange` member of
  `ServerValidationTarget` in Node.js), alongside `environmentKey`. See each client's changelog for
  its breaking details, and #9.

  The server was always going to validate the same data on submit, so this removes a second
  authority rather than a check: a client-side verdict could only agree, or disagree and be wrong.
  Two reference adapters kept in test sources now disagree *on purpose and under test* — ajv with
  `ajv-formats` rejects an `email` field holding `not-an-email`, while networknt under 2020-12
  treats `format` as an annotation and accepts it — which is the clearest statement of why.

- **One failure shape, pinned across the five clients.** Each client had reported a different one,
  all of them contradicting their own documentation: `$.customer.email` (Kotlin, Jakarta — neither
  set `PathType.JSON_POINTER`), `customer.name` (Node.js, Python) and `#/customer.name` with
  NJsonSchema's `StringExpected` as the keyword (.NET). `path` is now a JSON Pointer (RFC 6901) into
  the data and `keyword` a JSON Schema keyword, in every client, enforced by the new
  `template-data-validation` conformance scenario rather than by five separate test suites.

- **Releases are only started by hand, and can be pre-releases.** A push to a `release/*` branch no
  longer releases: every release, from `main` or a release branch, is started with `make release`
  (a published GitHub Release), which now works on release branches too and on macOS.
  `make release PRERELEASE=alpha.1` publishes a pre-release such as `v2.0.0-alpha.1`, also from a
  `feature/*` branch. A pre-release is published to every registry but never as the latest version:
  npm uses the `next` dist-tag, the mock-server image gets no `latest` tag, and the docs keep their
  `latest/` page. The release workflow refuses a GitHub Release whose pre-release mark does not
  match its tag.

### Removed

- **BREAKING: `TemplateDto.schema`, `CreateTemplateRequest.schema` and
  `UpdateTemplateRequest.schema`.** A template had two JSON Schema members, `schema` ("input
  parameters") and `dataModel`. Only `dataModel` is a data contract: it is the one the catalog
  wire format carries, the one examples and `validateTemplateData` are checked against, and the
  one shared schemas will be included into (ADR 0009). Epistola Suite has never stored `schema`:
  it ignored it on create and update and always returned `null`. `dataModel` takes over the
  invoice example `schema` had, which is the one the template's example `dataExamples` satisfy.
  `createTemplate` now takes `dataModel` and `dataExamples` itself (see Added). Each
  client's worked in-process validator read `schema`, so against a real server it had no schema
  and claimed nothing about the data; the Kotlin, Jakarta, .NET, Python and Node adapters now read
  `dataModel`. A consumer that falls back from `dataModel` to `schema` drops the fallback. The
  conformance scenario `update-template` (driver action `update-template`) holds all five clients
  to sending the contract as `dataModel`, a `$ref` and a pattern's backslashes untouched, with its
  examples and `If-Match`, and to reading it and the new `ETag` back.
- **BREAKING: `uploadImage`'s `sensitive` form field.** It chose a tenant-isolated deduplication
  scope for an image's bytes. Epistola Suite 2.0 keeps every image's bytes in one store,
  deduplicated alike, so the field had nothing left to choose. A server ignores a client that
  still sends it; conformance scenario `image-upload` now requires that it is not sent.
- **BREAKING: the `X-API-Key` header** (`apiKeyAuth` security scheme). An API key is sent as
  `Authorization: ApiKey <key>`, the only API-key transport the contract defines; no client sent
  `X-API-Key`, and their READMEs no longer mention it.
- **BREAKING: `UpdateTemplateRequest.forceUpdate`.** A template is edited in a draft, which nothing
  renders, so a breaking data-model change needs no confirmation; it is weighed when the draft is
  released. Data examples are still always validated (`422`).
- **BREAKING: `upgradeCatalog`, `previewCatalogUpgrade` and the 1.x reconciliation of a subscribed
  catalog** (see "nothing is upgraded" under Changed; `installCatalogRelease` and
  `listAvailableCatalogReleases` replace them): `CatalogUpgradeDiff`,
  `CatalogInstallResultDto` (with its `INSTALLED` / `UPDATED` / `SKIPPED` / `FAILED` status),
  `RemovedCatalogResourceDto`, and `UpgradeCatalogResponse`'s `installResults`, `removedResources`
  and `aborted`.
- **The schemas left from the bulk template import.** `ImportTemplatesRequest`,
  `ImportTemplateDto`, `ImportVariantDto`, `ImportTemplatesResponse` and `ImportTemplateResultDto`
  belonged to an operation removed before 1.0 and no operation referenced them;
  `ImportTemplateResultDto` was still registered under `components.schemas`, so clients generated a
  model for it. Importing is `importCatalog`.
- **BREAKING: the deprecated `id` and `slug` halves of each resource address.** `key` is now the
  only address on every read model, and is required (#84; see "a resource's address is its `key`"
  under Changed). `UpgradeCatalogRequest`, whose only field was the ignored `includeNewSlugs`, is
  gone with `upgradeCatalog`, as is the empty `Assets` tag. The
  `older-server-response` conformance scenario, which proved a 1.x client could read a server from
  before 1.3.0, is replaced by `template-listing-by-key`.

### Fixed

- **The Kotlin, Python and .NET result collectors surface `releaseId` and `releaseName`.** All three
  parse result lines by hand and dropped the two members, so a consumer could not tell which draft
  rendered a document; the Jakarta and Node collectors hand over the generated model and had them.
  Conformance scenario `collect-result-release` now requires both from every client. The Kotlin
  collector also read an optional member sent as JSON `null` (`batchId`, `documentId`,
  `correlationId`, …) as the string `"null"`; it is now null.
- **A v7 stencil keeps the scope v7 rendered it with** (wire v8 migration). V8 isolates a stencil
  unless it declares `readsTemplateData`, and shows a fill the stencil's parameters only through a
  placeholder that declares `passParameters` (ADR 0006); the v7-to-v8 migration set neither, so a
  migrated stencil lost the template's data and every fill its parameters. It now gives a v7
  stencil `readsTemplateData`, and each of its placeholders `passParameters`, with the notice
  `CATALOG_STENCIL_SCOPE_KEPT`: in v7 a stencil's content and every fill saw both. Every placeholder
  passes them because the templates filling a stencil's placeholders are often in another archive.
  The v7 fingerprint still verifies against the source content.
- **A data model reference that resolves to nothing is a finding** (catalog validation). The
  validator resolves a `$ref` only when data reaches it, so `CatalogValidator.validate` missed an
  unresolvable reference in a template without data examples, and threw a `SchemaException` for one
  with examples instead of reporting it. Checking a data model now resolves every reference, and
  each one that resolves to nothing, such as a missing local pointer, a relative file, an unknown
  bundled schema version or a remote URL (nothing is fetched), is a
  `CATALOG_TEMPLATE_DATA_SCHEMA_INVALID` finding on `dataModel`; the examples are not judged against
  it.

- **A failed collect call raises its error instead of a parse failure** (#30, Kotlin Spring
  RestClient client). `ResultCollector.collectOnce()` read the body of every response as NDJSON
  results, because `exchange {}` does not raise on an error status: a 403 problem then surfaced as a
  `NullPointerException` on its missing `sequence`. It now checks the status first and raises a
  `ProblemDetailException` for an `application/problem+json` body, else Spring's
  `HttpClientErrorException`/`HttpServerErrorException`, as the client does for every other call. A
  result line that lacks `sequence`, `requestId`, `status` or the catalog it names is refused with an
  `IllegalStateException` naming the line. The Python client's collector raised a bare
  `RuntimeError` there; it now raises the same typed exceptions (see its changelog). The Jakarta,
  .NET and Node.js collectors already checked the status; a test now pins the Jakarta one.

- **Validating an archive checks each binary's bytes against its `contentHash`** (#91).
  `CatalogValidator.validate` built the resource checks' context without the archive's content, so
  `CATALOG_ASSET_CONTENT_HASH_MISMATCH` could never fire and a catalog could claim bytes it does not
  carry. The archive's content now reaches them, so a binary whose bytes hash to something else is
  refused.

- **The `entry` parameter of `getCatalogDraftMergeSides` matched nothing in generated servers.** Its
  pattern held a star followed by a slash, which the Kotlin generator rewrites so it does not end a
  comment, changing the regular expression; every request was refused. The pattern now bounds the
  repetition (`^[A-Za-z][A-Za-z0-9-]{0,63}/[^/]+$`), and a server-stub test refuses any pattern the
  generator would change.

- **No update request reads null as "remove" any more.** Several said "null clears", which no
  generated server or client can honour, since they cannot tell an omitted member from a null one.
  On every update an omitted or null member now keeps its value, and a value is removed this way
  instead: an empty string (or one of only whitespace) removes `UpdateConsumerRequest.description`
  and `.contact`, `UpdateStencilRequest.description` and `UpdateThemeRequest.description`; an empty
  object removes every `UpdateThemeRequest.blockStylePresets` preset and an empty array every
  `UpdateCatalogReleaseRequest.presets` preset (null no longer does); `UpdateThemeRequest.spacingUnit`
  goes back to its default when sent as 4. A member with no empty value gets an explicit flag,
  `true` to remove it and refused with 400 beside the member it removes:
  `UpdateCatalogReleaseRequest.clearDefaultTheme`, `UpdateConsumerRequest.clearExpiresAt`
  (experimental) and `UpdateAttributeRequest.clearCodeListBinding`, which moves a bound attribute
  to inline `allowedValues`. The Kotlin, .NET and Node.js READMEs say so, and the conformance
  drivers send the empty values and the flags (`update-consumer-remove-values`,
  `clear-catalog-release-theme-settings`, and the new `update-attribute` action in
  `update-attribute-unbind-code-list`).
- **`UpdateTemplateRequest.description`: an empty string removes it.** It said "null clears it",
  which no generated server or client can honour, since they cannot tell an omitted member from a
  null one. Omitted or null now keeps the current description and an empty string (or one of only
  whitespace) removes it, as Epistola Suite does; `CreateTemplateRequest.description` says the same
  of creating a template with none.
- **A merge preview's `base` is the merge base**, the closest release the draft and the merged
  release both descend from (ADR 0005), not the draft's own base as `CatalogDraftMergePreview.base`
  said; it need not be the draft's base, and is null when none is known or it is not installed
  (`missingBase`). The preview, the outcome and ADR 0005 say "against the merge base".
- **The catalog's `CatalogReleaseVersionsTest` read the API's `ReleaseVersion` from
  `catalogs.yaml`**, which no longer defines it since the key and number rules moved to
  `identifiers.yaml`, so the parity check failed. It reads `identifiers.yaml` now.
- **`ping` sends credentials.** It declared `security: []`, which tells a generated client the
  operation takes none, so the authenticated `details` were unreachable. Authentication is now
  optional (`{}` and every scheme). `PongDetailsDto.partitions` no longer speaks of "this tenant"
  for an endpoint that names none. Conformance scenario `ping-sends-credentials`.
- **`rotatePublicKey` says who may call it**: only the consumer itself, with a self-signed JWT
  signed by its current key (`security: bearerAuth`, `x-required-authentication`), as the consumer
  documentation already said. It had no marker at all.
- **Responses the descriptions imply are declared.** `generateDocument` and
  `generateDocumentBatch`: 404 (an unknown tenant, catalog, template, variant or environment, no
  variant matching the attributes, or no version matching; the target is resolved when the request
  is accepted) and 429. Template data is checked when an item renders, not on submission, so
  neither declares `template-data-invalid`. 404 on `listGenerationJobs`, `listDocuments` and
  `collectGenerationResults`, as on the other tenant-scoped operations; 400 on
  `listDeploymentHistory`, `listStencilUsage` and `deployCatalogRelease`, which take query
  parameters; 409 on `createTenant` for a key another tenant has.
- **BREAKING: `listDocuments` filters by catalog.** A template key is unique only within its catalog, so the
  listing gains `registry`, `namespace` and `catalogKey` filters, and `templateKey` now needs
  `namespace` and `catalogKey` (400 otherwise). New `list-documents` driver action and conformance
  scenario `list-documents-by-catalog`.
- **`importCatalog` and dependencies it fetches.** A pin the archive does not carry is fetched as
  before; one that cannot be had, whatever the reason, refuses the import with 409
  `release-dependencies-missing`, as `installCatalogRelease` treats pinned releases. The text now
  says so, and that an import never answers `catalog-source-unavailable`.
- **The partition formula is stated once, exactly**: `collectGenerationResults` and
  `PartitionAssignment` give `(murmur3_x86_32(utf8(routingKey), seed=0) & 0x7FFFFFFF) % total`, as
  `PartitionRouting` computes it. `PartitionAssignment` no longer refers to v0.3/v0.4,
  `minInterval`, a `pending`/`cutoverAt` assignment or a document in another repository.
- **Descriptions that contradicted the operations.** `createVariant` and `updateVariant` spoke of
  tags they do not take, and `updateAttribute` of its description alone; each now names the members
  it takes. `CreateAttributeRequest` refused a mixed constraint with a 422 no operation declares; it
  is 400 `validation-error`. `deleteTheme` no longer says templates "fall back to their own styles":
  it is refused with 409 while the draft still uses the theme, as `deleteCatalogRelease` is. Resources
  have no versions of their own (`deleteVariant`, `deleteStencil`, `getVariant`, `getStencil`,
  `createStencil`, `CreateStencilRequest.parameterSchema`, the Stencils tag). Release-scoped
  operations no longer say "for a tenant", and resources addressed by key are got "by key".
  `FontDto` no longer calls images read-only, `DocumentDto.createdBy` is the consumer's id rather
  than a "future feature", `CodeListDto` names `sourceUrl` rather than "HTTPS",
  `ConsumerPermissions.environments` deploys releases, and `uploadImage` states no size
  limit of its own, hedging Epistola Suite's. An unset `spacingUnit` or `blockStylePresets` is
  absent from `ThemeDto`, as an update's null leaves it, instead of a null the schema does not
  allow.

- **Documentation names the real permissions and paths.** `docs/auth.md`'s role table lists
  `CATALOG_RELEASE` and `ENVIRONMENT_DEPLOY` (granted by `CONTENT_PUBLISHER`) in place of the removed
  `TEMPLATE_PUBLISH` and `STENCIL_PUBLISH`, and names the Suite permissions no operation requires.
  The Consumers tag lists the real permission levels (`none`, `read`, `write`, and `deploy`,
  `release` and `manage` where they exist), and the `bearerAuth` and `ConsumerDto.authMethod`
  descriptions name the consumer paths with their tenant prefix.

- **The Jakarta EE client can read an `ETag` and a `Location`.** Its generated MicroProfile Rest
  Client methods return the body alone, so a caller could send `If-Match` but never read the tag to
  send, nor the address of what it created. Every operation whose success response declares `ETag`
  or `Location` now has a sibling `<operation>WithHttpInfo` returning `jakarta.ws.rs.core.Response`
  (41 operations: every get and update that answers with an `ETag`, every create, and
  `generateDocument` and `generateDocumentBatch`), `uploadImageWithHttpInfo` with its one-argument-
  per-field overload; the build fails if one stops matching. `importCatalog`'s convenience overload
  takes the shared `CatalogType` enum. The other clients already exposed
  it (`…WithHttpInfo`, `…_with_http_info`, `…Raw`).
- **Conformance covers every operation contract 2.0 adds.** New scenarios drive
  `getVariantContent` and `getStencilContent` (reading the `ETag` exactly, quotes included),
  `updateStencilContent`, the four mark-ready operations, `getCatalogChanges`, `listStencilUsage`,
  `listEnvironmentDeployments` and `listDeploymentHistory` in all five clients.

- **The Jakarta EE client reads a null enum member as null.** The generated JSON-B enum
  deserializers read every value as a string, so a JSON `null` failed the whole response; the first
  nullable enum in the contract, `CatalogDraftReleasePreview.changeKind`, made a release preview of
  a catalog that was never released unreadable. The build now teaches each generated enum
  deserializer to return null for a JSON null. Found by the new
  `preview-catalog-draft-first-release` conformance scenario.

- **An example may leave out a required property that has a default.** The validator held data
  examples strictly to the schema, while rendering fills an absent property from its `default`
  before validating, so a catalog demonstrating schema defaults was refused on export. Examples are
  now validated with defaults filled in, recursing into nested objects; an explicit `null` is still a
  value.

- **`missingFields` and `invalidFields` reach consumers.** Contract 1.4.0 added both to
  `TemplateDataValidationProblemDetail`, but no client's hand-written problem parser read them: the
  Jakarta, Python and .NET parsers surfaced `errors` and `validationErrors` only, and the Kotlin and
  Node.js clients saw them solely because they expose unmodelled members generically. A
  `template-data-invalid` response therefore arrived with the field-by-field account that is the
  whole point of the problem type discarded. All five now expose them, with an
  `isTemplateDataProblem` flag, and the new `template-data-problem` conformance scenario holds them
  to it. A malformed member yields no findings rather than a guess.


- `make publish-local` stamps every artifact with the spec version (or `VERSION=`), as the release
  workflow does. It published the catalog as `0.1.0-SNAPSHOT`, the .NET client as `0.0.0-dev` and
  the Node.js client with a stale `package.json` version, so the local server stubs named a catalog
  version that was never published.

### Documentation

- **The template model, explained for building templates through the API** (#18). A guide,
  [`contracts/api/docs/template-model.md`](contracts/api/docs/template-model.md), explains the
  node/slot graph end to end with a worked letter: a heading, a paragraph with an expression, a
  conditional with an `else` branch, a table and a stencil instance. It points to the component and
  style registries as the source of truth rather than copying them. The API reference shows the same
  letter as the example of `updateVariantContent`, the template document schema's descriptions say
  what nodes, slots, props and styles are, and the spec's introduction has a "Template model"
  section. Tests keep the example a valid template, the spec's copy equal to it, and the guide's
  excerpts quoted from it.

- **ADR 0009, shared schemas included in data contracts by reference (Proposed).** A catalog
  resource holds a reusable JSON Schema. A template's data contract or a stencil's parameter schema
  includes it with `$ref` and an `epistola:schema/[{alias}/]{key}` URI, resolved lexically like a
  stencil (ADR 0006), and releasing checks the resolved contracts. The rich-text schemas stay
  built-in: the contract defines them as engine vocabulary, versioned in their id, not as catalog
  content. The API returns contracts as stored, references included, and a separate operation
  returns the contract bundled into one self-contained schema. Nothing in the spec changes yet.

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
- Dependencies are updated within their major versions. The Kotlin client builds on Spring
  Framework 6.2.19 (Spring Boot 3.5.16), Jackson 2.21.4 and Kotlin 2.4.20; the server stubs on
  Spring Boot 4.1.1 and Jackson 3.1.5; the catalog on json-schema-validator 3.0.7. The .NET client
  raises its package minimums (see its changelog).
- The clients and server stubs are generated with OpenAPI Generator 7.25.0 instead of 7.19.0.
  Wire behaviour is unchanged: every client passes the full conformance suite. Visible in source:
  - Kotlin client: enums serialize through `@get:JsonValue`, and constructor parameters carry
    `@param:JsonProperty`.
  - Server stubs: required properties are declared `@param:JsonProperty(required = true)`.
  - Jakarta client: model fields are `protected` instead of `private`.
  - .NET client: deprecated properties are marked `[Obsolete]`, which newly includes
    `UpgradeCatalogRequest.IncludeNewSlugs`.
  - Node.js client: a date that is `null` on the wire is read as `null` rather than `undefined`,
    as its type already declared.

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
