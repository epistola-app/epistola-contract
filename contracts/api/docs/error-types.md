# Error Types

The Epistola API reports errors as [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457)
with `Content-Type: application/problem+json`. Every problem body carries:

| field | type | notes |
| --- | --- | --- |
| `type` | string (URI) | **the machine-readable discriminator** — switch on this, not on a status code |
| `title` | string | short, human-readable summary of the problem type |
| `status` | integer | HTTP status code |
| `detail` | string (optional) | explanation specific to this occurrence |
| `instance` | string (URI reference, optional) | identifies this specific occurrence |
| `help` | string (URI, optional) | the `type`'s page with this request's context, such as `?operation=getTemplate`: hints for what was called. Names from this contract only, never values. Switch on `type`, not on this |

Every problem uses a `type` in the `https://epistola.app/errors/{slug}` namespace, including one the
server's framework raises before the request reaches application code, such as a malformed request
(`bad-request`) or a method the operation does not take (`method-not-allowed`).

A problem's `type` is always exactly its registered URI, and opening it gives the general
explanation of the type. `help`, when present, opens the same page with the request's context in
its query, such as the `operation` that answered, so the page can give the hints for that
operation. The context is made of this contract's own names, never a value from the request or the
installation: a `help` link can be shared, logged or opened in a browser without disclosing
anything.

A response may carry additional domain-specific **extension members** as top-level fields.
Clients must ignore members they do not recognize.

A server answers only with the types registered below. A type names a problem a caller can act on
differently; anything else is the generic type of its status, so a missing resource is `not-found`
whichever resource it is, and a refused authorization is `forbidden`. New types may be added in a
minor version: a client handles a type it does not recognize by its `status`, as the generic type
of that status.

An operation lists the problems it answers with among its responses, except six that any operation
may answer: `method-not-allowed`, `not-acceptable`, `content-too-large`, `unsupported-media-type`,
`internal-error` and `operation-not-implemented`. `x-problem-types` marks them `anyOperation: true`,
so tools can tell them apart, as the conformance harness does when it checks a scripted response
against the operation it answers.

## Canonical problem types

