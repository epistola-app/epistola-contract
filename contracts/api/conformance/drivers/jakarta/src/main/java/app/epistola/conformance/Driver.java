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
import app.epistola.client.jakarta.model.BehindReleaseDto;
import app.epistola.client.jakarta.model.CatalogChangesDto;
import app.epistola.client.jakarta.model.CatalogDependencyDto;
import app.epistola.client.jakarta.model.CatalogDependencyRequirement;
import app.epistola.client.jakarta.model.CatalogDraftReleasePreview;
import app.epistola.client.jakarta.model.CatalogVersionDto;
import app.epistola.client.jakarta.model.CatalogVersionStatus;
import app.epistola.client.jakarta.model.CreateCatalogDraftRequest;
import app.epistola.client.jakarta.model.CreateEnvironmentRequest;
import app.epistola.client.jakarta.model.DeploymentDto;
import app.epistola.client.jakarta.model.DeploymentEventDto;
import app.epistola.client.jakarta.model.DeploymentHistoryResponse;
import app.epistola.client.jakarta.model.EnvironmentDto;
import app.epistola.client.jakarta.model.EnvironmentOverrideDto;
import app.epistola.client.jakarta.model.SetEnvironmentOverrideRequest;
import app.epistola.client.jakarta.model.UpdateEnvironmentRequest;
import app.epistola.client.jakarta.model.GenerateDocumentRequest;
import app.epistola.client.jakarta.model.GenerationResult;
import app.epistola.client.jakarta.model.ImageDto;
import app.epistola.client.jakarta.model.ImportCatalogResponse;
import app.epistola.client.jakarta.model.MarkStencilReadyResponse;
import app.epistola.client.jakarta.model.PartitionAssignment;
import app.epistola.client.jakarta.model.PingRequest;
import app.epistola.client.jakarta.model.ReleaseCatalogDraftRequest;
import app.epistola.client.jakarta.model.ReleaseCatalogDraftResponse;
import app.epistola.client.jakarta.model.ReleasedDependencyDto;
import app.epistola.client.jakarta.model.ReleaseDependencyDto;
import app.epistola.client.jakarta.model.ReleasedChangeDto;
import app.epistola.client.jakarta.model.RenameCatalogDraftRequest;
import app.epistola.client.jakarta.model.StencilContentDto;
import app.epistola.client.jakarta.model.StencilUsageDto;
import app.epistola.client.jakarta.model.StencilUsageListResponse;
import app.epistola.client.jakarta.model.TemplateDto;
import app.epistola.client.jakarta.model.TemplateListResponse;
import app.epistola.client.jakarta.model.TemplateSummaryDto;
import app.epistola.client.jakarta.model.UpdateCatalogDependenciesRequest;
import app.epistola.client.jakarta.model.UpdateConsumerRequest;
import app.epistola.client.jakarta.model.Node;
import app.epistola.client.jakarta.model.TemplateDocument;
import app.epistola.client.jakarta.model.ThemeRef;
import app.epistola.client.jakarta.model.UpdateStencilContentRequest;
import app.epistola.client.jakarta.model.UpdateVariantContentRequest;
import app.epistola.client.jakarta.model.VariantContentDto;
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
                case "list-images" -> listImages(baseUrl, config);
                case "upload-image" -> uploadImage(baseUrl, config);
                case "import-catalog" -> importCatalog(baseUrl, config);
                case "download-image" -> downloadImage(baseUrl, config);
                case "delete-image" -> deleteImage(baseUrl, config);
                case "list-catalog-dependencies" -> listCatalogDependencies(baseUrl, config);
                case "update-catalog-dependencies" -> updateCatalogDependencies(baseUrl, config);
                case "create-catalog-draft" -> createCatalogDraft(baseUrl, config);
                case "list-catalog-versions" -> listCatalogVersions(baseUrl, config);
                case "get-catalog-version" -> getCatalogVersion(baseUrl, config);
                case "rename-catalog-draft" -> renameCatalogDraft(baseUrl, config);
                case "preview-catalog-draft-release" -> previewCatalogDraftRelease(baseUrl, config);
                case "delete-catalog-draft" -> deleteCatalogDraft(baseUrl, config);
                case "release-catalog-draft" -> releaseCatalogDraft(baseUrl, config);
                case "deploy-release" -> deployRelease(baseUrl, config);
                case "undeploy-release" -> undeployRelease(baseUrl, config);
                case "set-environment-override" -> setEnvironmentOverride(baseUrl, config);
                case "list-environment-overrides" -> listEnvironmentOverrides(baseUrl, config);
                case "clear-environment-override" -> clearEnvironmentOverride(baseUrl, config);
                case "create-environment" -> createEnvironment(baseUrl, config);
                case "update-environment" -> updateEnvironment(baseUrl, config);
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
            response = api.listTemplates(config.getString("tenantKey"), config.getString("registry"), config.getString("namespace"), config.getString("catalogKey"), config.getString("catalogVersion"), null, 0, 20, null, "desc");
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

    /**
     * Imports a catalog archive. Like an image upload, the bytes go to a file named the way the part
     * should be named; {@code registry}, {@code namespace} and {@code catalogType} are sent only when
     * the scenario names them (a null field is left out). A refusal is reported by its problem.
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
                                optional(config, "namespace"),
                                optional(config, "catalogType"));
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
                            "releaseVersion", String.valueOf(imported.getReleaseVersion()),
                            "newlyInstalled", String.valueOf(imported.getNewlyInstalled()),
                            "total", String.valueOf(imported.getTotal())));
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
        reportDependencies(
                baseUrl,
                clients(baseUrl, config)
                        .api(CatalogsApi.class)
                        .listCatalogDependencies(
                                config.getString("tenantKey"),
                                config.getString("registry"),
                                config.getString("namespace"),
                                config.getString("catalogKey"),
                                config.getString("catalogVersion"))
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
                        .releaseVersion(optional(dependency, "releaseVersion"))
                        .draftId(optionalUuid(dependency, "draftId")))
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

        reportDependencies(baseUrl, items);
    }

    /**
     * Reports declared dependencies as parsed. A declaration that names a draft, or pins no release
     * (migrated from wire v7), has a null {@code releaseVersion}, and one that pins a release a null
     * {@code draftId}; each is reported as {@code null} rather than lost.
     */
    private static void reportDependencies(String baseUrl, List<CatalogDependencyDto> items) {
        report(
                baseUrl,
                Map.of(
                        "registries", joined(items, CatalogDependencyDto::getRegistry),
                        "namespaces", joined(items, CatalogDependencyDto::getNamespace),
                        "catalogKeys", joined(items, CatalogDependencyDto::getCatalogKey),
                        "releaseVersions", joined(items, CatalogDependencyDto::getReleaseVersion),
                        "draftIds", joined(items, dependency -> String.valueOf(dependency.getDraftId()))));
    }

    private static void createCatalogDraft(String baseUrl, JsonObject config) {
        CatalogVersionDto version = clients(baseUrl, config)
                .api(CatalogsApi.class)
                .createCatalogDraft(
                        config.getString("tenantKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"),
                        new CreateCatalogDraftRequest()
                                .name(config.getString("name"))
                                .from(optional(config, "from")));
        report(
                baseUrl,
                Map.of(
                        "version", String.valueOf(version.getVersion()),
                        "draftId", String.valueOf(version.getDraftId()),
                        "name", String.valueOf(version.getName()),
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
                        "draftIds", joined(versions, version -> String.valueOf(version.getDraftId())),
                        "names",
                                versions.stream()
                                        .map(version -> String.valueOf(version.getName()))
                                        .collect(Collectors.joining("|")),
                        "statuses", joined(versions, version -> String.valueOf(version.getStatus())),
                        "bases", joined(versions, CatalogVersionDto::getBase),
                        "createdFrom", joined(versions, version -> String.valueOf(version.getCreatedFrom())),
                        "behindCounts", joined(versions, version -> String.valueOf(behind(version).size()))));
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
                Map.ofEntries(
                        Map.entry("version", String.valueOf(version.getVersion())),
                        Map.entry("draftId", String.valueOf(version.getDraftId())),
                        Map.entry("name", String.valueOf(version.getName())),
                        Map.entry("status", String.valueOf(version.getStatus())),
                        Map.entry("releasedAt", instant(version.getReleasedAt())),
                        Map.entry(
                                "fingerprint",
                                version.getFingerprint() == null ? "null" : version.getFingerprint()),
                        Map.entry(
                                "dependencies",
                                joined(
                                        dependencies,
                                        dependency -> dependency.getRegistry() + "/" + dependency.getNamespace() + "/"
                                                + dependency.getCatalogKey() + "@"
                                                + dependency.getReleaseVersion())),
                        Map.entry(
                                "deployedTo",
                                version.getDeployedTo() == null ? "null" : String.join(",", version.getDeployedTo())),
                        Map.entry(
                                "behind",
                                behind(version).stream()
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

    private static List<BehindReleaseDto> behind(CatalogVersionDto version) {
        return version.getBehind() == null ? List.of() : version.getBehind();
    }

    /** Renames a draft; only the name is sent, to its unchanged address. */
    private static void renameCatalogDraft(String baseUrl, JsonObject config) {
        CatalogVersionDto version = clients(baseUrl, config)
                .api(CatalogsApi.class)
                .renameCatalogDraft(
                        config.getString("tenantKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"),
                        config.getString("catalogVersion"),
                        new RenameCatalogDraftRequest().name(config.getString("name")));
        report(
                baseUrl,
                Map.of(
                        "version", String.valueOf(version.getVersion()),
                        "draftId", String.valueOf(version.getDraftId()),
                        "name", String.valueOf(version.getName()),
                        "status", String.valueOf(version.getStatus()),
                        "base", String.valueOf(version.getBase())));
    }

    /** The number a draft would be released as, and every reason, in order. */
    private static void previewCatalogDraftRelease(String baseUrl, JsonObject config) {
        CatalogDraftReleasePreview preview = clients(baseUrl, config)
                .api(CatalogsApi.class)
                .previewCatalogDraftRelease(
                        config.getString("tenantKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"),
                        config.getString("catalogVersion"));
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

    private static void deleteCatalogDraft(String baseUrl, JsonObject config) {
        clients(baseUrl, config)
                .api(CatalogsApi.class)
                .deleteCatalogDraft(
                        config.getString("tenantKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"),
                        config.getString("catalogVersion"));
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
                            config.getString("catalogVersion"),
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
                                                    + "#" + dependency.getDraftId() + "=" + dependency.getDraftName())
                                            .collect(Collectors.joining("|"))));
            return;
        }
        List<String> draftsBehind = response.getDraftsBehind() == null ? List.of() : response.getDraftsBehind();
        List<ReleasedDependencyDto> released =
                response.getReleasedDependencies() == null ? List.of() : response.getReleasedDependencies();
        report(
                baseUrl,
                Map.of(
                        "version", String.valueOf(response.getCatalogVersion().getVersion()),
                        "status", String.valueOf(response.getCatalogVersion().getStatus()),
                        "draftId", String.valueOf(response.getCatalogVersion().getDraftId()),
                        "draftsBehind", String.join("|", draftsBehind),
                        "releasedDependencies",
                                joined(
                                        released,
                                        dependency -> dependency.getRegistry() + "/" + dependency.getNamespace() + "/"
                                                + dependency.getCatalogKey() + "@"
                                                + dependency.getReleaseVersion())));
    }

    /** A timestamp as ISO 8601 in UTC with its seconds, or {@code null}; offsets and precision vary by parser. */
    private static String instant(OffsetDateTime value) {
        return value == null ? "null" : value.toInstant().toString();
    }

    /**
     * Deploys a release, passing {@code includeDependencies} and {@code replace} only when the
     * scenario names them. A refusal is reported as the problem it carried, with every missing
     * dependency in order.
     */
    private static void deployRelease(String baseUrl, JsonObject config) {
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
                    .deployRelease(
                            config.getString("tenantKey"),
                            config.getString("environmentKey"),
                            config.getString("registry"),
                            config.getString("namespace"),
                            config.getString("catalogKey"),
                            config.getString("releaseVersion"),
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
                        "releaseVersion", String.valueOf(deployment.getReleaseVersion()),
                        "lastUsedAt", instant(deployment.getLastUsedAt()),
                        "documentsRequested", String.valueOf(deployment.getDocumentsRequested())));
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
    /**
     * Overrides the version of a catalog an environment uses; {@code releaseVersion} and
     * {@code catalogVersion} are sent only when the scenario names them. A refusal is reported by
     * its problem.
     */
    private static void setEnvironmentOverride(String baseUrl, JsonObject config) {
        EnvironmentOverrideDto override;
        try {
            override = clients(baseUrl, config)
                    .api(EnvironmentsApi.class)
                    .setEnvironmentOverride(
                            config.getString("tenantKey"),
                            config.getString("environmentKey"),
                            config.getString("registry"),
                            config.getString("namespace"),
                            config.getString("catalogKey"),
                            new SetEnvironmentOverrideRequest()
                                    .releaseVersion(optional(config, "releaseVersion"))
                                    .catalogVersion(optional(config, "catalogVersion")));
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
                        "environmentKey", String.valueOf(override.getEnvironmentKey()),
                        "registry", String.valueOf(override.getRegistry()),
                        "namespace", String.valueOf(override.getNamespace()),
                        "catalogKey", String.valueOf(override.getCatalogKey()),
                        "releaseVersion", String.valueOf(override.getReleaseVersion()),
                        "draftId", String.valueOf(override.getDraftId()),
                        "draftLabel", String.valueOf(override.getDraftLabel()),
                        "setAt", instant(override.getSetAt())));
    }

    private static void listEnvironmentOverrides(String baseUrl, JsonObject config) {
        List<EnvironmentOverrideDto> overrides = clients(baseUrl, config)
                .api(EnvironmentsApi.class)
                .listEnvironmentOverrides(config.getString("tenantKey"), config.getString("environmentKey"))
                .getItems();
        report(
                baseUrl,
                Map.of(
                        "catalogKeys",
                                overrides.stream()
                                        .map(o -> String.valueOf(o.getCatalogKey()))
                                        .collect(Collectors.joining(",")),
                        "releaseVersions",
                                overrides.stream()
                                        .map(o -> String.valueOf(o.getReleaseVersion()))
                                        .collect(Collectors.joining(",")),
                        "draftIds",
                                overrides.stream()
                                        .map(o -> String.valueOf(o.getDraftId()))
                                        .collect(Collectors.joining(",")),
                        "draftLabels",
                                overrides.stream()
                                        .map(o -> String.valueOf(o.getDraftLabel()))
                                        .collect(Collectors.joining("|"))));
    }

    private static void clearEnvironmentOverride(String baseUrl, JsonObject config) {
        clients(baseUrl, config)
                .api(EnvironmentsApi.class)
                .clearEnvironmentOverride(
                        config.getString("tenantKey"),
                        config.getString("environmentKey"),
                        config.getString("registry"),
                        config.getString("namespace"),
                        config.getString("catalogKey"));
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
                            config.getString("catalogVersion"),
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
                        config.getString("catalogVersion"),
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
                        config.getString("catalogVersion"),
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
                        config.getString("catalogVersion"),
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
                        config.getString("catalogVersion"),
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
                        config.getString("catalogVersion"),
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
                        config.getString("catalogVersion"),
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
                        config.getString("catalogVersion"),
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
                        "totalElements", String.valueOf(response.getPage().getTotalElements())));
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
                                config.getString("catalogVersion")));
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
                                config.getString("catalogVersion")));
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
                        "releaseVersions", joined(items, DeploymentDto::getReleaseVersion),
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
                        "releaseVersions", joined(items, DeploymentEventDto::getReleaseVersion),
                        "draftLabels", items.stream().map(event -> String.valueOf(event.getDraftLabel())).collect(Collectors.joining("|")),
                        "changedAt", joined(items, event -> instant(event.getChangedAt())),
                        "changedBy", items.stream().map(event -> String.valueOf(event.getChangedBy())).collect(Collectors.joining("|")),
                        "totalElements", String.valueOf(response.getPage().getTotalElements())));
    }

    /** Creates an environment; {@code production} is sent only when the scenario names it. */
    private static void createEnvironment(String baseUrl, JsonObject config) {
        EnvironmentDto environment = clients(baseUrl, config)
                .api(EnvironmentsApi.class)
                .createEnvironment(
                        config.getString("tenantKey"),
                        new CreateEnvironmentRequest()
                                .key(config.getString("key"))
                                .name(config.getString("name"))
                                .production(optionalBoolean(config, "production")));
        reportEnvironment(baseUrl, environment);
    }

    /** A partial update: each member is sent only when the scenario names it. */
    private static void updateEnvironment(String baseUrl, JsonObject config) {
        EnvironmentDto environment = clients(baseUrl, config)
                .api(EnvironmentsApi.class)
                .updateEnvironment(
                        config.getString("tenantKey"),
                        config.getString("environmentKey"),
                        new UpdateEnvironmentRequest()
                                .name(optional(config, "name"))
                                .production(optionalBoolean(config, "production")));
        reportEnvironment(baseUrl, environment);
    }

    private static void reportEnvironment(String baseUrl, EnvironmentDto environment) {
        report(
                baseUrl,
                Map.of(
                        "key", String.valueOf(environment.getKey()),
                        "name", String.valueOf(environment.getName()),
                        "production", String.valueOf(environment.getProduction())));
    }

    private static Boolean optionalBoolean(JsonObject config, String key) {
        return config.containsKey(key) && !config.isNull(key) ? config.getBoolean(key) : null;
    }

    /** A draft id the scenario may leave out; absent stays null, so the request leaves it off the wire. */
    private static UUID optionalUuid(JsonObject config, String key) {
        String value = optional(config, key);
        return value == null ? null : UUID.fromString(value);
    }

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
