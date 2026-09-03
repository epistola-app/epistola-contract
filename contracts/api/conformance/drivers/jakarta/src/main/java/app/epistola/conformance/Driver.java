// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.conformance;

import app.epistola.client.jakarta.EpistolaRestClients;
import app.epistola.client.jakarta.api.GenerationApi;
import app.epistola.client.jakarta.api.SystemApi;
import app.epistola.client.jakarta.api.TemplatesApi;
import app.epistola.client.jakarta.auth.JwtSigner;
import app.epistola.client.jakarta.collect.GenerationCollectApi;
import app.epistola.client.jakarta.collect.ResultCollector;
import app.epistola.client.jakarta.error.ProblemDetailException;
import app.epistola.client.jakarta.identity.ClientIdentity;
import app.epistola.client.jakarta.model.GenerateDocumentRequest;
import app.epistola.client.jakarta.model.GenerationResult;
import app.epistola.client.jakarta.model.PartitionAssignment;
import app.epistola.client.jakarta.model.PingRequest;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
                case "routing" -> routing(baseUrl, config);
                case "generate-document" -> generateDocument(baseUrl, config);
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

    private static void listTemplates(String baseUrl, JsonObject config) {
        TemplatesApi api = clients(baseUrl, config).api(TemplatesApi.class);
        for (int i = 0; i < config.getInt("repeat", 1); i++) {
            api.listTemplates(config.getString("tenantId"), config.getString("catalogId"), null, 0, 20, null, "desc");
        }
    }

    private static void problem(String baseUrl, JsonObject config) {
        try {
            clients(baseUrl, config)
                    .api(TemplatesApi.class)
                    .listTemplates(config.getString("tenantId"), config.getString("catalogId"), null, 0, 20, null, "desc");
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
                                            .collect(Collectors.joining(","))));
        }
    }

    private static void collect(String baseUrl, JsonObject config) throws InterruptedException {
        List<GenerationResult> handled = new CopyOnWriteArrayList<>();
        long failOnSequence = config.containsKey("failHandlerOnSequence") ? config.getInt("failHandlerOnSequence") : -1;

        ResultCollector collector = ResultCollector.builder()
                .collectApi(clients(baseUrl, config).api(GenerationCollectApi.class))
                .tenantId(config.getString("tenantId"))
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
                        config.getString("tenantId"),
                        new GenerateDocumentRequest()
                                .catalogId(config.getString("catalogId"))
                                .templateId(config.getString("templateId"))
                                .data(config.getJsonObject("data"))
                                .correlationId(config.getString("correlationId"))
                                .routingKey(config.getString("routingKey")));
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
                .tenantId(config.getString("tenantId"))
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
