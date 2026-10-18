# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
The .NET, Python and Node.js clients keep their own changelogs for changes to their hand-written
libraries.

## [Unreleased]

### Changed

- **A draft is numbered when it is released** (ADR 0002, amendment 2). Every AUTHORED catalog has
  one next version, addressed as `next`, where everyday work goes and the only draft that is
  released; any other draft is long-term work, named and addressed by its `draftId`. A released
  version is still addressed by its number: `{catalogVersion}` accepts `next`, a draft id or a
  SemVer number. `CatalogVersionDto` gains `draftId`, `isNext` and `name`, and a draft's `version`
  is null. `CatalogDto.draftVersions` becomes `drafts` (`CatalogDraftSummary`).
  `createCatalogVersion` becomes `createCatalogDraft` (`{name, from?}`: long-term work, from the
  next version or a release), `renumberCatalogVersion` becomes `renameCatalogDraft`,
  `deleteCatalogVersion` becomes `deleteCatalogDraft` (discarding the next version starts it again
  on the latest release), `previewCatalogVersionRenumber` becomes `previewCatalogDraftRelease` (the
  number it would be released as, from the kind of change against the latest release, and why),
  and `releaseCatalogVersion` becomes `releaseCatalogDraft`, which numbers the next version (as
  `version`, never lower than its changes need, or as they need) and continues it on the release;
  it no longer returns `openedDraft`, and `draftsBehind` names long-term work. `RenumberWarningDto`
  is gone. Preview names a draft as `draft` (`next` or a draft id) in place of `draftVersion`.

### Added

