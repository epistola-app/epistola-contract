// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.conformance;

import app.epistola.client.jakarta.EpistolaRestClients;
import app.epistola.client.jakarta.api.CatalogsApi;
import app.epistola.client.jakarta.api.ConsumersApi;
import app.epistola.client.jakarta.api.EnvironmentsApi;
import app.epistola.client.jakarta.api.GenerationApi;
import app.epistola.client.jakarta.api.ImagesApi;
import app.epistola.client.jakarta.api.SystemApi;
import app.epistola.client.jakarta.api.TemplatesApi;
import app.epistola.client.jakarta.auth.JwtSigner;
import app.epistola.client.jakarta.collect.GenerationCollectApi;
import app.epistola.client.jakarta.collect.ResultCollector;
import app.epistola.client.jakarta.error.MissingDependency;
import app.epistola.client.jakarta.error.ProblemDetailException;
import app.epistola.client.jakarta.identity.ClientIdentity;
import app.epistola.client.jakarta.model.CatalogDependencyDto;
import app.epistola.client.jakarta.model.CatalogDependencyRequirement;
import app.epistola.client.jakarta.model.CatalogVersionDto;
import app.epistola.client.jakarta.model.CatalogVersionRenumberPreview;
import app.epistola.client.jakarta.model.CatalogVersionStatus;
import app.epistola.client.jakarta.model.CreateCatalogVersionRequest;
import app.epistola.client.jakarta.model.DeploymentDto;
import app.epistola.client.jakarta.model.ForwardFlowDto;
import app.epistola.client.jakarta.model.GenerateDocumentRequest;
import app.epistola.client.jakarta.model.GenerationResult;
import app.epistola.client.jakarta.model.ImageDto;
import app.epistola.client.jakarta.model.IncomingChangeDto;
import app.epistola.client.jakarta.model.IncomingChangeResolution;
import app.epistola.client.jakarta.model.IncomingChangeState;
import app.epistola.client.jakarta.model.PartitionAssignment;
import app.epistola.client.jakarta.model.PingRequest;
import app.epistola.client.jakarta.model.ReleaseCatalogVersionRequest;
import app.epistola.client.jakarta.model.ReleaseCatalogVersionResponse;
import app.epistola.client.jakarta.model.ReleaseDependencyDto;
import app.epistola.client.jakarta.model.RenumberCatalogVersionRequest;
import app.epistola.client.jakarta.model.RenumberWarningDto;
import app.epistola.client.jakarta.model.ResolveIncomingChangeRequest;
import app.epistola.client.jakarta.model.TemplateListResponse;
import app.epistola.client.jakarta.model.TemplateSummaryDto;
import app.epistola.client.jakarta.model.UpdateCatalogDependenciesRequest;
import app.epistola.client.jakarta.model.UpdateConsumerRequest;
import app.epistola.client.jakarta.validation.schema.TemplateDataValidationException;
import app.epistola.client.jakarta.validation.schema.TemplateSchemaValidator;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import java.io.File;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * The Jakarta EE client's conformance driver.
 *
 * <p>Asks the conformance server what to do, does it with the published client, and reports back.
 * It asserts nothing — the server judges the requests, so the four clients are held to one set of
 * expectations rather than four that drift. See ../../README.md for the driver contract.
 */
public final class Driver {

    private static final HttpClient CONTROL = HttpClient.newHttpClient();

