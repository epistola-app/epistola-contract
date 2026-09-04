# Epistola Node.js Client

Node.js client library for the [Epistola](https://github.com/epistola-app/epistola-contract)
document generation API, generated from the Epistola OpenAPI contract with
[OpenAPI Generator](https://openapi-generator.tech) (`typescript-fetch`, on the platform's own
`fetch`) and hand-written glue for identity, authentication, error handling, result collection, and
client-side validation. TypeScript declarations are included.

It adds, on top of the stock generated client:

- **Client identity** headers (`User-Agent` + `X-EP-Node-Id`) required on every request.
- **RFC 9457 problem-detail** error handling — a typed `ProblemDetailException` with a `typeSlug`
  discriminator and generated `KnownProblemSlugs` constants.
- **Self-signed JWT** authentication (`JwtSigner`), minting a fresh short-lived token per request on
  `node:crypto` alone.
- **Static API-key** authentication via `Authorization: ApiKey <key>`.
- **The `Accept` header each operation is declared with.** The generated classes never set one, and
  Node's `fetch` sends `*/*` by default — so without this a request never says it accepts
  `application/problem+json`, the document every error handler here is built to parse.
- **NDJSON result collection** (`ResultCollector`) with adaptive polling, compression, and
  partition-aware routing helpers.
- **Client-side JSON Schema validation** of template data (`TemplateSchemaValidator`) and
  generated `validate<Model>` helpers for the contract's own constraints.

The package version tracks the Epistola contract version (`info.version`) and releases in lockstep
with the Kotlin, Jakarta EE, .NET and Python clients. It needs Node.js 22.12 or later and ships as
an ES module (which Node's `require()` can load as well).

Release history: [CHANGELOG.md](CHANGELOG.md).

## Install

```bash
npm install @epistola.app/epistola-client
```

## Quick start

```ts
import { ClientIdentity, EpistolaClient, JwtSigner, TemplatesApi } from '@epistola.app/epistola-client'

const identity = ClientIdentity.builder()
  .nodeId('my-pod-123')                       // defaults to the hostname
  .product('my-app', '1.0.0')                 // appended to User-Agent
  .build()

const signer = JwtSigner.builder()
  .consumerId('invoice-service')
  .privateKey(JwtSigner.loadPrivateKey('private.pem'))
  .build()

const client = EpistolaClient.builder('https://epistola.example.com/api')
  .identity(identity)                         // User-Agent + X-EP-Node-Id
  .jwtSigner(signer)                          // Authorization: Bearer <jwt>, fresh per request
  .build()

const templates = new TemplatesApi(client)
const template = await templates.getTemplate({ tenantId: 'acme', catalogId: 'invoices', templateId: 'invoice' })
```

For static tenant API keys, use `.apiKey('epk_...')` instead of `.jwtSigner(...)`, or the shorthand
`EpistolaClient.builder(baseUrl, apiKey)`. The legacy `X-API-Key` header is not sent: it remains
supported server-side but is deprecated. Some Epistola Suite deployments disable API-key
authentication entirely; switch on `e.typeSlug === KnownProblemSlugs.API_KEY_AUTH_DISABLED` and
guide the caller to JWT auth.

`EpistolaClient` *is* a generated `Configuration`, so every generated API class takes it directly.
One builder can produce more than one client: call `build()` again after changing
`requestTimeoutMs(...)` for the two profiles a long-running consumer typically needs against the
same backend — no timeout (the default) for polling, rendering and large transfers, a bounded one
for everything else.

### Partial updates

The generated models are plain TypeScript interfaces, and the generated serializers drop a property
you never set while sending one you set to `null` as `null`. On the API's `PATCH` operations, where
the contract documents `null` as "clear this", that is exactly the distinction that matters:
`updateConsumer({ ..., updateConsumerRequest: { name: 'Billing' } })` renames the consumer and
touches nothing else; `{ name: 'Billing', description: null }` also clears the description.

### Binary downloads

Every operation the contract declares as `format: binary` — `downloadDocument`, `previewDocument`,
asset content — resolves to a `Blob`; `Buffer.from(await blob.arrayBuffer())` gives you the bytes.

## Error handling

Catch `ProblemDetailException` and switch on `typeSlug` against `KnownProblemSlugs`. The slug list is
**open** — the API can introduce new problem types without a client release — so always keep a
`default` branch and fall back to the HTTP status for unrecognized types.

```ts
import { KnownProblemSlugs, ProblemDetailException, ResponseError, TenantsApi } from '@epistola.app/epistola-client'

try {
  await new TenantsApi(client).getTenant({ tenantId: 'acme' })
} catch (e) {
  if (e instanceof ProblemDetailException) {
    switch (e.typeSlug) {
      case KnownProblemSlugs.NOT_FOUND:
        log.warn(`tenant not found: ${e.detail}`)
        break
      case KnownProblemSlugs.FORBIDDEN:
        throw new PermissionError(e.detail)
      case KnownProblemSlugs.VALIDATION_ERROR:
        for (const err of e.errors) log.warn(`${err.field}: ${err.message}`)     // field-level ValidationProblemDetail
        break
      case KnownProblemSlugs.DATA_MODEL_VALIDATION_ERROR:
        for (const [example, failures] of Object.entries(e.validationErrors)) {  // 422 data-model failures
          for (const f of failures) log.warn(`${example} ${f.path}: ${f.message}`)
        }
        break
      default:
        throw e                                                                  // unknown / framework error — fall back to e.statusCode
    }
  } else if (e instanceof ResponseError) {
    // an error response without a problem document
  }
}
```

`ProblemDetailException` extends the generated `ResponseError`, so existing `instanceof
ResponseError` sites keep working. It exposes `type`, `typeSlug`, `title`, `problemStatus`,
`detail`, `instance`, `errors`, `validationErrors`, `extensions` (every member outside the RFC 9457
base five), `statusCode`, `rawBody`, `isValidationProblem`, and `isDataModelValidationProblem`.

## Result collection

```ts
import { ResultCollector } from '@epistola.app/epistola-client'

const collector = ResultCollector.builder()
  .client(client)
  .tenantId('acme')
  .handler(async (result) => console.log(result.requestId, result.status))
  .build()

await collector.start()   // adaptive polling; resolves after collector.stop()
```

Results stream in one at a time and the batch is acknowledged only after every result in it was
handled: a handler that throws leaves the batch unacknowledged, so it is redelivered. gzip is always
decoded; zstd is offered and decoded where Node's zlib has it (22.15+). `partitionFor`,
`isMyPartition` and `routingKeyToMe` compute, from the assignment the server reports, a routing key
whose result comes back to this node.

## Client-side validation

```ts
import { TemplateSchemaValidator, ValidatingGenerationApi, validateCreateTenantRequest } from '@epistola.app/epistola-client'

// Data against the template's JSON Schema, fetched once and cached (TtlSchemaCache, 5 minutes).
const validator = new TemplateSchemaValidator(templatesApi)
await validator.validate('acme', 'invoices', 'invoice', data)     // throws TemplateDataValidationException

// Or transparently, before every generation call:
const generation = new ValidatingGenerationApi(new GenerationApi(client), templatesApi)

// A request model against the constraints the contract declares on it (slug patterns, lengths, ranges):
validateCreateTenantRequest({ id: 'Acme Corp', name: 'Acme' })  // throws ModelValidationException
```

## Development

The client is generated from the bundled spec. From the repository root:

```bash
make bundle                       # produce openapi.yaml
cd contracts/api/clients/nodejs-fetch
pnpm install --frozen-lockfile
./generate.sh                     # stock client + derived sources into src/generated/
pnpm build                        # dist/
pnpm test                         # node:test, against the compiled tree
```

`src/generated/` (the stock client under `api/`, and the derived `contractVersion`,
`contractIdentity`, `contractMediaTypes`, `contractOperations`, `knownProblemSlugs` and
`modelValidation`) is gitignored and rebuilt from the spec each time. The cross-client conformance
suite drives this client with `make conformance-node`.
