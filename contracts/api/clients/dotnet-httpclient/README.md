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
using Epistola.Client.Http;
using Epistola.Client.Identity;
using Epistola.Client.Model;

var identity = ClientIdentity.Builder()
    .NodeId("my-pod-123")                       // defaults to hostname
    .Product("my-app", "1.0.0")                 // appended to User-Agent
    .Build();

var http = new EpistolaHttpClientBuilder()
    .BaseUrl("https://epistola.example.com/api")
    .Identity(identity)                         // User-Agent + X-EP-Node-Id
    .ApiKey("epk_...")                          // Authorization: ApiKey <key>
    .InstallProblemDetailHandler()              // typed ProblemDetailException
    .Build();

var templates = new TemplatesApi(http, "https://epistola.example.com/api");
// Catalog content is addressed by registry, namespace, catalog key and catalog version (SemVer).
// The registry `~` is the server's default registry; another is named in full ("acme.com").
var template = templates.GetTemplate("my-tenant", "~", "acme-corp", "default", "1.4.0", "monthly-invoice");
```

The builder always installs two corrections the C# generator gives no way to configure on the model:
a handler that rewrites the request `Content-Type` to the versioned Epistola media type
`application/vnd.epistola.v1+json`, and one that **drops top-level properties you never set** from
request bodies instead of sending them as `null`.

The second is not cosmetic. The generated models are plain nullable properties with no way to
distinguish "not set" from "explicitly null", so whatever the serializer does with an unset property
becomes the request's meaning. On the API's `PATCH` operations a null is an instruction —
`description` and `contact` are documented "null to clear", `expiresAt` as "null to remove expiry" —
so a default serializer turned renaming a consumer into renaming it *and* erasing the rest. Nulls
inside caller-supplied free-form data (a generation request's `data`) are left alone; they are
yours.

The trade is that clearing a field is not expressible — but it never was, since you could not clear
one field without clearing every other you had not set.

## Client identity

Every request must carry `User-Agent` and `X-EP-Node-Id`. `ClientIdentity` builds them; the first
`User-Agent` token is always `epistola-contract/{contractVersion}`:

```
User-Agent: epistola-contract/0.11.0 my-app/1.0.0
X-EP-Node-Id: my-pod-123
```

## Authentication

Static tenant API keys are the supported method. `.ApiKey("epk_...")` sets
`Authorization: ApiKey <key>`. The legacy `X-API-Key` header remains supported for existing
integrations, but is deprecated. Some Epistola Suite deployments may disable API-key authentication
entirely; with `InstallProblemDetailHandler()`, switch on `ProblemDetailException.TypeSlug ==
KnownProblemSlugs.API_KEY_AUTH_DISABLED` and guide the caller to JWT auth.

`JwtSigner` (**experimental**) mints short-lived self-signed JWTs (RSA-2048+ or EC P-256) with `iss`,
`iat`, `exp`, and a unique `jti` per request; pass it with `.JwtSigner(signer)` instead of
`.ApiKey(...)`. Epistola Suite may not implement self-signed JWT authentication yet, and the flow
may still change. For OAuth 2.0 client-credentials (also experimental on the server side), supply
your own bearer handler via `EpistolaHttpClientBuilder.PrimaryHandler(...)` or add a
`DelegatingHandler` around the chain.

## Error handling

With `InstallProblemDetailHandler()`, `application/problem+json` error responses raise a typed
`ProblemDetailException`. Switch on the stable `TypeSlug` and compare against `KnownProblemSlugs`
(generated from the contract's `x-problem-types` registry):

```csharp
using Epistola.Client.Error;