- **Renumber warnings name the changes that make a number too low.** `RenumberWarningDto` gains
  `reasons`, and its known kinds gain `passes-draft`, `breaking-changes-in-minor` and
  `additions-in-patch` beside `breaking-changes-in-patch`: breaking changes need a major, additions
  a minor (ADR 0001's rule for what breaks).

- **Stencils by reference, with lexical scope (ADR 0006), catalog side.** A stencil declares
  `readsTemplateData` (wire v8, default `false`, left out of the JSON when false and not covered by
  the legacy fingerprint algorithms) when its content also sees the using template's data. The
  template validator accepts `readsTemplateData` on a stencil node, and `passParameters` and
  `parametersAs` on a placeholder (a placeholder that passes the stencil's parameters to its fill,
  under a name following the `paramsAlias` rules). `CatalogDocumentMigration` migrates one stored
  template document or style map from wire v7 to v8 exactly as the archive migration does inside a
  resource, so a producer's store and an archive migrate alike.

- **Catalog versions: several drafts at once, released in place** (ADR 0002). A catalog consists
  of versions, each a `draft` or `released`, addressed as
  `…/catalogs/{registry}/{namespace}/{catalogKey}/versions/{catalogVersion}`. `listCatalogVersions`,
  `createCatalogVersion` (from the version's base, or `from` any other version),
  `getCatalogVersion`, `renumberCatalogVersion` (drafts only; warnings from
  `previewCatalogVersionRenumber` must be acknowledged), `deleteCatalogVersion` (drafts only) and
  `releaseCatalogVersion`, which keeps the number and address, makes the version immutable (returned as `catalogVersion`), and
  changes no other draft: drafts are independent copies, and a draft lists the releases it is
  behind (see "catalog drafts are independent copies" under Changed). `CatalogDto` lists
  `draftVersions`.

- **The changes of a catalog version, and deployments.** A released version also carries the
  releases of other catalogs it renders with (`dependencies`) and the environments that serve it
  (`deployedTo`). `getCatalogChanges` lists what is new, modified, ready or removed since the
  version's base and whether releasing it would be accepted;
  `markCatalogReady` marks everything changed ready in one action. An environment can
  serve several releases of one catalog: `listEnvironmentDeployments`, `deployRelease` and
  `undeployRelease` (both addressed by the release, `…/deployments/{registry}/{namespace}/{catalogKey}/{releaseVersion}`,
  with no request body), and `listDeploymentHistory` with every deploy and undeploy.

- **A template has a `description`** (#97): optional plain-text notes for its authors, at most
  2000 characters, not rendered. On `TemplateDto`, `TemplateSummaryDto`, `CreateTemplateRequest`,
  `UpdateTemplateRequest` (omit to keep, null to clear) and `ImportTemplateDto`, and in wire v8 as
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
  (`UNAVAILABLE`, `CONTENT_NOT_RETAINED`; `NOT_DEPLOYED` and `NOT_INSTALLED` are no longer
  returned). The new `deployRelease` query parameter `includeDependencies` (default false) also
  deploys them explicitly, all or nothing. Every client's problem parser surfaces
  `missingDependencies`; the server stubs add `ProblemDetails.releaseDependenciesMissing`.

- **Catalog dependencies.** A catalog declares each catalog its resources reference, once, with a
  version selector: `listCatalogDependencies` and `updateCatalogDependencies`. References between
  catalogs carry no version; they render with the release the declaration resolves to, so a release
  renders with exactly one release of every catalog it depends on. A release is refused when a
  resource references an undeclared catalog, a dependency resolves to no release, or two paths need
  different releases of one catalog. `CatalogChangesDto` reports `dependenciesChanged`.

### Changed

- **Stencil usage reports whether an instance fits, not whether it is held back** (ADR 0006).
  `StencilUsageDto.heldBack` is replaced by `fits` and `problems`; `MarkStencilReadyResponse.heldBack`
  by `notFitting`, the instances that do not fit; `ResourceChangeDto.heldBackInstances` is removed.
  Marking a stencil ready moves nothing: every instance renders the stencil as it is, and one that
  does not fit blocks the release.

- **`UpdateTemplateRequest.forceUpdate` has no effect.** A template is edited in a draft catalog
  version, which nothing renders, so a breaking data-model change is saved without confirmation and
  is no longer refused with `409`; it is weighed when the draft is numbered and released (ADR 0002).
  The field stays so 1.x requests parse. Data examples are still always validated (`422`).

- **A variant attribute key may name another catalog's attribute.** Wire v8 gave variant
  attribute keys the resource key rule, which refused the qualified form `<catalogKey>.<key>`
  (`system.locale`) that names an attribute defined in another catalog, so a template using the
  system catalog's `locale` could not be validated. The key is now an attribute key, optionally
  qualified by a catalog key, each part by its own rule (`CatalogKeys.isValidAttributeKey`,
  `ATTRIBUTE_KEY_PATTERN`, at most 101 characters in the schema). The v7-to-v8 migration pads the
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
  UUID keys), in the API and in wire v8 alike. Before, the API had a rule per type (themes at most
  20 characters, fonts from 2, code lists and attributes from 1, images and archive keys up to 64)
  and wire v7 allowed 1–64, so a valid archive could hold resources the API could not address.
  The catalog's own key keeps 3–50, letter first; tenant and environment keys are unchanged. The
  v7→v8 migration pads a 1- or 2-character key with `0` (`nl` → `nl0`,
  `CATALOG_RESOURCE_KEY_PADDED`) and truncates one over 50 characters
  (`CATALOG_RESOURCE_KEY_TRUNCATED`), and rewrites every reference to it the same way, so
  catalogs migrated separately still agree; a renamed key that collides with an existing one is
  refused, never disambiguated. Epistola Suite must apply the same rule to the keys it stores.

- **BREAKING: catalog drafts are independent copies; nothing is merged** (ADR 0002, amended). A
  release no longer flows into higher drafts. A draft's `base` is fixed: the release it started
  from. A draft is **behind** the releases numbered between its base and its own number:
  `CatalogVersionDto.behind` lists them (`BehindReleaseDto`, with each release's `added` /
  `modified` / `removed` resources, `ReleasedChangeDto`). Releasing a draft that is behind is
  refused with 409 unless `ReleaseCatalogVersionRequest.acknowledgedBehind` names every one of
  them, and `ReleaseCatalogVersionResponse.draftsBehind` lists the higher drafts a release leaves
  behind. Removed: `listIncomingChanges`, `resolveIncomingChange`, `IncomingChange*`,
  `ResolveIncomingChangeRequest`, `ForwardFlowDto`, `flowedInto`, `openConflicts` and the renumber
  preview's `newBase`; renumber warnings `releases-flow-in` and `draft-order-changes` give way to
  `falls-behind`. Conformance: `list-incoming-changes` and `resolve-incoming-change` are gone;
  new scenarios `get-draft-behind` and `release-catalog-version-behind`. A merge on request is
  proposed in ADR 0005, outside 2.0.0.

- **BREAKING: a resource's address is its `key`** (#84). The rule: a `key` is a resource's public
  address within its parent; an `id` is a generated identifier that is not an address
  (`documentId`, `requestId`, `batchId`, `correlationId`, `consumerId`, `changeId`, node and slot
  ids).
  - Read models: `slug` → `key` on every DTO that had one (templates, variants, stencils, themes,
    environments, tenants, attributes, code lists, fonts, images, import and catalog results,
    resource and incoming changes). Create requests: `id` (and `CreateCodeListRequest.slug`) →
    `key`, as are `DataExampleDto.id` and `ImportVariantDto.id`.
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
  `CatalogVersionDto.fingerprint` are `v5:<hex>`, the value in the release's catalog archive
  (pattern `^(v[1-9][0-9]*:)?[0-9a-f]{64}$`). A bare hex value is a fingerprint recorded before
  contract 2.0 whose algorithm was not recorded.

- **BREAKING: portable catalog wire v8.** A catalog archive identifies its catalog by registry,
  namespace and key (ADR 0003) and versions catalogs as a whole;
  `x-epistola-catalog-contract.wireSchemaVersion` is 8.
  - `catalog.registry` (a DNS name, always in full) and `catalog.namespace` (a slug) are required.
    New manifest member `catalogDependencies` declares each catalog depended on once, with a version
    selector (`version`, npm range grammar), the exact `releaseVersion` the release was cut with, and
    that release's `releaseFingerprint`, so a copy fetched through any mirror can be checked (both
    null only for archives migrated from v7). Each resource dependency gains a required `registry`
    and `namespace`, and every catalog it names must be declared. References inside content
    (`ThemeRef` override, stencil nodes, `CodeListBindingRef`, `TemplateResource.themeRegistry` /
    `themeNamespace`, `FontRef`) gain an optional `registry` and `namespace`; each omitted one means
    the owning catalog's, and a `registry` without a `namespace` is invalid. The API embeds the
    catalog's `TemplateDocument`, so its `ThemeRef` gains them in every client too (the bundle
    normalization in `normalize-bundle.mjs` carries it).
  - Stencils carry no version: `StencilResource.version` is removed from the wire and the Kotlin
    model, and a stencil node no longer allows `version` or `draftVersion`.
  - `CatalogV7ToV8Migration` takes the registry and namespace from the caller
    (`CatalogMigrationOptions(namespace, registry)`, accepted by `CatalogSchemaMigrator.migrateManifest`,
    `CatalogArchiveReader.read` and `CatalogValidationPolicy`); without them a v7 archive is refused
    with `CATALOG_REGISTRY_MISSING` / `CATALOG_NAMESPACE_MISSING`. It qualifies dependencies with
    them, declares each referenced catalog with selector `*`, and removes stencil versions, each with
    a notice.
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
    `CATALOG_DEPENDENCY_CATALOG_VERSION_INVALID`. `CatalogNamespaces` (Kotlin) and the
    `CATALOG_REGISTRY_*`, `CATALOG_NAMESPACE_*` and `CATALOG_RELEASE_FINGERPRINT_PATTERN` constants
    (npm) publish the rules.
  - Kotlin API: `StencilResource.version`, `CatalogResourceReference.version`/`draftVersion`,
    `TemplateValidationContext.allowDraftStencilReferences` and the `version` parameter of
    `TemplateValidationContext.forStencil` are removed; `CatalogInfo.registry` and `namespace`,
    `CatalogManifest.catalogDependencies` and `registry` and `namespace` on `DependencyRef`,
    `CatalogResourceReference`, `ThemeRefOverride`, `CodeListBindingRef` and `FontRef` are added (nullable in the model so manifests stored under v7 still
    bind). `templateModelVersion` stays 1.

