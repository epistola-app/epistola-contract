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
`application/vnd.epistola.v2+json`, and one that **drops top-level properties you never set** from
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
`Authorization: ApiKey <key>`. Some Epistola Suite deployments may disable API-key authentication
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

A deployment refused because releases of other catalogs it renders with (the releases it pins)
cannot be had is a `release-dependencies-missing` problem. `MissingDependencies` lists each missing release and why,
with `Reason` kept as the string the server sent (compare against `MissingDependency.Reasons`):

```csharp
try
{
    environments.DeployCatalogVersion("my-tenant", "production", "~", "acme-corp", "letters", "1.4.0",
        includeDependencies: false);
}
catch (ProblemDetailException e) when (e.TypeSlug == KnownProblemSlugs.RELEASE_DEPENDENCIES_MISSING)
{
    foreach (var d in e.MissingDependencies)
        Console.WriteLine($"{d.Registry}/{d.Namespace}/{d.CatalogKey}@{d.ReleaseVersion}: {d.Reason}");
    // "unavailable": the release could not be fetched from its registry or a mirror, or not verified
}
```

Releasing a draft whose dependency declarations name drafts of other catalogs, without
`releaseDependencies`, is a `release-dependencies-unreleased` problem. `UnreleasedDependencies` lists
each such draft:

```csharp
catch (ProblemDetailException e) when (e.TypeSlug == KnownProblemSlugs.RELEASE_DEPENDENCIES_UNRELEASED)
{
    foreach (var d in e.UnreleasedDependencies)
        Console.WriteLine($"{d.Registry}/{d.Namespace}/{d.CatalogKey}: draft {d.DraftName} ({d.VersionId})");
}
```

Listing or installing the releases a subscribed catalog's source offers fails with
`catalog-source-unavailable` when that source cannot be used: 503 when it could not be reached or
timed out (a `Retry-After` header may say when to try again), 502 when it answered badly. Nothing
is installed:

```csharp
try
{
    catalogs.InstallCatalogRelease("my-tenant", "~", "acme-corp", "letters", "1.5.0");
}
catch (ProblemDetailException e) when (e.TypeSlug == KnownProblemSlugs.CATALOG_SOURCE_UNAVAILABLE)
{
    Console.WriteLine($"catalog source unavailable ({e.ProblemStatus}): {e.Detail}");
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
            // Registry, Namespace and CatalogKey name the catalog rendered; ReleaseVersion is null for a draft.
            case "completed": Download(result.DocumentId, result.CorrelationId); break;
            case "failed":    LogFailure(result.CorrelationId, result.Error);    break;
        }
    })
    .Build();

collector.Start();          // blocks, running the adaptive poll loop; Stop() to end
```

`CollectOnce()` / `CollectOnceAsync()` perform a single poll for custom scheduling; `Kick()` shortens
the backoff when a result is expected soon. Partition helpers: `PartitionFor`, `IsMyPartition`,
`RoutingKeyToMe`.

## Catalog versions

A catalog consists of versions, each a `draft` or `released`. A catalog has any number of drafts,
each named, on a fixed base (the release it started from), and each released on its own. A draft
has no number until it is released and is addressed by its `VersionId`, which it keeps when it is
released; a release by that id or by its SemVer number. Every catalog-content operation (templates, variants, stencils, themes, images,
dependencies, …) takes the version after the catalog key; template data validation
(`ValidateTemplateData`) does not. The versions themselves are managed through `CatalogsApi`:

```csharp
var catalogs = new CatalogsApi(http, "https://epistola.example.com/api");

// Start a draft, named, as a copy of a release (`from`, which becomes its base), of another draft
// (`from` its `VersionId`), or, without `from`, of the highest release. A fix to an older line is a draft
// from a release of that line.
var draft = catalogs.CreateCatalogDraft("my-tenant", "~", "acme-corp", "default",
    new CreateCatalogDraftRequest(name: "Fixes 1.4", from: "1.4.0"));
var id = draft.VersionId;

// The number releasing it would give it, from its changes against its base: `Needed`, or the next
// free number of the same kind when another release already has it (`Suggested`).
var preview = catalogs.PreviewCatalogDraftRelease("my-tenant", "~", "acme-corp", "default", id);
Console.WriteLine($"{preview.Suggested} (needs {preview.Needed}): {string.Join("; ", preview.Reasons)}");

// A draft is an independent copy: releases it does not contain are listed in `Behind`, and
// releasing it must acknowledge each of them. Leave `releaseVersion` out to release it as suggested.
// `releaseDependencies` releases the drafts of other catalogs its dependency declarations name
// along with it, in one transaction.
var current = catalogs.GetCatalogVersion("my-tenant", "~", "acme-corp", "default", id);
var released = catalogs.ReleaseCatalogDraft("my-tenant", "~", "acme-corp", "default", id,
    new ReleaseCatalogDraftRequest(
        notes: "Spring update",
        acknowledgedBehind: current.Behind.Select(b => b.ReleaseVersion).ToList(),
        releaseDependencies: true));

// Releasing never changes other drafts; the answer names those now behind it, and the releases
// made along with it.
foreach (var behind in released.DraftsBehind)
    Console.WriteLine($"{behind} does not contain {released.CatalogVersion.ReleaseVersion}");
foreach (var dependency in released.ReleasedDependencies)
    Console.WriteLine($"also released {dependency.CatalogKey} {dependency.ReleaseVersion}");
```

The generator names a `base` member `Base` (constructor argument `varBase`), because it collides
with a C# name. A draft's `ReleaseVersion` is null, and a preview's `ChangeKind` is null when the
draft has no base to compare with.

## Response headers and conditional updates

Each operation also has a `…WithHttpInfo` variant whose `ApiResponse<T>.Headers` carries the
response headers: the `Location` of what a create made (and of the job `GenerateDocument` and
`GenerateDocumentBatch` accepted), and the `ETag` of a resource. Pass that `ETag` back unchanged as
`ifMatch` on its update; when someone changed the resource in between, the update is refused with a
412 `resource-modified` problem:

```csharp
var read = catalogs.GetCatalogVersionWithHttpInfo("my-tenant", "~", "acme-corp", "default", id);
var etag = read.Headers["ETag"][0];
catalogs.UpdateCatalogDraft("my-tenant", "~", "acme-corp", "default", id,
    new UpdateCatalogDraftRequest(name: "Fixes 1.4 (spring)"), ifMatch: etag);
```

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
