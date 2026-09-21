// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.conformance

import app.epistola.client.epistolaMessageConverters
import app.epistola.client.api.ConsumersApi
import app.epistola.client.api.GenerationApi
import app.epistola.client.api.ImagesApi
import app.epistola.client.api.SystemApi
import app.epistola.client.api.TemplatesApi
import app.epistola.client.auth.ApiKeyAuth
import app.epistola.client.auth.JwtSigner
import app.epistola.client.collect.ResultCollector
import app.epistola.client.error.ProblemDetailException
import app.epistola.client.error.installProblemDetailHandler
import app.epistola.client.identity.ClientIdentity
import app.epistola.client.model.GenerateDocumentRequest
import app.epistola.client.model.PingRequest
import app.epistola.client.model.UpdateConsumerRequest
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.core.io.ByteArrayResource
import org.springframework.web.client.RestClient
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Kotlin client's conformance driver.
 *
 * It asks the conformance server what to do, does exactly that with the published client, and says
 * when it is finished. It asserts nothing: the server judges the requests it received, so that the
 * expectations live in one place for all four clients rather than four places that drift.
 *
 * See ../../README.md for the driver contract this implements.
 */
object Driver {

    private val mapper = ObjectMapper()
    private val control = HttpClient.newHttpClient()

    @JvmStatic
    fun main(args: Array<String>) {
        val baseUrl = args.firstOrNull() ?: error("usage: driver <conformance server base url>")
        val instruction = get("$baseUrl/__conformance/action")
        val config = instruction["config"] as ObjectNode

        try {
            when (val action = instruction["action"].asText()) {
                "ping" -> ping(baseUrl, config)
                "list-templates" -> listTemplates(baseUrl, config)
                "collect" -> collect(baseUrl, config)
                "problem" -> problem(baseUrl, config)
                "routing" -> routing(baseUrl, config)
                "generate-document" -> generateDocument(baseUrl, config)
                "update-consumer" -> updateConsumer(baseUrl, config)
                "download-document" -> downloadDocument(baseUrl, config)
                "list-images" -> listImages(baseUrl, config)
                "upload-image" -> uploadImage(baseUrl, config)
                "download-image" -> downloadImage(baseUrl, config)
                "delete-image" -> deleteImage(baseUrl, config)
                else -> error("unknown action $action")
            }
            done(baseUrl, null)
        } catch (e: Exception) {
            done(baseUrl, "${e::class.simpleName}: ${e.message}")
            System.err.println(e.stackTraceToString())
        }
    }

    // --- Actions ---

    private fun ping(baseUrl: String, config: ObjectNode) {
        SystemApi(restClient(baseUrl, config)).ping(
            PingRequest(
                name = "Conformance Driver",
                description = "Drives the Kotlin client through one conformance scenario",
                contact = "conformance@epistola.app",
            ),
        )
    }

    /** Reports what the client made of the last listing, `id` included while it is still served. */
    @Suppress("DEPRECATION")
    private fun listTemplates(baseUrl: String, config: ObjectNode) {
        val api = TemplatesApi(restClient(baseUrl, config))
        val templates = List(config.path("repeat").asInt(1)) {
            api.listTemplates(config["tenantId"].asText(), config["catalogId"].asText())
        }.last().items

        report(
            baseUrl,
            mapOf(
                "templateIds" to templates.joinToString(",") { it.id },
                "templateSlugs" to templates.joinToString(",") { it.slug.toString() },
            ),
        )
    }

    private fun problem(baseUrl: String, config: ObjectNode) {
        try {
            TemplatesApi(restClient(baseUrl, config))
                .listTemplates(config["tenantId"].asText(), config["catalogId"].asText())
            report(baseUrl, mapOf("problemTypeSlug" to "<no exception was thrown>"))
        } catch (e: ProblemDetailException) {
            report(
                baseUrl,
                mapOf(
                    "problemTypeSlug" to (e.typeSlug ?: "<null>"),
                    "problemStatus" to e.statusCode.value(),
                    "problemTitle" to (e.problem.title ?: "<null>"),
                    "problemFieldErrors" to e.errors.joinToString(",") { "${it.field}:${it.message}" },
                ),
            )
        }
    }