- **BREAKING: catalog content is edited and read per catalog version, not in one working copy.**
  Every path to a catalog's templates, variants, stencils, themes, attributes, code lists, fonts and
  images, and its `dependencies`, `changes` and `mark-ready`, gains `/versions/{catalogVersion}`
  after the catalog. Reads work on any version; writes only on a draft, and are refused with 409 on
  a released version. `validateTemplateData` stays on the catalog, as it selects a release like
  generation does. `releaseCatalog`, `listCatalogReleases` and `getCatalogRelease` are replaced by
  the version operations, and `CatalogReleaseDto` by `CatalogVersionDto`. Preview renders a draft by
  `draftVersion` instead of `workingCopy`. `CatalogChangesDto.latestRelease` is now `base`.
  `importCatalog` takes the draft to import into as `catalogVersion`, and reports it.

- **BREAKING: a catalog is identified by its registry, its namespace and its key** (ADR 0003).
  Every catalog-scoped path is `/tenants/{tenantKey}/catalogs/{registry}/{namespace}/{catalogKey}/…`
  instead of `…/catalogs/{catalogId}/…`. The registry is the DNS name of the registry the catalog
  is published on (`exchange.epistola.app`, `registry.gemeente-x.nl`); `~` in a path means the
  server's configured default registry, which may also be named in full. The namespace is a slug
  within the registry. Requests may omit the registry (the default); responses always name it in
  full. Every DTO that named a catalog (`catalogId`, `catalog`, or a catalog's `slug`)
  now carries `registry`, `namespace` and `catalogKey`: the generation, preview and batch requests,
  `VariantSelectionAttribute`, `CatalogDto`, `ReleaseDependencyDto`, `CatalogUpgradeDiff`,
  `DeploymentDto`, `DeploymentEventDto`, `AttributeDto` (and its `codeListBinding`), `CodeListDto`,
  `FontDto`, `ImageDto` and `ImportCatalogResponse`. Generated documents, generation items and
  collected results add nullable `registry`, `namespace` and `catalogKey` next to `releaseVersion`.
  `importCatalog` takes an optional `registry` and `namespace`. The client libraries' template data
  validators take `registry` before `namespace` (`DEFAULT_REGISTRY = "~"`).

- **BREAKING: a release is selected by a version selector, together with an environment.** The
  generation, preview, batch and validation requests take `version` in place of `releaseVersion`:
  a selector in the npm SemVer range grammar, of which a server may accept only exact versions for
  now. It combines with `environmentKey`, which narrows the choice to the releases that environment
  serves; the highest release that satisfies the selector is rendered. `DeployReleaseRequest` and
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
  working copy; `markStencilReady` marks it reviewed, moves every instance the change does not
  break, and names the instances it holds back; `listStencilUsage` lists the template variants that
  use the stencil and which are held back. `StencilDto` and `StencilSummaryDto` report a `status`
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
  `modified`, `ready`, `released`) in place of `hasDraft` and `publishedVersions`. Template import
  no longer takes `publishTo` or reports `publishedTo`: it fills the working copy.