    public static void main(String[] args) {
        if (args.length < 1) {
            throw new IllegalArgumentException("usage: driver <conformance server base url>");
        }
        String baseUrl = args[0];
        JsonObject instruction = get(baseUrl + "/__conformance/action");
        JsonObject config = instruction.getJsonObject("config");

        try {
            switch (instruction.getString("action")) {
                case "ping" -> ping(baseUrl, config);
                case "list-templates" -> listTemplates(baseUrl, config);
                case "collect" -> collect(baseUrl, config);
                case "problem" -> problem(baseUrl, config);
                case "validate-template-data" -> validateTemplateData(baseUrl, config);
                case "routing" -> routing(baseUrl, config);
                case "generate-document" -> generateDocument(baseUrl, config);
                case "update-consumer" -> updateConsumer(baseUrl, config);
                case "download-document" -> downloadDocument(baseUrl, config);
                case "list-images" -> listImages(baseUrl, config);
                case "upload-image" -> uploadImage(baseUrl, config);
                case "download-image" -> downloadImage(baseUrl, config);
                case "delete-image" -> deleteImage(baseUrl, config);
                case "list-catalog-dependencies" -> listCatalogDependencies(baseUrl, config);
                case "update-catalog-dependencies" -> updateCatalogDependencies(baseUrl, config);
                case "create-catalog-version" -> createCatalogVersion(baseUrl, config);
                case "list-catalog-versions" -> listCatalogVersions(baseUrl, config);
                case "get-catalog-version" -> getCatalogVersion(baseUrl, config);
                case "renumber-catalog-version" -> renumberCatalogVersion(baseUrl, config);
                case "preview-catalog-version-renumber" -> previewCatalogVersionRenumber(baseUrl, config);
                case "delete-catalog-version" -> deleteCatalogVersion(baseUrl, config);
                case "release-catalog-version" -> releaseCatalogVersion(baseUrl, config);
                case "list-incoming-changes" -> listIncomingChanges(baseUrl, config);
                case "resolve-incoming-change" -> resolveIncomingChange(baseUrl, config);
                case "deploy-release" -> deployRelease(baseUrl, config);
                case "undeploy-release" -> undeployRelease(baseUrl, config);
                default -> throw new IllegalArgumentException("unknown action " + instruction.getString("action"));
            }
            done(baseUrl, null);
        } catch (Exception e) {
            done(baseUrl, e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    // --- Actions ---

    private static void ping(String baseUrl, JsonObject config) {
        clients(baseUrl, config)
                .api(SystemApi.class)
                .ping(new PingRequest()
                        .name("Conformance Driver")
                        .description("Drives the Jakarta client through one conformance scenario")
                        .contact("conformance@epistola.app"));
    }

    /** Reports what the client made of the last listing, {@code id} included while it is still served. */
    @SuppressWarnings("deprecation")
    private static void listTemplates(String baseUrl, JsonObject config) {
        TemplatesApi api = clients(baseUrl, config).api(TemplatesApi.class);
        TemplateListResponse response = null;
        for (int i = 0; i < config.getInt("repeat", 1); i++) {
            response = api.listTemplates(config.getString("tenantKey"), config.getString("registry"), config.getString("namespace"), config.getString("catalogKey"), config.getString("catalogVersion"), null, 0, 20, null, "desc");
        }
        List<TemplateSummaryDto> templates = response.getItems();

        report(
                baseUrl,
                Map.of(
                        "templateKeys", templates.stream().map(template -> String.valueOf(template.getKey())).collect(Collectors.joining(","))));
    }

    private static void problem(String baseUrl, JsonObject config) {
        try {
            clients(baseUrl, config)
                    .api(TemplatesApi.class)
                    .listTemplates(config.getString("tenantKey"), config.getString("registry"), config.getString("namespace"), config.getString("catalogKey"), config.getString("catalogVersion"), null, 0, 20, null, "desc");
            report(baseUrl, Map.of("problemTypeSlug", "<no exception was thrown>"));
        } catch (ProblemDetailException e) {
            report(
                    baseUrl,
                    Map.of(
                            "problemTypeSlug", e.getTypeSlug() == null ? "<null>" : e.getTypeSlug(),
                            "problemStatus", e.getStatusCode(),
                            "problemTitle", e.getTitle() == null ? "<null>" : e.getTitle(),
                            "problemFieldErrors",
                                    e.getErrors().stream()
                                            .map(error -> error.getField() + ":" + error.getMessage())
                                            .collect(Collectors.joining(",")),
                            "problemMissingFields",
                                    e.getMissingFields().stream()
                                            .map(field -> field.getPath() + ":" + field.getRequired())
                                            .collect(Collectors.joining(",")),
                            "problemInvalidFields",
                                    e.getInvalidFields().stream()
                                            .map(field -> field.getPath() + ":" + field.getKeyword())
                                            .collect(Collectors.joining(","))));
        }
    }

    /** Reports the failures the client surfaced, which is the shape every client owes its callers. */
    private static void validateTemplateData(String baseUrl, JsonObject config) {
        TemplateSchemaValidator validator =
                new TemplateSchemaValidator(clients(baseUrl, config).api(TemplatesApi.class));
        try {
            validator.validate(
                    config.getString("tenantKey"),
                    config.getString("registry"), config.getString("namespace"), config.getString("catalogKey"),
                    config.getString("templateKey"),
                    config.containsKey("data") ? config.getJsonObject("data") : JsonValue.EMPTY_JSON_OBJECT);
            report(
                    baseUrl,
                    Map.of(
                            "validationFailurePaths", "<accepted>",
                            "validationFailureKeywords", "<accepted>"));
        } catch (TemplateDataValidationException e) {
            report(
                    baseUrl,
                    Map.of(
                            "validationFailurePaths",
                                    e.getErrors().stream()
                                            .map(TemplateDataValidationException.ValidationError::getPath)
                                            .collect(Collectors.joining(",")),
                            "validationFailureKeywords",
                                    e.getErrors().stream()
                                            .map(error -> error.getKeyword() == null ? "<null>" : error.getKeyword())
                                            .collect(Collectors.joining(","))));
        }
    }

    private static void collect(String baseUrl, JsonObject config) throws InterruptedException {
        List<GenerationResult> handled = new CopyOnWriteArrayList<>();
        long failOnSequence = config.containsKey("failHandlerOnSequence") ? config.getInt("failHandlerOnSequence") : -1;

        ResultCollector collector = ResultCollector.builder()
                .collectApi(clients(baseUrl, config).api(GenerationCollectApi.class))
                .tenantKey(config.getString("tenantKey"))
                .batchSize(config.getInt("batchSize"))
                .minInterval(Duration.ofMillis(config.getInt("minIntervalMs")))
                .maxInterval(Duration.ofMillis(config.getInt("maxIntervalMs")))
                .backoffMultiplier(config.getJsonNumber("multiplier").doubleValue())
                .registerShutdownHook(false)
                .handler(result -> {
                    handled.add(result);
                    if (result.getSequence() != null && result.getSequence() == failOnSequence) {
                        throw new IllegalStateException("conformance: deliberate handler failure");
                    }
                })
                // Without this the loop swallows collection failures and simply backs off, which
                // reaches the harness as "the client chose not to poll" rather than as the cause.
                .errorHandler(e -> e.printStackTrace())
                .build();

        Thread thread = new Thread(collector::start, "conformance-collector");
        thread.start();
        Thread.sleep(config.getInt("runForMs"));
        collector.stop();
        thread.join(5_000);

        PartitionAssignment assignment = collector.getPartitionAssignment();
        report(
                baseUrl,
                Map.of(
                        "resultsHandled", handled.size(),
                        "statuses",
                                handled.stream()
                                        .map(result -> result.getStatus().value())
                                        .collect(Collectors.joining(",")),
                        "correlationIds",
                                handled.stream()
                                        .map(result -> result.getCorrelationId() == null ? "" : result.getCorrelationId())
                                        .collect(Collectors.joining(",")),
                        "handledSequences",
                                handled.stream()
                                        .map(result -> String.valueOf(result.getSequence()))
                                        .collect(Collectors.joining(",")),
                        "releases",
                                handled.stream()
                                        .map(result -> result.getRegistry() + "/" + result.getNamespace() + "/"
                                                + result.getCatalogKey() + "@"
                                                + result.getReleaseVersion())
                                        .collect(Collectors.joining(",")),
                        "partitionTotal", assignment == null ? -1 : assignment.getTotal()));
    }

    /**
     * A request body with something in it: required fields, two of the optional ones set, the rest
     * left alone, and a free-form {@code data} object carrying every JSON type. What the server
     * receives is the generator's serialization, which is the part no client hand-writes and no
     * client's own tests inspect.
     */
    private static void generateDocument(String baseUrl, JsonObject config) {
        clients(baseUrl, config)
                .api(GenerationApi.class)
                .generateDocument(
                        config.getString("tenantKey"),
                        new GenerateDocumentRequest()
                                .registry(optional(config, "registry"))
                                .namespace(config.getString("namespace"))
                                .catalogKey(config.getString("catalogKey"))
                                .templateKey(config.getString("templateKey"))
                                .data(config.getJsonObject("data"))
                                .version(optional(config, "version"))
                                .environmentKey(optional(config, "environmentKey"))
                                .correlationId(optional(config, "correlationId"))
                                .routingKey(optional(config, "routingKey")));
    }

    /**
     * A partial update that sets exactly one field. Everything the caller did not name must stay off
     * the wire: the contract reads a null on these as "clear this", so a serializer that writes
     * nulls for unset properties turns "rename this consumer" into "rename it and erase its
     * description, contact and expiry".
     */
    private static void updateConsumer(String baseUrl, JsonObject config) {
        clients(baseUrl, config)
                .api(ConsumersApi.class)
                .updateConsumer(
                        config.getString("tenantKey"),
                        config.getString("consumerId"),
                        new UpdateConsumerRequest().name(config.getString("name")));
    }

    /**
     * One poll to learn the partition assignment from the {@code _meta} line, then the routing
     * helpers. The values are reported rather than asserted here: the harness holds all four
     * clients to the same answers, which is the only way four independent murmur3 implementations
     * stay in step.
     */
    private static void routing(String baseUrl, JsonObject config) {
        ResultCollector collector = ResultCollector.builder()
                .collectApi(clients(baseUrl, config).api(GenerationCollectApi.class))
                .tenantKey(config.getString("tenantKey"))
                .registerShutdownHook(false)
                .handler(result -> {})
                .build();

        collector.collectOnce();

        List<String> keys = config.getJsonArray("keys").getValuesAs(jakarta.json.JsonString::getString);
        PartitionAssignment assignment = collector.getPartitionAssignment();
        report(
                baseUrl,
                Map.of(
                        "partitionTotal", assignment == null ? -1 : assignment.getTotal(),
                        "partitions",
                                keys.stream()
                                        .map(key -> key + ":" + collector.partitionFor(key))
                                        .collect(Collectors.joining(",")),
                        "routed",
                                keys.stream()
                                        .map(key -> key + "=" + collector.routingKeyToMe(key))
                                        .collect(Collectors.joining(",")),
                        "routedPartitions",
                                keys.stream()
                                        .map(key -> String.valueOf(collector.partitionFor(collector.routingKeyToMe(key))))
                                        .collect(Collectors.joining(",")),
                        "mineFlags",
                                keys.stream()
                                        .map(key -> String.valueOf(collector.isMyPartition(key)))
                                        .collect(Collectors.joining(","))));
    }

    /**
     * Downloads a document and reports what arrived, byte for byte.
     *
     * The four clients return four different things here — a File, a FileParameter, a bytearray —
     * and the only thing that has to be identical is the content. A stack that decodes a PDF as
     * text corrupts every document it fetches, silently and irreversibly, so the fixture is
     * deliberately not valid UTF-8.
     */
    private static void downloadDocument(String baseUrl, JsonObject config) throws Exception {
        File file = clients(baseUrl, config)
                .api(GenerationApi.class)
                .downloadDocument(config.getString("tenantKey"), UUID.fromString(config.getString("documentId")));
        reportBytes(baseUrl, Files.readAllBytes(file.toPath()));
    }

    /**
     * Lists images and reports what the client made of them. Keys may be UUIDs or readable names, and
     * an SVG's dimensions are null, which must stay null rather than become 0.
     */
    private static void listImages(String baseUrl, JsonObject config) {
        List<ImageDto> images = clients(baseUrl, config)
                .api(ImagesApi.class)
                .listImages(config.getString("tenantKey"), config.getString("registry"), config.getString("namespace"), config.getString("catalogKey"), config.getString("catalogVersion"), config.getString("search"), null, null)
                .getItems();

        report(
                baseUrl,
                Map.of(
                        "imageKeys", images.stream().map(ImageDto::getKey).collect(Collectors.joining(",")),
                        "widths", images.stream().map(image -> String.valueOf(image.getWidth())).collect(Collectors.joining(",")),
                        "heights", images.stream().map(image -> String.valueOf(image.getHeight())).collect(Collectors.joining(",")),
                        "mediaTypes", images.stream().map(ImageDto::getMediaType).collect(Collectors.joining(","))));
    }

    /**
     * Uploads an image. The generated method takes a {@link File}, so the bytes go to a file named the
     * way the caller wants the part named: the part's filename comes from the file.
     */
    private static void uploadImage(String baseUrl, JsonObject config) throws Exception {
        Path directory = Files.createTempDirectory("conformance-upload");
        File file = Files.write(
                        directory.resolve(config.getString("filename")),
                        Base64.getDecoder().decode(config.getString("fileBase64")))
                .toFile();
        try {
            ImageDto image = clients(baseUrl, config)
                    .api(ImagesApi.class)
                    .uploadImage(config.getString("tenantKey"), config.getString("registry"), config.getString("namespace"), config.getString("catalogKey"), config.getString("catalogVersion"), file, config.getString("name"), null, null);
            report(baseUrl, Map.of("imageKey", image.getKey(), "imageName", image.getName()));
        } finally {
            Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(directory);
        }
    }

    private static void downloadImage(String baseUrl, JsonObject config) throws Exception {
        File file = clients(baseUrl, config)
                .api(ImagesApi.class)
                .downloadImageContent(config.getString("tenantKey"), config.getString("registry"), config.getString("namespace"), config.getString("catalogKey"), config.getString("catalogVersion"), config.getString("imageKey"));
        reportBytes(baseUrl, Files.readAllBytes(file.toPath()));
    }

    private static void deleteImage(String baseUrl, JsonObject config) {
        clients(baseUrl, config)
                .api(ImagesApi.class)
                .deleteImage(config.getString("tenantKey"), config.getString("registry"), config.getString("namespace"), config.getString("catalogKey"), config.getString("catalogVersion"), config.getString("imageKey"), config.getBoolean("force"));
    }

    private static void listCatalogDependencies(String baseUrl, JsonObject config) {
        clients(baseUrl, config)
                .api(CatalogsApi.class)
                .listCatalogDependencies(
                        config.getString("tenantKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"),
                        config.getString("catalogVersion"));
    }

    /** Replaces the declared dependencies and reports every field of the answer, a null as {@code null}. */
    private static void updateCatalogDependencies(String baseUrl, JsonObject config) {
        List<CatalogDependencyRequirement> dependencies = config.getJsonArray("dependencies").stream()
                .map(JsonValue::asJsonObject)
                .map(dependency -> new CatalogDependencyRequirement()
                        .registry(optional(dependency, "registry"))
                        .namespace(dependency.getString("namespace"))
                        .catalogKey(dependency.getString("catalogKey"))
                        .version(dependency.getString("version")))
                .collect(Collectors.toList());

        List<CatalogDependencyDto> items = clients(baseUrl, config)
                .api(CatalogsApi.class)
                .updateCatalogDependencies(
                        config.getString("tenantKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"),
                        config.getString("catalogVersion"),
                        new UpdateCatalogDependenciesRequest().dependencies(dependencies))
                .getItems();

        report(
                baseUrl,
                Map.of(
                        "registries", joined(items, CatalogDependencyDto::getRegistry),
                        "namespaces", joined(items, CatalogDependencyDto::getNamespace),
                        "catalogKeys", joined(items, CatalogDependencyDto::getCatalogKey),
                        "versions", joined(items, CatalogDependencyDto::getVersion),
                        "resolvedVersions", joined(items, CatalogDependencyDto::getResolvedVersion)));
    }

    private static void createCatalogVersion(String baseUrl, JsonObject config) {
        CatalogVersionDto version = clients(baseUrl, config)
                .api(CatalogsApi.class)
                .createCatalogVersion(
                        config.getString("tenantKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"),
                        new CreateCatalogVersionRequest()
                                .version(config.getString("version"))
                                .from(optional(config, "from")));
        report(
                baseUrl,
                Map.of(
                        "version", String.valueOf(version.getVersion()),
                        "status", String.valueOf(version.getStatus()),
                        "base", String.valueOf(version.getBase()),
                        "createdFrom", String.valueOf(version.getCreatedFrom()),
                        "releasedAt", instant(version.getReleasedAt())));
    }

    private static void listCatalogVersions(String baseUrl, JsonObject config) {
        String status = optional(config, "status");
        List<CatalogVersionDto> versions = clients(baseUrl, config)
                .api(CatalogsApi.class)
                .listCatalogVersions(
                        config.getString("tenantKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"),
                        status == null ? null : CatalogVersionStatus.fromValue(status),
                        null,
                        null)
                .getItems();
        report(
                baseUrl,
                Map.of(
                        "versions", joined(versions, CatalogVersionDto::getVersion),
                        "statuses", joined(versions, version -> String.valueOf(version.getStatus())),
                        "bases", joined(versions, CatalogVersionDto::getBase),
                        "openConflicts", joined(versions, version -> String.valueOf(version.getOpenConflicts()))));
    }

    private static void getCatalogVersion(String baseUrl, JsonObject config) {
        CatalogVersionDto version = clients(baseUrl, config)
                .api(CatalogsApi.class)
                .getCatalogVersion(
                        config.getString("tenantKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"),
                        config.getString("catalogVersion"));
        List<ReleaseDependencyDto> dependencies =
                version.getDependencies() == null ? List.of() : version.getDependencies();
        report(
                baseUrl,
                Map.of(
                        "version", String.valueOf(version.getVersion()),
                        "status", String.valueOf(version.getStatus()),
                        "releasedAt", instant(version.getReleasedAt()),
                        "fingerprint", version.getFingerprint() == null ? "null" : version.getFingerprint(),
                        "dependencies",
                                joined(
                                        dependencies,
                                        dependency -> dependency.getRegistry() + "/" + dependency.getNamespace() + "/"
                                                + dependency.getCatalogKey() + "@"
                                                + dependency.getReleaseVersion()),
                        "deployedTo",
                                version.getDeployedTo() == null ? "null" : String.join(",", version.getDeployedTo())));
    }

    private static void renumberCatalogVersion(String baseUrl, JsonObject config) {
        // The generated model initialises acknowledgeWarnings to its default, false; it is sent only
        // when the scenario names it.
        RenumberCatalogVersionRequest request = new RenumberCatalogVersionRequest()
                .version(config.getString("version"))
                .acknowledgeWarnings(
                        config.containsKey("acknowledgeWarnings") && !config.isNull("acknowledgeWarnings")
                                ? config.getBoolean("acknowledgeWarnings")
                                : null);
        CatalogVersionDto version = clients(baseUrl, config)
                .api(CatalogsApi.class)
                .renumberCatalogVersion(
                        config.getString("tenantKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"),
                        config.getString("catalogVersion"),
                        request);
        report(
                baseUrl,
                Map.of(
                        "version", String.valueOf(version.getVersion()),
                        "status", String.valueOf(version.getStatus())));
    }

    /** Warning kinds are an open set: one this client has never heard of is reported, not rejected. */
    private static void previewCatalogVersionRenumber(String baseUrl, JsonObject config) {
        CatalogVersionRenumberPreview preview = clients(baseUrl, config)
                .api(CatalogsApi.class)
                .previewCatalogVersionRenumber(
                        config.getString("to"),
                        config.getString("tenantKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"),
                        config.getString("catalogVersion"));
        List<RenumberWarningDto> warnings = preview.getWarnings() == null ? List.of() : preview.getWarnings();
        report(
                baseUrl,
                Map.of(
                        "newBase", String.valueOf(preview.getNewBase()),
                        "warningKinds", joined(warnings, RenumberWarningDto::getKind)));
    }

    private static void deleteCatalogVersion(String baseUrl, JsonObject config) {
        clients(baseUrl, config)
                .api(CatalogsApi.class)
                .deleteCatalogVersion(
                        config.getString("tenantKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"),
                        config.getString("catalogVersion"));
    }

    private static void releaseCatalogVersion(String baseUrl, JsonObject config) {
        ReleaseCatalogVersionResponse response = clients(baseUrl, config)
                .api(CatalogsApi.class)
                .releaseCatalogVersion(
                        config.getString("tenantKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"),
                        config.getString("catalogVersion"),
                        new ReleaseCatalogVersionRequest().notes(optional(config, "notes")));
        List<ForwardFlowDto> flowedInto = response.getFlowedInto() == null ? List.of() : response.getFlowedInto();
        report(
                baseUrl,
                Map.of(
                        "version", String.valueOf(response.getCatalogVersion().getVersion()),
                        "status", String.valueOf(response.getCatalogVersion().getStatus()),
                        "flowedInto",
                                joined(
                                        flowedInto,
                                        flow -> flow.getVersion() + ":" + flow.getMerged() + ":" + flow.getConflicts()),
                        "openedDraft", String.valueOf(response.getOpenedDraft())));
    }

    private static void listIncomingChanges(String baseUrl, JsonObject config) {
        String state = optional(config, "state");
        List<IncomingChangeDto> changes = clients(baseUrl, config)
                .api(CatalogsApi.class)
                .listIncomingChanges(
                        config.getString("tenantKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"),
                        config.getString("catalogVersion"),
                        state == null ? null : IncomingChangeState.fromValue(state))
                .getItems();
        report(
                baseUrl,
                Map.of(
                        "ids", joined(changes, IncomingChangeDto::getId),
                        "nodes", joined(changes, IncomingChangeDto::getNode),
                        "states", joined(changes, change -> String.valueOf(change.getState())),
                        "resolutions", joined(changes, change -> String.valueOf(change.getResolution()))));
    }

    private static void resolveIncomingChange(String baseUrl, JsonObject config) {
        IncomingChangeDto change = clients(baseUrl, config)
                .api(CatalogsApi.class)
                .resolveIncomingChange(
                        config.getString("tenantKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"),
                        config.getString("catalogVersion"),
                        config.getString("changeId"),
                        new ResolveIncomingChangeRequest()
                                .resolution(IncomingChangeResolution.fromValue(config.getString("resolution"))));
        report(
                baseUrl,
                Map.of(
                        "id", String.valueOf(change.getId()),
                        "state", String.valueOf(change.getState()),
                        "resolution", String.valueOf(change.getResolution())));
    }

    /** A timestamp as ISO 8601 in UTC with its seconds, or {@code null}; offsets and precision vary by parser. */
    private static String instant(OffsetDateTime value) {
        return value == null ? "null" : value.toInstant().toString();
    }

    /**
     * Deploys a release, passing {@code includeDependencies} only when the scenario names it. A
     * refusal is reported as the problem it carried, with every missing dependency in order.
     */
    private static void deployRelease(String baseUrl, JsonObject config) {
        Boolean includeDependencies = config.containsKey("includeDependencies") && !config.isNull("includeDependencies")
                ? config.getBoolean("includeDependencies")
                : null;
        DeploymentDto deployment;
        try {
            deployment = clients(baseUrl, config)
                    .api(EnvironmentsApi.class)
                    .deployRelease(
                            config.getString("tenantKey"),
                            config.getString("environmentKey"),
                            config.getString("registry"),
                            config.getString("namespace"),
                            config.getString("catalogKey"),
                            config.getString("releaseVersion"),
                            includeDependencies);
        } catch (ProblemDetailException e) {
            report(
                    baseUrl,
                    Map.of(
                            "problemTypeSlug", e.getTypeSlug() == null ? "<null>" : e.getTypeSlug(),
                            "problemStatus", e.getStatusCode(),
                            "problemMissingDependencies",
                                    joined(
                                            e.getMissingDependencies(),
                                            (MissingDependency dependency) -> dependency.getRegistry() + "/"
                                                    + dependency.getNamespace() + "/" + dependency.getCatalogKey()
                                                    + "@" + dependency.getReleaseVersion() + ":"
                                                    + dependency.getReason())));
            return;
        }
        report(
                baseUrl,
                Map.of(
                        "registry", String.valueOf(deployment.getRegistry()),
                        "namespace", String.valueOf(deployment.getNamespace()),
                        "catalogKey", String.valueOf(deployment.getCatalogKey()),
                        "releaseVersion", String.valueOf(deployment.getReleaseVersion())));
    }

    private static void undeployRelease(String baseUrl, JsonObject config) {
        clients(baseUrl, config)
                .api(EnvironmentsApi.class)
                .undeployRelease(
                        config.getString("tenantKey"),
                        config.getString("environmentKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"),
                        config.getString("releaseVersion"));
    }

    private static <T> String joined(List<T> items, java.util.function.Function<T, String> field) {
        return items.stream().map(item -> String.valueOf(field.apply(item))).collect(Collectors.joining(","));
    }

    /** A string the scenario may leave out; absent stays null, so the request leaves it off the wire. */
    private static String optional(JsonObject config, String key) {
        return config.containsKey(key) && !config.isNull(key) ? config.getString(key) : null;
    }

    private static void reportBytes(String baseUrl, byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(String.format("%02x", b));
        }
        report(baseUrl, Map.of("byteLength", bytes.length, "sha256", hex.toString()));
    }

    // --- Client assembly ---

    private static EpistolaRestClients clients(String baseUrl, JsonObject config) {
        ClientIdentity.Builder identity = ClientIdentity.builder().nodeId(config.getString("nodeId"));
        for (JsonValue product : config.getJsonArray("products") == null ? List.<JsonValue>of() : config.getJsonArray("products")) {
            JsonObject entry = product.asJsonObject();
            identity.product(entry.getString("name"), entry.getString("version"));
        }

        // The API base path is part of the contract's servers entry, so the driver appends it
        // rather than the harness serving the API at the root.
        EpistolaRestClients.Builder builder =
                EpistolaRestClients.builder().baseUri(baseUrl + "/api").identity(identity.build());

        switch (config.getString("auth", "none")) {
            case "api-key" -> builder.apiKey(config.getString("apiKey"));
            case "jwt" -> builder.jwtSigner(JwtSigner.builder()
                    .consumerId(config.getString("consumerId"))
                    .privateKey(JwtSigner.parsePrivateKeyPem(config.getString("privateKeyPem")))
                    .tokenLifetime(Duration.ofSeconds(config.getInt("tokenLifetimeSeconds")))
                    .build());
            default -> {
                // no authentication configured for this scenario
            }
        }

        return builder.build();
    }

    // --- Control plane ---

    private static JsonObject get(String url) {
        try {
            HttpResponse<String> response = CONTROL.send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
            try (var reader = Json.createReader(new StringReader(response.body()))) {
                return reader.readObject();
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not read the conformance instruction from " + url, e);
        }
    }

    private static void post(String url, String body) {
        try {
            CONTROL.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            throw new IllegalStateException("could not post to " + url, e);
        }
    }

    private static void report(String baseUrl, Map<String, Object> values) {
        var builder = Json.createObjectBuilder();
        values.forEach((key, value) -> {
            if (value instanceof Integer number) {
                builder.add(key, number);
            } else {
                builder.add(key, String.valueOf(value));
            }
        });
        post(baseUrl + "/__conformance/report", builder.build().toString());
    }

    private static void done(String baseUrl, String error) {
        var builder = Json.createObjectBuilder();
        if (error != null) {
            builder.add("error", error);
        }
        post(baseUrl + "/__conformance/done", builder.build().toString());
    }

    private Driver() {}
}