try
{
    templates.GetTemplate("my-tenant", "~", "acme-corp", "default", "1.4.0", "unknown");
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

A deployment refused because the environment does not serve the releases it renders with is a
`release-dependencies-missing` problem. `MissingDependencies` lists each missing release and why,
with `Reason` kept as the string the server sent (compare against `MissingDependency.Reasons`):

```csharp
try
{
    environments.DeployRelease("my-tenant", "production", "~", "acme-corp", "letters", "1.4.0",
        includeDependencies: false);
}
catch (ProblemDetailException e) when (e.TypeSlug == KnownProblemSlugs.RELEASE_DEPENDENCIES_MISSING)
{
    foreach (var d in e.MissingDependencies)
        Console.WriteLine($"{d.Registry}/{d.Namespace}/{d.CatalogKey}@{d.ReleaseVersion}: {d.Reason}");
    // retry with includeDependencies: true to deploy (and install) them along
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
    .TenantKey("acme-corp")
    .Handler(result =>
    {
        switch (result.Status)
        {
            // Registry, Namespace, CatalogKey and ReleaseVersion name the release rendered (null before contract 2.0).
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

## Catalog versions

A catalog consists of versions, each a `draft` or `released`. A draft has no number until it is
released: the catalog's next version, where everyday work goes, is addressed as `next`, and
long-term work by its `DraftId`; a release by its SemVer number. Every
catalog-content operation (templates, variants, stencils, themes, images, dependencies, …) takes the
version after the catalog key; template data validation (`ValidateTemplateData`) does not. The
versions themselves are managed through `CatalogsApi`:

```csharp
var catalogs = new CatalogsApi(http, "https://epistola.example.com/api");

// Start long-term work, named, as a copy of the next version or (with `from`) of a release.
var work = catalogs.CreateCatalogDraft("my-tenant", "~", "acme-corp", "default",
    new CreateCatalogDraftRequest(name: "Redesign 2027"));
Console.WriteLine($"{work.Name} is addressed as {work.DraftId}");

// The number releasing the next version would give it, from its changes against the latest release.
var preview = catalogs.PreviewCatalogDraftRelease("my-tenant", "~", "acme-corp", "default", "next");
Console.WriteLine($"{preview.Suggested}: {string.Join("; ", preview.Reasons)}");

// A draft is an independent copy: releases it does not contain are listed in `Behind`, and
// releasing it must acknowledge each of them. Leave `varVersion` out to release it as suggested.
var next = catalogs.GetCatalogVersion("my-tenant", "~", "acme-corp", "default", "next");
var released = catalogs.ReleaseCatalogDraft("my-tenant", "~", "acme-corp", "default", "next",
    new ReleaseCatalogDraftRequest(
        notes: "Spring update",
        acknowledgedBehind: next.Behind.Select(b => b.VarVersion).ToList()));

// Releasing never changes long-term work; the answer names the work that is now behind it.
foreach (var behind in released.DraftsBehind)
    Console.WriteLine($"{behind} does not contain {released.CatalogVersion.VarVersion}");

// Long-term work is not released. When it is ready, it becomes the next version; `keepPreviousAs`
// keeps the old next version as long-term work, and `acknowledgedBehind` names the releases it
// leaves out.
var promoted = catalogs.PromoteCatalogDraft("my-tenant", "~", "acme-corp", "default", work.DraftId.ToString(),
    new PromoteCatalogDraftRequest(
        keepPreviousAs: "Before the redesign",
        acknowledgedBehind: work.Behind.Select(b => b.VarVersion).ToList()));
```

The generator names a `version` member `VarVersion` (constructor argument `varVersion`), and
`base` `Base` (`varBase`), because both collide with C# names. A draft's `VarVersion` is null, and
a preview's `ChangeKind` is null when there is no release to compare with.

## Uploading files

Multipart operations such as `UploadImage` and `ImportCatalog` take a `FileParameter`. Give it the
filename and content type:

```csharp
using Epistola.Client.Client;

using var content = File.OpenRead("logo.png");
var image = new ImagesApi(http, "https://epistola.example.com/api").UploadImage(
    "acme-corp", "~", "acme-corp", "main", "next", new FileParameter("logo.png", "image/png", content), name: "Logo");
```

A `FileParameter` built from a bare stream is sent as `no_name_provided`, with type
`application/octet-stream`. The server then names the image after that placeholder, and rejects it
as not an image unless you also pass `mediaType`.

## Template data validation

Template data is checked by **Epistola**, through `validateTemplateData`. Nothing is compiled
locally, so the package references no JSON Schema library, and the verdict cannot disagree with what
generation will do — the server validates the same data when a job is submitted, and it also knows
which optional fields the version being rendered actually reads.

```csharp
using Epistola.Client.Validation.Schema;

var validator = new TemplateSchemaValidator(new TemplatesApi(http));
// A catalog is addressed by registry, namespace and key. The registry is `~` for the server's
// default registry (ITemplateDataValidator.DefaultRegistry), or named in full ("acme.com").
validator.Validate("my-tenant", "~", "acme-corp", "default", "invoice", data);   // throws TemplateDataValidationException

// Or around generation, which turns the server's `template-data-invalid` problem into the same exception:
var validating = new ValidatingGenerationApi(new GenerationApi(http), new TemplatesApi(http));
validating.GenerateDocument("my-tenant", request);
```

Each error carries a **JSON Pointer** into the data (`/customer/email`), the JSON Schema `Keyword`
that failed, and a human-readable `Message` — so a form can mark the field that is wrong.

`validateTemplateData` arrived with contract **1.4.0**; against an older server the call fails like
any other unknown operation rather than reporting unvalidated data as acceptable.

### Validating in-process instead

To avoid a request per check — pre-flighting a large batch, say — implement
`ITemplateDataValidator` over the library of your choice and pass it instead of the API:

```csharp
// A template is read from a catalog version, so a local validator is told which one.
var validator = new TemplateSchemaValidator(new MyNJsonSchemaValidator(templatesApi, "1.4.0"));
```

An implementation that answers in-process leaves `PreflightsGeneration` at its default `true`, so
`ValidatingGenerationApi` checks before submitting and reports every item of a batch at once, each
path prefixed `items[<index>]`.

Two things an implementation owes its callers, both described on `ITemplateDataValidator`: an empty
list means the data is acceptable, and `Path` is a JSON Pointer while `Keyword` is a JSON Schema
keyword. Neither is what a library hands you — NJsonSchema reports `#/customer.name` and a `Kind`
enum like `StringExpected`, and it raises nested failures against the container rather than the
field. A worked adapter that deals with all three is in
`test/Epistola.Client.Tests/Validation/Schema/Local/`; copy it.

Be aware that a local verdict is its own: draft coverage, `format` handling and error vocabulary are
the library's choices, so it can accept data Epistola will refuse, or refuse data Epistola would
render.

The generated request/response models also carry fail-fast constraint checks via `Validate()`
extension methods (from `Epistola.Client.Validation`):

```csharp
using Epistola.Client.Validation;

var request = new CreateTenantRequest(key: "acme-corp", name: "Acme").Validate();
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
