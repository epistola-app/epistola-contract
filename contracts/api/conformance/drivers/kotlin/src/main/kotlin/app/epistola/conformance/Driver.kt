// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.conformance

import app.epistola.client.epistolaMessageConverters
import app.epistola.client.api.AttributesApi
import app.epistola.client.api.CatalogsApi
import app.epistola.client.api.CodeListsApi
import app.epistola.client.api.ConsumersApi
import app.epistola.client.api.EnvironmentsApi
import app.epistola.client.api.GenerationApi
import app.epistola.client.api.ImagesApi
import app.epistola.client.api.StencilsApi
import app.epistola.client.api.SystemApi
import app.epistola.client.api.TemplatesApi
import app.epistola.client.api.TenantsApi
import app.epistola.client.api.VariantsApi
import app.epistola.client.auth.ApiKeyAuth
import app.epistola.client.auth.JwtSigner
import app.epistola.client.collect.ResultCollector
import app.epistola.client.error.ProblemDetailException
import app.epistola.client.error.installProblemDetailHandler
import app.epistola.client.identity.ClientIdentity
import app.epistola.client.model.CatalogChangesDto
import app.epistola.client.model.CatalogDependencyDto
import app.epistola.client.model.CatalogDependencyRequirement
import app.epistola.client.model.CatalogMergeSide
import app.epistola.client.model.CatalogPresetDto
import app.epistola.client.model.CatalogReleaseStatus
import app.epistola.client.model.CatalogThemeReference
import app.epistola.client.model.CreateCatalogReleaseRequest
import app.epistola.client.model.CreateEnvironmentRequest
import app.epistola.client.model.CreateTemplateRequest
import app.epistola.client.model.DataExampleDto
import app.epistola.client.model.EnvironmentDto
import app.epistola.client.model.GenerateDocumentRequest
import app.epistola.client.model.MergeIntoCatalogDraftRequest
import app.epistola.client.model.Node
import app.epistola.client.model.PingRequest
import app.epistola.client.model.PublishReleaseToRegistryRequest
import app.epistola.client.model.ReleaseCatalogDraftRequest
import app.epistola.client.model.InstalledCatalogReleaseDto
import app.epistola.client.model.TemplateDocument
import app.epistola.client.model.TemplateDto
import app.epistola.client.model.ThemeRef
import app.epistola.client.model.UpdateAttributeRequest
import app.epistola.client.model.UpdateAttributeRequestCodeListBinding
import app.epistola.client.model.UpdateCatalogDependenciesRequest
import app.epistola.client.model.UpdateCatalogReleaseRequest
import app.epistola.client.model.UpdateCodeListEntryHiddenRequest
import app.epistola.client.model.UpdateConsumerRequest
import app.epistola.client.model.UpdateEnvironmentRequest
import app.epistola.client.model.UpdateStencilContentRequest
import app.epistola.client.model.UpdateTemplateRequest
import app.epistola.client.model.UpdateTenantRequest
import app.epistola.client.model.UpdateVariantContentRequest
import app.epistola.client.model.VariantSelectionAttribute
import app.epistola.client.validation.schema.TemplateDataValidationException
import app.epistola.client.validation.schema.TemplateSchemaValidator
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
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
                "list-documents" -> listDocuments(baseUrl, config)
                "list-images" -> listImages(baseUrl, config)
                "upload-image" -> uploadImage(baseUrl, config)
                "import-catalog" -> importCatalog(baseUrl, config)
                "install-catalog-release" -> installCatalogRelease(baseUrl, config)
                "list-available-catalog-releases" -> listAvailableCatalogReleases(baseUrl, config)
                "download-image" -> downloadImage(baseUrl, config)
                "delete-image" -> deleteImage(baseUrl, config)
                "list-catalog-dependencies" -> listCatalogDependencies(baseUrl, config)
                "update-catalog-dependencies" -> updateCatalogDependencies(baseUrl, config)
                "create-catalog-release" -> createCatalogRelease(baseUrl, config)
                "list-catalog-releases" -> listCatalogReleases(baseUrl, config)
                "get-catalog-release" -> getCatalogRelease(baseUrl, config)
                "update-catalog-release" -> updateCatalogRelease(baseUrl, config)
                "set-catalog-release-theme-settings" -> setCatalogReleaseThemeSettings(baseUrl, config)
                "preview-catalog-draft-release" -> previewCatalogDraftRelease(baseUrl, config)
                "delete-catalog-release" -> deleteCatalogRelease(baseUrl, config)
                "release-catalog-draft" -> releaseCatalogDraft(baseUrl, config)
                "preview-catalog-draft-merge" -> previewCatalogDraftMerge(baseUrl, config)
                "get-catalog-draft-merge-sides" -> getCatalogDraftMergeSides(baseUrl, config)
                "merge-into-catalog-draft" -> mergeIntoCatalogDraft(baseUrl, config)
                "list-release-registries" -> listReleaseRegistries(baseUrl, config)
                "list-registry-releases" -> listRegistryReleases(baseUrl, config)
                "install-registry-release" -> installRegistryRelease(baseUrl, config)
                "publish-release-to-registry" -> publishReleaseToRegistry(baseUrl, config)
                "deploy-catalog-release" -> deployCatalogRelease(baseUrl, config)
                "undeploy-catalog-release" -> undeployCatalogRelease(baseUrl, config)
                "create-environment" -> createEnvironment(baseUrl, config)
                "create-template" -> createTemplate(baseUrl, config)
                "update-environment" -> updateEnvironment(baseUrl, config)
                "update-tenant" -> updateTenant(baseUrl, config)
                "update-template" -> updateTemplate(baseUrl, config)
                "update-code-list-entry-hidden" -> updateCodeListEntryHidden(baseUrl, config)
                "update-attribute" -> updateAttribute(baseUrl, config)
                "update-variant-content" -> updateVariantContent(baseUrl, config)
                "get-variant-content" -> getVariantContent(baseUrl, config)
                "mark-variant-ready" -> markVariantReady(baseUrl, config)
                "mark-template-ready" -> markTemplateReady(baseUrl, config)
                "get-stencil-content" -> getStencilContent(baseUrl, config)
                "update-stencil-content" -> updateStencilContent(baseUrl, config)
                "mark-stencil-ready" -> markStencilReady(baseUrl, config)
                "list-stencil-usage" -> listStencilUsage(baseUrl, config)
                "get-catalog-changes" -> getCatalogChanges(baseUrl, config)
                "mark-catalog-ready" -> markCatalogReady(baseUrl, config)
                "list-environment-deployments" -> listEnvironmentDeployments(baseUrl, config)
                "list-deployment-history" -> listDeploymentHistory(baseUrl, config)
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
            api.listTemplates(config["tenantKey"].asText(), config["registry"].asText(), config["namespace"].asText(), config["catalogKey"].asText(), config["release"].asText())
        }.last().items

        report(
            baseUrl,
            mapOf(
                "templateKeys" to templates.joinToString(",") { it.key.toString() },
                "templateDescriptions" to templates.joinToString("|") { it.description ?: "null" },
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
                    config["release"].asText(),
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
                    "${it.registry}/${it.namespace}/${it.catalogKey}@${it.version ?: "null"}#${it.releaseId ?: "null"}:${it.releaseName ?: "null"}"
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
        val response = GenerationApi(restClient(baseUrl, config)).generateDocumentWithHttpInfo(
            config["tenantKey"].asText(),
            GenerateDocumentRequest(
                registry = config.optionalText("registry"),
                namespace = config["namespace"].asText(),
                catalogKey = config["catalogKey"].asText(),
                templateKey = config["templateKey"].asText(),
                variantKey = config.optionalText("variantKey"),
                attributes = config["attributes"]?.takeUnless { it.isNull }?.map { attribute ->
                    VariantSelectionAttribute(
                        key = attribute["key"].asText(),
                        `value` = attribute["value"].asText(),
                        catalogKey = attribute.optionalText("catalogKey"),
                        required = attribute["required"]?.takeUnless { it.isNull }?.asBoolean() ?: true,
                    )
                },
                data = mapper.convertValue(config["data"], Map::class.java),
                versionRange = config.optionalText("versionRange"),
                environmentKey = config.optionalText("environmentKey"),
                correlationId = config.optionalText("correlationId"),
                routingKey = config.optionalText("routingKey"),
            ),
        )
        val job = response.body!!
        report(
            baseUrl,
            mapOf(
                "requestId" to job.requestId.toString(),
                "status" to job.status.value,
                "jobType" to job.jobType.value,
                "total" to job.total,
                "location" to (response.headers.getFirst(HttpHeaders.LOCATION) ?: "null"),
            ),
        )
    }

    /**
     * A partial update that sets only the members the scenario names. Everything the caller did not
     * name must stay off the wire: a server built to a contract before 2.0 reads a null on these as
     * "clear this", so a serializer that writes nulls for unset properties turns "rename this
     * consumer" into "rename it and erase its description, contact and expiry". An empty
     * `description` is a value, and goes out as is.
     */
    private fun updateConsumer(baseUrl: String, config: ObjectNode) = conditionalUpdate(baseUrl, {
        ConsumersApi(restClient(baseUrl, config)).updateConsumerWithHttpInfo(
            config["tenantKey"].asText(),
            config["consumerId"].asText(),
            UpdateConsumerRequest(
                name = config.optionalText("name"),
                description = config.optionalText("description"),
                clearExpiresAt = config.optionalBoolean("clearExpiresAt"),
            ),
            ifMatch = config.optionalText("ifMatch"),
        )
    }) { consumer ->
        mapOf(
            "id" to consumer.id.toString(),
            "name" to consumer.name,
            "lastModified" to timestamp(consumer.lastModified),
        )
    }

    /** Renames the tenant, conditionally when `config.ifMatch` is set. */
    private fun updateTenant(baseUrl: String, config: ObjectNode) = conditionalUpdate(baseUrl, {
        TenantsApi(restClient(baseUrl, config)).updateTenantWithHttpInfo(
            config["tenantKey"].asText(),
            UpdateTenantRequest(name = config.optionalText("name")),
            ifMatch = config.optionalText("ifMatch"),
        )
    }) { tenant -> mapOf("key" to tenant.key, "name" to tenant.name) }

    /**
     * Replaces a template's data contract with `config.dataModel` and `config.dataExamples`, each only
     * when present, conditionally when `config.ifMatch` is set. The data model is free-form JSON,
     * passed as a caller holding that JSON would.
     */
    private fun updateTemplate(baseUrl: String, config: ObjectNode) = conditionalUpdate(baseUrl, {
        TemplatesApi(restClient(baseUrl, config)).updateTemplateWithHttpInfo(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["release"].asText(),
            config["templateKey"].asText(),
            UpdateTemplateRequest(
                dataModel = config.get("dataModel")?.takeUnless { it.isNull }?.let { mapper.convertValue(it, Map::class.java) },
                dataExamples = dataExamples(config),
            ),
            ifMatch = config.optionalText("ifMatch"),
        )
    }, ::templateFields)

    /**
     * Creates a template, with `config.dataModel` and `config.dataExamples` only when present. The data
     * model is free-form JSON, passed as a caller holding that JSON would.
     */
    private fun createTemplate(baseUrl: String, config: ObjectNode) {
        val response = TemplatesApi(restClient(baseUrl, config)).createTemplateWithHttpInfo(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["release"].asText(),
            CreateTemplateRequest(
                key = config["key"].asText(),
                name = config["name"].asText(),
                description = config.optionalText("description"),
                dataModel = config.get("dataModel")?.takeUnless { it.isNull }?.let { mapper.convertValue(it, Map::class.java) },
                dataExamples = dataExamples(config),
            ),
        )
        report(baseUrl, templateFields(response.body!!) + ("location" to (response.headers.getFirst(HttpHeaders.LOCATION) ?: "null")))
    }

    private fun dataExamples(config: ObjectNode) = config.get("dataExamples")?.takeUnless { it.isNull }?.map { example ->
        DataExampleDto(
            key = example["key"].asText(),
            name = example["name"].asText(),
            data = mapper.convertValue(example["data"], Map::class.java),
        )
    }

    /** A template's data contract as the client read it; `dataModelMembers` tells `{}` from null. */
    private fun templateFields(template: TemplateDto): Map<String, Any> {
        val dataModel = template.dataModel as? Map<*, *>
        return mapOf(
            "key" to template.key,
            "dataModelMembers" to (dataModel?.keys?.map { it.toString() }?.sorted()?.joinToString(",") ?: "null"),
            "dataModelType" to (dataModel?.get("type")?.toString() ?: "null"),
            "dataModelRequired" to ((dataModel?.get("required") as? List<*>)?.joinToString(",") ?: "null"),
            "dataExampleKeys" to template.dataExamples.orEmpty().joinToString(",") { it.key },
        )
    }

    /** Hides or shows one code list entry, conditionally when `config.ifMatch` is set. */
    private fun updateCodeListEntryHidden(baseUrl: String, config: ObjectNode) = conditionalUpdate(baseUrl, {
        CodeListsApi(restClient(baseUrl, config)).updateCodeListEntryHiddenWithHttpInfo(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["release"].asText(),
            config["codeListKey"].asText(),
            config["code"].asText(),
            UpdateCodeListEntryHiddenRequest(hidden = config["hidden"].asBoolean()),
            ifMatch = config.optionalText("ifMatch"),
        )
    }) { entry -> mapOf("code" to entry.code, "hidden" to (entry.hidden?.toString() ?: "null")) }

    /**
     * Changes an attribute's constraint with only the members the scenario names, conditionally when
     * `config.ifMatch` is set; a code-list binding names its catalog by alias, or by nothing for the
     * attribute's own.
     */
    private fun updateAttribute(baseUrl: String, config: ObjectNode) = conditionalUpdate(baseUrl, {
        AttributesApi(restClient(baseUrl, config)).updateAttributeWithHttpInfo(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["release"].asText(),
            config["attributeKey"].asText(),
            UpdateAttributeRequest(
                allowedValues = config.get("allowedValues")?.takeUnless { it.isNull }?.map { it.asText() },
                codeListBinding = config.get("codeListBinding")?.takeUnless { it.isNull }?.let {
                    UpdateAttributeRequestCodeListBinding(key = it["key"].asText(), catalogKey = it.optionalText("catalogKey"))
                },
                clearCodeListBinding = config.optionalBoolean("clearCodeListBinding"),
            ),
            ifMatch = config.optionalText("ifMatch"),
        )
    }) { attribute ->
        mapOf(
            "key" to attribute.key,
            "allowedValues" to attribute.allowedValues.joinToString(","),
            "codeListBinding" to (attribute.codeListBinding?.let { "${it.catalogKey ?: "null"}/${it.key}" } ?: "null"),
        )
    }

    /**
     * The shape every conditional update shares: call the operation's `…WithHttpInfo` variant, report
     * the fields it parsed plus the `ETag` response header exactly as received (quotes included,
     * `null` when absent), or the problem when the server refused it.
     */
    private fun <T : Any> conditionalUpdate(
        baseUrl: String,
        call: () -> ResponseEntity<T>,
        fields: (T) -> Map<String, Any>,
    ) {
        val response = try {
            call()
        } catch (e: ProblemDetailException) {
            report(baseUrl, mapOf("problemTypeSlug" to (e.typeSlug ?: "<null>"), "problemStatus" to e.statusCode.value()))
            return
        }
        report(baseUrl, fields(response.body!!) + ("etag" to (response.headers.getFirst(HttpHeaders.ETAG) ?: "null")))
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
     * Lists documents with the catalog and template filters the scenario sets, each sent only when
     * set, and reports what the client made of the page.
     */
    private fun listDocuments(baseUrl: String, config: ObjectNode) {
        val documents = GenerationApi(restClient(baseUrl, config))
            .listDocuments(
                config["tenantKey"].asText(),
                templateKey = config.optionalText("templateKey"),
                registry = config.optionalText("registry"),
                namespace = config.optionalText("namespace"),
                catalogKey = config.optionalText("catalogKey"),
            )
            .items

        report(
            baseUrl,
            mapOf(
                "catalogs" to documents.joinToString(",") { "${it.registry}/${it.namespace}/${it.catalogKey}" },
                "templateKeys" to documents.joinToString(",") { it.templateKey },
                "variantKeys" to documents.joinToString(",") { it.variantKey },
            ),
        )
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
                config["release"].asText(),
                q = config["q"].asText(),
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
            config["release"].asText(),
            file,
            name = config["name"].asText(),
        )

        report(baseUrl, mapOf("imageKey" to image.key, "imageName" to image.name))
    }

    /**
     * Imports a catalog archive from bytes, as a Resource that reports a filename, like an image
     * upload. `registry` and `namespace` go out only when configured. A refusal is reported by its
     * problem.
     */
    private fun importCatalog(baseUrl: String, config: ObjectNode) {
        val filename = config["filename"].asText()
        val file = object : ByteArrayResource(Base64.getDecoder().decode(config["fileBase64"].asText())) {
            override fun getFilename() = filename
        }
        val imported = try {
            CatalogsApi(restClient(baseUrl, config)).importCatalog(
                config["tenantKey"].asText(),
                file,
                registry = config.optionalText("registry"),
                namespace = config.optionalText("namespace"),
            )
        } catch (e: ProblemDetailException) {
            report(baseUrl, mapOf("problemTypeSlug" to (e.typeSlug ?: "<null>"), "problemStatus" to e.statusCode.value()))
            return
        }
        report(
            baseUrl,
            mapOf(
                "registry" to imported.registry,
                "namespace" to imported.namespace,
                "catalogKey" to imported.catalogKey,
                "version" to imported.version,
                "newlyInstalled" to imported.newlyInstalled.toString(),
                "total" to imported.total.toString(),
            ),
        )
    }

    /**
     * Installs a release a catalog's source offers, named in the path. A refusal or a
     * failing source is reported by its problem, missing dependencies included.
     */
    private fun installCatalogRelease(baseUrl: String, config: ObjectNode) {
        val installed = try {
            CatalogsApi(restClient(baseUrl, config)).installCatalogRelease(
                config["tenantKey"].asText(),
                config["registry"].asText(),
                config["namespace"].asText(),
                config["catalogKey"].asText(),
                config["version"].asText(),
            )
        } catch (e: ProblemDetailException) {
            report(
                baseUrl,
                mapOf(
                    "problemTypeSlug" to (e.typeSlug ?: "<null>"),
                    "problemStatus" to e.statusCode.value(),
                    "problemMissingDependencies" to e.missingDependencies.joinToString(",") {
                        "${it.registry}/${it.namespace}/${it.catalogKey}@${it.version}:${it.reason}"
                    },
                ),
            )
            return
        }
        report(
            baseUrl,
            mapOf(
                "registry" to installed.registry,
                "namespace" to installed.namespace,
                "catalogKey" to installed.catalogKey,
                "version" to installed.version,
                "newlyInstalled" to installed.newlyInstalled.toString(),
                "total" to installed.total.toString(),
                "installedDependencies" to installed.installedDependencies.orEmpty().joinToString(",") { installedRelease(it) },
                "presentDependencies" to installed.presentDependencies.orEmpty().joinToString(",") { installedRelease(it) },
            ),
        )
    }

    /**
     * Lists the releases a catalog's source offers that the tenant lacks, reporting
     * every one in order. A failing source is reported by its problem.
     */
    private fun listAvailableCatalogReleases(baseUrl: String, config: ObjectNode) {
        val releases = try {
            CatalogsApi(restClient(baseUrl, config)).listAvailableCatalogReleases(
                config["tenantKey"].asText(),
                config["registry"].asText(),
                config["namespace"].asText(),
                config["catalogKey"].asText(),
            )
        } catch (e: ProblemDetailException) {
            report(baseUrl, mapOf("problemTypeSlug" to (e.typeSlug ?: "<null>"), "problemStatus" to e.statusCode.value()))
            return
        }
        report(
            baseUrl,
            mapOf(
                "registry" to releases.registry,
                "namespace" to releases.namespace,
                "catalogKey" to releases.catalogKey,
                "availableComparedTo" to releases.available.joinToString(";") { "${it.version}=${it.comparedTo ?: "null"}" },
                "availableChanges" to releases.available.joinToString(";") { release ->
                    "${release.version}=" + release.changes.joinToString("+") { "${it.type}/${it.key}:${it.change.value}" }
                },
                "availableDependencies" to releases.available.joinToString(";") { release ->
                    "${release.version}=" + release.dependencies.joinToString("+") {
                        "${it.registry}/${it.namespace}/${it.catalogKey}@${it.version}:${it.present}"
                    }
                },
            ),
        )
    }

    private fun downloadImage(baseUrl: String, config: ObjectNode) {
        val resource = ImagesApi(restClient(baseUrl, config)).downloadImageContent(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["release"].asText(),
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
            config["release"].asText(),
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
            config["release"].asText(),
        ).items
        reportDependencies(baseUrl, dependencies)
    }

    /**
     * Sends the declared dependencies, each pinning an exact release or naming a draft, exactly as
     * configured (an unconfigured `version` or `releaseId` stays off the wire) and reports what
     * came back, in order, a null value as `null`.
     */
    private fun updateCatalogDependencies(baseUrl: String, config: ObjectNode) = conditionalUpdate(baseUrl, {
        CatalogsApi(restClient(baseUrl, config)).updateCatalogDependenciesWithHttpInfo(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["release"].asText(),
            UpdateCatalogDependenciesRequest(
                dependencies = config.path("dependencies").map {
                    CatalogDependencyRequirement(
                        alias = it.optionalText("alias"),
                        registry = it.optionalText("registry"),
                        namespace = it["namespace"].asText(),
                        catalogKey = it["catalogKey"].asText(),
                        version = it.optionalText("version"),
                        releaseId = it.optionalText("releaseId"),
                    )
                },
            ),
            ifMatch = config.optionalText("ifMatch"),
        )
    }) { dependencyFields(it.items) }

    private fun reportDependencies(baseUrl: String, dependencies: List<CatalogDependencyDto>) = report(baseUrl, dependencyFields(dependencies))

    private fun dependencyFields(dependencies: List<CatalogDependencyDto>): Map<String, Any> = (
        mapOf(
            "aliases" to dependencies.joinToString(",") { it.alias },
            "registries" to dependencies.joinToString(",") { it.registry },
            "namespaces" to dependencies.joinToString(",") { it.namespace },
            "catalogKeys" to dependencies.joinToString(",") { it.catalogKey },
            "versions" to dependencies.joinToString(",") { it.version ?: "null" },
            "releaseIds" to dependencies.joinToString(",") { it.releaseId ?: "null" },
            "contentHashes" to dependencies.joinToString(",") { it.contentHash ?: "null" },
        )
        )

    // --- Catalog releases ---

    /** Starts a draft release; `from` (a version or a draft id) is sent only when configured, so an absent one stays off the wire. */
    private fun createCatalogRelease(baseUrl: String, config: ObjectNode) {
        val response = CatalogsApi(restClient(baseUrl, config)).createCatalogReleaseWithHttpInfo(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            CreateCatalogReleaseRequest(name = config["name"].asText(), from = config.optionalText("from")),
        )
        val release = response.body!!
        report(
            baseUrl,
            mapOf(
                "version" to (release.version ?: "null"),
                "releaseId" to release.releaseId,
                "name" to (release.name ?: "null"),
                "status" to release.status.value,
                "base" to (release.base ?: "null"),
                "createdFrom" to (release.createdFrom?.toString() ?: "null"),
                "releasedAt" to timestamp(release.releasedAt),
                "location" to (response.headers.getFirst(HttpHeaders.LOCATION) ?: "null"),
            ),
        )
    }

    private fun listCatalogReleases(baseUrl: String, config: ObjectNode) {
        val releases = CatalogsApi(restClient(baseUrl, config)).listCatalogReleases(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            status = config.optionalText("status")?.let { CatalogReleaseStatus.decode(it) },
            contentHash = config.optionalText("contentHash"),
        ).items
        report(
            baseUrl,
            mapOf(
                "versions" to releases.joinToString(",") { it.version ?: "null" },
                "releaseIds" to releases.joinToString(",") { it.releaseId },
                "names" to releases.joinToString("|") { it.name ?: "null" },
                "statuses" to releases.joinToString(",") { it.status.value },
                "bases" to releases.joinToString(",") { it.base ?: "null" },
                "createdFrom" to releases.joinToString(",") { it.createdFrom?.toString() ?: "null" },
                "behindCounts" to releases.joinToString(",") { it.behind.size.toString() },
                "contentHashes" to releases.joinToString(",") { it.contentHash ?: "null" },
            ),
        )
    }

    /** Reports the release data, with each dependency named as registry/namespace/catalogKey. */
    private fun getCatalogRelease(baseUrl: String, config: ObjectNode) {
        val release = CatalogsApi(restClient(baseUrl, config)).getCatalogRelease(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["release"].asText(),
        )
        report(
            baseUrl,
            mapOf(
                "version" to (release.version ?: "null"),
                "releaseId" to release.releaseId,
                "name" to (release.name ?: "null"),
                "status" to release.status.value,
                "releasedAt" to timestamp(release.releasedAt),
                "fingerprint" to (release.fingerprint ?: "null"),
                "contentHash" to (release.contentHash ?: "null"),
                "dependencies" to release.dependencies.joinToString(",") {
                    "${it.registry}/${it.namespace}/${it.catalogKey}@${it.version}"
                },
                "dependencyAliases" to release.dependencies.joinToString(",") { it.alias ?: "null" },
                "dependencyContentHashes" to release.dependencies.joinToString(",") { it.contentHash ?: "null" },
                "deployedTo" to release.deployedTo.joinToString(","),
                "behind" to release.behind.joinToString(";") { behind ->
                    "${behind.version}=" + behind.changes.joinToString("+") { "${it.type}/${it.key}:${it.change.value}" }
                },
            ),
        )
    }

    /** Renames a draft, conditionally when `config.ifMatch` is set; only the name is sent, to its unchanged address. */
    private fun updateCatalogRelease(baseUrl: String, config: ObjectNode) = conditionalUpdate(baseUrl, {
        CatalogsApi(restClient(baseUrl, config)).updateCatalogReleaseWithHttpInfo(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["release"].asText(),
            UpdateCatalogReleaseRequest(name = config["name"].asText()),
            ifMatch = config.optionalText("ifMatch"),
        )
    }) { release ->
        mapOf(
            "version" to (release.version ?: "null"),
            "releaseId" to release.releaseId,
            "name" to (release.name ?: "null"),
            "status" to release.status.value,
            "base" to (release.base ?: "null"),
        )
    }

    /** Sets a draft's default theme and presets; no name goes out, and an unset qualifier stays off the wire. */
    private fun setCatalogReleaseThemeSettings(baseUrl: String, config: ObjectNode) {
        val release = CatalogsApi(restClient(baseUrl, config)).updateCatalogRelease(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["release"].asText(),
            UpdateCatalogReleaseRequest(
                defaultTheme = config.optionalText("defaultThemeKey")?.let { key ->
                    CatalogThemeReference(key = key, catalogKey = config.optionalText("defaultThemeCatalogKey"))
                },
                clearDefaultTheme = config.optionalBoolean("clearDefaultTheme"),
                // Present, an empty array included, goes out as given; absent stays off the wire.
                presets = config.get("presets")?.takeUnless { it.isNull }?.map {
                    CatalogPresetDto(key = it["key"].asText(), name = it["name"].asText(), description = it.optionalText("description"))
                },
            ),
        )
        val presets = release.presets.orEmpty()
        report(
            baseUrl,
            mapOf(
                "releaseId" to release.releaseId,
                "name" to (release.name ?: "null"),
                "defaultThemeKey" to (release.defaultTheme?.key ?: "null"),
                "defaultThemeCatalogKey" to (release.defaultTheme?.catalogKey ?: "null"),
                "presetKeys" to presets.joinToString(",") { it.key },
                "presetNames" to presets.joinToString("|") { it.name },
                "presetDescriptions" to presets.joinToString("|") { it.description ?: "null" },
            ),
        )
    }

    /** Surfaces the version a draft would be released as, and every reason, in order. */
    private fun previewCatalogDraftRelease(baseUrl: String, config: ObjectNode) {
        val preview = CatalogsApi(restClient(baseUrl, config)).previewCatalogDraftRelease(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["release"].asText(),
        )
        report(
            baseUrl,
            mapOf(
                "base" to (preview.base ?: "null"),
                "changeKind" to (preview.changeKind?.value ?: "null"),
                "reasons" to preview.reasons.joinToString("|"),
                "needed" to preview.needed,
                "suggested" to preview.suggested,
            ),
        )
    }

    private fun deleteCatalogRelease(baseUrl: String, config: ObjectNode) {
        CatalogsApi(restClient(baseUrl, config)).deleteCatalogRelease(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["release"].asText(),
        )
    }

    /**
     * Releases a draft; each member is sent only when configured. A refusal is reported by its
     * problem, with each unreleased dependency draft named in full.
     */
    private fun releaseCatalogDraft(baseUrl: String, config: ObjectNode) {
        val response = try {
            CatalogsApi(restClient(baseUrl, config)).releaseCatalogDraft(
                config["tenantKey"].asText(),
                config["registry"].asText(),
                config["namespace"].asText(),
                config["catalogKey"].asText(),
                config["release"].asText(),
                ReleaseCatalogDraftRequest(
                    version = config.optionalText("version"),
                    notes = config.optionalText("notes"),
                    acknowledgedBehind = config.get("acknowledgedBehind")?.takeUnless { it.isNull }?.map { it.asText() },
                    releaseDependencies = config.get("releaseDependencies")?.takeUnless { it.isNull }?.asBoolean(),
                ),
            )
        } catch (e: ProblemDetailException) {
            report(
                baseUrl,
                mapOf(
                    "problemTypeSlug" to (e.typeSlug ?: "<null>"),
                    "problemStatus" to e.statusCode.value(),
                    "problemUnreleasedDependencies" to e.unreleasedDependencies.joinToString("|") {
                        "${it.registry}/${it.namespace}/${it.catalogKey}#${it.releaseId}=${it.releaseName}"
                    },
                ),
            )
            return
        }
        report(
            baseUrl,
            mapOf(
                "version" to (response.release.version ?: "null"),
                "status" to response.release.status.value,
                "releaseId" to response.release.releaseId,
                "contentHash" to (response.release.contentHash ?: "null"),
                "unchangedContent" to response.unchangedContent.toString(),
                "draftsBehind" to response.draftsBehind.joinToString("|"),
                "releasedDependencies" to response.releasedDependencies.joinToString(",") {
                    "${it.registry}/${it.namespace}/${it.catalogKey}@${it.version}"
                },
            ),
        )
    }

    /** Previews merging the release `config.version` into the draft `config.release` (alpha). */
    private fun previewCatalogDraftMerge(baseUrl: String, config: ObjectNode) {
        val preview = CatalogsApi(restClient(baseUrl, config)).previewCatalogDraftMerge(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["release"].asText(),
            config["version"].asText(),
        )
        report(
            baseUrl,
            mapOf(
                "draft" to preview.draft,
                "base" to (preview.base ?: "null"),
                "release" to preview.release,
                "alreadyMerged" to preview.alreadyMerged.toString(),
                "resources" to preview.resources.joinToString(",") {
                    "${it.resourceType}/${it.resourceKey}:${it.outcome.value}:${it.draftChange?.value ?: "null"}:${it.releaseChange?.value ?: "null"}"
                },
                "resourceNames" to preview.resources.joinToString("|") { it.name ?: "null" },
                "resourceNeeds" to preview.resources.filter { !it.needs.isNullOrEmpty() }
                    .joinToString(";") { "${it.resourceType}/${it.resourceKey}=${it.needs.orEmpty().joinToString("+")}" },
                "unchanged" to preview.unchanged.toString(),
                "token" to preview.token,
                "missingBase" to (preview.missingBase?.let { "${it.version}@${it.fingerprint}" } ?: "null"),
                "missingBaseContentHash" to (preview.missingBase?.contentHash ?: "null"),
            ),
        )
    }

    /** Reads the three sides of `config.entry` in merging `config.version` into the draft `config.release` (alpha). */
    private fun getCatalogDraftMergeSides(baseUrl: String, config: ObjectNode) {
        val sides = CatalogsApi(restClient(baseUrl, config)).getCatalogDraftMergeSides(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["release"].asText(),
            config["version"].asText(),
            config["entry"].asText(),
        )
        fun side(content: Map<String, Any?>?): String = content?.let { "${it["type"]}/${it["key"]}:${it["name"]}" } ?: "null"
        report(
            baseUrl,
            mapOf(
                "entry" to sides.entry,
                "mergeBase" to (sides.mergeBase ?: "null"),
                "token" to sides.token,
                "base" to side(sides.base),
                "draft" to side(sides.draft),
                "release" to side(sides.release),
                "draftMembers" to (sides.draft?.keys?.sorted()?.joinToString(",") ?: "null"),
            ),
        )
    }

    /** Lists the release registries (alpha), each as `name:kind`. */
    private fun listReleaseRegistries(baseUrl: String, config: ObjectNode) {
        val registries = CatalogsApi(restClient(baseUrl, config)).listReleaseRegistries(config["tenantKey"].asText())
        report(baseUrl, mapOf("registries" to registries.items.joinToString(",") { "${it.name}:${it.kind.value}" }))
    }

    /**
     * Lists the releases of a catalog in a release registry (alpha), each as
     * `version:fingerprint:releaseId:base`, the base as `version@fingerprint`, a missing value as `null`.
     */
    private fun listRegistryReleases(baseUrl: String, config: ObjectNode) {
        val releases = try {
            CatalogsApi(restClient(baseUrl, config)).listRegistryReleases(
                config["tenantKey"].asText(),
                config["name"].asText(),
                config["registry"].asText(),
                config["namespace"].asText(),
                config["catalogKey"].asText(),
            )
        } catch (e: ProblemDetailException) {
            report(baseUrl, mapOf("problemTypeSlug" to (e.typeSlug ?: "<null>"), "problemStatus" to e.statusCode.value()))
            return
        }
        report(
            baseUrl,
            mapOf(
                "releaseRegistry" to releases.releaseRegistry,
                "registry" to releases.registry,
                "namespace" to releases.namespace,
                "catalogKey" to releases.catalogKey,
                "releases" to releases.items.joinToString(",") {
                    "${it.version}:${it.fingerprint ?: "null"}:${it.releaseId ?: "null"}:${it.base?.let { b -> "${b.version}@${b.fingerprint}" } ?: "null"}"
                },
                "contentHashes" to releases.items.joinToString(",") { it.contentHash ?: "null" },
                "baseContentHashes" to releases.items.joinToString(",") { it.base?.contentHash ?: "null" },
            ),
        )
    }

    /** Installs a release from a release registry (alpha). A refusal is reported by its problem. */
    private fun installRegistryRelease(baseUrl: String, config: ObjectNode) {
        val installed = try {
            CatalogsApi(restClient(baseUrl, config)).installRegistryRelease(
                config["tenantKey"].asText(),
                config["name"].asText(),
                config["registry"].asText(),
                config["namespace"].asText(),
                config["catalogKey"].asText(),
                config["version"].asText(),
            )
        } catch (e: ProblemDetailException) {
            report(baseUrl, mapOf("problemTypeSlug" to (e.typeSlug ?: "<null>"), "problemStatus" to e.statusCode.value()))
            return
        }
        report(
            baseUrl,
            mapOf(
                "registry" to installed.registry,
                "namespace" to installed.namespace,
                "catalogKey" to installed.catalogKey,
                "version" to installed.version,
                "newlyInstalled" to installed.newlyInstalled.toString(),
                "total" to installed.total.toString(),
            ),
        )
    }

    /** Publishes a released release to the release registry `config.to` (alpha). A refusal is reported by its problem. */
    private fun publishReleaseToRegistry(baseUrl: String, config: ObjectNode) {
        val published = try {
            CatalogsApi(restClient(baseUrl, config)).publishReleaseToRegistry(
                config["tenantKey"].asText(),
                config["registry"].asText(),
                config["namespace"].asText(),
                config["catalogKey"].asText(),
                config["release"].asText(),
                PublishReleaseToRegistryRequest(to = config["to"].asText()),
            )
        } catch (e: ProblemDetailException) {
            report(baseUrl, mapOf("problemTypeSlug" to (e.typeSlug ?: "<null>"), "problemStatus" to e.statusCode.value()))
            return
        }
        report(
            baseUrl,
            mapOf(
                "status" to published.status.value,
                "releaseRegistry" to published.releaseRegistry,
                "version" to published.version,
                "digest" to (published.digest ?: "null"),
            ),
        )
    }

    /**
     * Merges the release `config.version` into the draft `config.release` (alpha), with
     * `config.resolutions` when present. A refusal is reported by its problem, with the conflicts it
     * lists and the read-only reason it gives.
     */
    private fun mergeIntoCatalogDraft(baseUrl: String, config: ObjectNode) {
        val resolutions = config.get("resolutions")?.takeUnless { it.isNull }?.let { node ->
            node.fieldNames().asSequence().associateWith { CatalogMergeSide.decode(node[it].asText())!! }
        }
        val merged = try {
            CatalogsApi(restClient(baseUrl, config)).mergeIntoCatalogDraft(
                config["tenantKey"].asText(),
                config["registry"].asText(),
                config["namespace"].asText(),
                config["catalogKey"].asText(),
                config["release"].asText(),
                MergeIntoCatalogDraftRequest(
                    release = config["version"].asText(),
                    resolutions = resolutions,
                    mergedContent = config.get("mergedContent")?.takeUnless { it.isNull }?.let { node ->
                        node.fieldNames().asSequence().associateWith {
                            @Suppress("UNCHECKED_CAST")
                            (mapper.convertValue(node[it], Map::class.java) as Map<String, Any>)
                        }
                    },
                    expectedToken = config.get("expectedToken")?.takeUnless { it.isNull }?.asText(),
                ),
            )
        } catch (e: ProblemDetailException) {
            report(
                baseUrl,
                mapOf(
                    "problemTypeSlug" to (e.typeSlug ?: "<null>"),
                    "problemStatus" to e.statusCode.value(),
                    "problemUnresolvedConflicts" to e.unresolvedConflicts.joinToString(","),
                    "problemReadOnlyReason" to (e.readOnlyReason ?: "null"),
                    "problemMissingBase" to (e.missingBaseVersion?.let { "$it@${e.missingBaseFingerprint}" } ?: "null"),
                ),
            )
            return
        }
        report(
            baseUrl,
            mapOf(
                "release" to merged.release,
                "taken" to merged.taken.joinToString(","),
                "removed" to merged.removed.joinToString(","),
                "kept" to merged.kept.joinToString(","),
                "merged" to merged.merged.orEmpty().joinToString(","),
            ),
        )
    }

    /** An instant as ISO 8601 with seconds, which `OffsetDateTime.toString()` drops when zero. */
    private fun timestamp(value: OffsetDateTime?): String = value?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) ?: "null"

    /**
     * Deploys one release of a catalog, a released one by its version or a draft by its `releaseId`; the
     * registry, namespace and release go into the path as given, and `includeDependencies` and
     * `replace` are passed only when configured, so an unset one sends nothing. A refusal is
     * reported by its problem, with each missing dependency release and why.
     */
    private fun deployCatalogRelease(baseUrl: String, config: ObjectNode) {
        val deployment = try {
            EnvironmentsApi(restClient(baseUrl, config)).deployCatalogRelease(
                config["tenantKey"].asText(),
                config["environmentKey"].asText(),
                config["registry"].asText(),
                config["namespace"].asText(),
                config["catalogKey"].asText(),
                config["release"].asText(),
                config.get("includeDependencies")?.takeUnless { it.isNull }?.asBoolean(),
                config.get("replace")?.takeUnless { it.isNull }?.asBoolean(),
            )
        } catch (e: ProblemDetailException) {
            report(
                baseUrl,
                mapOf(
                    "problemTypeSlug" to (e.typeSlug ?: "<null>"),
                    "problemStatus" to e.statusCode.value(),
                    "problemMissingDependencies" to e.missingDependencies.joinToString(",") {
                        "${it.registry}/${it.namespace}/${it.catalogKey}@${it.version}:${it.reason}"
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
                "releaseId" to deployment.releaseId,
                "status" to deployment.status.value,
                "version" to (deployment.version ?: "null"),
                "releaseName" to (deployment.releaseName ?: "null"),
                "lastUsedAt" to timestamp(deployment.lastUsedAt),
                "documentsRequested" to deployment.documentsRequested.toString(),
            ),
        )
    }

    private fun undeployCatalogRelease(baseUrl: String, config: ObjectNode) {
        EnvironmentsApi(restClient(baseUrl, config)).undeployCatalogRelease(
            config["tenantKey"].asText(),
            config["environmentKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["release"].asText(),
        )
    }

    /**
     * Replaces a variant's content with a one-node template model, sending `config.ifMatch` as
     * `If-Match` when present and no `If-Match` otherwise.
     */
    private fun updateVariantContent(baseUrl: String, config: ObjectNode) {
        val content = try {
            VariantsApi(restClient(baseUrl, config)).updateVariantContent(
                config["tenantKey"].asText(),
                config["registry"].asText(),
                config["namespace"].asText(),
                config["catalogKey"].asText(),
                config["release"].asText(),
                config["templateKey"].asText(),
                config["variantKey"].asText(),
                UpdateVariantContentRequest(
                    templateModel = TemplateDocument(
                        modelVersion = TemplateDocument.ModelVersion._1,
                        root = "root",
                        nodes = mapOf("root" to Node(id = "root", type = "root", slots = emptyList())),
                        slots = emptyMap(),
                        themeRef = ThemeRef(type = ThemeRef.Type.inherit),
                    ),
                ),
                ifMatch = config.optionalText("ifMatch"),
            )
        } catch (e: ProblemDetailException) {
            report(baseUrl, mapOf("problemTypeSlug" to (e.typeSlug ?: "<null>"), "problemStatus" to e.statusCode.value()))
            return
        }
        report(baseUrl, mapOf("variantKey" to content.variantKey))
    }

    // --- Content, ready marks and changes ---

    private fun oneNodeModel() = TemplateDocument(
        modelVersion = TemplateDocument.ModelVersion._1,
        root = "root",
        nodes = mapOf("root" to Node(id = "root", type = "root", slots = emptyList())),
        slots = emptyMap(),
        themeRef = ThemeRef(type = ThemeRef.Type.inherit),
    )

    /** The `type` member of a free-form JSON Schema object, or null when there is none. */
    private fun schemaType(schema: Any?): String = ((schema as? Map<*, *>)?.get("type"))?.toString() ?: "null"

    /**
     * Reads a variant's content through `…WithHttpInfo`, which is how a caller reaches the response
     * headers: the `ETag` is reported exactly as received, quotes included.
     */
    private fun getVariantContent(baseUrl: String, config: ObjectNode) {
        val response = VariantsApi(restClient(baseUrl, config)).getVariantContentWithHttpInfo(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["release"].asText(),
            config["templateKey"].asText(),
            config["variantKey"].asText(),
        )
        val content = response.body!!
        report(
            baseUrl,
            mapOf(
                "variantKey" to content.variantKey,
                "templateModelRoot" to content.templateModel.root,
                "status" to content.status.value,
                "lastModified" to timestamp(content.lastModified),
                "readyAt" to timestamp(content.readyAt),
                "etag" to (response.headers.eTag ?: "null"),
            ),
        )
    }

    private fun markVariantReady(baseUrl: String, config: ObjectNode) {
        val content = VariantsApi(restClient(baseUrl, config)).markVariantReady(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["release"].asText(),
            config["templateKey"].asText(),
            config["variantKey"].asText(),
        )
        report(
            baseUrl,
            mapOf(
                "variantKey" to content.variantKey,
                "status" to content.status.value,
                "lastModified" to timestamp(content.lastModified),
                "readyAt" to timestamp(content.readyAt),
            ),
        )
    }

    private fun markTemplateReady(baseUrl: String, config: ObjectNode) {
        val template = TemplatesApi(restClient(baseUrl, config)).markTemplateReady(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["release"].asText(),
            config["templateKey"].asText(),
        )
        report(
            baseUrl,
            mapOf(
                "key" to template.key,
                "name" to template.name,
                "variantKeys" to template.variants.joinToString(",") { it.key },
                "variantStatuses" to template.variants.joinToString(",") { it.status.value },
            ),
        )
    }

    /** Reads a stencil's content through `…WithHttpInfo`, reporting the `ETag` exactly as received. */
    private fun getStencilContent(baseUrl: String, config: ObjectNode) {
        val response = StencilsApi(restClient(baseUrl, config)).getStencilContentWithHttpInfo(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["release"].asText(),
            config["stencilKey"].asText(),
        )
        val content = response.body!!
        report(
            baseUrl,
            mapOf(
                "stencilKey" to content.stencilKey,
                "contentRoot" to content.content.root,
                "parameterSchemaType" to schemaType(content.parameterSchema),
                "status" to content.status.value,
                "lastModified" to timestamp(content.lastModified),
                "readyAt" to timestamp(content.readyAt),
                "etag" to (response.headers.eTag ?: "null"),
            ),
        )
    }

    /**
     * Replaces a stencil's content with a one-node template model and `config.parameterSchema`,
     * sending `config.ifMatch` as `If-Match`, and reports the new `ETag` the update answered with.
     */
    private fun updateStencilContent(baseUrl: String, config: ObjectNode) {
        val response = StencilsApi(restClient(baseUrl, config)).updateStencilContentWithHttpInfo(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["release"].asText(),
            config["stencilKey"].asText(),
            UpdateStencilContentRequest(
                content = oneNodeModel(),
                parameterSchema = mapper.convertValue(config["parameterSchema"], Map::class.java),
            ),
            ifMatch = config.optionalText("ifMatch"),
        )
        val content = response.body!!
        report(
            baseUrl,
            mapOf(
                "stencilKey" to content.stencilKey,
                "status" to content.status.value,
                "readyAt" to timestamp(content.readyAt),
                "etag" to (response.headers.eTag ?: "null"),
            ),
        )
    }

    private fun markStencilReady(baseUrl: String, config: ObjectNode) {
        val response = StencilsApi(restClient(baseUrl, config)).markStencilReady(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["release"].asText(),
            config["stencilKey"].asText(),
        )
        report(
            baseUrl,
            mapOf(
                "stencilKey" to response.stencil.stencilKey,
                "status" to response.stencil.status.value,
                "readyAt" to timestamp(response.stencil.readyAt),
                "parameterSchemaType" to schemaType(response.stencil.parameterSchema),
                "notFitting" to response.notFitting.joinToString(",") { "${it.templateKey}/${it.variantKey}:${it.fits}" },
                "notFittingProblems" to response.notFitting.joinToString(";") { it.problems.joinToString("|") },
            ),
        )
    }

    private fun listStencilUsage(baseUrl: String, config: ObjectNode) {
        val response = StencilsApi(restClient(baseUrl, config)).listStencilUsage(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["release"].asText(),
            config["stencilKey"].asText(),
            page = config["page"].asInt(),
            size = config["size"].asInt(),
        )
        report(
            baseUrl,
            mapOf(
                "templateKeys" to response.items.joinToString(",") { it.templateKey },
                "templateNames" to response.items.joinToString("|") { it.templateName },
                "variantKeys" to response.items.joinToString(",") { it.variantKey },
                "fits" to response.items.joinToString(",") { it.fits.toString() },
                "problems" to response.items.joinToString(";") { it.problems.joinToString("|") },
                "pageNumber" to response.page.number.toString(),
                "total" to response.page.total.toString(),
            ),
        )
    }

    private fun getCatalogChanges(baseUrl: String, config: ObjectNode) = reportChanges(
        baseUrl,
        CatalogsApi(restClient(baseUrl, config)).getCatalogChanges(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["release"].asText(),
        ),
    )

    private fun markCatalogReady(baseUrl: String, config: ObjectNode) = reportChanges(
        baseUrl,
        CatalogsApi(restClient(baseUrl, config)).markCatalogReady(
            config["tenantKey"].asText(),
            config["registry"].asText(),
            config["namespace"].asText(),
            config["catalogKey"].asText(),
            config["release"].asText(),
        ),
    )

    private fun reportChanges(baseUrl: String, changes: CatalogChangesDto) = report(
        baseUrl,
        mapOf(
            "base" to (changes.base ?: "null"),
            "releasable" to changes.releasable.toString(),
            "dependenciesChanged" to changes.dependenciesChanged.toString(),
            "changes" to changes.changes.joinToString(",") { "${it.type}/${it.key}:${it.change.value}" },
            "mergedForReview" to changes.changes.filter { it.mergedForReview == true }.joinToString(",") { "${it.type}/${it.key}" },
            "catalogChanges" to changes.catalogChanges.joinToString(",") { "${it.type}/${it.key}:${it.change.value}" },
        ),
    )

    private fun listEnvironmentDeployments(baseUrl: String, config: ObjectNode) {
        val deployments = EnvironmentsApi(restClient(baseUrl, config))
            .listEnvironmentDeployments(config["tenantKey"].asText(), config["environmentKey"].asText())
            .items
        report(
            baseUrl,
            mapOf(
                "registries" to deployments.joinToString(",") { it.registry },
                "catalogKeys" to deployments.joinToString(",") { it.catalogKey },
                "releaseIds" to deployments.joinToString(",") { it.releaseId },
                "statuses" to deployments.joinToString(",") { it.status.value },
                "versions" to deployments.joinToString(",") { it.version ?: "null" },
                "releaseNames" to deployments.joinToString("|") { it.releaseName ?: "null" },
                "deployedAt" to deployments.joinToString(",") { timestamp(it.deployedAt) },
                "lastUsedAt" to deployments.joinToString(",") { timestamp(it.lastUsedAt) },
                "documentsRequested" to deployments.joinToString(",") { it.documentsRequested.toString() },
            ),
        )
    }

    private fun listDeploymentHistory(baseUrl: String, config: ObjectNode) {
        val response = EnvironmentsApi(restClient(baseUrl, config)).listDeploymentHistory(
            config["tenantKey"].asText(),
            config["environmentKey"].asText(),
            page = config["page"].asInt(),
            size = config["size"].asInt(),
        )
        val events = response.items
        report(
            baseUrl,
            mapOf(
                "catalogKeys" to events.joinToString(",") { it.catalogKey },
                "actions" to events.joinToString(",") { it.action.value },
                "releaseIds" to events.joinToString(",") { it.releaseId },
                "statuses" to events.joinToString(",") { it.status.value },
                "versions" to events.joinToString(",") { it.version ?: "null" },
                "releaseNames" to events.joinToString("|") { it.releaseName ?: "null" },
                "changedAt" to events.joinToString(",") { timestamp(it.changedAt) },
                "changedBy" to events.joinToString("|") { it.changedBy ?: "null" },
                "total" to response.page.total.toString(),
            ),
        )
    }

    /** An installed release as `registry/namespace/catalogKey@version#releaseId`. */
    private fun installedRelease(release: InstalledCatalogReleaseDto) =
        "${release.registry}/${release.namespace}/${release.catalogKey}@${release.version}#${release.releaseId}"

    /** Creates an environment; `production` is sent only when configured. */
    private fun createEnvironment(baseUrl: String, config: ObjectNode) {
        val response = EnvironmentsApi(restClient(baseUrl, config)).createEnvironmentWithHttpInfo(
            config["tenantKey"].asText(),
            CreateEnvironmentRequest(
                key = config["key"].asText(),
                name = config["name"].asText(),
                production = config.get("production")?.takeUnless { it.isNull }?.asBoolean(),
            ),
        )
        report(baseUrl, environmentFields(response.body!!) + ("location" to (response.headers.getFirst(HttpHeaders.LOCATION) ?: "null")))
    }

    /** A partial update: each member is sent only when configured, and `If-Match` only when `config.ifMatch` is. */
    private fun updateEnvironment(baseUrl: String, config: ObjectNode) = conditionalUpdate(baseUrl, {
        EnvironmentsApi(restClient(baseUrl, config)).updateEnvironmentWithHttpInfo(
            config["tenantKey"].asText(),
            config["environmentKey"].asText(),
            UpdateEnvironmentRequest(
                name = config.optionalText("name"),
                production = config.get("production")?.takeUnless { it.isNull }?.asBoolean(),
            ),
            ifMatch = config.optionalText("ifMatch"),
        )
    }, ::environmentFields)

    private fun environmentFields(environment: EnvironmentDto): Map<String, Any> =
        mapOf("key" to environment.key, "name" to environment.name, "production" to environment.production.toString())

    /** An optional value from `config`: absent or null stays null, so the request omits it. */
    private fun JsonNode.optionalText(name: String): String? = get(name)?.takeUnless { it.isNull }?.asText()

    private fun JsonNode.optionalBoolean(name: String): Boolean? = get(name)?.takeUnless { it.isNull }?.asBoolean()

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