| `type` | slug | status | shape | when |
| --- | --- | --- | --- | --- |
| `https://epistola.app/errors/validation-error` | `validation-error` | 400 | `ValidationProblemDetail` | the request body or parameters failed validation; the `errors[]` array carries the field-level failures |
| `https://epistola.app/errors/template-data-invalid` | `template-data-invalid` | 400 | `ValidationProblemDetail` + `missingFields`, `invalidFields` | the template data (e.g. a preview's `data`) breaks the template's data contract; `errors[]` lists each field with `field` a JSON Pointer into the request body, and the extension members describe what to supply or correct, with paths into `data` |
| `https://epistola.app/errors/bad-request` | `bad-request` | 400 | `ProblemDetail` | the request is malformed or not applicable to the resource's current state, but not a field-level validation failure (no `errors[]`) |
| `https://epistola.app/errors/catalog-rendering-defaults-too-new` | `catalog-rendering-defaults-too-new` | 400 | `ProblemDetail` + `renderingDefaultsVersion`, `supportedVersion` | a release received from elsewhere (`importCatalog`, `installCatalogRelease`) renders with rendering defaults this installation does not have, newer or unknown, as its `release.metadata` names them; rendering it with other defaults would change its documents, so it was refused before anything was written. `renderingDefaultsVersion` is what the release names (a string, as it names it), `supportedVersion` the newest this installation has. Upgrade Epistola to receive it |
| `https://epistola.app/errors/catalog-schema-too-new` | `catalog-schema-too-new` | 400 | `ProblemDetail` | a catalog archive (`importCatalog`, `installCatalogRelease`) is written in a wire version newer than this installation reads; upgrade Epistola to receive it |
| `https://epistola.app/errors/catalog-schema-too-old` | `catalog-schema-too-old` | 400 | `ProblemDetail` | a catalog archive is written in a wire version older than the oldest this installation migrates; export it again from a current source |
| `https://epistola.app/errors/catalog-schema-unknown` | `catalog-schema-unknown` | 400 | `ProblemDetail` | the payload is not a catalog archive this installation recognizes: not JSON, or its `schemaVersion` is missing or not an integer |
| `https://epistola.app/errors/unauthorized` | `unauthorized` | 401 | `ProblemDetail` | missing or invalid authentication credentials |
| `https://epistola.app/errors/api-key-auth-disabled` | `api-key-auth-disabled` | 401 | `ProblemDetail` | API-key authentication is disabled for this deployment; use `Authorization: Bearer <jwt>` instead |
| `https://epistola.app/errors/forbidden` | `forbidden` | 403 | `ProblemDetail` + `requiredPermission`, `requiredPlatformRole`, `tenantKey`, `reason` (all optional) | authenticated, but refused. Which members are present says what was refused: a permission in a tenant (`requiredPermission`, as the operation's `x-required-permissions` names it, and `tenantKey`), access to the tenant itself (`tenantKey` alone), a platform role (`requiredPlatformRole`), or a policy the server applies (`reason`, text for a person, with no single permission to grant) |
| `https://epistola.app/errors/not-found` | `not-found` | 404 | `ProblemDetail` | the addressed resource (tenant, catalog, template, …) does not exist |
| `https://epistola.app/errors/no-matching-variant` | `no-matching-variant` | 404 | `ProblemDetail` | the template exists, but none of its variants matches the attributes the request requires (`generateDocument`, `generateDocumentBatch`, `previewDocument`) |
| `https://epistola.app/errors/release-registry-not-found` | `release-registry-not-found` | 404 | `ProblemDetail` | the release registry the request names (alpha, ADR 0008) is not one this installation has configured or is connected to; `listReleaseRegistries` lists those it has. Nothing was published or installed |
| `https://epistola.app/errors/method-not-allowed` | `method-not-allowed` | 405 | `ProblemDetail` | the operation does not accept this HTTP method; the `Allow` header lists those it does. Any operation may answer it; it is not listed on each |
| `https://epistola.app/errors/not-acceptable` | `not-acceptable` | 406 | `ProblemDetail` | the operation cannot answer in any media type the `Accept` header accepts. Any operation may answer it; it is not listed on each |
| `https://epistola.app/errors/conflict` | `conflict` | 409 | `ProblemDetail` | the request conflicts with the current state of the resource |
| `https://epistola.app/errors/catalog-version-behind` | `catalog-version-behind` | 409 | `ProblemDetail` + `behind` | the draft is behind releases the request did not acknowledge (`releaseCatalogDraft`); `behind` lists their versions, to name in `acknowledgedBehind` |
| `https://epistola.app/errors/release-in-use` | `release-in-use` | 409 | `ProblemDetail` + `environments`, `pinnedBy` | the release cannot be deleted while something uses it: `environments` deploy it, `pinnedBy` lists the releases of other catalogs that render with it. No operation deletes a released release yet |
| `https://epistola.app/errors/catalog-draft-in-use` | `catalog-draft-in-use` | 409 | `ProblemDetail` + `dependents`, `heldResources` | the draft cannot be discarded while something outside it relies on it (`deleteCatalogRelease`): `dependents` lists the drafts of other catalogs whose dependency declarations name it, `heldResources` the resources only it holds that something outside it still uses |
| `https://epistola.app/errors/catalog-draft-name-in-use` | `catalog-draft-name-in-use` | 409 | `ProblemDetail` | another draft of the catalog has this name (`createCatalogRelease`, `updateCatalogRelease`); a name tells a catalog's drafts apart |
| `https://epistola.app/errors/ambiguous-variant` | `ambiguous-variant` | 409 | `ProblemDetail` + `tiedVariants` | several variants match the request's attributes equally well, and the server will not choose; `tiedVariants` lists their keys |
| `https://epistola.app/errors/generation-job-not-cancellable` | `generation-job-not-cancellable` | 409 | `ProblemDetail` | the generation job has finished (completed, failed or cancelled), so there is nothing left to cancel (`cancelGenerationJob`) |
| `https://epistola.app/errors/release-dependencies-missing` | `release-dependencies-missing` | 409 | `ProblemDetail` + `missingDependencies` | a release cannot be deployed, or imported, because releases of other catalogs it renders with (the releases it pins) cannot be had: the tenant does not have them, the imported archive does not carry them, and they could not be fetched or verified; the `missingDependencies` member lists each one with its `reason` |
| `https://epistola.app/errors/release-dependencies-unreleased` | `release-dependencies-unreleased` | 409 | `ProblemDetail` + `unreleasedDependencies` | a draft cannot be released because its dependency declarations name drafts of other catalogs, and a release pins only releases; release them first, or along with it (`releaseDependencies`). The `unreleasedDependencies` member lists each such draft (`registry`, `namespace`, `catalogKey`, `releaseId`, `releaseName`) |
| `https://epistola.app/errors/catalog-read-only` | `catalog-read-only` | 409 | `ProblemDetail` + `readOnlyReason` | the catalog cannot be changed in this installation (`CatalogDto.editable` is false), so a write to it was refused and nothing changed; `readOnlyReason` is `editing-disabled` (the installation only receives and runs releases) or `supplied-by-epistola` (the server supplies the catalog and replaces it on upgrade) |
| `https://epistola.app/errors/merge-conflicts-unresolved` | `merge-conflicts-unresolved` | 409 | `ProblemDetail` + `unresolvedConflicts` | merging a release into a draft (`mergeIntoCatalogDraft`, alpha) was refused because conflicts have no resolution; `unresolvedConflicts` lists each as `resourceType/resourceKey`. Nothing changed |
| `https://epistola.app/errors/merge-refused` | `merge-refused` | 409 | `ProblemDetail` | merging a release into a draft (alpha) cannot be applied as asked: the result would leave a reference that does not resolve, within the catalog (a resource the release removes that the draft still uses, or one a taken or kept entry refers to that the result lacks) or into another catalog through a declared dependency, checked against the release that declaration pins (the catalog's default theme included), or into a catalog the result does not declare. The `detail` names what is missing and what to choose. Nothing changed; keep the draft's side (`ours`), or take the release's side of what it needs too |
| `https://epistola.app/errors/merge-preview-outdated` | `merge-preview-outdated` | 409 | `ProblemDetail` | merging a release into a draft (alpha) was refused because the draft or the release changed since the preview whose `token` the request sent as `expectedToken`. Nothing changed; preview again and choose again |
| `https://epistola.app/errors/merge-base-missing` | `merge-base-missing` | 409 | `ProblemDetail` + `version`, `fingerprint` | merging a release into a draft (alpha), or reading the sides of one of its entries (`getCatalogDraftMergeSides`), was refused because this installation does not have the merge base, the closest release both the draft and the release descend from; `version` and `fingerprint` name that release, to install first. Nothing changed |
| `https://epistola.app/errors/release-number-taken` | `release-number-taken` | 409 | `ProblemDetail` | a release's number or id names another release: publishing a release to a release registry (`publishReleaseToRegistry`, alpha) was refused because the registry holds this version of the catalog with other content; receiving one (`importCatalog`, `installCatalogRelease`, `installRegistryRelease`) was refused because the tenant has this version of the catalog, or of a release it pins, with another fingerprint, or another release or a draft of the catalog has its release id. One number and one id name one release. Nothing was sent or installed; release the changes under a new version |
| `https://epistola.app/errors/namespace-not-granted` | `namespace-not-granted` | 409 | `ProblemDetail` | publishing a release to a release registry that grants namespaces (the Exchange, alpha) was refused because it does not grant this installation the catalog's namespace. Nothing was sent |
| `https://epistola.app/errors/environment-deployment-refused` | `environment-deployment-refused` | 409 | `ProblemDetail` | a production environment serves only releases: a draft is deployed to it, or it is marked production while it serves a draft |
| `https://epistola.app/errors/resource-modified` | `resource-modified` | 412 | `ProblemDetail` | an update sent `If-Match` with an `ETag` that is no longer current: the resource changed since it was read, and nothing changed. Read it again, reapply the change and retry with the new `ETag` |
| `https://epistola.app/errors/content-too-large` | `content-too-large` | 413 | `ProblemDetail` | the request body is larger than this server accepts. Any operation that takes a body may answer it; it is not listed on each |
| `https://epistola.app/errors/unsupported-media-type` | `unsupported-media-type` | 415 | `ProblemDetail` | the request body's `Content-Type` is not one the operation accepts. Any operation that takes a body may answer it; it is not listed on each |
| `https://epistola.app/errors/data-model-validation-error` | `data-model-validation-error` | 422 | `ProblemDetail` + `validationErrors` | the request is well-formed but semantically invalid: supplied data examples do not validate against the data model; the `validationErrors` member maps each example's key to its failures |
| `https://epistola.app/errors/merge-not-applicable` | `merge-not-applicable` | 422 | `ProblemDetail` | merging a release into a draft (alpha) does not apply: the draft already contains the release (its base, or merged before), or a resolution names a resource that is not a conflict of the merge |
| `https://epistola.app/errors/rate-limited` | `rate-limited` | 429 | `ProblemDetail` | too many requests; a `Retry-After` header indicates how long to wait |
| `https://epistola.app/errors/internal-error` | `internal-error` | 500 | `ProblemDetail` | the server failed to answer the request. Retrying may succeed; report it when it does not. Any operation may answer it; it is not listed on each |
| `https://epistola.app/errors/operation-not-implemented` | `operation-not-implemented` | 501 | `ProblemDetail` | the operation is part of the contract, but this server does not implement it yet; any operation may answer it |
| `https://epistola.app/errors/catalog-source-unavailable` | `catalog-source-unavailable` | 503 | `ProblemDetail` | a catalog's source (its registry, through the mirrors configured for it) could not be used, so `listAvailableCatalogReleases` or `installCatalogRelease` could not be carried out and nothing was installed; also a release registry (alpha: `listRegistryReleases`, `installRegistryRelease`, `publishReleaseToRegistry`), with nothing installed or published. **503** when the source could not be reached or did not answer in time, possibly with a `Retry-After` header; **502** when it answered with an error or with something that is not a valid answer. The registry lists 503; the same type is sent with either status |

The slug list is **open**: the API may introduce new `type` values without a major version
bump. Clients that switch on `type` must always keep a default branch for unrecognized types
and fall back to the HTTP `status`.

## Validation errors

A `validation-error` problem extends the base shape with an `errors` array. Each entry is:

| field | type | notes |
| --- | --- | --- |
| `field` | string | the field that failed validation |
| `message` | string | description of the failure, for a person |
| `code` | string (optional) | why it failed, machine-readably: a code from [Validation codes](#validation-codes) |
| `rejectedValue` | any (optional) | the value that was rejected |

```json
{
  "type": "https://epistola.app/errors/validation-error",
  "title": "Bad Request",
  "status": 400,
  "detail": "The request body failed validation",
  "errors": [
    { "field": "name", "code": "min-length", "message": "must not be blank" },
    { "field": "key", "code": "pattern", "message": "must match ^[a-z][a-z0-9-]*$", "rejectedValue": "A B" }
  ]
}
```

### Validation codes

`code` says why a field failed, so a client can react without parsing `message`. It is optional,
and new codes may be added in a minor version: keep a default branch, and show `message` to a
person. The codes are listed, machine-readably, in the spec's `x-validation-codes` extension, and
generated into every client as `KnownValidationCodes`.

- A **constraint** code names the JSON Schema keyword of this contract that the value broke. The
  operation's schema says what the keyword asks for.
- A **template** code is a finding of the portable template validator (the catalog contract's
  `TemplateValidationCodes`, in kebab-case): what is wrong in template or stencil content.
- A **domain** code names a rule the server applies beyond the schema.

| code | kind | when |
| --- | --- | --- |
| `required` | constraint | A required member or parameter is missing, or null where the contract does not allow it |
| `type` | constraint | The value has another JSON type than the contract declares, such as text where a number is expected |
| `enum` | constraint | The value is not one of the values the contract lists |
| `format` | constraint | The value does not have the declared `format`, such as `date-time`, `uuid` or `uri` |
| `pattern` | constraint | A text value does not match the declared `pattern`, such as a key that is not a lowercase slug |
| `min-length` | constraint | A text value is shorter than its `minLength` |
| `max-length` | constraint | A text value is longer than its `maxLength` |
| `minimum` | constraint | A number is below its `minimum` |
| `maximum` | constraint | A number is above its `maximum`, such as a page `size` above 100 |
| `min-items` | constraint | A list has fewer items than its `minItems` |
| `max-items` | constraint | A list has more items than its `maxItems`, or an object more members than its `maxProperties` |
| `template-graph-invalid` | template | the template's node graph is malformed: a node or slot it refers to is missing, a node has two parents, or the graph has a cycle |
| `template-node-type-unsupported` | template | a node has a type this installation does not know |
| `template-slot-invalid` | template | a slot is malformed or not one the node's type has |
| `template-child-type-not-allowed` | template | a node holds a child of a type its slot does not allow |
| `template-node-property-invalid` | template | a node property does not have the shape its component declares |
| `template-style-preset-unknown` | template | a node uses a style preset the theme does not have |
| `template-theme-not-found` | template | the template names a theme that does not exist in the catalog or its dependencies |
| `template-expression-invalid` | template | an expression in the template does not parse or uses something the expression language does not have |
| `placeholder-name-duplicate` | template | two placeholders in the stencil have the same name |
| `placeholder-name-invalid` | template | a placeholder's name is not a valid name |
| `placeholder-nested-definition` | template | a placeholder is defined inside another placeholder |
| `placeholder-outside-stencil` | template | a placeholder is used outside a stencil |
| `stencil-nesting-depth-exceeded` | template | stencils are nested deeper than the contract allows |
| `stencil-recursion` | template | a stencil uses itself, directly or through other stencils |
| `stencil-reference-invalid` | template | a stencil instance does not fit the stencil it uses: it fills a placeholder or binds a parameter the stencil does not have, or with the wrong type |
| `stencil-reference-not-found` | template | a stencil instance uses a stencil that does not exist |
| `node-parameter-binding-unknown` | template | a node binds a parameter its stencil does not declare |
| `node-parameter-binding-syntax-invalid` | template | a parameter binding's expression does not parse |
| `node-parameter-binding-missing-required` | template | a node leaves a required parameter of its stencil unbound |
| `node-parameter-bindings-invalid-shape` | template | a node's parameter bindings are not a map of names to expressions |
| `node-parameter-binding-name-invalid` | template | a parameter binding names a parameter that is not a valid name |
| `node-parameter-binding-empty` | template | a parameter binding has an empty expression |
| `node-params-alias-reserved` | template | a node uses `params`, which is reserved, as an alias |
| `parameter-schema-invalid-type` | template | a stencil parameter's schema is not an object schema of supported types |
| `parameter-required-unknown` | template | a stencil marks a parameter as required that it does not declare |
| `parameter-name-invalid` | template | a stencil parameter's name is not a valid name |
| `parameter-name-reserved` | template | a stencil parameter uses a reserved name |
| `parameter-type-unsupported` | template | a stencil parameter has a type stencil parameters do not support |
| `parameter-default-type-mismatch` | template | a stencil parameter's default value does not have the parameter's type |
| `pageband-nested` | template | a page header or footer is placed inside another page header or footer |
| `data-example-required` | domain | the template's data contract needs at least one data example, and the request removes the last one or sends none |
| `data-contract-schema-invalid` | domain | the template's data contract is not a JSON Schema this installation can store and validate against |
| `catalog-namespace-not-held` | domain | the tenant does not hold the namespace the catalog is named or released in |
| `catalog-registry-local` | domain | a catalog in the `local` registry never leaves the organisation, so it cannot be published |
| `catalog-key-ambiguous` | domain | a catalog key names several catalogs of the tenant, and nothing says which namespace is meant |
| `catalog-dependency-invalid` | domain | a catalog dependency declaration is malformed, or the declarations would make catalogs depend on each other in a cycle |
| `catalog-dependency-unresolved` | domain | something refers to a catalog through an alias the draft does not declare as a dependency |
| `catalog-reference-unresolved` | domain | the change would leave the draft referring to a resource that neither the draft nor its dependencies have |
| `catalog-resources-not-ready` | domain | the draft has resources that are not marked ready, so it cannot be released yet |
| `source-url-not-allowed` | domain | a code list's source URL is not one this installation allows fetching from |

## Resource modified (optimistic locking)

Templates, variants, themes, stencils, code lists and attributes carry a revision, returned as a
strong `ETag` response header (a quoted decimal integer, e.g. `"7"`) on their `GET` and on a
successful update. An update that sends that value back as `If-Match` is applied only if the
resource has not changed since; otherwise the server answers 412 `resource-modified` and changes
nothing:

```json
{
  "type": "https://epistola.app/errors/resource-modified",
  "title": "Resource Modified",
  "status": 412,
  "detail": "The resource changed since revision 7 was read; it is now at revision 8"
}
```

Read the resource again, reapply your change to what you read, and retry with the new `ETag`. Do
not retry the same request unchanged: it would only be refused again, or, without `If-Match`,
overwrite the other change. `If-Match` is optional; an update without it is applied
unconditionally, and the last write wins.

## Catalog source unavailable

`listAvailableCatalogReleases` and `installCatalogRelease` need a catalog's source (`CatalogDto.source`): its
registry, through the mirrors configured for it (ADR 0003). When the source cannot be used, the
request fails with `catalog-source-unavailable`, and nothing is installed. The status says why:

- **503 Service Unavailable**: the source could not be reached, or did not answer in time. A
  `Retry-After` header, when present, says how long to wait.
- **502 Bad Gateway**: the source answered with an error, or with something that is not a valid
  answer.

```json
{
  "type": "https://epistola.app/errors/catalog-source-unavailable",
  "title": "Catalog Source Unavailable",
  "status": 503,
  "detail": "registry.gemeente-x.nl could not be reached"
}
```

The request may succeed later, unchanged, once the source is back; a 502 usually needs the source,
or the mirror configuration, fixed first.

## Catalog rendering defaults too new

A release says which rendering defaults it renders with, in its `release.metadata` (Epistola Suite
writes `{"app.epistola.suite": {"renderingDefaultsVersion": 6}}`). An installation that receives
a release (`importCatalog`, `installCatalogRelease`) naming defaults it does not have, newer than
its own or a value it cannot read, refuses it with 400 `catalog-rendering-defaults-too-new` before
anything is written: rendering it with other defaults would change its documents. Upgrade Epistola
to receive the release; retrying unchanged does not help.

```json
{
  "type": "https://epistola.app/errors/catalog-rendering-defaults-too-new",
  "title": "Catalog Rendering Defaults Too New",
  "status": 400,
  "detail": "letters 1.1.0 renders with rendering defaults version 9, which this Epistola does not have (it has up to 6). Upgrade Epistola to receive this release.",
  "renderingDefaultsVersion": "9",
  "supportedVersion": 6
}
```

`renderingDefaultsVersion` is a string, the value as the release names it; `supportedVersion` is
the newest version this installation has. A release that names none renders with the
installation's current defaults and is not refused.

## Catalog read-only

Whether a catalog can be changed depends on the installation and on the catalog
(`CatalogDto.editable`), not on where its releases come from. Every write to a catalog that cannot
be changed (a draft started, edited, discarded, merged into or released, a resource created,
updated, deleted or marked ready) is refused with 409 `catalog-read-only`, and nothing changes. Its
`readOnlyReason` member, the same value as `CatalogDto.readOnlyReason`, says why:

- `editing-disabled`: the installation only receives and runs releases. Releases can still be
  imported, installed, deployed and undeployed.
- `supplied-by-epistola`: the server supplies the catalog itself (such as the bundled `system`
  catalog) and replaces it on every upgrade.

```json
{
  "type": "https://epistola.app/errors/catalog-read-only",
  "title": "Catalog Read Only",
  "status": 409,
  "detail": "exchange.epistola.app/epistola/system is supplied by Epistola and cannot be changed",
  "readOnlyReason": "supplied-by-epistola"
}
```

Retrying does not help; a client offers no write on a catalog whose `editable` is false.

## Merging (alpha)

`mergeIntoCatalogDraft` (ADR 0005) is all or nothing. It is refused, with nothing changed, by:

- 400 `validation-error`: merged content (`mergedContent`) is not valid for its entry, validated as
  an import validates content; each failure's `field` names the entry (`mergedContent.template/invoice`)
  and the path within it. Also a conflict resolved both in `resolutions` and in `mergedContent`.

- 409 `merge-conflicts-unresolved`: conflicts have no resolution. `unresolvedConflicts` lists each
  as `resourceType/resourceKey` (a catalog setting as `catalog/defaultTheme` or `catalog/presets`, a
  catalog used as `catalogDependency/<catalogKey>`); resolve each to `ours` or `theirs` and send the
  merge again.
- 409 `merge-refused`: the result would leave a reference that does not resolve. Within the catalog:
  the release removes a resource the draft still uses, or an entry taken or kept refers to one the
  result does not have. Into another catalog: the result does not declare that catalog, or the
  release its declaration pins (or the draft it names) does not have what is referred to; the
  catalog's default theme is checked the same way. Only what the merge leaves unresolved is refused.
  The `detail` names what is missing and what to choose, for example "the default theme 'brand' of
  'shared' needs 'shared' as a dependency: take v1.0.1's dependency on 'shared' too, or keep your
  default theme". The preview lists with each entry the entries it goes with (`needs`).
- 409 `merge-preview-outdated`: the request sent the preview's `token` as `expectedToken`, and the
  draft or the release changed since that preview. Preview again and choose again.
- 409 `merge-base-missing`: this installation does not have the merge base, the closest release both
  the draft and the release descend from. `version` and `fingerprint` name it; install that release
  (`importCatalog`, `installCatalogRelease`) and merge again. The preview names it as `missingBase`.
- 422 `merge-not-applicable`: the draft already contains the release, or a resolution or merged
  content names an entry that is not a conflict.
- 409 `catalog-read-only`: the catalog cannot be changed in this installation.

```json
{
  "type": "https://epistola.app/errors/merge-conflicts-unresolved",
  "title": "Merge Conflicts Unresolved",
  "status": 409,
  "detail": "Merging 1.5.0 into Redesign 2027 needs a choice for: template/invoice",
  "unresolvedConflicts": ["template/invoice"]
}
```

## Handling problems in the generated clients

- **Kotlin (Spring RestClient):** opt in with `RestClient.Builder.installProblemDetailHandler()`
  and catch `ProblemDetailException`, switching on `e.typeSlug` (see
  [the client README](../clients/kotlin-spring-restclient/README.md#error-handling)).
- **.NET (HttpClient):** opt in with `EpistolaHttpClientBuilder.InstallProblemDetailHandler()`
  and catch `ProblemDetailException`, switching on `e.TypeSlug` (see
  [the client README](../clients/dotnet-httpclient/README.md#error-handling)).
- **Python (urllib3):** opt in with `EpistolaClientBuilder.install_problem_detail_handler()`
  and catch `ProblemDetailException`, switching on `e.type_slug` (see
  [the client README](../clients/python-urllib3/README.md#error-handling)).
- **Node.js (fetch):** `EpistolaClient` always parses problems; catch `ProblemDetailException`,
  switching on `e.typeSlug` (see
  [the client README](../clients/nodejs-fetch/README.md#error-handling)).
- **Kotlin (Spring server):** build problem bodies with the opt-in
  `app.epistola.api.error.ProblemDetails` helper (see
  [the server README](../server-stubs/kotlin-springboot4/README.md#error-responses)).
