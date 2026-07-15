# Epistola .NET Client

.NET client library for the [Epistola](https://github.com/epistola-app/epistola-contract) document
generation API, generated from the OpenAPI contract with
[OpenAPI Generator](https://openapi-generator.tech/) (`csharp` / `HttpClient`) and hand-written glue
for identity, authentication, error handling, result collection, and client-side validation.

It is the .NET counterpart of the Kotlin client and targets **.NET 8** — consumable by any modern
.NET project (ASP.NET Core, worker services, console apps, …).

Release history: [CHANGELOG.md](CHANGELOG.md).

## Installation

```bash
dotnet add package Epistola.Contract.Client
```

The generated API namespaces are `Epistola.Client.Api` (operations) and `Epistola.Client.Model`
(DTOs). The hand-written helpers live under `Epistola.Client.{Identity,Auth,Error,Http,Collect,Validation}`.

## Quick start

```csharp
using Epistola.Client.Api;
using Epistola.Client.Auth;
using Epistola.Client.Http;
using Epistola.Client.Identity;
using Epistola.Client.Model;

var identity = ClientIdentity.Builder()
    .NodeId("my-pod-123")                       // defaults to hostname
    .Product("my-app", "1.0.0")                 // appended to User-Agent
    .Build();

var signer = JwtSigner.Builder()
    .ConsumerId("my-consumer")
    .PrivateKey(JwtSigner.LoadPrivateKey("private.pem"))
    .Build();

var http = new EpistolaHttpClientBuilder()
    .BaseUrl("https://epistola.example.com/api")
    .Identity(identity)                         // User-Agent + X-EP-Node-Id
    .JwtSigner(signer)                          // Authorization: Bearer <jwt>
    .InstallProblemDetailHandler()              // typed ProblemDetailException
    .Build();

var templates = new TemplatesApi(http, "https://epistola.example.com/api");
var template = templates.GetTemplate("my-tenant", "default", "monthly-invoice");
```

The builder always installs a handler that rewrites the request `Content-Type` to the versioned
Epistola media type `application/vnd.epistola.v1+json`.

## Client identity

Every request must carry `User-Agent` and `X-EP-Node-Id`. `ClientIdentity` builds them; the first
`User-Agent` token is always `epistola-contract/{contractVersion}`:

```
User-Agent: epistola-contract/0.11.0 my-app/1.0.0
X-EP-Node-Id: my-pod-123
```

## Authentication

`JwtSigner` mints short-lived self-signed JWTs (RSA-2048+ or EC P-256) with `iss`, `iat`, `exp`, and a
unique `jti` per request. For OAuth 2.0 client-credentials, supply your own bearer handler via
`EpistolaHttpClientBuilder.PrimaryHandler(...)` or add a `DelegatingHandler` around the chain.

## Error handling

With `InstallProblemDetailHandler()`, `application/problem+json` error responses raise a typed
`ProblemDetailException`. Switch on the stable `TypeSlug` and compare against `KnownProblemSlugs`
(generated from the contract's `x-problem-types` registry):

```csharp
using Epistola.Client.Error;

try
{
    templates.GetTemplate("my-tenant", "default", "unknown");
}
catch (ProblemDetailException e)
{
    switch (e.TypeSlug)
    {
        case KnownProblemSlugs.NOT_FOUND:
            Console.WriteLine($"not found: {e.Detail}");
            break;
        case KnownProblemSlugs.VALIDATION_ERROR:
            foreach (var err in e.Errors) Console.WriteLine($"{err.Field}: {err.Message}");
            break;
        default:                                 // always keep a default branch
            Console.WriteLine($"{e.ProblemStatus} {e.Title}");
            break;
    }
}
```

`ProblemDetailException` extends the generated `ApiException`, so existing `catch (ApiException)`
sites keep working. Non-problem errors fall through to the generated `ApiException`.

## Document generation & result collection

Poll completed/failed generation results with `ResultCollector` — NDJSON streaming (constant memory),
compression (gzip built-in; lz4/zstd auto-detected when `K4os.Compression.LZ4` / `ZstdSharp` are
present), adaptive polling, and partition-aware routing helpers:

```csharp
using Epistola.Client.Collect;

var collector = ResultCollector.Builder()
    .HttpClient(http)
    .TenantId("acme-corp")
    .Handler(result =>
    {
        switch (result.Status)
        {
            case "COMPLETED": Download(result.DocumentId, result.CorrelationId); break;
            case "FAILED":    LogFailure(result.CorrelationId, result.Error);    break;
        }
    })
    .Build();

collector.Start();          // blocks, running the adaptive poll loop; Stop() to end
```

`CollectOnce()` / `CollectOnceAsync()` perform a single poll for custom scheduling; `Kick()` shortens
the backoff when a result is expected soon. Partition helpers: `PartitionFor`, `IsMyPartition`,
`RoutingKeyToMe`.

## Client-side schema validation

Validate request data against a template's JSON Schema before sending:

```csharp
using Epistola.Client.Validation.Schema;

var validating = new ValidatingGenerationApi(new GenerationApi(http), new TemplatesApi(http));
validating.GenerateDocument("my-tenant", request);   // throws TemplateDataValidationException on failure
```

The generated request/response models also carry fail-fast constraint checks via `Validate()`
extension methods (from `Epistola.Client.Validation`):

```csharp
using Epistola.Client.Validation;

var request = new CreateTenantRequest(id: "acme-corp", name: "Acme").Validate();
```

## Building from source

This module is generated from the bundled OpenAPI spec. From the repository root:

```bash
make bundle          # produce openapi.yaml
make build-dotnet    # generate.sh (client + derived sources) then dotnet build
```

`generate.sh` runs the OpenAPI Generator and a small derived-source generator
(`KnownProblemSlugs`, `Validate()` methods, and the contract version) — the .NET analogue of the
Kotlin build's generation tasks. Generated sources are not committed.
