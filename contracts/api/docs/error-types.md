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

Application-level problems use a `type` in the `https://epistola.app/errors/{slug}` namespace.
Framework-level problems (e.g. a malformed request the framework rejects before it reaches
application code) keep RFC 9457's default `about:blank`.

A response may carry additional domain-specific **extension members** as top-level fields.
Clients must ignore members they do not recognize.

## Canonical problem types

| `type` | slug | status | shape | when |
| --- | --- | --- | --- | --- |
| `https://epistola.app/errors/validation-error` | `validation-error` | 400 | `ValidationProblemDetail` | the request body or parameters failed validation; the `errors[]` array carries the field-level failures |
| `https://epistola.app/errors/template-data-invalid` | `template-data-invalid` | 400 | `ValidationProblemDetail` + `missingFields`, `invalidFields` | the template data (e.g. a preview's `data`) breaks the template's data contract; `errors[]` lists each field with `field` a JSON Pointer into the request body, and the extension members describe what to supply or correct, with paths into `data` |
| `https://epistola.app/errors/bad-request` | `bad-request` | 400 | `ProblemDetail` | the request is malformed or not applicable to the resource's current state, but not a field-level validation failure (no `errors[]`) |
| `https://epistola.app/errors/unauthorized` | `unauthorized` | 401 | `ProblemDetail` | missing or invalid authentication credentials |
| `https://epistola.app/errors/api-key-auth-disabled` | `api-key-auth-disabled` | 401 | `ProblemDetail` | API-key authentication is disabled for this deployment; use `Authorization: Bearer <jwt>` instead |
| `https://epistola.app/errors/forbidden` | `forbidden` | 403 | `ProblemDetail` | authenticated, but the caller lacks the required role or tenant permission |
| `https://epistola.app/errors/not-found` | `not-found` | 404 | `ProblemDetail` | the addressed resource (tenant, catalog, template, …) does not exist |
| `https://epistola.app/errors/conflict` | `conflict` | 409 | `ProblemDetail` | the request conflicts with the current state of the resource (e.g. publishing a backwards-incompatible data-model change without confirmation) |
| `https://epistola.app/errors/release-dependencies-missing` | `release-dependencies-missing` | 409 | `ProblemDetail` + `missingDependencies` | a release cannot be deployed, or imported, because releases of other catalogs it renders with (the releases it pins) cannot be had: the tenant does not have them, the imported archive does not carry them, and they could not be fetched or verified; the `missingDependencies` member lists each one with its `reason` |
| `https://epistola.app/errors/release-dependencies-unreleased` | `release-dependencies-unreleased` | 409 | `ProblemDetail` + `unreleasedDependencies` | a draft cannot be released because its dependency declarations name drafts of other catalogs, and a release pins only releases; release them first, or along with it (`releaseDependencies`). The `unreleasedDependencies` member lists each such draft (`registry`, `namespace`, `catalogKey`, `releaseId`, `releaseName`) |
| `https://epistola.app/errors/catalog-read-only` | `catalog-read-only` | 409 | `ProblemDetail` + `readOnlyReason` | the catalog cannot be changed in this installation (`CatalogDto.editable` is false), so a write to it was refused and nothing changed; `readOnlyReason` is `editing-disabled` (the installation only receives and runs releases) or `supplied-by-epistola` (the server supplies the catalog and replaces it on upgrade) |
| `https://epistola.app/errors/environment-deployment-refused` | `environment-deployment-refused` | 409 | `ProblemDetail` | a production environment serves only releases: a draft is deployed to it, or it is marked production while it serves a draft |
| `https://epistola.app/errors/resource-modified` | `resource-modified` | 412 | `ProblemDetail` | an update sent `If-Match` with an `ETag` that is no longer current: the resource changed since it was read, and nothing changed. Read it again, reapply the change and retry with the new `ETag` |
| `https://epistola.app/errors/data-model-validation-error` | `data-model-validation-error` | 422 | `ProblemDetail` + `validationErrors` | the request is well-formed but semantically invalid: supplied data examples do not validate against the data model; the `validationErrors` member maps each example name to its failures |
| `https://epistola.app/errors/rate-limited` | `rate-limited` | 429 | `ProblemDetail` | too many requests; a `Retry-After` header indicates how long to wait |
| `https://epistola.app/errors/catalog-source-unavailable` | `catalog-source-unavailable` | 503 | `ProblemDetail` | a catalog's source (its registry, through the mirrors configured for it) could not be used, so `listAvailableCatalogReleases` or `installCatalogRelease` could not be carried out and nothing was installed. **503** when the source could not be reached or did not answer in time, possibly with a `Retry-After` header; **502** when it answered with an error or with something that is not a valid answer. The registry lists 503; the same type is sent with either status |

The slug list is **open**: the API may introduce new `type` values without a major version
bump. Clients that switch on `type` must always keep a default branch for unrecognized types
and fall back to the HTTP `status`.

## Validation errors

A `validation-error` problem extends the base shape with an `errors` array. Each entry is:

| field | type | notes |
| --- | --- | --- |
| `field` | string | the field that failed validation |
| `message` | string | description of the failure |
| `rejectedValue` | any (optional) | the value that was rejected |

```json
{
  "type": "https://epistola.app/errors/validation-error",
  "title": "Bad Request",
  "status": 400,
  "detail": "The request body failed validation",
  "errors": [
    { "field": "name", "message": "must not be blank" },
    { "field": "key", "message": "must match ^[a-z][a-z0-9-]*$", "rejectedValue": "A B" }
  ]
}
```

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
