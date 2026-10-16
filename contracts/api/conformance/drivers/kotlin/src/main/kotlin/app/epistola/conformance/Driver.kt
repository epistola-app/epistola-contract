// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.conformance

import app.epistola.client.epistolaMessageConverters
import app.epistola.client.api.CatalogsApi
import app.epistola.client.api.ConsumersApi
import app.epistola.client.api.EnvironmentsApi
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
import app.epistola.client.model.CatalogDependencyDto
import app.epistola.client.model.CatalogDependencyRequirement
import app.epistola.client.model.CatalogVersionStatus
import app.epistola.client.model.CreateCatalogVersionRequest
import app.epistola.client.model.IncomingChangeResolution
import app.epistola.client.model.IncomingChangeState
import app.epistola.client.model.ReleaseCatalogVersionRequest
import app.epistola.client.model.RenumberCatalogVersionRequest
import app.epistola.client.model.ResolveIncomingChangeRequest
import app.epistola.client.model.GenerateDocumentRequest
import app.epistola.client.model.PingRequest
import app.epistola.client.model.UpdateCatalogDependenciesRequest
import app.epistola.client.model.UpdateConsumerRequest
import app.epistola.client.validation.schema.TemplateDataValidationException
import app.epistola.client.validation.schema.TemplateSchemaValidator
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
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
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
                "validate-template-data" -> validateTemplateData(baseUrl, config)
                "routing" -> routing(baseUrl, config)
                "generate-document" -> generateDocument(baseUrl, config)
                "update-consumer" -> updateConsumer(baseUrl, config)
                "download-document" -> downloadDocument(baseUrl, config)
                "list-images" -> listImages(baseUrl, config)
                "upload-image" -> uploadImage(baseUrl, config)
                "download-image" -> downloadImage(baseUrl, config)
                "delete-image" -> deleteImage(baseUrl, config)
                "list-catalog-dependencies" -> listCatalogDependencies(baseUrl, config)
                "update-catalog-dependencies" -> updateCatalogDependencies(baseUrl, config)
                "create-catalog-version" -> createCatalogVersion(baseUrl, config)
                "list-catalog-versions" -> listCatalogVersions(baseUrl, config)
                "get-catalog-version" -> getCatalogVersion(baseUrl, config)
                "renumber-catalog-version" -> renumberCatalogVersion(baseUrl, config)
                "preview-catalog-version-renumber" -> previewCatalogVersionRenumber(baseUrl, config)
                "delete-catalog-version" -> deleteCatalogVersion(baseUrl, config)
                "release-catalog-version" -> releaseCatalogVersion(baseUrl, config)
                "list-incoming-changes" -> listIncomingChanges(baseUrl, config)
                "resolve-incoming-change" -> resolveIncomingChange(baseUrl, config)
                "deploy-release" -> deployRelease(baseUrl, config)
                "undeploy-release" -> undeployRelease(baseUrl, config)
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
            api.listTemplates(config["tenantKey"].asText(), config["registry"].asText(), config["namespace"].asText(), config["catalogKey"].asText(), config["catalogVersion"].asText())
        }.last().items

        report(
            baseUrl,
            mapOf(
                "templateKeys" to templates.joinToString(",") { it.key.toString() },
            ),
        )
    }

    private fun problem(baseUrl: String, config: ObjectNode) {
        try {
            TemplatesApi(restClient(baseUrl, config))
                .listTemplates(
                    config["tenantKey"].asText(),
                    config["registry"].asText(),
                    config["namespace"].asText(),
                    config["catalogKey"].asText(),
                    config["catalogVersion"].asText(),
                )
            report(baseUrl, mapOf("problemTypeSlug" to "<no exception was thrown>"))
        } catch (e: ProblemDetailException) {
            report(
                baseUrl,
                mapOf(
                    "problemTypeSlug" to (e.typeSlug ?: "<null>"),
                    "problemStatus" to e.statusCode.value(),
                    "problemTitle" to (e.problem.title ?: "<null>"),
                    "problemFieldErrors" to e.errors.joinToString(",") { "${it.field}:${it.message}" },
                    "problemMissingFields" to e.missingFields.joinToString(",") { "${it.path}:${it.required}" },
                    "problemInvalidFields" to e.invalidFields.joinToString(",") { "${it.path}:${it.keyword}" },
                ),
            )
        }
    }

    /** Reports the failures the client surfaced, which is the shape every client owes its callers. */
    private fun validateTemplateData(baseUrl: String, config: ObjectNode) {
        val validator = TemplateSchemaValidator(TemplatesApi(restClient(baseUrl, config)))
        try {
            validator.validate(
                config["tenantKey"].asText(),
                config["registry"].asText(),
                config["namespace"].asText(),
                config["catalogKey"].asText(),
                config["templateKey"].asText(),
                mapper.convertValue(config["data"] ?: mapper.createObjectNode(), Map::class.java),
            )
            report(
                baseUrl,
                mapOf(
                    "validationFailurePaths" to "<accepted>",
                    "validationFailureKeywords" to "<accepted>",
                ),
            )
        } catch (e: TemplateDataValidationException) {
            report(
                baseUrl,
                mapOf(
                    "validationFailurePaths" to e.errors.joinToString(",") { it.path },
                    "validationFailureKeywords" to e.errors.joinToString(",") { it.keyword ?: "<null>" },
                ),
            )
        }
    }

    private fun collect(baseUrl: String, config: ObjectNode) {
        val handled = CopyOnWriteArrayList<ResultCollector.GenerationResult>()
        val failOnSequence = config.path("failHandlerOnSequence").asLong(-1)

        val collector = ResultCollector.builder()
            .restClient(restClient(baseUrl, config))
            .tenantKey(config["tenantKey"].asText())
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
                "releases" to handled.joinToString(",") {
                    "${it.registry ?: "null"}/${it.namespace ?: "null"}/${it.catalogKey ?: "null"}@${it.releaseVersion ?: "null"}"
                },
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
            config["tenantKey"].asText(),
            GenerateDocumentRequest(
                registry = config.optionalText("registry"),
                namespace = config["namespace"].asText(),
                catalogKey = config["catalogKey"].asText(),
                templateKey = config["templateKey"].asText(),
                data = mapper.convertValue(config["data"], Map::class.java),
                version = config.optionalText("version"),
                environmentKey = config.optionalText("environmentKey"),
                correlationId = config.optionalText("correlationId"),
                routingKey = config.optionalText("routingKey"),
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
            config["tenantKey"].asText(),
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
            .tenantKey(config["tenantKey"].asText())
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
            .downloadDocument(config["tenantKey"].asText(), UUID.fromString(config["documentId"].asText()))
        reportBytes(baseUrl, resource.inputStream.use { it.readBytes() })
    }

    /**
     * Lists images and reports what the client made of them. Keys may be UUIDs or readable names, and
     * an SVG's dimensions are null, which must stay null rather than become 0.
     */
    private fun listImages(baseUrl: String, config: ObjectNode) {
        val images = ImagesApi(restClient(baseUrl, config))
            .listImages(
                config["tenantKey"].asText(),
                config["registry"].asText(),
                config["namespace"].asText(),
                config["catalogKey"].asText(),
                config["catalogVersion"].asText(),
                search = config["search"].asText(),
            )
            .items

        report(
            baseUrl,
            mapOf(
                "imageKeys" to images.joinToString(",") { it.key },
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
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["catalogVersion"].asText(),
            file,
            name = config["name"].asText(),
        )

        report(baseUrl, mapOf("imageKey" to image.key, "imageName" to image.name))
    }

    private fun downloadImage(baseUrl: String, config: ObjectNode) {
        val resource = ImagesApi(restClient(baseUrl, config)).downloadImageContent(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["catalogVersion"].asText(),
            config["imageKey"].asText(),
        )
        reportBytes(baseUrl, resource.inputStream.use { it.readBytes() })
    }

    private fun deleteImage(baseUrl: String, config: ObjectNode) {
        ImagesApi(restClient(baseUrl, config)).deleteImage(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["catalogVersion"].asText(),
            config["imageKey"].asText(),
            force = config["force"].asBoolean(),
        )
    }

    private fun listCatalogDependencies(baseUrl: String, config: ObjectNode) {
        val dependencies = CatalogsApi(restClient(baseUrl, config)).listCatalogDependencies(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["catalogVersion"].asText(),
        ).items
        reportDependencies(baseUrl, dependencies)
    }

    /** Sends the declared dependencies exactly as configured and reports what came back, in order. */
    private fun updateCatalogDependencies(baseUrl: String, config: ObjectNode) {
        val dependencies = CatalogsApi(restClient(baseUrl, config)).updateCatalogDependencies(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["catalogVersion"].asText(),
            UpdateCatalogDependenciesRequest(
                dependencies = config.path("dependencies").map {
                    CatalogDependencyRequirement(
                        registry = it.optionalText("registry"),
                        namespace = it["namespace"].asText(),
                        catalogKey = it["catalogKey"].asText(),
                        version = it["version"].asText(),
                    )
                },
            ),
        ).items
        reportDependencies(baseUrl, dependencies)
    }

    private fun reportDependencies(baseUrl: String, dependencies: List<CatalogDependencyDto>) = report(
        baseUrl,
        mapOf(
            "registries" to dependencies.joinToString(",") { it.registry },
            "namespaces" to dependencies.joinToString(",") { it.namespace },
            "catalogKeys" to dependencies.joinToString(",") { it.catalogKey },
            "versions" to dependencies.joinToString(",") { it.version },
            "resolvedVersions" to dependencies.joinToString(",") { it.resolvedVersion ?: "null" },
        ),
    )

    // --- Catalog versions ---

    /** Opens a draft; `from` is sent only when configured, so an absent one stays off the wire. */
    private fun createCatalogVersion(baseUrl: String, config: ObjectNode) {
        val version = CatalogsApi(restClient(baseUrl, config)).createCatalogVersion(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            CreateCatalogVersionRequest(version = config["version"].asText(), from = config.optionalText("from")),
        )
        report(
            baseUrl,
            mapOf(
                "version" to version.version,
                "status" to version.status.value,
                "base" to (version.base ?: "null"),
                "createdFrom" to (version.createdFrom ?: "null"),
                "releasedAt" to timestamp(version.releasedAt),
            ),
        )
    }

    private fun listCatalogVersions(baseUrl: String, config: ObjectNode) {
        val versions = CatalogsApi(restClient(baseUrl, config)).listCatalogVersions(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            status = config.optionalText("status")?.let { CatalogVersionStatus.decode(it) },
        ).items
        report(
            baseUrl,
            mapOf(
                "versions" to versions.joinToString(",") { it.version },
                "statuses" to versions.joinToString(",") { it.status.value },
                "bases" to versions.joinToString(",") { it.base ?: "null" },
                "openConflicts" to versions.joinToString(",") { it.openConflicts?.toString() ?: "null" },
            ),
        )
    }

    /** Reports the release data, with each dependency named as registry/namespace/catalogKey. */
    private fun getCatalogVersion(baseUrl: String, config: ObjectNode) {
        val version = CatalogsApi(restClient(baseUrl, config)).getCatalogVersion(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["catalogVersion"].asText(),
        )
        report(
            baseUrl,
            mapOf(
                "version" to version.version,
                "status" to version.status.value,
                "releasedAt" to timestamp(version.releasedAt),
                "fingerprint" to (version.fingerprint ?: "null"),
                "dependencies" to version.dependencies.joinToString(",") {
                    "${it.registry}/${it.namespace}/${it.catalogKey}@${it.releaseVersion}"
                },
                "deployedTo" to version.deployedTo.joinToString(","),
            ),
        )
    }

    /**
     * Renumbers a draft. The acknowledgement is passed explicitly, null when not configured, because
     * the generated request defaults it to `false` and a default must not reach the wire uninvited.
     */
    private fun renumberCatalogVersion(baseUrl: String, config: ObjectNode) {
        val version = CatalogsApi(restClient(baseUrl, config)).renumberCatalogVersion(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["catalogVersion"].asText(),
            RenumberCatalogVersionRequest(
                version = config["version"].asText(),
                acknowledgeWarnings = config.get("acknowledgeWarnings")?.takeUnless { it.isNull }?.asBoolean(),
            ),
        )
        report(baseUrl, mapOf("version" to version.version, "status" to version.status.value))
    }

    /** Surfaces every warning kind, including ones this client was not built to know. */
    private fun previewCatalogVersionRenumber(baseUrl: String, config: ObjectNode) {
        val preview = CatalogsApi(restClient(baseUrl, config)).previewCatalogVersionRenumber(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["catalogVersion"].asText(),
            config["to"].asText(),
        )
        report(
            baseUrl,
            mapOf(
                "newBase" to (preview.newBase ?: "null"),
                "warningKinds" to preview.warnings.joinToString(",") { it.kind },
            ),
        )
    }

    private fun deleteCatalogVersion(baseUrl: String, config: ObjectNode) {
        CatalogsApi(restClient(baseUrl, config)).deleteCatalogVersion(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["catalogVersion"].asText(),
        )
    }

    private fun releaseCatalogVersion(baseUrl: String, config: ObjectNode) {
        val response = CatalogsApi(restClient(baseUrl, config)).releaseCatalogVersion(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["catalogVersion"].asText(),
            ReleaseCatalogVersionRequest(notes = config.optionalText("notes")),
        )
        report(
            baseUrl,
            mapOf(
                "version" to response.catalogVersion.version,
                "status" to response.catalogVersion.status.value,
                "flowedInto" to response.flowedInto.joinToString(",") { "${it.version}:${it.merged}:${it.conflicts}" },
                "openedDraft" to (response.openedDraft ?: "null"),
            ),
        )
    }

    private fun listIncomingChanges(baseUrl: String, config: ObjectNode) {
        val changes = CatalogsApi(restClient(baseUrl, config)).listIncomingChanges(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["catalogVersion"].asText(),
            state = config.optionalText("state")?.let { IncomingChangeState.decode(it) },
        ).items
        report(
            baseUrl,
            mapOf(
                "ids" to changes.joinToString(",") { it.id },
                "nodes" to changes.joinToString(",") { it.node ?: "null" },
                "states" to changes.joinToString(",") { it.state.value },
                "resolutions" to changes.joinToString(",") { it.resolution?.value ?: "null" },
            ),
        )
    }

    private fun resolveIncomingChange(baseUrl: String, config: ObjectNode) {
        val change = CatalogsApi(restClient(baseUrl, config)).resolveIncomingChange(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["catalogVersion"].asText(),
            config["changeId"].asText(),
            ResolveIncomingChangeRequest(
                resolution = IncomingChangeResolution.decode(config["resolution"].asText()),
            ),
        )
        report(
            baseUrl,
            mapOf(
                "id" to change.id,
                "state" to change.state.value,
                "resolution" to (change.resolution?.value ?: "null"),
            ),
        )
    }

    /** An instant as ISO 8601 with seconds, which `OffsetDateTime.toString()` drops when zero. */
    private fun timestamp(value: OffsetDateTime?): String = value?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) ?: "null"

    /**
     * Deploys one release; the registry and namespace go into the path as given, and
     * `includeDependencies` is passed only when configured, so an unset one sends nothing. A refusal
     * is reported by its problem, with each missing dependency release and why.
     */
    private fun deployRelease(baseUrl: String, config: ObjectNode) {
        val deployment = try {
            EnvironmentsApi(restClient(baseUrl, config)).deployRelease(
                config["tenantKey"].asText(),
                config["environmentKey"].asText(),
                config["registry"].asText(),
                config["namespace"].asText(),
                config["catalogKey"].asText(),
                config["releaseVersion"].asText(),
                config.get("includeDependencies")?.takeUnless { it.isNull }?.asBoolean(),
            )
        } catch (e: ProblemDetailException) {
            report(
                baseUrl,
                mapOf(
                    "problemTypeSlug" to (e.typeSlug ?: "<null>"),
                    "problemStatus" to e.statusCode.value(),
                    "problemMissingDependencies" to e.missingDependencies.joinToString(",") {
                        "${it.registry}/${it.namespace}/${it.catalogKey}@${it.releaseVersion}:${it.reason}"
                    },
                ),
            )
            return
        }
        report(
            baseUrl,
            mapOf(
                "registry" to deployment.registry,
                "namespace" to deployment.namespace,
                "catalogKey" to deployment.catalogKey,
                "releaseVersion" to deployment.releaseVersion,
            ),
        )
    }

    private fun undeployRelease(baseUrl: String, config: ObjectNode) {
        EnvironmentsApi(restClient(baseUrl, config)).undeployRelease(
            config["tenantKey"].asText(),
            config["environmentKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["releaseVersion"].asText(),
        )
    }

    /** An optional value from `config`: absent or null stays null, so the request omits it. */
    private fun JsonNode.optionalText(name: String): String? = get(name)?.takeUnless { it.isNull }?.asText()

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