    private fun collect(baseUrl: String, config: ObjectNode) {
        val handled = CopyOnWriteArrayList<ResultCollector.GenerationResult>()
        val failOnSequence = config.path("failHandlerOnSequence").asLong(-1)

        val collector = ResultCollector.builder()
            .restClient(restClient(baseUrl, config))
            .tenantId(config["tenantId"].asText())
            .batchSize(config["batchSize"].asInt())
            .minInterval(Duration.ofMillis(config["minIntervalMs"].asLong()))
            .maxInterval(Duration.ofMillis(config["maxIntervalMs"].asLong()))
            .backoffMultiplier(config["multiplier"].asDouble())
            .registerShutdownHook(false)
            .handler {
                handled.add(it)
                if (it.sequence == failOnSequence) error("conformance: deliberate handler failure")
            }
            .build()

        val thread = Thread({ collector.start() }, "conformance-collector").apply { start() }
        Thread.sleep(config["runForMs"].asLong())
        collector.stop()
        thread.join(5_000)

        report(
            baseUrl,
            mapOf(
                "resultsHandled" to handled.size,
                "statuses" to handled.joinToString(",") { it.status },
                "correlationIds" to handled.joinToString(",") { it.correlationId ?: "" },
                "handledSequences" to handled.joinToString(",") { it.sequence.toString() },
                "partitionTotal" to (collector.partitionAssignment?.total ?: -1),
            ),
        )
    }

    /**
     * A request body with something in it: required fields, two of the optional ones set, the rest
     * left alone, and a free-form `data` object carrying every JSON type. What the server receives
     * is the generator's serialization, which is the part no client hand-writes and no client's own
     * tests inspect.
     */
    private fun generateDocument(baseUrl: String, config: ObjectNode) {
        GenerationApi(restClient(baseUrl, config)).generateDocument(
            config["tenantId"].asText(),
            GenerateDocumentRequest(
                catalogId = config["catalogId"].asText(),
                templateId = config["templateId"].asText(),
                data = mapper.convertValue(config["data"], Map::class.java),
                correlationId = config["correlationId"].asText(),
                routingKey = config["routingKey"].asText(),
            ),
        )
    }

    /**
     * A partial update that sets exactly one field. Everything the caller did not name must stay off
     * the wire: the contract reads a null on these as "clear this", so a serializer that writes
     * nulls for unset properties turns "rename this consumer" into "rename it and erase its
     * description, contact and expiry".
     */
    private fun updateConsumer(baseUrl: String, config: ObjectNode) {
        ConsumersApi(restClient(baseUrl, config)).updateConsumer(
            config["tenantId"].asText(),
            config["consumerId"].asText(),
            UpdateConsumerRequest(name = config["name"].asText()),
        )
    }

    /**
     * One poll to learn the partition assignment from the `_meta` line, then the routing helpers.
     * The values are reported rather than asserted here: the harness holds all four clients to the
     * same answers, which is the only way four independent murmur3 implementations stay in step.
     */
    private fun routing(baseUrl: String, config: ObjectNode) {
        val collector = ResultCollector.builder()
            .restClient(restClient(baseUrl, config))
            .tenantId(config["tenantId"].asText())
            .registerShutdownHook(false)
            .handler { }
            .build()

        collector.collectOnce()

        val keys = config.path("keys").map { it.asText() }
        report(
            baseUrl,
            mapOf(
                "partitionTotal" to (collector.partitionAssignment?.total ?: -1),
                "partitions" to keys.joinToString(",") { "$it:${collector.partitionFor(it)}" },
                "routed" to keys.joinToString(",") { "$it=${collector.routingKeyToMe(it)}" },
                "routedPartitions" to keys.joinToString(",") {
                    collector.routingKeyToMe(it)?.let(collector::partitionFor).toString()
                },
                "mineFlags" to keys.joinToString(",") { collector.isMyPartition(it).toString() },
            ),
        )
    }

    /**
     * Downloads a document and reports what arrived, byte for byte.
     *
     * The four clients return four different things here — a Resource, a FileParameter, a
     * bytearray — and the only thing that has to be identical is the content. A stack that decodes
     * a PDF as text corrupts every document it fetches, silently and irreversibly, so the fixture
     * is deliberately not valid UTF-8.
     */
    private fun downloadDocument(baseUrl: String, config: ObjectNode) {
        val resource = GenerationApi(restClient(baseUrl, config))
            .downloadDocument(config["tenantId"].asText(), UUID.fromString(config["documentId"].asText()))
        reportBytes(baseUrl, resource.inputStream.use { it.readBytes() })
    }

