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
                case "create-catalog-version": CreateCatalogVersion(baseUrl, config); break;
                case "list-catalog-versions": ListCatalogVersions(baseUrl, config); break;
                case "get-catalog-version": GetCatalogVersion(baseUrl, config); break;
                case "renumber-catalog-version": RenumberCatalogVersion(baseUrl, config); break;
                case "preview-catalog-version-renumber": PreviewCatalogVersionRenumber(baseUrl, config); break;
                case "delete-catalog-version": DeleteCatalogVersion(baseUrl, config); break;
                case "release-catalog-version": ReleaseCatalogVersion(baseUrl, config); break;
                case "list-incoming-changes": ListIncomingChanges(baseUrl, config); break;
                case "resolve-incoming-change": ResolveIncomingChange(baseUrl, config); break;
                case "deploy-release": DeployRelease(baseUrl, config); break;
                case "undeploy-release": UndeployRelease(baseUrl, config); break;
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
    /// Reports what the client made of the last listing, <c>id</c> included while it is still served.
    /// </summary>
    private static void ListTemplates(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var api = new TemplatesApi(http, apiBase);
        TemplateListResponse? response = null;
        for (var i = 0; i < Int(config, "repeat", 1); i++)
        {
            response = api.ListTemplates(Str(config, "tenantId"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"));
        }
        var templates = response!.Items;

        Report(baseUrl, new Dictionary<string, object>
        {
            ["templateSlugs"] = string.Join(",", templates.Select(template => Show(template.Slug))),
        });
    }

    private static void Problem(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        try
        {
            new TemplatesApi(http, apiBase).ListTemplates(Str(config, "tenantId"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"));
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
            validator.Validate(Str(config, "tenantId"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "templateId"), data);
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
            .TenantId(Str(config, "tenantId"))
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
            Str(config, "tenantId"),
            new GenerateDocumentRequest(
                // Left unset when the scenario does not name it: omitted means the default registry, which
                // only the server knows, so the client must not fill one in.
                registry: OptStr(config, "registry")!,
                varNamespace: Str(config, "namespace"),
                catalogKey: Str(config, "catalogKey"),
                templateId: Str(config, "templateId"),
                // Optional, and left unset when the scenario does not name them: an unset optional
                // must stay off the wire (scenario 120), not go out as null.
                varVersion: OptStr(config, "version"),
                environmentId: OptStr(config, "environmentId"),
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
            Str(config, "tenantId"),
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
            .TenantId(Str(config, "tenantId"))
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
            Str(config, "tenantId"), Guid.Parse(Str(config, "documentId")));

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
            .ListImages(Str(config, "tenantId"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"), search: Str(config, "search"))
            .Items;

        Report(baseUrl, new Dictionary<string, object>
        {
            ["imageSlugs"] = string.Join(",", images.Select(image => image.Slug)),
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
            Str(config, "tenantId"),
            Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"),
            new FileParameter(Str(config, "filename"), Str(config, "fileContentType"), content),
            name: Str(config, "name"));

        Report(baseUrl, new Dictionary<string, object> { ["imageSlug"] = image.Slug, ["imageName"] = image.Name });
    }

    private static void DownloadImage(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        ReportBytes(baseUrl, new ImagesApi(http, apiBase).DownloadImageContent(
            Str(config, "tenantId"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"), Str(config, "imageSlug")));
    }

    private static void DeleteImage(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        new ImagesApi(http, apiBase).DeleteImage(
            Str(config, "tenantId"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"), Str(config, "imageSlug"),
            force: config.GetProperty("force").GetBoolean());
    }

    private static void ListCatalogDependencies(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        ReportDependencies(baseUrl, new CatalogsApi(http, apiBase).ListCatalogDependencies(
            Str(config, "tenantId"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion")));
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
            Str(config, "tenantId"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"),
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
    /// Opens a draft. <c>from</c> is optional and left unset when the scenario does not name it, so
    /// it stays off the wire rather than going out as null.
    /// </summary>
    private static void CreateCatalogVersion(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var version = new CatalogsApi(http, apiBase).CreateCatalogVersion(
            Str(config, "tenantId"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"),
            new CreateCatalogVersionRequest(varVersion: Str(config, "version"), from: OptStr(config, "from")!));

        Report(baseUrl, new Dictionary<string, object>
        {
            ["version"] = Show(version.VarVersion),
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
            Str(config, "tenantId"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"),
            status: status == null ? null : Parse<CatalogVersionStatus>(status)).Items;

        Report(baseUrl, new Dictionary<string, object>
        {
            ["versions"] = string.Join(",", items.Select(v => Show(v.VarVersion))),
            ["statuses"] = string.Join(",", items.Select(v => Show(Wire(v.Status)))),
            ["bases"] = string.Join(",", items.Select(v => Show(v.Base))),
            ["openConflicts"] = string.Join(",", items.Select(v => v.OpenConflicts)),
        });
    }

    private static void GetCatalogVersion(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var version = new CatalogsApi(http, apiBase).GetCatalogVersion(
            Str(config, "tenantId"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"));

        Report(baseUrl, new Dictionary<string, object>
        {
            ["version"] = Show(version.VarVersion),
            ["status"] = Show(Wire(version.Status)),
            ["releasedAt"] = Show(Iso(version.ReleasedAt)),
            ["dependencies"] = string.Join(",", (version.Dependencies ?? new List<ReleaseDependencyDto>())
                .Select(d => $"{Show(d.Registry)}/{Show(d.Namespace)}/{Show(d.CatalogKey)}@{Show(d.ReleaseVersion)}")),
            ["deployedTo"] = string.Join(",", version.DeployedTo ?? new List<string>()),
        });
    }

    /// <summary>
    /// Renumbers a draft. <c>acknowledgeWarnings</c> is passed only when the scenario names it; the
    /// generated model types it as a plain <c>bool</c> defaulting to the contract's <c>false</c>.
    /// </summary>
    private static void RenumberCatalogVersion(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var request = config.TryGetProperty("acknowledgeWarnings", out var acknowledge) && acknowledge.ValueKind != JsonValueKind.Null
            ? new RenumberCatalogVersionRequest(varVersion: Str(config, "version"), acknowledgeWarnings: acknowledge.GetBoolean())
            : new RenumberCatalogVersionRequest(varVersion: Str(config, "version"));
        var version = new CatalogsApi(http, apiBase).RenumberCatalogVersion(
            Str(config, "tenantId"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"),
            request);

        Report(baseUrl, new Dictionary<string, object>
        {
            ["version"] = Show(version.VarVersion),
            ["status"] = Show(Wire(version.Status)),
        });
    }

    /// <summary>
    /// A warning's <c>kind</c> is an open set, a plain string in the contract, so a kind the client
    /// was not built with must come through rather than fail the whole preview.
    /// </summary>
    private static void PreviewCatalogVersionRenumber(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var preview = new CatalogsApi(http, apiBase).PreviewCatalogVersionRenumber(
            Str(config, "to"),
            Str(config, "tenantId"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"));

        Report(baseUrl, new Dictionary<string, object>
        {
            ["newBase"] = Show(preview.NewBase),
            ["warningKinds"] = string.Join(",", preview.Warnings.Select(w => Show(w.Kind))),
        });
    }

    private static void DeleteCatalogVersion(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        new CatalogsApi(http, apiBase).DeleteCatalogVersion(
            Str(config, "tenantId"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"));
    }

    private static void ReleaseCatalogVersion(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var response = new CatalogsApi(http, apiBase).ReleaseCatalogVersion(
            Str(config, "tenantId"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"),
            new ReleaseCatalogVersionRequest(notes: OptStr(config, "notes")!));

        Report(baseUrl, new Dictionary<string, object>
        {
            ["version"] = Show(response.CatalogVersion?.VarVersion),
            ["status"] = Show(response.CatalogVersion == null ? null : Wire(response.CatalogVersion.Status)),
            ["flowedInto"] = string.Join(",", response.FlowedInto.Select(f => $"{Show(f.VarVersion)}:{f.Merged}:{f.Conflicts}")),
            ["openedDraft"] = Show(response.OpenedDraft),
        });
    }

    /// <summary>
    /// A change to a whole resource has a null <c>node</c>, and only a resolved one carries a
    /// <c>resolution</c>; both are reported as <c>null</c> rather than lost.
    /// </summary>
    private static void ListIncomingChanges(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var state = OptStr(config, "state");
        var items = new CatalogsApi(http, apiBase).ListIncomingChanges(
            Str(config, "tenantId"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"),
            state: state == null ? null : Parse<IncomingChangeState>(state)).Items;

        Report(baseUrl, new Dictionary<string, object>
        {
            ["ids"] = string.Join(",", items.Select(c => Show(c.Id))),
            ["nodes"] = string.Join(",", items.Select(c => Show(c.Node))),
            ["states"] = string.Join(",", items.Select(c => Show(Wire(c.State)))),
            ["resolutions"] = string.Join(",", items.Select(c => Show(c.Resolution == null ? null : Wire(c.Resolution.Value)))),
        });
    }

    private static void ResolveIncomingChange(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var change = new CatalogsApi(http, apiBase).ResolveIncomingChange(
            Str(config, "tenantId"), Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "catalogVersion"),
            Str(config, "changeId"),
            new ResolveIncomingChangeRequest(resolution: Parse<IncomingChangeResolution>(Str(config, "resolution"))));

        Report(baseUrl, new Dictionary<string, object>
        {
            ["id"] = Show(change.Id),
            ["state"] = Show(Wire(change.State)),
            ["resolution"] = Show(change.Resolution == null ? null : Wire(change.Resolution.Value)),
        });
    }

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
    /// </summary>
    private static void DeployRelease(string baseUrl, JsonElement config)
    {
        var (http, apiBase) = Client(baseUrl, config);
        var deployment = new EnvironmentsApi(http, apiBase).DeployRelease(
            Str(config, "tenantId"), Str(config, "environmentId"),
            Str(config, "registry"), Str(config, "namespace"), Str(config, "catalogKey"), Str(config, "releaseVersion"));

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
            Str(config, "tenantId"), Str(config, "environmentId"),
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

    private static string Str(JsonElement config, string name, string? fallback = null) =>
        config.TryGetProperty(name, out var value) ? value.GetString()! : fallback
            ?? throw new ArgumentException($"scenario config has no {name}");

    private static string? OptStr(JsonElement config, string name) =>
        config.TryGetProperty(name, out var value) && value.ValueKind != JsonValueKind.Null ? value.GetString() : null;

    private static int Int(JsonElement config, string name, int? fallback = null) =>
        config.TryGetProperty(name, out var value) ? value.GetInt32() : fallback
            ?? throw new ArgumentException($"scenario config has no {name}");
}