- **BREAKING: generation renders a catalog release, never a template version.** `versionId` is gone
  from `GenerateDocumentRequest`, `BatchGenerationItem`, `PreviewDocumentRequest` and
  `ValidateTemplateDataRequest`. A request names the release with `releaseVersion`, or an
  environment with `environmentKey` (which renders the release that environment serves); with
  neither, the catalog's latest release is rendered, never unreleased changes. Preview gains
  `workingCopy: true` to render the working copy instead. Jobs, documents and collected results
  report `releaseVersion` in place of `versionId`; it is null for anything generated before 2.0.

- **The clients no longer bundle a JSON Schema library.** Every client validated template data
  itself, each with a different library and under a different dependency policy — networknt 1.5.9
  `compileOnly` in the two JVM clients, NJsonSchema 11.6.1 as a hard reference in .NET,
  `jsonschema` as a hard dependency in Python, ajv as an optional peer in Node.js. On the JVM the
  dependency was never published at all, so `TemplateSchemaValidator` threw
  `NoClassDefFoundError` for any consumer who did not already happen to have a 1.x copy on the
  classpath; it worked only in the clients' own tests. Validation now goes through a pluggable
  `TemplateDataValidator` (`ITemplateDataValidator` in .NET, a `Protocol` in Python) whose one
  shipped implementation asks the server's `validateTemplateData`. See each client's changelog for
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

### Removed

- **BREAKING: the deprecated `id` and `slug` halves of each resource address.** `key` is now the
  only address on every read model, and is required (#84; see "a resource's address is its `key`"
  under Changed). `upgradeCatalog` takes no request body: its only field, the ignored
  `includeNewSlugs`, is gone with `UpgradeCatalogRequest`, as is the empty `Assets` tag. The
  `older-server-response` conformance scenario, which proved a 1.x client could read a server from
  before 1.3.0, is replaced by `template-listing-by-key`.

### Fixed

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
