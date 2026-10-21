// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.conformance;

import app.epistola.client.jakarta.EpistolaRestClients;
import app.epistola.client.jakarta.api.AttributesApi;
import app.epistola.client.jakarta.api.CatalogsApi;
import app.epistola.client.jakarta.api.ConsumersApi;
import app.epistola.client.jakarta.api.EnvironmentsApi;
import app.epistola.client.jakarta.api.GenerationApi;
import app.epistola.client.jakarta.api.ImagesApi;
import app.epistola.client.jakarta.api.StencilsApi;
import app.epistola.client.jakarta.api.SystemApi;
import app.epistola.client.jakarta.api.TemplatesApi;
import app.epistola.client.jakarta.api.VariantsApi;
import app.epistola.client.jakarta.auth.JwtSigner;
import app.epistola.client.jakarta.collect.GenerationCollectApi;
import app.epistola.client.jakarta.collect.ResultCollector;
import app.epistola.client.jakarta.error.MissingDependency;
import app.epistola.client.jakarta.error.ProblemDetailException;
import app.epistola.client.jakarta.identity.ClientIdentity;
import app.epistola.client.jakarta.api.CodeListsApi;
import app.epistola.client.jakarta.api.TenantsApi;
import app.epistola.client.jakarta.model.AttributeDto;
import app.epistola.client.jakarta.model.AttributeDtoCodeListBinding;
import app.epistola.client.jakarta.model.AvailableCatalogReleases;
import app.epistola.client.jakarta.model.AvailableReleaseDependencyDto;
import app.epistola.client.jakarta.model.AvailableReleaseDto;
import app.epistola.client.jakarta.model.BehindReleaseDto;
import app.epistola.client.jakarta.model.CatalogChangesDto;
import app.epistola.client.jakarta.model.CatalogDependencyDto;
import app.epistola.client.jakarta.model.CatalogDependencyListResponse;
import app.epistola.client.jakarta.model.CatalogDependencyRequirement;
import app.epistola.client.jakarta.model.CatalogDraftMergePreview;
import app.epistola.client.jakarta.model.CatalogDraftMergeResource;
import app.epistola.client.jakarta.model.CatalogDraftReleasePreview;
import app.epistola.client.jakarta.model.CatalogMergeSide;
import app.epistola.client.jakarta.model.CatalogPresetDto;
import app.epistola.client.jakarta.model.CatalogReleaseDto;
import app.epistola.client.jakarta.model.CatalogReleaseStatus;
import app.epistola.client.jakarta.model.CatalogThemeReference;
import app.epistola.client.jakarta.model.CodeListEntryDto;
import app.epistola.client.jakarta.model.ConsumerDto;
import app.epistola.client.jakarta.model.CreateCatalogReleaseRequest;
import app.epistola.client.jakarta.model.CreateEnvironmentRequest;
import app.epistola.client.jakarta.model.DeploymentDto;
import app.epistola.client.jakarta.model.DeploymentEventDto;
import app.epistola.client.jakarta.model.DeploymentHistoryResponse;
import app.epistola.client.jakarta.model.DocumentDto;
import app.epistola.client.jakarta.model.EnvironmentDto;
import app.epistola.client.jakarta.model.GenerateDocumentRequest;
import app.epistola.client.jakarta.model.GenerationJobResponse;
import app.epistola.client.jakarta.model.GenerationResult;
import app.epistola.client.jakarta.model.ImageDto;
import app.epistola.client.jakarta.model.ImportCatalogResponse;
import app.epistola.client.jakarta.model.InstallCatalogReleaseResponse;
import app.epistola.client.jakarta.model.MarkStencilReadyResponse;
import app.epistola.client.jakarta.model.MergeIntoCatalogDraftRequest;
import app.epistola.client.jakarta.model.MergeIntoCatalogDraftResponse;
import app.epistola.client.jakarta.model.Node;
import app.epistola.client.jakarta.model.PartitionAssignment;
import app.epistola.client.jakarta.model.PingRequest;
import app.epistola.client.jakarta.model.PublishReleaseToRegistryRequest;
import app.epistola.client.jakarta.model.RegistryReleaseListResponse;
import app.epistola.client.jakarta.model.ReleasePublicationDto;
import app.epistola.client.jakarta.model.ReleaseRegistryListResponse;
import app.epistola.client.jakarta.model.ReleaseCatalogDraftRequest;
import app.epistola.client.jakarta.model.ReleaseCatalogDraftResponse;
import app.epistola.client.jakarta.model.ReleaseDependencyDto;
import app.epistola.client.jakarta.model.ReleasedChangeDto;
import app.epistola.client.jakarta.model.ReleasedDependencyDto;
import app.epistola.client.jakarta.model.StencilContentDto;
import app.epistola.client.jakarta.model.StencilUsageDto;
import app.epistola.client.jakarta.model.StencilUsageListResponse;
import app.epistola.client.jakarta.model.TemplateDocument;
import app.epistola.client.jakarta.model.TemplateDto;
import app.epistola.client.jakarta.model.TemplateListResponse;
import app.epistola.client.jakarta.model.TemplateSummaryDto;
import app.epistola.client.jakarta.model.TenantDto;
import app.epistola.client.jakarta.model.ThemeRef;
import app.epistola.client.jakarta.model.UpdateCatalogDependenciesRequest;
import app.epistola.client.jakarta.model.UpdateAttributeRequest;
import app.epistola.client.jakarta.model.UpdateCatalogReleaseRequest;
import app.epistola.client.jakarta.model.UpdateCodeListEntryHiddenRequest;
import app.epistola.client.jakarta.model.UpdateConsumerRequest;
import app.epistola.client.jakarta.model.UpdateEnvironmentRequest;
import app.epistola.client.jakarta.model.UpdateStencilContentRequest;
import app.epistola.client.jakarta.model.UpdateTenantRequest;
import app.epistola.client.jakarta.model.UpdateVariantContentRequest;
import app.epistola.client.jakarta.model.VariantContentDto;
import app.epistola.client.jakarta.model.VariantSelectionAttribute;
import app.epistola.client.jakarta.validation.schema.TemplateDataValidationException;
import app.epistola.client.jakarta.validation.schema.TemplateSchemaValidator;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.json.bind.JsonbBuilder;
import jakarta.ws.rs.core.Response;
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
                case "list-documents" -> listDocuments(baseUrl, config);
                case "list-images" -> listImages(baseUrl, config);
                case "upload-image" -> uploadImage(baseUrl, config);
                case "import-catalog" -> importCatalog(baseUrl, config);
                case "install-catalog-release" -> installCatalogRelease(baseUrl, config);
                case "list-available-catalog-releases" -> listAvailableCatalogReleases(baseUrl, config);
                case "download-image" -> downloadImage(baseUrl, config);
                case "delete-image" -> deleteImage(baseUrl, config);
                case "list-catalog-dependencies" -> listCatalogDependencies(baseUrl, config);
                case "update-catalog-dependencies" -> updateCatalogDependencies(baseUrl, config);
                case "create-catalog-release" -> createCatalogRelease(baseUrl, config);
                case "list-catalog-releases" -> listCatalogReleases(baseUrl, config);
                case "get-catalog-release" -> getCatalogRelease(baseUrl, config);
                case "update-catalog-release" -> updateCatalogRelease(baseUrl, config);
                case "set-catalog-release-theme-settings" -> setCatalogReleaseThemeSettings(baseUrl, config);
                case "preview-catalog-draft-release" -> previewCatalogDraftRelease(baseUrl, config);
                case "delete-catalog-release" -> deleteCatalogRelease(baseUrl, config);
                case "release-catalog-draft" -> releaseCatalogDraft(baseUrl, config);
                case "preview-catalog-draft-merge" -> previewCatalogDraftMerge(baseUrl, config);
                case "merge-into-catalog-draft" -> mergeIntoCatalogDraft(baseUrl, config);
                case "list-release-registries" -> listReleaseRegistries(baseUrl, config);
                case "list-registry-releases" -> listRegistryReleases(baseUrl, config);
                case "install-registry-release" -> installRegistryRelease(baseUrl, config);
                case "publish-release-to-registry" -> publishReleaseToRegistry(baseUrl, config);
                case "deploy-catalog-release" -> deployCatalogRelease(baseUrl, config);
                case "undeploy-catalog-release" -> undeployCatalogRelease(baseUrl, config);
                case "create-environment" -> createEnvironment(baseUrl, config);
                case "update-environment" -> updateEnvironment(baseUrl, config);
                case "update-tenant" -> updateTenant(baseUrl, config);
                case "update-code-list-entry-hidden" -> updateCodeListEntryHidden(baseUrl, config);
                case "update-attribute" -> updateAttribute(baseUrl, config);
                case "update-variant-content" -> updateVariantContent(baseUrl, config);
                case "get-variant-content" -> getVariantContent(baseUrl, config);
                case "mark-variant-ready" -> markVariantReady(baseUrl, config);
                case "mark-template-ready" -> markTemplateReady(baseUrl, config);
                case "get-stencil-content" -> getStencilContent(baseUrl, config);
                case "update-stencil-content" -> updateStencilContent(baseUrl, config);
                case "mark-stencil-ready" -> markStencilReady(baseUrl, config);
                case "list-stencil-usage" -> listStencilUsage(baseUrl, config);
                case "get-catalog-changes" -> getCatalogChanges(baseUrl, config);
                case "mark-catalog-ready" -> markCatalogReady(baseUrl, config);
                case "list-environment-deployments" -> listEnvironmentDeployments(baseUrl, config);
                case "list-deployment-history" -> listDeploymentHistory(baseUrl, config);
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
            response = api.listTemplates(config.getString("tenantKey"), config.getString("registry"), config.getString("namespace"), config.getString("catalogKey"), config.getString("release"), null, 0, 20, null, "desc");
        }
        List<TemplateSummaryDto> templates = response.getItems();

        report(
                baseUrl,
                Map.of(
                        "templateKeys", templates.stream().map(template -> String.valueOf(template.getKey())).collect(Collectors.joining(",")),
                        "templateDescriptions", templates.stream().map(template -> String.valueOf(template.getDescription())).collect(Collectors.joining("|"))));
    }

    private static void problem(String baseUrl, JsonObject config) {
        try {
            clients(baseUrl, config)
                    .api(TemplatesApi.class)
                    .listTemplates(config.getString("tenantKey"), config.getString("registry"), config.getString("namespace"), config.getString("catalogKey"), config.getString("release"), null, 0, 20, null, "desc");
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
                                                + result.getVersion())
                                        .collect(Collectors.joining(",")),
                        "partitionTotal", assignment == null ? -1 : assignment.getTotal()));
    }

    /**
     * A request body with something in it: required fields, two of the optional ones set, the rest
     * left alone, and a free-form {@code data} object carrying every JSON type. What the server
     * receives is the generator's serialization, which is the part no client hand-writes and no
     * client's own tests inspect.
     */
    /** Reports the accepted job as parsed, and the {@code Location} of the job exactly as received. */
    private static void generateDocument(String baseUrl, JsonObject config) {
        GenerateDocumentRequest request = new GenerateDocumentRequest()
                .registry(optional(config, "registry"))
                .namespace(config.getString("namespace"))
                .catalogKey(config.getString("catalogKey"))
                .templateKey(config.getString("templateKey"))
                .variantKey(optional(config, "variantKey"))
                .data(config.getJsonObject("data"))
                .versionRange(optional(config, "versionRange"))
                .environmentKey(optional(config, "environmentKey"))
                .correlationId(optional(config, "correlationId"))
                .routingKey(optional(config, "routingKey"));
        // Attribute criteria, only when the scenario names them: an unset optional stays off the wire.
        if (config.containsKey("attributes") && !config.isNull("attributes")) {
            request.attributes(config.getJsonArray("attributes").getValuesAs(JsonObject.class).stream()
                    .map(attribute -> new VariantSelectionAttribute()
                            .registry(optional(attribute, "registry"))
                            .namespace(optional(attribute, "namespace"))
                            .catalogKey(optional(attribute, "catalogKey"))
                            .key(attribute.getString("key"))
                            .value(attribute.getString("value"))
                            .required(attribute.containsKey("required") && !attribute.isNull("required")
                                    ? attribute.getBoolean("required")
                                    : null))
                    .collect(Collectors.toList()));
        }
        try (Response response = clients(baseUrl, config)
                .api(GenerationApi.class)
                .generateDocumentWithHttpInfo(config.getString("tenantKey"), request)) {
            GenerationJobResponse job = response.readEntity(GenerationJobResponse.class);
            Map<String, Object> values = new java.util.LinkedHashMap<>();
            values.put("requestId", String.valueOf(job.getRequestId()));
            values.put("status", String.valueOf(job.getStatus()));
            values.put("jobType", String.valueOf(job.getJobType()));
            values.put("total", job.getTotal() == null ? "null" : job.getTotal());
            values.put("location", String.valueOf(response.getHeaderString("Location")));
            report(baseUrl, values);
        }
    }

    /**
     * A partial update that sets only the members the scenario names. Everything the caller did not
     * name must stay off the wire: a server built to a contract before 2.0 reads a null on these as
     * "clear this", so a serializer that writes nulls for unset properties turns "rename this
     * consumer" into "rename it and erase its description, contact and expiry". An empty
     * {@code description} is a value, and goes out as is.
     */
    private static void updateConsumer(String baseUrl, JsonObject config) {
        conditionalUpdate(
                baseUrl,
                () -> clients(baseUrl, config)
                        .api(ConsumersApi.class)
                        .updateConsumerWithHttpInfo(
                                config.getString("tenantKey"),
                                config.getString("consumerId"),
                                new UpdateConsumerRequest()
                                        .name(optional(config, "name"))
                                        .description(optional(config, "description"))
                                        .clearExpiresAt(optionalBoolean(config, "clearExpiresAt")),
                                optional(config, "ifMatch")),
                response -> {
                    ConsumerDto consumer = response.readEntity(ConsumerDto.class);
                    return Map.of(
                            "id", String.valueOf(consumer.getId()),
                            "name", String.valueOf(consumer.getName()),
                            "lastModified", instant(consumer.getLastModified()));
                });
    }

    /**
     * Calls an update through its header-exposing sibling and reports what {@code fields} read from
     * the response plus its {@code ETag} exactly as received ({@code null} when absent). A problem
     * response is reported by its slug and status instead.
     */
    private static void conditionalUpdate(
            String baseUrl,
            java.util.function.Supplier<Response> call,
            java.util.function.Function<Response, Map<String, Object>> fields) {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        try (Response response = call.get()) {
            values.putAll(fields.apply(response));
            values.put("etag", String.valueOf(response.getHeaderString("ETag")));
        } catch (ProblemDetailException e) {
            values.clear();
            values.put("problemTypeSlug", e.getTypeSlug() == null ? "<null>" : e.getTypeSlug());
            values.put("problemStatus", e.getStatusCode());
        }
        report(baseUrl, values);
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
     * Lists documents with the catalog and template filters the scenario sets, each sent only when
     * set, and reports what the client made of the page.
     */
    private static void listDocuments(String baseUrl, JsonObject config) {
        List<DocumentDto> documents = clients(baseUrl, config)
                .api(GenerationApi.class)
                .listDocuments(
                        config.getString("tenantKey"),
                        optional(config, "templateKey"),
                        null,
                        null,
                        null,
                        optional(config, "registry"),
                        optional(config, "namespace"),
                        optional(config, "catalogKey"))
                .getItems();

        report(
                baseUrl,
                Map.of(
                        "catalogs", documents.stream()
                                .map(document -> document.getRegistry() + "/" + document.getNamespace() + "/" + document.getCatalogKey())
                                .collect(Collectors.joining(",")),
                        "templateKeys", documents.stream().map(DocumentDto::getTemplateKey).collect(Collectors.joining(",")),
                        "variantKeys", documents.stream().map(DocumentDto::getVariantKey).collect(Collectors.joining(","))));
    }

    /**
     * Lists images and reports what the client made of them. Keys may be UUIDs or readable names, and
     * an SVG's dimensions are null, which must stay null rather than become 0.
     */
    private static void listImages(String baseUrl, JsonObject config) {
        List<ImageDto> images = clients(baseUrl, config)
                .api(ImagesApi.class)
                .listImages(config.getString("tenantKey"), config.getString("registry"), config.getString("namespace"), config.getString("catalogKey"), config.getString("release"), config.getString("search"), null, null)
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
                    .uploadImage(config.getString("tenantKey"), config.getString("registry"), config.getString("namespace"), config.getString("catalogKey"), config.getString("release"), file, config.getString("name"), null, null);
            report(baseUrl, Map.of("imageKey", image.getKey(), "imageName", image.getName()));
        } finally {
            Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(directory);
        }
    }

    /**
     * Imports a catalog archive. Like an image upload, the bytes go to a file named the way the part
     * should be named; {@code registry} and {@code namespace} are sent only when the scenario names
     * them (a null field is left out). A refusal is reported by its problem.
     */
    private static void importCatalog(String baseUrl, JsonObject config) throws Exception {
        Path directory = Files.createTempDirectory("conformance-import");
        File file = Files.write(
                        directory.resolve(config.getString("filename")),
                        Base64.getDecoder().decode(config.getString("fileBase64")))
                .toFile();
        try {
            ImportCatalogResponse imported;
            try {
                imported = clients(baseUrl, config)
                        .api(CatalogsApi.class)
                        .importCatalog(
                                config.getString("tenantKey"),
                                file,
                                optional(config, "registry"),
                                optional(config, "namespace"));
            } catch (ProblemDetailException e) {
                report(
                        baseUrl,
                        Map.of(
                                "problemTypeSlug", e.getTypeSlug() == null ? "<null>" : e.getTypeSlug(),
                                "problemStatus", e.getStatusCode()));
                return;
            }
            report(
                    baseUrl,
                    Map.of(
                            "registry", String.valueOf(imported.getRegistry()),
                            "namespace", String.valueOf(imported.getNamespace()),
                            "catalogKey", String.valueOf(imported.getCatalogKey()),
                            "version", String.valueOf(imported.getVersion()),
                            "newlyInstalled", String.valueOf(imported.getNewlyInstalled()),
                            "total", String.valueOf(imported.getTotal())));
        } finally {
            Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(directory);
        }
    }

    /**
     * Installs a release a catalog's source offers, named in the path. A refusal or a
     * failing source is reported by its problem, missing dependencies included.
     */
    private static void installCatalogRelease(String baseUrl, JsonObject config) {
        InstallCatalogReleaseResponse installed;
        try {
            installed = clients(baseUrl, config)
                    .api(CatalogsApi.class)
                    .installCatalogRelease(
                            config.getString("tenantKey"),
                            config.getString("registry"),
                            config.getString("namespace"),
                            config.getString("catalogKey"),
                            config.getString("version"));
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
                                                    + "@" + dependency.getVersion() + ":"
                                                    + dependency.getReason())));
            return;
        }
        report(
                baseUrl,
                Map.of(
                        "registry", String.valueOf(installed.getRegistry()),
                        "namespace", String.valueOf(installed.getNamespace()),
                        "catalogKey", String.valueOf(installed.getCatalogKey()),
                        "version", String.valueOf(installed.getVersion()),
                        "newlyInstalled", String.valueOf(installed.getNewlyInstalled()),
                        "total", String.valueOf(installed.getTotal()),
                        "installedDependencies",
                                joined(orEmpty(installed.getInstalledDependencies()), String::valueOf),
                        "presentDependencies",
                                joined(orEmpty(installed.getPresentDependencies()), String::valueOf)));
    }

    /**
     * Lists the releases a catalog's source offers that the tenant lacks, reporting every
     * one in order. A failing source is reported by its problem.
     */
    private static void listAvailableCatalogReleases(String baseUrl, JsonObject config) {
        AvailableCatalogReleases releases;
        try {
            releases = clients(baseUrl, config)
                    .api(CatalogsApi.class)
                    .listAvailableCatalogReleases(
                            config.getString("tenantKey"),
                            config.getString("registry"),
                            config.getString("namespace"),
                            config.getString("catalogKey"));
        } catch (ProblemDetailException e) {
            report(
                    baseUrl,
                    Map.of(
                            "problemTypeSlug", e.getTypeSlug() == null ? "<null>" : e.getTypeSlug(),
                            "problemStatus", e.getStatusCode()));
            return;
        }
        List<AvailableReleaseDto> available = orEmpty(releases.getAvailable());
        report(
                baseUrl,
                Map.of(
                        "registry", String.valueOf(releases.getRegistry()),
                        "namespace", String.valueOf(releases.getNamespace()),
                        "catalogKey", String.valueOf(releases.getCatalogKey()),
                        "availableComparedTo",
                                available.stream()
                                        .map(release -> release.getVersion() + "=" + release.getComparedTo())
                                        .collect(Collectors.joining(";")),
                        "availableChanges",
                                available.stream()
                                        .map(release -> release.getVersion() + "="
                                                + orEmpty(release.getChanges()).stream()
                                                        .map(change -> change.getType() + "/" + change.getKey() + ":"
                                                                + change.getChange())
                                                        .collect(Collectors.joining("+")))
                                        .collect(Collectors.joining(";")),
                        "availableDependencies",
                                available.stream()
                                        .map(release -> release.getVersion() + "="
                                                + orEmpty(release.getDependencies()).stream()
                                                        .map((AvailableReleaseDependencyDto dependency) ->
                                                                dependency.getRegistry() + "/"
                                                                        + dependency.getNamespace() + "/"
                                                                        + dependency.getCatalogKey() + "@"
                                                                        + dependency.getVersion() + ":"
                                                                        + dependency.getPresent())
                                                        .collect(Collectors.joining("+")))
                                        .collect(Collectors.joining(";"))));
    }

    private static <T> List<T> orEmpty(List<T> items) {
        return items == null ? List.of() : items;
    }

    private static void downloadImage(String baseUrl, JsonObject config) throws Exception {
        File file = clients(baseUrl, config)
                .api(ImagesApi.class)
                .downloadImageContent(config.getString("tenantKey"), config.getString("registry"), config.getString("namespace"), config.getString("catalogKey"), config.getString("release"), config.getString("imageKey"));
        reportBytes(baseUrl, Files.readAllBytes(file.toPath()));
    }

    private static void deleteImage(String baseUrl, JsonObject config) {
        clients(baseUrl, config)
                .api(ImagesApi.class)
                .deleteImage(config.getString("tenantKey"), config.getString("registry"), config.getString("namespace"), config.getString("catalogKey"), config.getString("release"), config.getString("imageKey"), config.getBoolean("force"));
    }

    private static void listCatalogDependencies(String baseUrl, JsonObject config) {
        reportDependencies(
                baseUrl,
                clients(baseUrl, config)
                        .api(CatalogsApi.class)
                        .listCatalogDependencies(
                                config.getString("tenantKey"),
                                config.getString("registry"),
                                config.getString("namespace"),
                                config.getString("catalogKey"),
                                config.getString("release"))
                        .getItems());
    }

    /** Replaces the declared dependencies and reports every field of the answer, a null as {@code null}. */
    private static void updateCatalogDependencies(String baseUrl, JsonObject config) {
        List<CatalogDependencyRequirement> dependencies = config.getJsonArray("dependencies").stream()
                .map(JsonValue::asJsonObject)
                .map(dependency -> new CatalogDependencyRequirement()
                        .registry(optional(dependency, "registry"))
                        .namespace(dependency.getString("namespace"))
                        .catalogKey(dependency.getString("catalogKey"))
                        .version(optional(dependency, "version"))
                        .releaseId(optional(dependency, "releaseId")))
                .collect(Collectors.toList());

        conditionalUpdate(
                baseUrl,
                () -> clients(baseUrl, config)
                        .api(CatalogsApi.class)
                        .updateCatalogDependenciesWithHttpInfo(
                                config.getString("tenantKey"),
                                config.getString("registry"),
                                config.getString("namespace"),
                                config.getString("catalogKey"),
                                config.getString("release"),
                                new UpdateCatalogDependenciesRequest().dependencies(dependencies),
                                optional(config, "ifMatch")),
                response -> dependencyFields(
                        response.readEntity(CatalogDependencyListResponse.class).getItems()));
    }

    /**
     * Reports declared dependencies as parsed. A declaration that names a draft, or pins no release
     * (migrated from wire v7), has a null {@code version}, and one that pins a release a null
     * {@code releaseId}; each is reported as {@code null} rather than lost.
     */
    private static void reportDependencies(String baseUrl, List<CatalogDependencyDto> items) {
        report(baseUrl, dependencyFields(items));
    }

    private static Map<String, Object> dependencyFields(List<CatalogDependencyDto> items) {
        return Map.of(
                        "registries", joined(items, CatalogDependencyDto::getRegistry),
                        "namespaces", joined(items, CatalogDependencyDto::getNamespace),
                        "catalogKeys", joined(items, CatalogDependencyDto::getCatalogKey),
                        "versions", joined(items, CatalogDependencyDto::getVersion),
                        "releaseIds", joined(items, dependency -> String.valueOf(dependency.getReleaseId())));
    }

    private static void createCatalogRelease(String baseUrl, JsonObject config) {
        try (Response response = clients(baseUrl, config)
                .api(CatalogsApi.class)
                .createCatalogReleaseWithHttpInfo(
                        config.getString("tenantKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"),
                        new CreateCatalogReleaseRequest()
                                .name(config.getString("name"))
                                .from(optional(config, "from")))) {
            CatalogReleaseDto release = response.readEntity(CatalogReleaseDto.class);
            report(
                    baseUrl,
                    Map.of(
                            "version", String.valueOf(release.getVersion()),
                            "releaseId", String.valueOf(release.getReleaseId()),
                            "name", String.valueOf(release.getName()),
                            "status", String.valueOf(release.getStatus()),
                            "base", String.valueOf(release.getBase()),
                            "createdFrom", String.valueOf(release.getCreatedFrom()),
                            "releasedAt", instant(release.getReleasedAt()),
                            "location", String.valueOf(response.getHeaderString("Location"))));
        }
    }

    private static void listCatalogReleases(String baseUrl, JsonObject config) {
        String status = optional(config, "status");
        List<CatalogReleaseDto> releases = clients(baseUrl, config)
                .api(CatalogsApi.class)
                .listCatalogReleases(
                        config.getString("tenantKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"),
                        status == null ? null : CatalogReleaseStatus.fromValue(status),
                        null,
                        null)
                .getItems();
        report(
                baseUrl,
                Map.of(
                        "versions", joined(releases, CatalogReleaseDto::getVersion),
                        "releaseIds", joined(releases, release -> String.valueOf(release.getReleaseId())),
                        "names",
                                releases.stream()
                                        .map(release -> String.valueOf(release.getName()))
                                        .collect(Collectors.joining("|")),
                        "statuses", joined(releases, release -> String.valueOf(release.getStatus())),
                        "bases", joined(releases, CatalogReleaseDto::getBase),
                        "createdFrom", joined(releases, release -> String.valueOf(release.getCreatedFrom())),
                        "behindCounts", joined(releases, release -> String.valueOf(behind(release).size()))));
    }

    private static void getCatalogRelease(String baseUrl, JsonObject config) {
        CatalogReleaseDto release = clients(baseUrl, config)
                .api(CatalogsApi.class)
                .getCatalogRelease(
                        config.getString("tenantKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"),
                        config.getString("release"));
        List<ReleaseDependencyDto> dependencies =
                release.getDependencies() == null ? List.of() : release.getDependencies();
        report(
                baseUrl,
                Map.ofEntries(
                        Map.entry("version", String.valueOf(release.getVersion())),
                        Map.entry("releaseId", String.valueOf(release.getReleaseId())),
                        Map.entry("name", String.valueOf(release.getName())),
                        Map.entry("status", String.valueOf(release.getStatus())),
                        Map.entry("releasedAt", instant(release.getReleasedAt())),
                        Map.entry(
                                "fingerprint",
                                release.getFingerprint() == null ? "null" : release.getFingerprint()),
                        Map.entry(
                                "dependencies",
                                joined(
                                        dependencies,
                                        dependency -> dependency.getRegistry() + "/" + dependency.getNamespace() + "/"
                                                + dependency.getCatalogKey() + "@"
                                                + dependency.getVersion())),
                        Map.entry(
                                "deployedTo",
                                release.getDeployedTo() == null ? "null" : String.join(",", release.getDeployedTo())),
                        Map.entry(
                                "behind",
                                behind(release).stream()
                                        .map(Driver::behindRelease)
                                        .collect(Collectors.joining(";")))));
    }

    /** One release a draft is behind, as {@code version=type/key:change+type/key:change}. */
    private static String behindRelease(BehindReleaseDto release) {
        List<ReleasedChangeDto> changes = release.getChanges() == null ? List.of() : release.getChanges();
        return release.getVersion() + "="
                + changes.stream()
                        .map(change -> change.getType() + "/" + change.getKey() + ":" + change.getChange())
                        .collect(Collectors.joining("+"));
    }

    private static List<BehindReleaseDto> behind(CatalogReleaseDto release) {
        return release.getBehind() == null ? List.of() : release.getBehind();
    }

    /** Renames a draft; only the name is sent, to its unchanged address. */
    private static void updateCatalogRelease(String baseUrl, JsonObject config) {
        conditionalUpdate(
                baseUrl,
                () -> clients(baseUrl, config)
                        .api(CatalogsApi.class)
                        .updateCatalogReleaseWithHttpInfo(
                                config.getString("tenantKey"),
                                config.getString("registry"),
                                config.getString("namespace"),
                                config.getString("catalogKey"),
                                config.getString("release"),
                                new UpdateCatalogReleaseRequest().name(config.getString("name")),
                                optional(config, "ifMatch")),
                response -> {
                    CatalogReleaseDto release = response.readEntity(CatalogReleaseDto.class);
                    return Map.of(
                            "version", String.valueOf(release.getVersion()),
                            "releaseId", String.valueOf(release.getReleaseId()),
                            "name", String.valueOf(release.getName()),
                            "status", String.valueOf(release.getStatus()),
                            "base", String.valueOf(release.getBase()));
                });
    }

    /** Sets a draft's default theme and presets; no name goes out, and an unset qualifier stays off the wire. */
    private static void setCatalogReleaseThemeSettings(String baseUrl, JsonObject config) {
        // Present, an empty array included, goes out as given; absent stays off the wire.
        List<CatalogPresetDto> presets = config.containsKey("presets") && !config.isNull("presets")
                ? config.getJsonArray("presets").stream()
                        .map(JsonValue::asJsonObject)
                        .map(preset -> new CatalogPresetDto()
                                .key(preset.getString("key"))
                                .name(preset.getString("name"))
                                .description(optional(preset, "description")))
                        .collect(Collectors.toList())
                : null;
        String themeKey = optional(config, "defaultThemeKey");
        CatalogReleaseDto release = clients(baseUrl, config)
                .api(CatalogsApi.class)
                .updateCatalogRelease(
                        config.getString("tenantKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"),
                        config.getString("release"),
                        new UpdateCatalogReleaseRequest()
                                .defaultTheme(themeKey == null
                                        ? null
                                        : new CatalogThemeReference()
                                                .key(themeKey)
                                                .catalogKey(optional(config, "defaultThemeCatalogKey")))
                                .clearDefaultTheme(optionalBoolean(config, "clearDefaultTheme"))
                                .presets(presets),
                        null);
        List<CatalogPresetDto> parsed = release.getPresets() == null ? List.of() : release.getPresets();
        CatalogThemeReference defaultTheme = release.getDefaultTheme();
        report(
                baseUrl,
                Map.of(
                        "releaseId", String.valueOf(release.getReleaseId()),
                        "name", String.valueOf(release.getName()),
                        "defaultThemeKey", defaultTheme == null ? "null" : String.valueOf(defaultTheme.getKey()),
                        "defaultThemeCatalogKey", defaultTheme == null ? "null" : String.valueOf(defaultTheme.getCatalogKey()),
                        "presetKeys", parsed.stream().map(CatalogPresetDto::getKey).collect(Collectors.joining(",")),
                        "presetNames", parsed.stream().map(CatalogPresetDto::getName).collect(Collectors.joining("|")),
                        "presetDescriptions", parsed.stream().map(p -> String.valueOf(p.getDescription())).collect(Collectors.joining("|"))));
    }

    /** The version a draft would be released as, and every reason, in order. */
    private static void previewCatalogDraftRelease(String baseUrl, JsonObject config) {
        CatalogDraftReleasePreview preview = clients(baseUrl, config)
                .api(CatalogsApi.class)
                .previewCatalogDraftRelease(
                        config.getString("tenantKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"),
                        config.getString("release"));
        List<String> reasons = preview.getReasons() == null ? List.of() : preview.getReasons();
        report(
                baseUrl,
                Map.of(
                        "base", String.valueOf(preview.getBase()),
                        "changeKind", String.valueOf(preview.getChangeKind()),
                        "reasons", String.join("|", reasons),
                        "needed", String.valueOf(preview.getNeeded()),
                        "suggested", String.valueOf(preview.getSuggested())));
    }

    private static void deleteCatalogRelease(String baseUrl, JsonObject config) {
        clients(baseUrl, config)
                .api(CatalogsApi.class)
                .deleteCatalogRelease(
                        config.getString("tenantKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"),
                        config.getString("release"));
    }

    /**
     * Releases a draft; each member is sent only when the scenario names it. A refusal is reported
     * as the problem it carried, with every unreleased dependency draft in order.
     */
    private static void releaseCatalogDraft(String baseUrl, JsonObject config) {
        ReleaseCatalogDraftResponse response;
        try {
            response = clients(baseUrl, config)
                    .api(CatalogsApi.class)
                    .releaseCatalogDraft(
                            config.getString("tenantKey"),
                            config.getString("registry"),
                            config.getString("namespace"),
                            config.getString("catalogKey"),
                            config.getString("release"),
                            new ReleaseCatalogDraftRequest()
                                    .version(optional(config, "version"))
                                    .notes(optional(config, "notes"))
                                    // The generated model leaves the list null until set; null stays off the wire.
                                    .acknowledgedBehind(
                                            config.containsKey("acknowledgedBehind") && !config.isNull("acknowledgedBehind")
                                                    ? config.getJsonArray("acknowledgedBehind")
                                                            .getValuesAs(jakarta.json.JsonString::getString)
                                                    : null)
                                    .releaseDependencies(optionalBoolean(config, "releaseDependencies")));
        } catch (ProblemDetailException e) {
            report(
                    baseUrl,
                    Map.of(
                            "problemTypeSlug", e.getTypeSlug() == null ? "<null>" : e.getTypeSlug(),
                            "problemStatus", e.getStatusCode(),
                            "problemUnreleasedDependencies",
                                    e.getUnreleasedDependencies().stream()
                                            .map(dependency -> dependency.getRegistry() + "/"
                                                    + dependency.getNamespace() + "/" + dependency.getCatalogKey()
                                                    + "#" + dependency.getReleaseId() + "=" + dependency.getReleaseName())
                                            .collect(Collectors.joining("|"))));
            return;
        }
        List<String> draftsBehind = response.getDraftsBehind() == null ? List.of() : response.getDraftsBehind();
        List<ReleasedDependencyDto> released =
                response.getReleasedDependencies() == null ? List.of() : response.getReleasedDependencies();
        report(
                baseUrl,
                Map.of(
                        "version", String.valueOf(response.getRelease().getVersion()),
                        "status", String.valueOf(response.getRelease().getStatus()),
                        "releaseId", String.valueOf(response.getRelease().getReleaseId()),
                        "draftsBehind", String.join("|", draftsBehind),
                        "releasedDependencies",
                                joined(
                                        released,
                                        dependency -> dependency.getRegistry() + "/" + dependency.getNamespace() + "/"
                                                + dependency.getCatalogKey() + "@"
                                                + dependency.getVersion())));
    }

    /** Previews merging the release {@code config.version} into the draft {@code config.release} (alpha). */
    private static void previewCatalogDraftMerge(String baseUrl, JsonObject config) {
        CatalogDraftMergePreview preview = clients(baseUrl, config)
                .api(CatalogsApi.class)
                // The generator puts the operation's own query parameter before the path's parameters.
                .previewCatalogDraftMerge(
                        config.getString("version"),
                        config.getString("tenantKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"),
                        config.getString("release"));
        List<CatalogDraftMergeResource> resources =
                preview.getResources() == null ? List.of() : preview.getResources();
        report(
                baseUrl,
                Map.of(
                        "draft", String.valueOf(preview.getDraft()),
                        "base", String.valueOf(preview.getBase()),
                        "release", String.valueOf(preview.getRelease()),
                        "alreadyMerged", String.valueOf(preview.getAlreadyMerged()),
                        "resources",
                                joined(
                                        resources,
                                        r -> r.getResourceType() + "/" + r.getResourceKey() + ":" + r.getOutcome()
                                                + ":" + r.getDraftChange() + ":" + r.getReleaseChange()),
                        "resourceNames",
                                resources.stream()
                                        .map(r -> String.valueOf(r.getName()))
                                        .collect(Collectors.joining("|")),
                        "unchanged", String.valueOf(preview.getUnchanged()),
                        "token", String.valueOf(preview.getToken()),
                        "missingBase",
                                preview.getMissingBase() == null
                                        ? "null"
                                        : preview.getMissingBase().getVersion() + "@"
                                                + preview.getMissingBase().getFingerprint()));
    }

    /** Lists the release registries (alpha), each as {@code name:kind}. */
    private static void listReleaseRegistries(String baseUrl, JsonObject config) {
        ReleaseRegistryListResponse registries =
                clients(baseUrl, config).api(CatalogsApi.class).listReleaseRegistries(config.getString("tenantKey"));
        report(baseUrl, Map.of("registries", joined(registries.getItems(), r -> r.getName() + ":" + r.getKind())));
    }

    /**
     * Lists the releases of a catalog in a release registry (alpha), each as
     * {@code version:fingerprint:releaseId:base}, the base as {@code version@fingerprint}, a missing
     * value as {@code null}.
     */
    private static void listRegistryReleases(String baseUrl, JsonObject config) {
        RegistryReleaseListResponse releases;
        try {
            releases = clients(baseUrl, config)
                    .api(CatalogsApi.class)
                    .listRegistryReleases(
                            config.getString("tenantKey"),
                            config.getString("name"),
                            config.getString("namespace"),
                            config.getString("catalogKey"));
        } catch (ProblemDetailException e) {
            reportProblemOnly(baseUrl, e);
            return;
        }
        report(
                baseUrl,
                Map.of(
                        "registry", String.valueOf(releases.getRegistry()),
                        "namespace", String.valueOf(releases.getNamespace()),
                        "catalogKey", String.valueOf(releases.getCatalogKey()),
                        "releases",
                                joined(
                                        releases.getItems(),
                                        r -> r.getVersion() + ":" + r.getFingerprint() + ":" + r.getReleaseId() + ":"
                                                + (r.getBase() == null
                                                        ? "null"
                                                        : r.getBase().getVersion() + "@" + r.getBase().getFingerprint()))));
    }

    /** Installs a release from a release registry (alpha). A refusal is reported by its problem. */
    private static void installRegistryRelease(String baseUrl, JsonObject config) {
        ImportCatalogResponse installed;
        try {
            installed = clients(baseUrl, config)
                    .api(CatalogsApi.class)
                    .installRegistryRelease(
                            config.getString("tenantKey"),
                            config.getString("name"),
                            config.getString("namespace"),
                            config.getString("catalogKey"),
                            config.getString("version"));
        } catch (ProblemDetailException e) {
            reportProblemOnly(baseUrl, e);
            return;
        }
        report(
                baseUrl,
                Map.of(
                        "registry", String.valueOf(installed.getRegistry()),
                        "namespace", String.valueOf(installed.getNamespace()),
                        "catalogKey", String.valueOf(installed.getCatalogKey()),
                        "version", String.valueOf(installed.getVersion()),
                        "newlyInstalled", String.valueOf(installed.getNewlyInstalled()),
                        "total", String.valueOf(installed.getTotal())));
    }

    /** Publishes a released release to the release registry {@code config.to} (alpha). */
    private static void publishReleaseToRegistry(String baseUrl, JsonObject config) {
        ReleasePublicationDto published;
        try {
            published = clients(baseUrl, config)
                    .api(CatalogsApi.class)
                    .publishReleaseToRegistry(
                            config.getString("tenantKey"),
                            config.getString("registry"),
                            config.getString("namespace"),
                            config.getString("catalogKey"),
                            config.getString("release"),
                            new PublishReleaseToRegistryRequest().to(config.getString("to")));
        } catch (ProblemDetailException e) {
            reportProblemOnly(baseUrl, e);
            return;
        }
        report(
                baseUrl,
                Map.of(
                        "status", String.valueOf(published.getStatus()),
                        "registry", String.valueOf(published.getRegistry()),
                        "version", String.valueOf(published.getVersion()),
                        "digest", String.valueOf(published.getDigest())));
    }

    private static void reportProblemOnly(String baseUrl, ProblemDetailException e) {
        report(
                baseUrl,
                Map.of(
                        "problemTypeSlug", e.getTypeSlug() == null ? "<null>" : e.getTypeSlug(),
                        "problemStatus", e.getStatusCode()));
    }

    /**
     * Merges the release {@code config.version} into the draft {@code config.release} (alpha), with
     * {@code config.resolutions} when present. A refusal is reported by its problem, with the
     * conflicts it lists and the read-only reason it gives.
     */
    private static void mergeIntoCatalogDraft(String baseUrl, JsonObject config) {
        MergeIntoCatalogDraftRequest request = new MergeIntoCatalogDraftRequest().release(config.getString("version"));
        if (config.containsKey("resolutions") && !config.isNull("resolutions")) {
            Map<String, CatalogMergeSide> resolutions = new java.util.LinkedHashMap<>();
            config.getJsonObject("resolutions")
                    .forEach((key, side) -> resolutions.put(
                            key, CatalogMergeSide.fromValue(((jakarta.json.JsonString) side).getString())));
            request.resolutions(resolutions);
        }
        if (config.containsKey("expectedToken") && !config.isNull("expectedToken")) {
            request.expectedToken(config.getString("expectedToken"));
        }
        MergeIntoCatalogDraftResponse merged;
        try {
            merged = clients(baseUrl, config)
                    .api(CatalogsApi.class)
                    .mergeIntoCatalogDraft(
                            config.getString("tenantKey"),
                            config.getString("registry"),
                            config.getString("namespace"),
                            config.getString("catalogKey"),
                            config.getString("release"),
                            request);
        } catch (ProblemDetailException e) {
            report(
                    baseUrl,
                    Map.of(
                            "problemTypeSlug", e.getTypeSlug() == null ? "<null>" : e.getTypeSlug(),
                            "problemStatus", e.getStatusCode(),
                            "problemUnresolvedConflicts", String.join(",", e.getUnresolvedConflicts()),
                            "problemReadOnlyReason", String.valueOf(e.getReadOnlyReason()),
                            "problemMissingBase",
                                    e.getMissingBaseVersion() == null
                                            ? "null"
                                            : e.getMissingBaseVersion() + "@" + e.getMissingBaseFingerprint()));
            return;
        }
        report(
                baseUrl,
                Map.of(
                        "release", String.valueOf(merged.getRelease()),
                        "taken", String.join(",", merged.getTaken() == null ? List.of() : merged.getTaken()),
                        "removed", String.join(",", merged.getRemoved() == null ? List.of() : merged.getRemoved()),
                        "kept", String.join(",", merged.getKept() == null ? List.of() : merged.getKept())));
    }

    /** A timestamp as ISO 8601 in UTC with its seconds, or {@code null}; offsets and precision vary by parser. */
    private static String instant(OffsetDateTime value) {
        return value == null ? "null" : value.toInstant().toString();
    }

    /**
     * Deploys a release of a catalog, a released one by its version or a draft by its {@code releaseId},
     * passing {@code includeDependencies} and {@code replace} only when the scenario names them. A refusal is reported as the problem it carried, with every missing
     * dependency in order.
     */
    private static void deployCatalogRelease(String baseUrl, JsonObject config) {
        Boolean includeDependencies = config.containsKey("includeDependencies") && !config.isNull("includeDependencies")
                ? config.getBoolean("includeDependencies")
                : null;
        Boolean replace = config.containsKey("replace") && !config.isNull("replace")
                ? config.getBoolean("replace")
                : null;
        DeploymentDto deployment;
        try {
            deployment = clients(baseUrl, config)
                    .api(EnvironmentsApi.class)
                    .deployCatalogRelease(
                            config.getString("tenantKey"),
                            config.getString("environmentKey"),
                            config.getString("registry"),
                            config.getString("namespace"),
                            config.getString("catalogKey"),
                            config.getString("release"),
                            includeDependencies,
                            replace);
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
                                                    + "@" + dependency.getVersion() + ":"
                                                    + dependency.getReason())));
            return;
        }
        report(
                baseUrl,
                Map.of(
                        "registry", String.valueOf(deployment.getRegistry()),
                        "namespace", String.valueOf(deployment.getNamespace()),
                        "catalogKey", String.valueOf(deployment.getCatalogKey()),
                        "releaseId", String.valueOf(deployment.getReleaseId()),
                        "status", String.valueOf(deployment.getStatus()),
                        "version", String.valueOf(deployment.getVersion()),
                        "releaseName", String.valueOf(deployment.getReleaseName()),
                        "lastUsedAt", instant(deployment.getLastUsedAt()),
                        "documentsRequested", String.valueOf(deployment.getDocumentsRequested())));
    }

    private static void undeployCatalogRelease(String baseUrl, JsonObject config) {
        clients(baseUrl, config)
                .api(EnvironmentsApi.class)
                .undeployCatalogRelease(
                        config.getString("tenantKey"),
                        config.getString("environmentKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"),
                        config.getString("release"));
    }

    private static <T> String joined(List<T> items, java.util.function.Function<T, String> field) {
        return items.stream().map(item -> String.valueOf(field.apply(item))).collect(Collectors.joining(","));
    }

    /**
     * Replaces a variant's content with a one-node template model, sending {@code config.ifMatch}
     * as {@code If-Match} when present and no {@code If-Match} otherwise.
     */
    private static void updateVariantContent(String baseUrl, JsonObject config) {
        VariantContentDto content;
        try {
            content = clients(baseUrl, config)
                    .api(VariantsApi.class)
                    .updateVariantContent(
                            config.getString("tenantKey"),
                            config.getString("registry"),
                            config.getString("namespace"),
                            config.getString("catalogKey"),
                            config.getString("release"),
                            config.getString("templateKey"),
                            config.getString("variantKey"),
                            new UpdateVariantContentRequest()
                                    .templateModel(new TemplateDocument()
                                            .modelVersion(TemplateDocument.ModelVersionEnum.NUMBER_1)
                                            .root("root")
                                            .nodes(Map.of("root", new Node().id("root").type("root").slots(List.of())))
                                            .slots(Map.of())
                                            .themeRef(new ThemeRef().type(ThemeRef.TypeEnum.INHERIT))),
                            optional(config, "ifMatch"));
        } catch (ProblemDetailException e) {
            report(
                    baseUrl,
                    Map.of(
                            "problemTypeSlug", e.getTypeSlug() == null ? "<null>" : e.getTypeSlug(),
                            "problemStatus", e.getStatusCode()));
            return;
        }
        report(baseUrl, Map.of("variantKey", String.valueOf(content.getVariantKey())));
    }

    // --- Content, ready marks and changes ---

    private static TemplateDocument oneNodeModel() {
        return new TemplateDocument()
                .modelVersion(TemplateDocument.ModelVersionEnum.NUMBER_1)
                .root("root")
                .nodes(Map.of("root", new Node().id("root").type("root").slots(List.of())))
                .slots(Map.of())
                .themeRef(new ThemeRef().type(ThemeRef.TypeEnum.INHERIT));
    }

    /** The {@code type} member of a free-form JSON Schema object, which JSON-B reads as a Map. */
    private static String schemaType(Object schema) {
        return schema instanceof Map<?, ?> map ? String.valueOf(map.get("type")) : "null";
    }

    /** A free-form JSON object from the scenario, as the plain Map a caller would hand the client. */
    private static Object freeForm(JsonValue value) {
        try (var jsonb = JsonbBuilder.create()) {
            return jsonb.fromJson(value.toString(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("could not read " + value, e);
        }
    }

    /**
     * Reads a variant's content through {@code getVariantContentWithHttpInfo}, which is how a caller
     * reaches the response headers: the {@code ETag} is reported exactly as received, quotes included.
     */
    private static void getVariantContent(String baseUrl, JsonObject config) {
        try (Response response = clients(baseUrl, config)
                .api(VariantsApi.class)
                .getVariantContentWithHttpInfo(
                        config.getString("tenantKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"),
                        config.getString("release"),
                        config.getString("templateKey"),
                        config.getString("variantKey"))) {
            VariantContentDto content = response.readEntity(VariantContentDto.class);
            report(
                    baseUrl,
                    Map.of(
                            "variantKey", String.valueOf(content.getVariantKey()),
                            "templateModelRoot", String.valueOf(content.getTemplateModel().getRoot()),
                            "status", String.valueOf(content.getStatus()),
                            "lastModified", instant(content.getLastModified()),
                            "readyAt", instant(content.getReadyAt()),
                            "etag", String.valueOf(response.getHeaderString("ETag"))));
        }
    }

    private static void markVariantReady(String baseUrl, JsonObject config) {
        VariantContentDto content = clients(baseUrl, config)
                .api(VariantsApi.class)
                .markVariantReady(
                        config.getString("tenantKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"),
                        config.getString("release"),
                        config.getString("templateKey"),
                        config.getString("variantKey"));
        report(
                baseUrl,
                Map.of(
                        "variantKey", String.valueOf(content.getVariantKey()),
                        "status", String.valueOf(content.getStatus()),
                        "lastModified", instant(content.getLastModified()),
                        "readyAt", instant(content.getReadyAt())));
    }

    private static void markTemplateReady(String baseUrl, JsonObject config) {
        TemplateDto template = clients(baseUrl, config)
                .api(TemplatesApi.class)
                .markTemplateReady(
                        config.getString("tenantKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"),
                        config.getString("release"),
                        config.getString("templateKey"));
        report(
                baseUrl,
                Map.of(
                        "key", String.valueOf(template.getKey()),
                        "name", String.valueOf(template.getName()),
                        "variantKeys", joined(template.getVariants(), variant -> variant.getKey()),
                        "variantStatuses", joined(template.getVariants(), variant -> String.valueOf(variant.getStatus()))));
    }

    /** Reads a stencil's content through {@code getStencilContentWithHttpInfo}, reporting the {@code ETag} as received. */
    private static void getStencilContent(String baseUrl, JsonObject config) {
        try (Response response = clients(baseUrl, config)
                .api(StencilsApi.class)
                .getStencilContentWithHttpInfo(
                        config.getString("tenantKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"),
                        config.getString("release"),
                        config.getString("stencilKey"))) {
            StencilContentDto content = response.readEntity(StencilContentDto.class);
            report(
                    baseUrl,
                    Map.of(
                            "stencilKey", String.valueOf(content.getStencilKey()),
                            "contentRoot", String.valueOf(content.getContent().getRoot()),
                            "parameterSchemaType", schemaType(content.getParameterSchema()),
                            "status", String.valueOf(content.getStatus()),
                            "lastModified", instant(content.getLastModified()),
                            "readyAt", instant(content.getReadyAt()),
                            "etag", String.valueOf(response.getHeaderString("ETag"))));
        }
    }

    /**
     * Replaces a stencil's content with a one-node template model and {@code config.parameterSchema},
     * sending {@code config.ifMatch} as {@code If-Match}, and reports the new {@code ETag} the update
     * answered with.
     */
    private static void updateStencilContent(String baseUrl, JsonObject config) {
        try (Response response = clients(baseUrl, config)
                .api(StencilsApi.class)
                .updateStencilContentWithHttpInfo(
                        config.getString("tenantKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"),
                        config.getString("release"),
                        config.getString("stencilKey"),
                        new UpdateStencilContentRequest()
                                .content(oneNodeModel())
                                .parameterSchema(freeForm(config.get("parameterSchema"))),
                        optional(config, "ifMatch"))) {
            StencilContentDto content = response.readEntity(StencilContentDto.class);
            report(
                    baseUrl,
                    Map.of(
                            "stencilKey", String.valueOf(content.getStencilKey()),
                            "status", String.valueOf(content.getStatus()),
                            "readyAt", instant(content.getReadyAt()),
                            "etag", String.valueOf(response.getHeaderString("ETag"))));
        }
    }

    private static void markStencilReady(String baseUrl, JsonObject config) {
        MarkStencilReadyResponse response = clients(baseUrl, config)
                .api(StencilsApi.class)
                .markStencilReady(
                        config.getString("tenantKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"),
                        config.getString("release"),
                        config.getString("stencilKey"));
        List<StencilUsageDto> notFitting = response.getNotFitting();
        report(
                baseUrl,
                Map.of(
                        "stencilKey", String.valueOf(response.getStencil().getStencilKey()),
                        "status", String.valueOf(response.getStencil().getStatus()),
                        "readyAt", instant(response.getStencil().getReadyAt()),
                        "parameterSchemaType", schemaType(response.getStencil().getParameterSchema()),
                        "notFitting", joined(notFitting, usage -> usage.getTemplateKey() + "/" + usage.getVariantKey() + ":" + usage.getFits()),
                        "notFittingProblems", notFitting.stream()
                                .map(usage -> String.join("|", usage.getProblems()))
                                .collect(Collectors.joining(";"))));
    }

    private static void listStencilUsage(String baseUrl, JsonObject config) {
        StencilUsageListResponse response = clients(baseUrl, config)
                .api(StencilsApi.class)
                .listStencilUsage(
                        config.getString("tenantKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"),
                        config.getString("release"),
                        config.getString("stencilKey"),
                        config.getInt("page"),
                        config.getInt("size"));
        List<StencilUsageDto> items = response.getItems();
        report(
                baseUrl,
                Map.of(
                        "templateKeys", joined(items, StencilUsageDto::getTemplateKey),
                        "templateNames", items.stream().map(StencilUsageDto::getTemplateName).collect(Collectors.joining("|")),
                        "variantKeys", joined(items, StencilUsageDto::getVariantKey),
                        "fits", joined(items, usage -> String.valueOf(usage.getFits())),
                        "problems", items.stream()
                                .map(usage -> String.join("|", usage.getProblems()))
                                .collect(Collectors.joining(";")),
                        "pageNumber", String.valueOf(response.getPage().getNumber()),
                        "total", String.valueOf(response.getPage().getTotal())));
    }

    private static void getCatalogChanges(String baseUrl, JsonObject config) {
        reportChanges(
                baseUrl,
                clients(baseUrl, config)
                        .api(CatalogsApi.class)
                        .getCatalogChanges(
                                config.getString("tenantKey"),
                                config.getString("registry"),
                                config.getString("namespace"),
                                config.getString("catalogKey"),
                                config.getString("release")));
    }

    private static void markCatalogReady(String baseUrl, JsonObject config) {
        reportChanges(
                baseUrl,
                clients(baseUrl, config)
                        .api(CatalogsApi.class)
                        .markCatalogReady(
                                config.getString("tenantKey"),
                                config.getString("registry"),
                                config.getString("namespace"),
                                config.getString("catalogKey"),
                                config.getString("release")));
    }

    private static void reportChanges(String baseUrl, CatalogChangesDto changes) {
        report(
                baseUrl,
                Map.of(
                        "base", String.valueOf(changes.getBase()),
                        "releasable", String.valueOf(changes.getReleasable()),
                        "dependenciesChanged", String.valueOf(changes.getDependenciesChanged()),
                        "changes", joined(changes.getChanges(), change -> change.getType() + "/" + change.getKey() + ":" + change.getChange())));
    }

    private static void listEnvironmentDeployments(String baseUrl, JsonObject config) {
        List<DeploymentDto> items = clients(baseUrl, config)
                .api(EnvironmentsApi.class)
                .listEnvironmentDeployments(config.getString("tenantKey"), config.getString("environmentKey"))
                .getItems();
        report(
                baseUrl,
                Map.of(
                        "registries", joined(items, DeploymentDto::getRegistry),
                        "catalogKeys", joined(items, DeploymentDto::getCatalogKey),
                        "releaseIds", joined(items, DeploymentDto::getReleaseId),
                        "statuses", joined(items, deployment -> String.valueOf(deployment.getStatus())),
                        "versions", joined(items, DeploymentDto::getVersion),
                        "releaseNames", items.stream().map(deployment -> String.valueOf(deployment.getReleaseName())).collect(Collectors.joining("|")),
                        "deployedAt", joined(items, deployment -> instant(deployment.getDeployedAt())),
                        "lastUsedAt", joined(items, deployment -> instant(deployment.getLastUsedAt())),
                        "documentsRequested", joined(items, deployment -> String.valueOf(deployment.getDocumentsRequested()))));
    }

    private static void listDeploymentHistory(String baseUrl, JsonObject config) {
        DeploymentHistoryResponse response = clients(baseUrl, config)
                .api(EnvironmentsApi.class)
                .listDeploymentHistory(
                        config.getString("tenantKey"),
                        config.getString("environmentKey"),
                        config.getInt("page"),
                        config.getInt("size"));
        List<DeploymentEventDto> items = response.getItems();
        report(
                baseUrl,
                Map.of(
                        "catalogKeys", joined(items, DeploymentEventDto::getCatalogKey),
                        "actions", joined(items, event -> String.valueOf(event.getAction())),
                        "releaseIds", joined(items, DeploymentEventDto::getReleaseId),
                        "statuses", joined(items, event -> String.valueOf(event.getStatus())),
                        "versions", joined(items, DeploymentEventDto::getVersion),
                        "releaseNames", items.stream().map(event -> String.valueOf(event.getReleaseName())).collect(Collectors.joining("|")),
                        "changedAt", joined(items, event -> instant(event.getChangedAt())),
                        "changedBy", items.stream().map(event -> String.valueOf(event.getChangedBy())).collect(Collectors.joining("|")),
                        "total", String.valueOf(response.getPage().getTotal())));
    }

    /** Creates an environment; {@code production} is sent only when the scenario names it. */
    private static void createEnvironment(String baseUrl, JsonObject config) {
        try (Response response = clients(baseUrl, config)
                .api(EnvironmentsApi.class)
                .createEnvironmentWithHttpInfo(
                        config.getString("tenantKey"),
                        new CreateEnvironmentRequest()
                                .key(config.getString("key"))
                                .name(config.getString("name"))
                                .production(optionalBoolean(config, "production")))) {
            Map<String, Object> values = new java.util.LinkedHashMap<>(
                    environmentFields(response.readEntity(EnvironmentDto.class)));
            values.put("location", String.valueOf(response.getHeaderString("Location")));
            report(baseUrl, values);
        }
    }

    /**
     * A partial update: each member is sent only when the scenario names it. A refusal is reported
     * by its problem.
     */
    private static void updateEnvironment(String baseUrl, JsonObject config) {
        conditionalUpdate(
                baseUrl,
                () -> clients(baseUrl, config)
                        .api(EnvironmentsApi.class)
                        .updateEnvironmentWithHttpInfo(
                                config.getString("tenantKey"),
                                config.getString("environmentKey"),
                                new UpdateEnvironmentRequest()
                                        .name(optional(config, "name"))
                                        .production(optionalBoolean(config, "production")),
                                optional(config, "ifMatch")),
                response -> environmentFields(response.readEntity(EnvironmentDto.class)));
    }

    private static Map<String, Object> environmentFields(EnvironmentDto environment) {
        return Map.of(
                "key", String.valueOf(environment.getKey()),
                "name", String.valueOf(environment.getName()),
                "production", String.valueOf(environment.getProduction()));
    }

    /** Renames a tenant, sending {@code config.ifMatch} as {@code If-Match} when present. */
    private static void updateTenant(String baseUrl, JsonObject config) {
        conditionalUpdate(
                baseUrl,
                () -> clients(baseUrl, config)
                        .api(TenantsApi.class)
                        .updateTenantWithHttpInfo(
                                config.getString("tenantKey"),
                                new UpdateTenantRequest().name(config.getString("name")),
                                optional(config, "ifMatch")),
                response -> {
                    TenantDto tenant = response.readEntity(TenantDto.class);
                    return Map.of(
                            "key", String.valueOf(tenant.getKey()),
                            "name", String.valueOf(tenant.getName()));
                });
    }

    /** Hides or shows a code-list entry, conditional on the code list's {@code ETag} when given. */
    private static void updateCodeListEntryHidden(String baseUrl, JsonObject config) {
        conditionalUpdate(
                baseUrl,
                () -> clients(baseUrl, config)
                        .api(CodeListsApi.class)
                        .updateCodeListEntryHiddenWithHttpInfo(
                                config.getString("tenantKey"),
                                config.getString("registry"),
                                config.getString("namespace"),
                                config.getString("catalogKey"),
                                config.getString("release"),
                                config.getString("codeListKey"),
                                config.getString("code"),
                                new UpdateCodeListEntryHiddenRequest().hidden(config.getBoolean("hidden")),
                                optional(config, "ifMatch")),
                response -> {
                    CodeListEntryDto entry = response.readEntity(CodeListEntryDto.class);
                    return Map.of(
                            "code", String.valueOf(entry.getCode()),
                            "hidden", String.valueOf(entry.getHidden()));
                });
    }

    /** Changes an attribute's constraint with only the members the scenario names. */
    private static void updateAttribute(String baseUrl, JsonObject config) {
        List<String> allowedValues = config.containsKey("allowedValues") && !config.isNull("allowedValues")
                ? config.getJsonArray("allowedValues").getValuesAs(jakarta.json.JsonString::getString)
                : null;
        conditionalUpdate(
                baseUrl,
                () -> clients(baseUrl, config)
                        .api(AttributesApi.class)
                        .updateAttributeWithHttpInfo(
                                config.getString("tenantKey"),
                                config.getString("registry"),
                                config.getString("namespace"),
                                config.getString("catalogKey"),
                                config.getString("release"),
                                config.getString("attributeKey"),
                                new UpdateAttributeRequest()
                                        .allowedValues(allowedValues)
                                        .clearCodeListBinding(optionalBoolean(config, "clearCodeListBinding")),
                                optional(config, "ifMatch")),
                response -> {
                    AttributeDto attribute = response.readEntity(AttributeDto.class);
                    AttributeDtoCodeListBinding binding = attribute.getCodeListBinding();
                    return Map.of(
                            "key", String.valueOf(attribute.getKey()),
                            "allowedValues", String.join(",", attribute.getAllowedValues() == null ? List.of() : attribute.getAllowedValues()),
                            "codeListBinding", binding == null
                                    ? "null"
                                    : binding.getRegistry() + "/" + binding.getNamespace() + "/" + binding.getCatalogKey() + "/" + binding.getKey());
                });
    }

    private static Boolean optionalBoolean(JsonObject config, String key) {
        return config.containsKey(key) && !config.isNull(key) ? config.getBoolean(key) : null;
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
