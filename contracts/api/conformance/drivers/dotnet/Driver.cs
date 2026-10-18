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
                case "list-images": ListImages(baseUrl, config); break;
                case "upload-image": UploadImage(baseUrl, config); break;
                case "download-image": DownloadImage(baseUrl, config); break;
                case "delete-image": DeleteImage(baseUrl, config); break;
                case "list-catalog-dependencies": ListCatalogDependencies(baseUrl, config); break;
                case "update-catalog-dependencies": UpdateCatalogDependencies(baseUrl, config); break;
                case "create-catalog-draft": CreateCatalogDraft(baseUrl, config); break;
                case "list-catalog-versions": ListCatalogVersions(baseUrl, config); break;
                case "get-catalog-version": GetCatalogVersion(baseUrl, config); break;
                case "rename-catalog-draft": RenameCatalogDraft(baseUrl, config); break;
                case "preview-catalog-draft-release": PreviewCatalogDraftRelease(baseUrl, config); break;
                case "delete-catalog-draft": DeleteCatalogDraft(baseUrl, config); break;
                case "release-catalog-draft": ReleaseCatalogDraft(baseUrl, config); break;
                case "promote-catalog-draft": PromoteCatalogDraft(baseUrl, config); break;
                case "deploy-release": DeployRelease(baseUrl, config); break;
                case "undeploy-release": UndeployRelease(baseUrl, config); break;
                case "set-environment-override": SetEnvironmentOverride(baseUrl, config); break;
                case "list-environment-overrides": ListEnvironmentOverrides(baseUrl, config); break;
                case "clear-environment-override": ClearEnvironmentOverride(baseUrl, config); break;
                case "create-environment": CreateEnvironment(baseUrl, config); break;
                case "update-environment": UpdateEnvironment(baseUrl, config); break;
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
        new GenerationApi(http, apiBase).GenerateDocument(
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
                varVersion: OptStr(config, "version"),
                environmentKey: OptStr(config, "environmentKey"),
                // Parsed with Newtonsoft, not System.Text.Json: the generated models serialize
                // through Newtonsoft, which does not know what to do with a JsonElement and emits
                // an empty object for it.
                data: Newtonsoft.Json.JsonConvert.DeserializeObject(config.GetProperty("data").GetRawText()),
                correlationId: OptStr(config, "correlationId"),
                routingKey: OptStr(config, "routingKey")));
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
        new ConsumersApi(http, apiBase).UpdateConsumer(
            Str(config, "tenantKey"),
            Str(config, "consumerId"),
            new UpdateConsumerRequest(name: Str(config, "name")));
    }

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
    /// Declares the catalog's dependencies as the scenario lists them, and reports what came back.
    /// A null <c>resolvedVersion</c> is the state that blocks a release, so it is reported as
    /// <c>null</c> rather than lost.
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
                varVersion: Str(d, "version")))
            .ToList();
        ReportDependencies(baseUrl, new CatalogsApi(http, apiBase).UpdateCatalogDependencies(
            Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"),
            new UpdateCatalogDependenciesRequest(dependencies: dependencies)));
    }

    private static void ReportDependencies(string baseUrl, CatalogDependencyListResponse response)
    {
        var items = response.Items;
        Report(baseUrl, new Dictionary<string, object>
        {
            ["registries"] = string.Join(",", items.Select(d => Show(d.Registry))),
            ["namespaces"] = string.Join(",", items.Select(d => Show(d.Namespace))),
            ["catalogKeys"] = string.Join(",", items.Select(d => Show(d.CatalogKey))),
            ["versions"] = string.Join(",", items.Select(d => Show(d.VarVersion))),
            ["resolvedVersions"] = string.Join(",", items.Select(d => Show(d.ResolvedVersion))),
        });
    }

    // --- Catalog versions ---

    /// <summary>
    /// Starts long-term work. <c>from</c> is optional and left unset when the scenario does not name
    /// it, so it stays off the wire rather than going out as null.
    /// </summary>
    private static void CreateCatalogDraft(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var version = new CatalogsApi(http, apiBase).CreateCatalogDraft(
            Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"),
            new CreateCatalogDraftRequest(name: Str(config, "name"), from: OptStr(config, "from")!));

        Report(baseUrl, new Dictionary<string, object>
        {
            ["version"] = Show(version.VarVersion),
            ["draftId"] = Show(version.DraftId),
            ["isNext"] = Bool(version.IsNext),
            ["name"] = Show(version.Name),
            ["status"] = Show(Wire(version.Status)),
            ["base"] = Show(version.Base),
            ["createdFrom"] = Show(version.CreatedFrom),
            ["releasedAt"] = Show(Iso(version.ReleasedAt)),
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
            ["versions"] = string.Join(",", items.Select(v => Show(v.VarVersion))),
            ["draftIds"] = string.Join(",", items.Select(v => Show(v.DraftId))),
            ["isNext"] = string.Join(",", items.Select(v => Bool(v.IsNext))),
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
            ["version"] = Show(version.VarVersion),
            ["draftId"] = Show(version.DraftId),
            ["isNext"] = Bool(version.IsNext),
            ["name"] = Show(version.Name),
            ["status"] = Show(Wire(version.Status)),
            ["releasedAt"] = Show(Iso(version.ReleasedAt)),
            ["fingerprint"] = Show(version.Fingerprint),
            ["dependencies"] = string.Join(",", (version.Dependencies ?? new List<ReleaseDependencyDto>())
                .Select(d => $"{Show(d.Registry)}/{Show(d.Namespace)}/{Show(d.CatalogKey)}@{Show(d.ReleaseVersion)}")),
            ["deployedTo"] = string.Join(",", version.DeployedTo ?? new List<string>()),
            ["behind"] = string.Join(";", (version.Behind ?? new List<BehindReleaseDto>())
                .Select(b => $"{Show(b.VarVersion)}=" + string.Join("+", (b.Changes ?? new List<ReleasedChangeDto>())
                    .Select(c => $"{Show(c.Type)}/{Show(c.Key)}:{Wire(c.Change)}")))),
        });
    }

    /// <summary>Renames long-term work; only the name is sent, to its unchanged address.</summary>
    private static void RenameCatalogDraft(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var version = new CatalogsApi(http, apiBase).RenameCatalogDraft(
            Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"),
            new RenameCatalogDraftRequest(name: Str(config, "name")));

        Report(baseUrl, new Dictionary<string, object>
        {
            ["version"] = Show(version.VarVersion),
            ["draftId"] = Show(version.DraftId),
            ["isNext"] = Bool(version.IsNext),
            ["name"] = Show(version.Name),
            ["status"] = Show(Wire(version.Status)),
            ["base"] = Show(version.Base),
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
            ["latest"] = Show(preview.Latest),
            ["changeKind"] = preview.ChangeKind is { } kind ? Wire(kind) : "null",
            ["reasons"] = string.Join("|", preview.Reasons ?? new List<string>()),
            ["suggested"] = Show(preview.Suggested),
        });
    }

    private static void DeleteCatalogDraft(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        new CatalogsApi(http, apiBase).DeleteCatalogDraft(
            Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"));
    }

    /// <summary>Releases the next version; each member is left unset when the scenario does not name it.</summary>
    private static void ReleaseCatalogDraft(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var response = new CatalogsApi(http, apiBase).ReleaseCatalogDraft(
            Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"),
            new ReleaseCatalogDraftRequest(
                varVersion: OptStr(config, "version")!,
                notes: OptStr(config, "notes")!,
                acknowledgedBehind: OptStrList(config, "acknowledgedBehind")!));

        Report(baseUrl, new Dictionary<string, object>
        {
            ["version"] = Show(response.CatalogVersion?.VarVersion),
            ["status"] = Show(response.CatalogVersion == null ? null : Wire(response.CatalogVersion.Status)),
            ["draftId"] = Show(response.CatalogVersion?.DraftId),
            ["isNext"] = response.CatalogVersion == null ? "null" : Bool(response.CatalogVersion.IsNext),
            ["draftsBehind"] = string.Join("|", response.DraftsBehind ?? new List<string>()),
        });
    }

    /// <summary>Makes long-term work the next version; each member is left unset when the scenario does not name it.</summary>
    private static void PromoteCatalogDraft(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var version = new CatalogsApi(http, apiBase).PromoteCatalogDraft(
            Str(config, "tenantKey"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"),
            new PromoteCatalogDraftRequest(
                keepPreviousAs: OptStr(config, "keepPreviousAs")!,
                acknowledgedBehind: OptStrList(config, "acknowledgedBehind")!));

        Report(baseUrl, new Dictionary<string, object>
        {
            ["version"] = Show(version.VarVersion),
            ["draftId"] = Show(version.DraftId),
            ["isNext"] = Bool(version.IsNext),
            ["name"] = Show(version.Name),
            ["status"] = Show(Wire(version.Status)),
            ["base"] = Show(version.Base),
            ["behind"] = (version.Behind ?? new List<BehindReleaseDto>()).Count.ToString(System.Globalization.CultureInfo.InvariantCulture),
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
    /// Deploys one release. The registry goes into the path exactly as the scenario gives it,
    /// <c>~</c> for the server's default registry or a name in full; the request has no body.
    /// <c>includeDependencies</c> is passed only when the scenario names it, so it stays off the
    /// query otherwise. A refusal is reported as its problem and the dependency releases it lists.
    /// </summary>
    private static void DeployRelease(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        DeploymentDto deployment;
        try
        {
            deployment = new EnvironmentsApi(http, apiBase).DeployRelease(
                Str(config, "tenantKey"), Str(config, "environmentKey"),
                Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "releaseVersion"),
                includeDependencies: OptBool(config, "includeDependencies"));
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
            ["releaseVersion"] = Show(deployment.ReleaseVersion),
        });
    }

    private static void UndeployRelease(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        new EnvironmentsApi(http, apiBase).UndeployRelease(
            Str(config, "tenantKey"), Str(config, "environmentKey"),
            Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "releaseVersion"));
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
    /// Overrides the version of a catalog an environment uses; <c>releaseVersion</c> and
    /// <c>catalogVersion</c> are left unset when the scenario does not name them. A refusal is
    /// reported by its problem.
    /// </summary>
    private static void SetEnvironmentOverride(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        EnvironmentOverrideDto environmentOverride;
        try
        {
            environmentOverride = new EnvironmentsApi(http, apiBase).SetEnvironmentOverride(
                Str(config, "tenantKey"), Str(config, "environmentKey"),
                Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"),
                new SetEnvironmentOverrideRequest(
                    releaseVersion: OptStr(config, "releaseVersion")!,
                    catalogVersion: OptStr(config, "catalogVersion")!));
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
            ["environmentKey"] = Show(environmentOverride.EnvironmentKey),
            ["registry"] = Show(environmentOverride.Registry),
            ["namespace"] = Show(environmentOverride.Namespace),
            ["catalogKey"] = Show(environmentOverride.CatalogKey),
            ["releaseVersion"] = Show(environmentOverride.ReleaseVersion),
            ["draftId"] = Show(environmentOverride.DraftId),
            ["draftLabel"] = Show(environmentOverride.DraftLabel),
            ["setAt"] = Show(Iso(environmentOverride.SetAt)),
        });
    }

    private static void ListEnvironmentOverrides(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var overrides = new EnvironmentsApi(http, apiBase)
            .ListEnvironmentOverrides(Str(config, "tenantKey"), Str(config, "environmentKey")).Items;
        Report(baseUrl, new Dictionary<string, object>
        {
            ["catalogKeys"] = string.Join(",", overrides.Select(o => Show(o.CatalogKey))),
            ["releaseVersions"] = string.Join(",", overrides.Select(o => Show(o.ReleaseVersion))),
            ["draftIds"] = string.Join(",", overrides.Select(o => Show(o.DraftId))),
            ["draftLabels"] = string.Join("|", overrides.Select(o => Show(o.DraftLabel))),
        });
    }

    private static void ClearEnvironmentOverride(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        new EnvironmentsApi(http, apiBase).ClearEnvironmentOverride(
            Str(config, "tenantKey"), Str(config, "environmentKey"),
            Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"));
    }

    /// <summary>Creates an environment; <c>production</c> keeps its default when the scenario does not name it.</summary>
    private static void CreateEnvironment(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var environment = new EnvironmentsApi(http, apiBase).CreateEnvironment(
            Str(config, "tenantKey"),
            new CreateEnvironmentRequest(
                key: Str(config, "key"),
                name: Str(config, "name"),
                production: OptBool(config, "production") ?? false));
        ReportEnvironment(baseUrl, environment);
    }

    /// <summary>A partial update: each member is left unset when the scenario does not name it.</summary>
    private static void UpdateEnvironment(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var environment = new EnvironmentsApi(http, apiBase).UpdateEnvironment(
            Str(config, "tenantKey"), Str(config, "environmentKey"),
            new UpdateEnvironmentRequest(
                name: OptStr(config, "name")!,
                production: OptBool(config, "production")));
        ReportEnvironment(baseUrl, environment);
    }

    private static void ReportEnvironment(string baseUrl, EnvironmentDto environment) =>
        Report(baseUrl, new Dictionary<string, object>
        {
            ["key"] = Show(environment.Key),
            ["name"] = Show(environment.Name),
            ["production"] = Bool(environment.Production),
        });

    private static string Str(JsonElement config, string name, string? fallback = null) =>
        config.TryGetProperty(name, out var value) ? value.GetString()! : fallback
            ?? throw new ArgumentException($"scenario config has no {name}");

    private static string? OptStr(JsonElement config, string name) =>
        config.TryGetProperty(name, out var value) && value.ValueKind != JsonValueKind.Null ? value.GetString() : null;

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
