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
| `https://epistola.app/errors/bad-request` | `bad-request` | 400 | `ProblemDetail` | the request is malformed or not applicable to the resource's current state, but not a field-level validation failure (no `errors[]`) |
| `https://epistola.app/errors/unauthorized` | `unauthorized` | 401 | `ProblemDetail` | missing or invalid authentication credentials |
| `https://epistola.app/errors/forbidden` | `forbidden` | 403 | `ProblemDetail` | authenticated, but the caller lacks the required role or tenant permission |
| `https://epistola.app/errors/not-found` | `not-found` | 404 | `ProblemDetail` | the addressed resource (tenant, catalog, template, …) does not exist |
| `https://epistola.app/errors/conflict` | `conflict` | 409 | `ProblemDetail` | the request conflicts with the current state of the resource (e.g. publishing a backwards-incompatible data-model change without confirmation) |
| `https://epistola.app/errors/data-model-validation-error` | `data-model-validation-error` | 422 | `ProblemDetail` + `validationErrors` | the request is well-formed but semantically invalid: supplied data examples do not validate against the data model; the `validationErrors` member maps each example name to its failures |
| `https://epistola.app/errors/rate-limited` | `rate-limited` | 429 | `ProblemDetail` | too many requests; a `Retry-After` header indicates how long to wait |

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
    { "field": "slug", "message": "must match ^[a-z][a-z0-9-]*$", "rejectedValue": "A B" }
  ]
}
```

## Handling problems in the generated clients

- **Kotlin (Spring RestClient):** opt in with `RestClient.Builder.installProblemDetailHandler()`
  and catch `ProblemDetailException`, switching on `e.typeSlug` (see
  [the client README](../client-kotlin-spring-restclient/README.md#error-handling)).
- **Kotlin (Spring server):** build problem bodies with the opt-in
  `app.epistola.api.error.ProblemDetails` helper (see
  [the server README](../server-kotlin-springboot4/README.md#error-responses)).
