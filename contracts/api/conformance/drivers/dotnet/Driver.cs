// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using System.Text;
using System.Text.Json;
using Epistola.Client.Api;
using Epistola.Client.Auth;
using Epistola.Client.Client;
using Epistola.Client.Collect;
using Epistola.Client.Error;
using Epistola.Client.Http;
using Epistola.Client.Identity;
using Epistola.Client.Model;
using Epistola.Client.Validation.Schema;

namespace Epistola.Conformance;

/// <summary>
/// The .NET client's conformance driver.
///
/// Asks the conformance server what to do, does it with the published client, and reports back. It
/// asserts nothing — the server judges the requests, so the four clients are held to one set of
/// expectations rather than four that drift. See ../../README.md for the driver contract.
/// </summary>
public static class Driver
{
    private static readonly HttpClient Control = new();

    public static int Main(string[] args)
    {
        if (args.Length < 1)
        {
            Console.Error.WriteLine("usage: driver <conformance server base url>");
            return 2;
        }

        var baseUrl = args[0];
        var instruction = Get($"{baseUrl}/__conformance/action");
        var config = instruction.GetProperty("config");

        try
        {
            switch (instruction.GetProperty("action").GetString())
            {
                case "ping": Ping(baseUrl, config); break;
                case "list-templates": ListTemplates(baseUrl, config); break;
                case "collect": Collect(baseUrl, config); break;
                case "problem": Problem(baseUrl, config); break;
                case "validate-template-data": ValidateTemplateData(baseUrl, config); break;
                case "routing": Routing(baseUrl, config); break;
                case "generate-document": GenerateDocument(baseUrl, config); break;
                case "update-consumer": UpdateConsumer(baseUrl, config); break;
                case "download-document": DownloadDocument(baseUrl, config); break;
                case "list-documents": ListDocuments(baseUrl, config); break;
                case "list-images": ListImages(baseUrl, config); break;
                case "upload-image": UploadImage(baseUrl, config); break;
                case "import-catalog": ImportCatalog(baseUrl, config); break;
                case "install-catalog-release": InstallCatalogRelease(baseUrl, config); break;
                case "list-available-catalog-releases": ListAvailableCatalogReleases(baseUrl, config); break;
                case "download-image": DownloadImage(baseUrl, config); break;
                case "delete-image": DeleteImage(baseUrl, config); break;
                case "list-catalog-dependencies": ListCatalogDependencies(baseUrl, config); break;
                case "update-catalog-dependencies": UpdateCatalogDependencies(baseUrl, config); break;
                case "create-catalog-draft": CreateCatalogDraft(baseUrl, config); break;
                case "list-catalog-versions": ListCatalogVersions(baseUrl, config); break;
                case "get-catalog-version": GetCatalogVersion(baseUrl, config); break;
                case "update-catalog-draft": UpdateCatalogDraft(baseUrl, config); break;
                case "set-catalog-draft-theme-settings": SetCatalogDraftThemeSettings(baseUrl, config); break;
                case "preview-catalog-draft-release": PreviewCatalogDraftRelease(baseUrl, config); break;
                case "delete-catalog-draft": DeleteCatalogDraft(baseUrl, config); break;
                case "release-catalog-draft": ReleaseCatalogDraft(baseUrl, config); break;
                case "deploy-catalog-version": DeployCatalogVersion(baseUrl, config); break;
                case "undeploy-catalog-version": UndeployCatalogVersion(baseUrl, config); break;
                case "create-environment": CreateEnvironment(baseUrl, config); break;
                case "update-environment": UpdateEnvironment(baseUrl, config); break;
                case "update-tenant": UpdateTenant(baseUrl, config); break;
                case "update-code-list-entry-hidden": UpdateCodeListEntryHidden(baseUrl, config); break;
                case "update-variant-content": UpdateVariantContent(baseUrl, config); break;
                case "get-variant-content": GetVariantContent(baseUrl, config); break;
                case "mark-variant-ready": MarkVariantReady(baseUrl, config); break;
                case "mark-template-ready": MarkTemplateReady(baseUrl, config); break;
                case "get-stencil-content": GetStencilContent(baseUrl, config); break;
                case "update-stencil-content": UpdateStencilContent(baseUrl, config); break;
                case "mark-stencil-ready": MarkStencilReady(baseUrl, config); break;
                case "list-stencil-usage": ListStencilUsage(baseUrl, config); break;
                case "get-catalog-changes": GetCatalogChanges(baseUrl, config); break;
                case "mark-catalog-ready": MarkCatalogReady(baseUrl, config); break;
                case "list-environment-deployments": ListEnvironmentDeployments(baseUrl, config); break;
                case "list-deployment-history": ListDeploymentHistory(baseUrl, config); break;
                default: throw new ArgumentException($"unknown action {instruction.GetProperty("action")}");
            }

            Done(baseUrl, null);
            return 0;
        }
        catch (Exception e)
        {
            Done(baseUrl, $"{e.GetType().Name}: {e.Message}");
            Console.Error.WriteLine(e);
            return 1;
        }
    }

    // --- Actions ---

    private static void Ping(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        new SystemApi(http, apiBase).Ping(new PingRequest(
            name: "Conformance Driver",
            description: "Drives the .NET client through one conformance scenario",
            contact: "conformance@epistola.app"));
    }