    /**
     * Lists images and reports what the client made of them. Keys may be UUIDs or readable names, and
     * an SVG's dimensions are null, which must stay null rather than become 0.
     */
    private fun listImages(baseUrl: String, config: ObjectNode) {
        val images = ImagesApi(restClient(baseUrl, config))
            .listImages(config["tenantId"].asText(), config["catalogId"].asText(), search = config["search"].asText())
            .items

        report(
            baseUrl,
            mapOf(
                "imageSlugs" to images.joinToString(",") { it.slug },
                "widths" to images.joinToString(",") { it.width.toString() },
                "heights" to images.joinToString(",") { it.height.toString() },
                "mediaTypes" to images.joinToString(",") { it.mediaType },
            ),
        )
    }

    /**
     * Uploads an image from bytes the way the README tells consumers to: as a Resource that reports a
     * filename, because a part without one is not a file to a multipart parser. Spring derives the
     * part's content type from that filename.
     */
    private fun uploadImage(baseUrl: String, config: ObjectNode) {
        val filename = config["filename"].asText()
        val file = object : ByteArrayResource(Base64.getDecoder().decode(config["fileBase64"].asText())) {
            override fun getFilename() = filename
        }
        val image = ImagesApi(restClient(baseUrl, config)).uploadImage(
            config["tenantId"].asText(),
            config["catalogId"].asText(),
            file,
            name = config["name"].asText(),
        )

        report(baseUrl, mapOf("imageSlug" to image.slug, "imageName" to image.name))
    }

    private fun downloadImage(baseUrl: String, config: ObjectNode) {
        val resource = ImagesApi(restClient(baseUrl, config)).downloadImageContent(
            config["tenantId"].asText(),
            config["catalogId"].asText(),
            config["imageSlug"].asText(),
        )
        reportBytes(baseUrl, resource.inputStream.use { it.readBytes() })
    }

    private fun deleteImage(baseUrl: String, config: ObjectNode) {
        ImagesApi(restClient(baseUrl, config)).deleteImage(
            config["tenantId"].asText(),
            config["catalogId"].asText(),
            config["imageSlug"].asText(),
            force = config["force"].asBoolean(),
        )
    }

    private fun reportBytes(baseUrl: String, bytes: ByteArray) = report(
        baseUrl,
        mapOf(
            "byteLength" to bytes.size,
            "sha256" to MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        ),
    )

    // --- Client assembly ---

    /**
     * Builds the client the way the README tells consumers to: identity first, then auth, then the
     * problem handler. The API base path is part of the contract's `servers` entry, so the driver
     * appends it rather than the harness serving the API at the root.
     */
    private fun restClient(baseUrl: String, config: ObjectNode): RestClient {
        val identity = ClientIdentity.builder()
            .nodeId(config["nodeId"].asText())
            .apply {
                config.path("products").forEach { product ->
                    product(product["name"].asText(), product["version"].asText())
                }
            }
            .build()

        val builder = RestClient.builder()
            .baseUrl("$baseUrl/api")
            .epistolaMessageConverters()
            .requestInterceptor(identity.interceptor())
            .installProblemDetailHandler()

        when (config.path("auth").asText("none")) {
            "api-key" -> builder.requestInterceptor(ApiKeyAuth.of(config["apiKey"].asText()).interceptor())
            "jwt" -> builder.requestInterceptor(
                JwtSigner.builder()
                    .consumerId(config["consumerId"].asText())
                    .privateKey(JwtSigner.parsePrivateKeyPem(config["privateKeyPem"].asText()))
                    .tokenLifetime(Duration.ofSeconds(config["tokenLifetimeSeconds"].asLong()))
                    .build()
                    .interceptor(),
            )
        }

        return builder.build()
    }

    // --- Control plane ---

    private fun get(url: String): JsonNode {
        val response = control.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return mapper.readTree(response.body())
    }

    private fun post(url: String, body: Any) {
        control.send(
            HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.discarding(),
        )
    }

    private fun report(baseUrl: String, values: Map<String, Any>) = post("$baseUrl/__conformance/report", values)

    private fun done(baseUrl: String, error: String?) = post("$baseUrl/__conformance/done", if (error == null) emptyMap() else mapOf("error" to error))
}