    /// <summary>
    /// Reports the keys the client parsed from the last listing.
    /// </summary>
    private static void ListTemplates(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var api = new TemplatesApi(http, apiBase);
        TemplateListResponse? response = null;
        for (var i = 0; i < Int(config, "repeat", 1); i++)
        {
            response = api.ListTemplates(Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"));
        }
        var templates = response!.Items;

        Report(baseUrl, new Dictionary<string, object>
        {
            ["templateKeys"] = string.Join(",", templates.Select(template => Show(template.Key))),
            ["templateDescriptions"] = string.Join("|", templates.Select(template => Show(template.Description))),
        });
    }

    private static void Problem(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        try
        {
            new TemplatesApi(http, apiBase).ListTemplates(Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"));
            Report(baseUrl, new Dictionary<string, object> { ["problemTypeSlug"] = "<no exception was thrown>" });
        }
        catch (ProblemDetailException e)
        {
            Report(baseUrl, new Dictionary<string, object>
            {
                ["problemTypeSlug"] = e.TypeSlug ?? "<null>",
                ["problemStatus"] = (int)e.StatusCode,
                ["problemTitle"] = e.Title ?? "<null>",
                ["problemFieldErrors"] = string.Join(",", e.Errors.Select(error => $"{error.Field}:{error.Message}")),
                ["problemMissingFields"] = string.Join(",", e.MissingFields.Select(f => $"{f.Path}:{(f.Required ? "true" : "false")}")),
                ["problemInvalidFields"] = string.Join(",", e.InvalidFields.Select(f => $"{f.Path}:{f.Keyword}")),
            });
        }
    }

    /// <summary>Reports the failures the client surfaced, which is the shape every client owes its callers.</summary>
    private static void ValidateTemplateData(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var validator = new TemplateSchemaValidator(new TemplatesApi(http, apiBase));
        object data = config.TryGetProperty("data", out var supplied)
            ? JsonSerializer.Deserialize<Dictionary<string, object>>(supplied.GetRawText())!
            : new Dictionary<string, object>();
        try
        {
            validator.Validate(Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "templateKey"), data);
            Report(baseUrl, new Dictionary<string, object>
            {
                ["validationFailurePaths"] = "<accepted>",
                ["validationFailureKeywords"] = "<accepted>",
            });
        }
        catch (TemplateDataValidationException e)
        {
            Report(baseUrl, new Dictionary<string, object>
            {
                ["validationFailurePaths"] = string.Join(",", e.Errors.Select(failure => failure.Path)),
                ["validationFailureKeywords"] = string.Join(",", e.Errors.Select(failure => failure.Keyword ?? "<null>")),
            });
        }
    }

    private static void Collect(string baseUrl, JsonElement config)
    {
        var (http, _) = Client(baseUrl, config);
        var handled = new List<ResultCollector.GenerationResult>();
        var handledLock = new object();
        var failOnSequence = config.TryGetProperty("failHandlerOnSequence", out var fail) ? fail.GetInt64() : -1L;

        var collector = ResultCollector.Builder()
            .HttpClient(http)
            .TenantKey(Str(config, "tenantKey"))
            .BatchSize(Int(config, "batchSize"))
            .MinInterval(TimeSpan.FromMilliseconds(Int(config, "minIntervalMs")))
            .MaxInterval(TimeSpan.FromMilliseconds(Int(config, "maxIntervalMs")))
            .BackoffMultiplier(config.GetProperty("multiplier").GetDouble())
            .RegisterShutdownHook(false)
            .Handler(result =>
            {
                lock (handledLock) { handled.Add(result); }
                if (result.Sequence == failOnSequence) throw new InvalidOperationException("conformance: deliberate handler failure");
            })
            // Without this the loop swallows collection failures and simply backs off, which reaches
            // the harness as "the client chose not to poll" rather than as the cause.
            .ErrorHandler(e => Console.Error.WriteLine(e))
            .Build();

        var thread = new Thread(collector.Start) { Name = "conformance-collector", IsBackground = true };
        thread.Start();
        Thread.Sleep(Int(config, "runForMs"));
        collector.Stop();
        thread.Join(TimeSpan.FromSeconds(5));

        lock (handledLock)
        {
            Report(baseUrl, new Dictionary<string, object>
            {
                ["resultsHandled"] = handled.Count,
                ["statuses"] = string.Join(",", handled.Select(r => r.Status)),
                ["correlationIds"] = string.Join(",", handled.Select(r => r.CorrelationId ?? "")),
                ["handledSequences"] = string.Join(",", handled.Select(r => r.Sequence)),
                ["partitionTotal"] = collector.CurrentPartitionAssignment?.Total ?? -1,
                ["releases"] = string.Join(",", handled.Select(r =>
                    $"{Show(r.Registry)}/{Show(r.Namespace)}/{Show(r.CatalogKey)}@{Show(r.ReleaseVersion)}")),
            });
        }
    }

    /// <summary>
    /// A request body with something in it: required fields, two of the optional ones set, the rest
    /// left alone, and a free-form <c>data</c> object carrying every JSON type. What the server
    /// receives is the generator's serialization, which is the part no client hand-writes and no
    /// client's own tests inspect.
    /// </summary>
    private static void GenerateDocument(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var response = new GenerationApi(http, apiBase).GenerateDocumentWithHttpInfo(
            Str(config, "tenantKey"),
            new GenerateDocumentRequest(
                // Left unset when the scenario does not name it: omitted means the default registry, which
                // only the server knows, so the client must not fill one in.
                registry: OptStr(config, "registry")!,
                varNamespace: Str(config, "namespace"),
                catalogKey: Str(config, "catalogKey"),
                templateKey: Str(config, "templateKey"),
                // Optional, and left unset when the scenario does not name them: an unset optional
                // must stay off the wire (scenario 120), not go out as null.
                variantKey: OptStr(config, "variantKey"),
                attributes: OptAttributes(config),
                versionRange: OptStr(config, "versionRange"),
                environmentKey: OptStr(config, "environmentKey"),
                // Parsed with Newtonsoft, not System.Text.Json: the generated models serialize
                // through Newtonsoft, which does not know what to do with a JsonElement and emits
                // an empty object for it.
                data: Newtonsoft.Json.JsonConvert.DeserializeObject(config.GetProperty("data").GetRawText()),
                correlationId: OptStr(config, "correlationId"),
                routingKey: OptStr(config, "routingKey")));
        var job = response.Data;

        Report(baseUrl, new Dictionary<string, object>
        {
            ["requestId"] = Show(job?.RequestId),
            ["status"] = job == null ? "null" : Wire(job.Status),
            ["jobType"] = job == null ? "null" : Wire(job.JobType),
            ["total"] = job == null ? "null" : job.Total,
            ["location"] = Header(response, "Location"),
        });
    }

    /// <summary>
    /// A partial update that sets exactly one field. Everything the caller did not name must stay
    /// off the wire: the contract reads a null on these as "clear this", so a serializer that writes
    /// nulls for unset properties turns "rename this consumer" into "rename it and erase its
    /// description, contact and expiry".
    /// </summary>
    private static void UpdateConsumer(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        ApiResponse<ConsumerDto> response;
        try
        {
            response = new ConsumersApi(http, apiBase).UpdateConsumerWithHttpInfo(
                Str(config, "tenantKey"),
                Str(config, "consumerId"),
                new UpdateConsumerRequest(name: Str(config, "name")),
                ifMatch: OptStr(config, "ifMatch"));
        }
        catch (ProblemDetailException e)
        {
            ReportProblem(baseUrl, e);
            return;
        }

        Report(baseUrl, new Dictionary<string, object>
        {
            ["id"] = Show(response.Data.Id),
            ["name"] = Show(response.Data.Name),
            ["lastModified"] = Show(Iso(response.Data.LastModified)),
            ["etag"] = Header(response, "ETag"),
        });
    }

    /// <summary>
    /// Renames a tenant, sending <c>config.ifMatch</c> as <c>If-Match</c> when present, and reports
    /// the new <c>ETag</c> exactly as received.
    /// </summary>
    private static void UpdateTenant(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        ApiResponse<TenantDto> response;
        try
        {
            response = new TenantsApi(http, apiBase).UpdateTenantWithHttpInfo(
                Str(config, "tenantKey"),
                new UpdateTenantRequest(name: Str(config, "name")),
                ifMatch: OptStr(config, "ifMatch"));
        }
        catch (ProblemDetailException e)
        {
            ReportProblem(baseUrl, e);
            return;
        }

        Report(baseUrl, new Dictionary<string, object>
        {
            ["key"] = Show(response.Data.Key),
            ["name"] = Show(response.Data.Name),
            ["etag"] = Header(response, "ETag"),
        });
    }

    /// <summary>
    /// Hides or shows one code-list entry, conditional on the code list's <c>ETag</c> when
    /// <c>config.ifMatch</c> is present, and reports the code list's new <c>ETag</c>.
    /// </summary>
    private static void UpdateCodeListEntryHidden(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        ApiResponse<CodeListEntryDto> response;
        try
        {
            response = new CodeListsApi(http, apiBase).UpdateCodeListEntryHiddenWithHttpInfo(
                Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"),
                Str(config, "codeListKey"), Str(config, "code"),
                new UpdateCodeListEntryHiddenRequest(hidden: config.GetProperty("hidden").GetBoolean()),
                ifMatch: OptStr(config, "ifMatch"));
        }
        catch (ProblemDetailException e)
        {
            ReportProblem(baseUrl, e);
            return;
        }

        Report(baseUrl, new Dictionary<string, object>
        {
            ["code"] = Show(response.Data.Code),
            ["hidden"] = Bool(response.Data.Hidden),
            ["etag"] = Header(response, "ETag"),
        });
    }

    private static void ReportProblem(string baseUrl, ProblemDetailException e) =>
        Report(baseUrl, new Dictionary<string, object>
        {
            ["problemTypeSlug"] = e.TypeSlug ?? "<null>",
            ["problemStatus"] = (int)e.StatusCode,
        });

    /// <summary>
    /// One poll to learn the partition assignment from the _meta line, then the routing helpers.
    /// The values are reported rather than asserted here: the harness holds all four clients to the
    /// same answers, which is the only way four independent murmur3 implementations stay in step.
    /// </summary>
    private static void Routing(string baseUrl, JsonElement config)
    {
        var (http, _) = Client(baseUrl, config);
        var collector = ResultCollector.Builder()
            .HttpClient(http)
            .TenantKey(Str(config, "tenantKey"))
            .RegisterShutdownHook(false)
            .Handler(_ => { })
            .Build();

        collector.CollectOnce();

        var keys = config.GetProperty("keys").EnumerateArray().Select(k => k.GetString()!).ToList();
        Report(baseUrl, new Dictionary<string, object>
        {
            ["partitionTotal"] = collector.CurrentPartitionAssignment?.Total ?? -1,
            ["partitions"] = string.Join(",", keys.Select(k => $"{k}:{Show(collector.PartitionFor(k))}")),
            ["routed"] = string.Join(",", keys.Select(k => $"{k}={Show(collector.RoutingKeyToMe(k))}")),
            ["routedPartitions"] = string.Join(",", keys.Select(k => Show(collector.PartitionFor(collector.RoutingKeyToMe(k)!)))),
            ["mineFlags"] = string.Join(",", keys.Select(k => collector.IsMyPartition(k) ? "true" : "false")),
        });
    }

    /// <summary>Renders a null the way the other drivers' languages print theirs, so the harness
    /// compares one spelling rather than four.</summary>
    private static string Show(object? value) => value?.ToString() ?? "null";

    /// <summary>
    /// Downloads a document and reports what arrived, byte for byte.
    ///
    /// The four clients return four different things here — a File, a FileParameter, a bytearray —
    /// and the only thing that has to be identical is the content. A stack that decodes a PDF as
    /// text corrupts every document it fetches, silently and irreversibly, so the fixture is
    /// deliberately not valid UTF-8.
    /// </summary>
    private static void DownloadDocument(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var file = new GenerationApi(http, apiBase).DownloadDocument(
            Str(config, "tenantKey"), Guid.Parse(Str(config, "documentId")));

        ReportBytes(baseUrl, file);
    }

    /// <summary>
    /// Lists documents with the catalog and template filters the scenario sets, each sent only
    /// when set, and reports what the client made of the page.
    /// </summary>
    private static void ListDocuments(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var documents = new GenerationApi(http, apiBase)
            .ListDocuments(
                Str(config, "tenantKey"),
                templateKey: OptStr(config, "templateKey"),
                registry: OptStr(config, "registry"),
                varNamespace: OptStr(config, "namespace"),
                catalogKey: OptStr(config, "catalogKey"))
            .Items;

        Report(baseUrl, new Dictionary<string, object>
        {
            ["catalogs"] = string.Join(",", documents.Select(d => $"{Show(d.Registry)}/{Show(d.Namespace)}/{Show(d.CatalogKey)}")),
            ["templateKeys"] = string.Join(",", documents.Select(d => d.TemplateKey)),
            ["variantKeys"] = string.Join(",", documents.Select(d => d.VariantKey)),
        });
    }

    /// <summary>
    /// Lists images and reports what the client made of them. Keys may be UUIDs or readable names,
    /// and an SVG's dimensions are null, which must stay null rather than become 0.
    /// </summary>
    private static void ListImages(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var images = new ImagesApi(http, apiBase)
            .ListImages(Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"), search: Str(config, "search"))
            .Items;

        Report(baseUrl, new Dictionary<string, object>
        {
            ["imageKeys"] = string.Join(",", images.Select(image => image.Key)),
            ["widths"] = string.Join(",", images.Select(image => Show(image.Width))),
            ["heights"] = string.Join(",", images.Select(image => Show(image.Height))),
            ["mediaTypes"] = string.Join(",", images.Select(image => image.MediaType)),
        });
    }

    /// <summary>
    /// Uploads an image from bytes. A <c>FileParameter</c> carries the part's filename and content
    /// type explicitly, so both come from the scenario rather than being inferred.
    /// </summary>
    private static void UploadImage(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        using var content = new MemoryStream(Convert.FromBase64String(Str(config, "fileBase64")));
        var image = new ImagesApi(http, apiBase).UploadImage(
            Str(config, "tenantKey"),
            Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"),
            new FileParameter(Str(config, "filename"), Str(config, "fileContentType"), content),
            name: Str(config, "name"));

        Report(baseUrl, new Dictionary<string, object> { ["imageKey"] = image.Key, ["imageName"] = image.Name });
    }

    /// <summary>
    /// Imports a catalog archive from bytes, as a <c>FileParameter</c> with the scenario's filename
    /// and content type. <c>registry</c>, <c>namespace</c> and <c>catalogType</c> go out only when
    /// the scenario sets them. A refusal is reported by its problem.
    /// </summary>
    private static void ImportCatalog(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        using var content = new MemoryStream(Convert.FromBase64String(Str(config, "fileBase64")));
        ImportCatalogResponse imported;
        try
        {
            imported = new CatalogsApi(http, apiBase).ImportCatalog(
                Str(config, "tenantKey"),
                new FileParameter(Str(config, "filename"), Str(config, "fileContentType"), content),
                registry: OptStr(config, "registry"),
                varNamespace: OptStr(config, "namespace"),
                catalogType: OptStr(config, "catalogType") is { } catalogType ? Parse<CatalogType>(catalogType) : null);
        }
        catch (ProblemDetailException e)
        {
            Report(baseUrl, new Dictionary<string, object>
            {
                ["problemTypeSlug"] = e.TypeSlug ?? "<null>",
                ["problemStatus"] = (int)e.StatusCode,
            });
            return;
        }

        Report(baseUrl, new Dictionary<string, object>
        {
            ["registry"] = Show(imported.Registry),
            ["namespace"] = Show(imported.Namespace),
            ["catalogKey"] = Show(imported.CatalogKey),
            ["releaseVersion"] = Show(imported.ReleaseVersion),
            ["newlyInstalled"] = Bool(imported.NewlyInstalled),
            ["total"] = Show(imported.Total),
        });
    }

    /// <summary>
    /// Installs a release a subscribed catalog's source offers, named in the path. A refusal or a
    /// failing source is reported by its problem, missing dependencies included.
    /// </summary>
    private static void InstallCatalogRelease(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        InstallCatalogReleaseResponse installed;
        try
        {
            installed = new CatalogsApi(http, apiBase).InstallCatalogRelease(
                Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"),
                Str(config, "releaseVersion"));
        }
        catch (ProblemDetailException e)
        {
            Report(baseUrl, new Dictionary<string, object>
            {
                ["problemTypeSlug"] = e.TypeSlug ?? "<null>",
                ["problemStatus"] = (int)e.StatusCode,
                ["problemMissingDependencies"] = string.Join(",", e.MissingDependencies.Select(d =>
                    $"{d.Registry}/{d.Namespace}/{d.CatalogKey}@{d.ReleaseVersion}:{d.Reason}")),
            });
            return;
        }

        Report(baseUrl, new Dictionary<string, object>
        {
            ["registry"] = Show(installed.Registry),
            ["namespace"] = Show(installed.Namespace),
            ["catalogKey"] = Show(installed.CatalogKey),
            ["releaseVersion"] = Show(installed.ReleaseVersion),
            ["newlyInstalled"] = Bool(installed.NewlyInstalled),
            ["total"] = Show(installed.Total),
            ["installedDependencies"] = string.Join(",", installed.InstalledDependencies ?? new List<string>()),
            ["presentDependencies"] = string.Join(",", installed.PresentDependencies ?? new List<string>()),
        });
    }

    /// <summary>
    /// Lists the releases a subscribed catalog's source offers that the tenant lacks, reporting
    /// every one in order. A failing source is reported by its problem.
    /// </summary>
    private static void ListAvailableCatalogReleases(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        AvailableCatalogReleases releases;
        try
        {
            releases = new CatalogsApi(http, apiBase).ListAvailableCatalogReleases(
                Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"));
        }
        catch (ProblemDetailException e)
        {
            Report(baseUrl, new Dictionary<string, object>
            {
                ["problemTypeSlug"] = e.TypeSlug ?? "<null>",
                ["problemStatus"] = (int)e.StatusCode,
            });
            return;
        }
        var available = releases.Available ?? new List<AvailableReleaseDto>();

        Report(baseUrl, new Dictionary<string, object>
        {
            ["registry"] = Show(releases.Registry),
            ["namespace"] = Show(releases.Namespace),
            ["catalogKey"] = Show(releases.CatalogKey),
            ["availableComparedTo"] = string.Join(";", available.Select(r => $"{Show(r.ReleaseVersion)}={Show(r.ComparedTo)}")),
            ["availableChanges"] = string.Join(";", available
                .Select(r => $"{Show(r.ReleaseVersion)}=" + string.Join("+", (r.Changes ?? new List<ReleasedChangeDto>())
                    .Select(c => $"{Show(c.Type)}/{Show(c.Key)}:{Wire(c.Change)}")))),
            ["availableDependencies"] = string.Join(";", available
                .Select(r => $"{Show(r.ReleaseVersion)}=" + string.Join("+", (r.Dependencies ?? new List<AvailableReleaseDependencyDto>())
                    .Select(d => $"{Show(d.Registry)}/{Show(d.Namespace)}/{Show(d.CatalogKey)}@{Show(d.ReleaseVersion)}:{Bool(d.Present)}")))),
        });
    }

    private static void DownloadImage(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        ReportBytes(baseUrl, new ImagesApi(http, apiBase).DownloadImageContent(
            Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"), Str(config, "imageKey")));
    }

    private static void DeleteImage(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        new ImagesApi(http, apiBase).DeleteImage(
            Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"), Str(config, "imageKey"),
            force: config.GetProperty("force").GetBoolean());
    }

    private static void ListCatalogDependencies(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        ReportDependencies(baseUrl, new CatalogsApi(http, apiBase).ListCatalogDependencies(
            Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion")));
    }

    /// <summary>
    /// Declares the catalog's dependencies as the scenario lists them, each pinning an exact
    /// release or naming a draft, and reports what came back. <c>releaseVersion</c> and
    /// <c>versionId</c> are set only when the entry names them, so the other stays off the wire. A
    /// declaration that names a draft, or pins no release (migrated from wire v7), has a null
    /// <c>releaseVersion</c>, reported as <c>null</c> rather than lost.
    /// </summary>
    private static void UpdateCatalogDependencies(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var dependencies = config.GetProperty("dependencies").EnumerateArray()
            .Select(d => new CatalogDependencyRequirement(
                // Set only when the entry names one: omitted means the default registry.
                registry: OptStr(d, "registry")!,
                varNamespace: Str(d, "namespace"),
                catalogKey: Str(d, "catalogKey"),
                releaseVersion: OptStr(d, "releaseVersion")!,
                versionId: OptStr(d, "versionId")))
            .ToList();
        ApiResponse<CatalogDependencyListResponse> response;
        try
        {
            response = new CatalogsApi(http, apiBase).UpdateCatalogDependenciesWithHttpInfo(
                Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"),
                new UpdateCatalogDependenciesRequest(dependencies: dependencies),
                ifMatch: OptStr(config, "ifMatch"));
        }
        catch (ProblemDetailException e)
        {
            ReportProblem(baseUrl, e);
            return;
        }
        ReportDependencies(baseUrl, response.Data, Header(response, "ETag"));
    }

    private static void ReportDependencies(string baseUrl, CatalogDependencyListResponse response, string? etag = null)
    {
        var items = response.Items;
        var values = new Dictionary<string, object>
        {
            ["registries"] = string.Join(",", items.Select(d => Show(d.Registry))),
            ["namespaces"] = string.Join(",", items.Select(d => Show(d.Namespace))),
            ["catalogKeys"] = string.Join(",", items.Select(d => Show(d.CatalogKey))),
            ["releaseVersions"] = string.Join(",", items.Select(d => Show(d.ReleaseVersion))),
            ["versionIds"] = string.Join(",", items.Select(d => Show(d.VersionId))),
        };
        if (etag != null) values["etag"] = etag;
        Report(baseUrl, values);
    }

    // --- Catalog versions ---

    /// <summary>
    /// Starts a draft, from a release number or another draft's id. <c>from</c> is optional and left unset when the scenario does not name
    /// it, so it stays off the wire rather than going out as null.
    /// </summary>
    private static void CreateCatalogDraft(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var response = new CatalogsApi(http, apiBase).CreateCatalogDraftWithHttpInfo(
            Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"),
            new CreateCatalogDraftRequest(name: Str(config, "name"), from: OptStr(config, "from")!));
        var version = response.Data;

        Report(baseUrl, new Dictionary<string, object>
        {
            ["releaseVersion"] = Show(version.ReleaseVersion),
            ["versionId"] = Show(version.VersionId),
            ["name"] = Show(version.Name),
            ["status"] = Show(Wire(version.Status)),
            ["base"] = Show(version.Base),
            ["createdFrom"] = Show(version.CreatedFrom),
            ["releasedAt"] = Show(Iso(version.ReleasedAt)),
            ["location"] = Header(response, "Location"),
        });
    }

    private static void ListCatalogVersions(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var status = OptStr(config, "status");
        var items = new CatalogsApi(http, apiBase).ListCatalogVersions(
            Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"),
            status: status == null ? null : Parse<CatalogVersionStatus>(status)).Items;

        Report(baseUrl, new Dictionary<string, object>
        {
            ["releaseVersions"] = string.Join(",", items.Select(v => Show(v.ReleaseVersion))),
            ["versionIds"] = string.Join(",", items.Select(v => Show(v.VersionId))),
            ["names"] = string.Join("|", items.Select(v => Show(v.Name))),
            ["statuses"] = string.Join(",", items.Select(v => Show(Wire(v.Status)))),
            ["bases"] = string.Join(",", items.Select(v => Show(v.Base))),
            ["createdFrom"] = string.Join(",", items.Select(v => Show(v.CreatedFrom))),
            ["behindCounts"] = string.Join(",", items.Select(v => (v.Behind ?? new List<BehindReleaseDto>()).Count)),
        });
    }

    private static void GetCatalogVersion(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var version = new CatalogsApi(http, apiBase).GetCatalogVersion(
            Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"));

        Report(baseUrl, new Dictionary<string, object>
        {
            ["releaseVersion"] = Show(version.ReleaseVersion),
            ["versionId"] = Show(version.VersionId),
            ["name"] = Show(version.Name),
            ["status"] = Show(Wire(version.Status)),
            ["releasedAt"] = Show(Iso(version.ReleasedAt)),
            ["fingerprint"] = Show(version.Fingerprint),
            ["dependencies"] = string.Join(",", (version.Dependencies ?? new List<ReleaseDependencyDto>())
                .Select(d => $"{Show(d.Registry)}/{Show(d.Namespace)}/{Show(d.CatalogKey)}@{Show(d.ReleaseVersion)}")),
            ["deployedTo"] = string.Join(",", version.DeployedTo ?? new List<string>()),
            ["behind"] = string.Join(";", (version.Behind ?? new List<BehindReleaseDto>())
                .Select(b => $"{Show(b.ReleaseVersion)}=" + string.Join("+", (b.Changes ?? new List<ReleasedChangeDto>())
                    .Select(c => $"{Show(c.Type)}/{Show(c.Key)}:{Wire(c.Change)}")))),
        });
    }

    /// <summary>
    /// Renames a draft; only the name is sent, to its unchanged address, with <c>config.ifMatch</c>
    /// as <c>If-Match</c> when present. Reports the new <c>ETag</c> exactly as received.
    /// </summary>
    private static void UpdateCatalogDraft(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        ApiResponse<CatalogVersionDto> response;
        try
        {
            response = new CatalogsApi(http, apiBase).UpdateCatalogDraftWithHttpInfo(
                Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"),
                new UpdateCatalogDraftRequest(name: Str(config, "name")),
                ifMatch: OptStr(config, "ifMatch"));
        }
        catch (ProblemDetailException e)
        {
            ReportProblem(baseUrl, e);
            return;
        }
        var version = response.Data;

        Report(baseUrl, new Dictionary<string, object>
        {
            ["releaseVersion"] = Show(version.ReleaseVersion),
            ["versionId"] = Show(version.VersionId),
            ["name"] = Show(version.Name),
            ["status"] = Show(Wire(version.Status)),
            ["base"] = Show(version.Base),
            ["etag"] = Header(response, "ETag"),
        });
    }

    /// <summary>Sets a draft's default theme and presets; no name is sent, and an unset qualifier stays off the wire.</summary>
    private static void SetCatalogDraftThemeSettings(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var presets = config.GetProperty("presets").EnumerateArray()
            .Select(p => new CatalogPresetDto(key: Str(p, "key"), name: Str(p, "name"), description: OptStr(p, "description")!))
            .ToList();
        var version = new CatalogsApi(http, apiBase).UpdateCatalogDraft(
            Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"),
            new UpdateCatalogDraftRequest(
                defaultTheme: new CatalogThemeReference(key: Str(config, "defaultThemeKey"), catalogKey: OptStr(config, "defaultThemeCatalogKey")!),
                presets: presets));

        var parsed = version.Presets ?? new List<CatalogPresetDto>();
        Report(baseUrl, new Dictionary<string, object>
        {
            ["versionId"] = Show(version.VersionId),
            ["name"] = Show(version.Name),
            ["defaultThemeKey"] = Show(version.DefaultTheme?.Key),
            ["defaultThemeCatalogKey"] = Show(version.DefaultTheme?.CatalogKey),
            ["presetKeys"] = string.Join(",", parsed.Select(p => Show(p.Key))),
            ["presetNames"] = string.Join("|", parsed.Select(p => Show(p.Name))),
            ["presetDescriptions"] = string.Join("|", parsed.Select(p => Show(p.Description))),
        });
    }

    /// <summary>The number a draft would be released as, and every reason, in order.</summary>
    private static void PreviewCatalogDraftRelease(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var preview = new CatalogsApi(http, apiBase).PreviewCatalogDraftRelease(
            Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"));

        Report(baseUrl, new Dictionary<string, object>
        {
            ["base"] = Show(preview.Base),
            ["changeKind"] = preview.ChangeKind is { } kind ? Wire(kind) : "null",
            ["reasons"] = string.Join("|", preview.Reasons ?? new List<string>()),
            ["needed"] = Show(preview.Needed),
            ["suggested"] = Show(preview.Suggested),
        });
    }

    private static void DeleteCatalogDraft(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        new CatalogsApi(http, apiBase).DeleteCatalogDraft(
            Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"));
    }

    /// <summary>
    /// Releases a draft; each member is left unset when the scenario does not name it. A refusal is
    /// reported as its problem and the dependency drafts it lists.
    /// </summary>
    private static void ReleaseCatalogDraft(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        ReleaseCatalogDraftResponse response;
        try
        {
            response = new CatalogsApi(http, apiBase).ReleaseCatalogDraft(
                Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"),
                new ReleaseCatalogDraftRequest(
                    releaseVersion: OptStr(config, "releaseVersion")!,
                    notes: OptStr(config, "notes")!,
                    acknowledgedBehind: OptStrList(config, "acknowledgedBehind")!,
                    // The generated model has a plain bool with the contract's default, false, and
                    // writes it: an unset flag goes out as `false`, which means the same.
                    releaseDependencies: OptBool(config, "releaseDependencies") ?? false));
        }
        catch (ProblemDetailException e)
        {
            Report(baseUrl, new Dictionary<string, object>
            {
                ["problemTypeSlug"] = e.TypeSlug ?? "<null>",
                ["problemStatus"] = (int)e.StatusCode,
                ["problemUnreleasedDependencies"] = string.Join("|", e.UnreleasedDependencies.Select(d =>
                    $"{d.Registry}/{d.Namespace}/{d.CatalogKey}#{d.VersionId}={d.DraftName}")),
            });
            return;
        }

        Report(baseUrl, new Dictionary<string, object>
        {
            ["releaseVersion"] = Show(response.CatalogVersion?.ReleaseVersion),
            ["status"] = Show(response.CatalogVersion == null ? null : Wire(response.CatalogVersion.Status)),
            ["versionId"] = Show(response.CatalogVersion?.VersionId),
            ["draftsBehind"] = string.Join("|", response.DraftsBehind ?? new List<string>()),
            ["releasedDependencies"] = string.Join(",", (response.ReleasedDependencies ?? new List<ReleasedDependencyDto>())
                .Select(d => $"{Show(d.Registry)}/{Show(d.Namespace)}/{Show(d.CatalogKey)}@{Show(d.ReleaseVersion)}")),
        });
    }

    /// <summary>A boolean as JSON spells it; <c>ToString()</c> of one is <c>True</c>.</summary>
    private static string Bool(bool value) => value ? "true" : "false";

    /// <summary>
    /// An instant in the ISO 8601 form the contract carries (<c>2026-10-01T12:00:00Z</c>), rather
    /// than the culture's own <c>ToString()</c>; fractional seconds only when there are any.
    /// </summary>
    private static string? Iso(DateTimeOffset? value) =>
        value?.ToUniversalTime().ToString("yyyy-MM-dd'T'HH:mm:ss.FFFFFFF'Z'", System.Globalization.CultureInfo.InvariantCulture);

    /// <summary>A generated enum constant as its wire value, through the generator's own converter.</summary>
    private static string Wire<T>(T value) where T : struct, Enum =>
        Newtonsoft.Json.JsonConvert.SerializeObject(value).Trim('"');

    /// <summary>A wire value as the generated enum constant, through the generator's own converter.</summary>
    private static T Parse<T>(string wire) where T : struct, Enum =>
        Newtonsoft.Json.JsonConvert.DeserializeObject<T>(Newtonsoft.Json.JsonConvert.SerializeObject(wire));

    /// <summary>
    /// Deploys one catalog version, a release by its number or a draft by its <c>versionId</c>. The
    /// registry and the version go into the path exactly as the scenario gives them,
    /// <c>~</c> for the server's default registry or a name in full; the request has no body.
    /// <c>includeDependencies</c> and <c>replace</c> are passed only when the scenario names them, so
    /// they stay off the query otherwise. A refusal is reported as its problem and the dependency
    /// releases it lists.
    /// </summary>
    private static void DeployCatalogVersion(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        DeploymentDto deployment;
        try
        {
            deployment = new EnvironmentsApi(http, apiBase).DeployCatalogVersion(
                Str(config, "tenantKey"), Str(config, "environmentKey"),
                Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"),
                includeDependencies: OptBool(config, "includeDependencies"),
                replace: OptBool(config, "replace"));
        }
        catch (ProblemDetailException e)
        {
            Report(baseUrl, new Dictionary<string, object>
            {
                ["problemTypeSlug"] = e.TypeSlug ?? "<null>",
                ["problemStatus"] = (int)e.StatusCode,
                ["problemMissingDependencies"] = string.Join(",", e.MissingDependencies.Select(d =>
                    $"{d.Registry}/{d.Namespace}/{d.CatalogKey}@{d.ReleaseVersion}:{d.Reason}")),
            });
            return;
        }

        Report(baseUrl, new Dictionary<string, object>
        {
            ["registry"] = Show(deployment.Registry),
            ["namespace"] = Show(deployment.Namespace),
            ["catalogKey"] = Show(deployment.CatalogKey),
            ["versionId"] = Show(deployment.VersionId),
            ["kind"] = Wire(deployment.Kind),
            ["releaseVersion"] = Show(deployment.ReleaseVersion),
            ["draftName"] = Show(deployment.DraftName),
            ["lastUsedAt"] = Show(Iso(deployment.LastUsedAt)),
            ["documentsRequested"] = Show(deployment.DocumentsRequested),
        });
    }

    private static void UndeployCatalogVersion(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        new EnvironmentsApi(http, apiBase).UndeployCatalogVersion(
            Str(config, "tenantKey"), Str(config, "environmentKey"),
            Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"));
    }

    private static void ReportBytes(string baseUrl, FileParameter file)
    {
        using var buffer = new MemoryStream();
        file.Content.CopyTo(buffer);
        var bytes = buffer.ToArray();

        Report(baseUrl, new Dictionary<string, object>
        {
            ["byteLength"] = bytes.Length,
            ["sha256"] = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(bytes)).ToLowerInvariant(),
        });
    }

    // --- Client assembly ---

    /// <summary>
    /// Builds the client the way the README tells consumers to. The API base path is part of the
    /// contract's servers entry, so the driver appends it rather than the harness serving the API
    /// at the root; the generated APIs take it separately from the HttpClient.
    /// </summary>
    private static (HttpClient Http, string ApiBase) Client(string baseUrl, JsonElement config)
    {
        var apiBase = $"{baseUrl}/api";

        var identity = ClientIdentity.Builder().NodeId(Str(config, "nodeId"));
        if (config.TryGetProperty("products", out var products))
        {
            foreach (var product in products.EnumerateArray())
            {
                identity.Product(product.GetProperty("name").GetString()!, product.GetProperty("version").GetString()!);
            }
        }

        var builder = new EpistolaHttpClientBuilder()
            .BaseUrl(apiBase)
            .Identity(identity.Build())
            .InstallProblemDetailHandler();

        switch (Str(config, "auth", "none"))
        {
            case "api-key":
                builder.ApiKey(Str(config, "apiKey"));
                break;
            case "jwt":
                builder.JwtSigner(JwtSigner.Builder()
                    .ConsumerId(Str(config, "consumerId"))
                    .PrivateKey(JwtSigner.ParsePrivateKeyPem(Str(config, "privateKeyPem")))
                    .TokenLifetime(TimeSpan.FromSeconds(Int(config, "tokenLifetimeSeconds")))
                    .Build());
                break;
        }

        return (builder.Build(), apiBase);
    }

    // --- Control plane ---

    private static JsonElement Get(string url)
    {
        var body = Control.GetStringAsync(url).GetAwaiter().GetResult();
        return JsonDocument.Parse(body).RootElement.Clone();
    }

    private static void Post(string url, string body)
    {
        Control.PostAsync(url, new StringContent(body, Encoding.UTF8, "application/json"))
            .GetAwaiter().GetResult();
    }

    private static void Report(string baseUrl, Dictionary<string, object> values) =>
        Post($"{baseUrl}/__conformance/report", JsonSerializer.Serialize(values));

    private static void Done(string baseUrl, string? error) =>
        Post($"{baseUrl}/__conformance/done",
            error == null ? "{}" : JsonSerializer.Serialize(new Dictionary<string, string> { ["error"] = error }));

    // --- Config helpers ---

    /// <summary>
    /// Replaces a variant's content with a one-node template model, sending <c>config.ifMatch</c>
    /// as <c>If-Match</c> when present and no <c>If-Match</c> otherwise.
    /// </summary>
    private static void UpdateVariantContent(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        VariantContentDto content;
        try
        {
            content = new VariantsApi(http, apiBase).UpdateVariantContent(
                Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"),
                Str(config, "catalogKey"), Str(config, "catalogVersion"),
                Str(config, "templateKey"), Str(config, "variantKey"),
                new UpdateVariantContentRequest(new TemplateDocument(
                    modelVersion: TemplateDocument.ModelVersionEnum.NUMBER_1,
                    root: "root",
                    nodes: new Dictionary<string, Node> { ["root"] = new Node(id: "root", type: "root", slots: new List<string>()) },
                    slots: new Dictionary<string, Slot>(),
                    themeRef: new ThemeRef(type: ThemeRef.TypeEnum.Inherit))),
                ifMatch: OptStr(config, "ifMatch"));
        }
        catch (ProblemDetailException e)
        {
            Report(baseUrl, new Dictionary<string, object>
            {
                ["problemTypeSlug"] = e.TypeSlug ?? "<null>",
                ["problemStatus"] = (int)e.StatusCode,
            });
            return;
        }

        Report(baseUrl, new Dictionary<string, object> { ["variantKey"] = Show(content.VariantKey) });
    }

    // --- Content, ready marks and changes ---

    private static TemplateDocument OneNodeModel() => new(
        modelVersion: TemplateDocument.ModelVersionEnum.NUMBER_1,
        root: "root",
        nodes: new Dictionary<string, Node> { ["root"] = new Node(id: "root", type: "root", slots: new List<string>()) },
        slots: new Dictionary<string, Slot>(),
        themeRef: new ThemeRef(type: ThemeRef.TypeEnum.Inherit));

    /// <summary>The <c>type</c> member of a free-form JSON Schema object, which the generated models read as a JObject.</summary>
    private static string SchemaType(object? schema) =>
        schema is Newtonsoft.Json.Linq.JObject json ? Show(json["type"]?.ToString()) : "null";

    /// <summary>A response header as received, the first value when there are several.</summary>
    private static string Header<T>(ApiResponse<T> response, string name) =>
        response.Headers
            .Where(header => string.Equals(header.Key, name, StringComparison.OrdinalIgnoreCase) && header.Value.Count > 0)
            .Select(header => header.Value[0])
            .FirstOrDefault() ?? "null";

    /// <summary>
    /// Reads a variant's content through <c>…WithHttpInfo</c>, which is how a caller reaches the
    /// response headers: the <c>ETag</c> is reported exactly as received, quotes included.
    /// </summary>
    private static void GetVariantContent(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var response = new VariantsApi(http, apiBase).GetVariantContentWithHttpInfo(
            Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"),
            Str(config, "templateKey"), Str(config, "variantKey"));
        var content = response.Data;

        Report(baseUrl, new Dictionary<string, object>
        {
            ["variantKey"] = Show(content.VariantKey),
            ["templateModelRoot"] = Show(content.TemplateModel?.Root),
            ["status"] = Wire(content.Status),
            ["lastModified"] = Show(Iso(content.LastModified)),
            ["readyAt"] = Show(Iso(content.ReadyAt)),
            ["etag"] = Header(response, "ETag"),
        });
    }

    private static void MarkVariantReady(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var content = new VariantsApi(http, apiBase).MarkVariantReady(
            Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"),
            Str(config, "templateKey"), Str(config, "variantKey"));

        Report(baseUrl, new Dictionary<string, object>
        {
            ["variantKey"] = Show(content.VariantKey),
            ["status"] = Wire(content.Status),
            ["lastModified"] = Show(Iso(content.LastModified)),
            ["readyAt"] = Show(Iso(content.ReadyAt)),
        });
    }

    private static void MarkTemplateReady(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var template = new TemplatesApi(http, apiBase).MarkTemplateReady(
            Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"),
            Str(config, "templateKey"));
        var variants = template.Variants ?? new List<VariantSummaryDto>();

        Report(baseUrl, new Dictionary<string, object>
        {
            ["key"] = Show(template.Key),
            ["name"] = Show(template.Name),
            ["variantKeys"] = string.Join(",", variants.Select(v => Show(v.Key))),
            ["variantStatuses"] = string.Join(",", variants.Select(v => Wire(v.Status))),
        });
    }

    /// <summary>Reads a stencil's content through <c>…WithHttpInfo</c>, reporting the <c>ETag</c> exactly as received.</summary>
    private static void GetStencilContent(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var response = new StencilsApi(http, apiBase).GetStencilContentWithHttpInfo(
            Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"),
            Str(config, "stencilKey"));
        var content = response.Data;

        Report(baseUrl, new Dictionary<string, object>
        {
            ["stencilKey"] = Show(content.StencilKey),
            ["contentRoot"] = Show(content.Content?.Root),
            ["parameterSchemaType"] = SchemaType(content.ParameterSchema),
            ["status"] = Wire(content.Status),
            ["lastModified"] = Show(Iso(content.LastModified)),
            ["readyAt"] = Show(Iso(content.ReadyAt)),
            ["etag"] = Header(response, "ETag"),
        });
    }

    /// <summary>
    /// Replaces a stencil's content with a one-node template model and <c>config.parameterSchema</c>,
    /// sending <c>config.ifMatch</c> as <c>If-Match</c>, and reports the new <c>ETag</c> the update
    /// answered with.
    /// </summary>
    private static void UpdateStencilContent(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var response = new StencilsApi(http, apiBase).UpdateStencilContentWithHttpInfo(
            Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"),
            Str(config, "stencilKey"),
            new UpdateStencilContentRequest(
                content: OneNodeModel(),
                // Parsed with Newtonsoft, which the generated models serialize through (see GenerateDocument).
                parameterSchema: Newtonsoft.Json.JsonConvert.DeserializeObject(config.GetProperty("parameterSchema").GetRawText())!),
            ifMatch: OptStr(config, "ifMatch"));
        var content = response.Data;

        Report(baseUrl, new Dictionary<string, object>
        {
            ["stencilKey"] = Show(content.StencilKey),
            ["status"] = Wire(content.Status),
            ["readyAt"] = Show(Iso(content.ReadyAt)),
            ["etag"] = Header(response, "ETag"),
        });
    }

    private static void MarkStencilReady(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var response = new StencilsApi(http, apiBase).MarkStencilReady(
            Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"),
            Str(config, "stencilKey"));
        var notFitting = response.NotFitting ?? new List<StencilUsageDto>();

        Report(baseUrl, new Dictionary<string, object>
        {
            ["stencilKey"] = Show(response.Stencil?.StencilKey),
            ["status"] = response.Stencil == null ? "null" : Wire(response.Stencil.Status),
            ["readyAt"] = Show(Iso(response.Stencil?.ReadyAt)),
            ["parameterSchemaType"] = SchemaType(response.Stencil?.ParameterSchema),
            ["notFitting"] = string.Join(",", notFitting.Select(u => $"{Show(u.TemplateKey)}/{Show(u.VariantKey)}:{Bool(u.Fits)}")),
            ["notFittingProblems"] = string.Join(";", notFitting.Select(u => string.Join("|", u.Problems ?? new List<string>()))),
        });
    }

    private static void ListStencilUsage(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var response = new StencilsApi(http, apiBase).ListStencilUsage(
            Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"),
            Str(config, "stencilKey"),
            page: Int(config, "page"),
            size: Int(config, "size"));
        var items = response.Items ?? new List<StencilUsageDto>();

        Report(baseUrl, new Dictionary<string, object>
        {
            ["templateKeys"] = string.Join(",", items.Select(u => Show(u.TemplateKey))),
            ["templateNames"] = string.Join("|", items.Select(u => Show(u.TemplateName))),
            ["variantKeys"] = string.Join(",", items.Select(u => Show(u.VariantKey))),
            ["fits"] = string.Join(",", items.Select(u => Bool(u.Fits))),
            ["problems"] = string.Join(";", items.Select(u => string.Join("|", u.Problems ?? new List<string>()))),
            ["pageNumber"] = Show(response.Page?.Number),
            ["total"] = Show(response.Page?.Total),
        });
    }

    private static void GetCatalogChanges(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        ReportChanges(baseUrl, new CatalogsApi(http, apiBase).GetCatalogChanges(
            Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion")));
    }

    private static void MarkCatalogReady(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        ReportChanges(baseUrl, new CatalogsApi(http, apiBase).MarkCatalogReady(
            Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion")));
    }

    private static void ReportChanges(string baseUrl, CatalogChangesDto changes) =>
        Report(baseUrl, new Dictionary<string, object>
        {
            ["base"] = Show(changes.Base),
            ["releasable"] = Bool(changes.Releasable),
            ["dependenciesChanged"] = Bool(changes.DependenciesChanged),
            ["changes"] = string.Join(",", (changes.Changes ?? new List<ResourceChangeDto>())
                .Select(c => $"{Show(c.Type)}/{Show(c.Key)}:{Wire(c.Change)}")),
        });

    private static void ListEnvironmentDeployments(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var items = new EnvironmentsApi(http, apiBase)
            .ListEnvironmentDeployments(Str(config, "tenantKey"), Str(config, "environmentKey")).Items ?? new List<DeploymentDto>();

        Report(baseUrl, new Dictionary<string, object>
        {
            ["registries"] = string.Join(",", items.Select(d => Show(d.Registry))),
            ["catalogKeys"] = string.Join(",", items.Select(d => Show(d.CatalogKey))),
            ["versionIds"] = string.Join(",", items.Select(d => Show(d.VersionId))),
            ["kinds"] = string.Join(",", items.Select(d => Wire(d.Kind))),
            ["releaseVersions"] = string.Join(",", items.Select(d => Show(d.ReleaseVersion))),
            ["draftNames"] = string.Join("|", items.Select(d => Show(d.DraftName))),
            ["deployedAt"] = string.Join(",", items.Select(d => Show(Iso(d.DeployedAt)))),
            ["lastUsedAt"] = string.Join(",", items.Select(d => Show(Iso(d.LastUsedAt)))),
            ["documentsRequested"] = string.Join(",", items.Select(d => d.DocumentsRequested.ToString(System.Globalization.CultureInfo.InvariantCulture))),
        });
    }

    private static void ListDeploymentHistory(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var response = new EnvironmentsApi(http, apiBase).ListDeploymentHistory(
            Str(config, "tenantKey"), Str(config, "environmentKey"),
            page: Int(config, "page"),
            size: Int(config, "size"));
        var items = response.Items ?? new List<DeploymentEventDto>();

        Report(baseUrl, new Dictionary<string, object>
        {
            ["catalogKeys"] = string.Join(",", items.Select(e => Show(e.CatalogKey))),
            ["actions"] = string.Join(",", items.Select(e => Wire(e.Action))),
            ["versionIds"] = string.Join(",", items.Select(e => Show(e.VersionId))),
            ["kinds"] = string.Join(",", items.Select(e => Wire(e.Kind))),
            ["releaseVersions"] = string.Join(",", items.Select(e => Show(e.ReleaseVersion))),
            ["draftNames"] = string.Join("|", items.Select(e => Show(e.DraftName))),
            ["changedAt"] = string.Join(",", items.Select(e => Show(Iso(e.ChangedAt)))),
            ["changedBy"] = string.Join("|", items.Select(e => Show(e.ChangedBy))),
            ["total"] = Show(response.Page?.Total),
        });
    }

    /// <summary>Creates an environment; <c>production</c> keeps its default when the scenario does not name it.</summary>
    private static void CreateEnvironment(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var response = new EnvironmentsApi(http, apiBase).CreateEnvironmentWithHttpInfo(
            Str(config, "tenantKey"),
            new CreateEnvironmentRequest(
                key: Str(config, "key"),
                name: Str(config, "name"),
                production: OptBool(config, "production") ?? false));
        ReportEnvironment(baseUrl, response.Data, "location", Header(response, "Location"));
    }

    /// <summary>
    /// A partial update: each member is left unset when the scenario does not name it. A refusal is
    /// reported by its problem.
    /// </summary>
    private static void UpdateEnvironment(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        ApiResponse<EnvironmentDto> response;
        try
        {
            response = new EnvironmentsApi(http, apiBase).UpdateEnvironmentWithHttpInfo(
                Str(config, "tenantKey"), Str(config, "environmentKey"),
                new UpdateEnvironmentRequest(
                    name: OptStr(config, "name")!,
                    production: OptBool(config, "production")),
                ifMatch: OptStr(config, "ifMatch"));
        }
        catch (ProblemDetailException e)
        {
            ReportProblem(baseUrl, e);
            return;
        }
        ReportEnvironment(baseUrl, response.Data, "etag", Header(response, "ETag"));
    }

    /// <summary>The environment as parsed, plus one response header (<c>location</c> or <c>etag</c>) exactly as received.</summary>
    private static void ReportEnvironment(string baseUrl, EnvironmentDto environment, string headerKey, string headerValue) =>
        Report(baseUrl, new Dictionary<string, object>
        {
            ["key"] = Show(environment.Key),
            ["name"] = Show(environment.Name),
            ["production"] = Bool(environment.Production),
            [headerKey] = headerValue,
        });

    private static string Str(JsonElement config, string name, string? fallback = null) =>
        config.TryGetProperty(name, out var value) ? value.GetString()! : fallback
            ?? throw new ArgumentException($"scenario config has no {name}");

    private static string? OptStr(JsonElement config, string name) =>
        config.TryGetProperty(name, out var value) && value.ValueKind != JsonValueKind.Null ? value.GetString() : null;

    // Attribute criteria, null when the scenario names none so they stay off the wire.
    private static List<VariantSelectionAttribute>? OptAttributes(JsonElement config) =>
        config.TryGetProperty("attributes", out var value) && value.ValueKind != JsonValueKind.Null
            ? value.EnumerateArray().Select(attribute => new VariantSelectionAttribute(
                registry: OptStr(attribute, "registry")!,
                varNamespace: OptStr(attribute, "namespace")!,
                catalogKey: OptStr(attribute, "catalogKey")!,
                key: Str(attribute, "key"),
                value: Str(attribute, "value"),
                required: !attribute.TryGetProperty("required", out var required) || required.GetBoolean())).ToList()
            : null;

    private static List<string>? OptStrList(JsonElement config, string name) =>
        config.TryGetProperty(name, out var value) && value.ValueKind != JsonValueKind.Null
            ? value.EnumerateArray().Select(v => v.GetString()!).ToList()
            : null;

    private static bool? OptBool(JsonElement config, string name) =>
        config.TryGetProperty(name, out var value) && value.ValueKind != JsonValueKind.Null ? value.GetBoolean() : null;

    private static int Int(JsonElement config, string name, int? fallback = null) =>
        config.TryGetProperty(name, out var value) ? value.GetInt32() : fallback
            ?? throw new ArgumentException($"scenario config has no {name}");
}
