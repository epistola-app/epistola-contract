// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.validation.schema

import app.epistola.client.api.GenerationApi
import app.epistola.client.api.TemplatesApi
import app.epistola.client.error.KnownProblemSlugs
import app.epistola.client.error.ProblemDetailException
import app.epistola.client.model.GenerateBatchRequest
import app.epistola.client.model.GenerateDocumentRequest
import app.epistola.client.model.GenerationJobResponse
import org.springframework.http.ResponseEntity

/**
 * A wrapper around [GenerationApi] that reports unacceptable template data as a
 * [TemplateDataValidationException] rather than a generic problem response.
 *
 * It gets there two ways, and which one applies is the [TemplateDataValidator]'s call:
 *
 * - **Before the request**, when the validator answers in-process
 *   ([TemplateDataValidator.preflightsGeneration]). Nothing is submitted, and every item of a batch
 *   is reported at once with its `items[i]` path prefix.
 * - **From the response**, always. The server validates the data it is given, so a rejected request
 *   comes back as a `template-data-invalid` problem, which is translated into the same exception
 *   with the same field pointers.
 *
 * The default validator asks the server, and therefore declines the pre-flight: checking first
 * would spend an extra round trip — one per item, for a batch — to learn what submitting already
 * tells us. Either way the caller catches one exception type and reads one error shape.
 *
 * ```kotlin
 * val validating = ValidatingGenerationApi(GenerationApi(restClient), TemplatesApi(restClient))
 * try {
 *     validating.generateDocument("my-tenant", request)
 * } catch (e: TemplateDataValidationException) {
 *     e.errors.forEach { println("${it.path}: ${it.message}") }
 * }
 * ```
 */
class ValidatingGenerationApi(
    private val delegate: GenerationApi,
    private val validator: TemplateDataValidator,
) {

    /** Validates against the server, using [ServerTemplateDataValidator]. */
    constructor(
        delegate: GenerationApi,
        templatesApi: TemplatesApi,
    ) : this(delegate, ServerTemplateDataValidator(templatesApi))

    fun generateDocument(
        tenantKey: String,
        generateDocumentRequest: GenerateDocumentRequest,
    ): GenerationJobResponse {
        preflight(tenantKey, generateDocumentRequest)
        return translatingProblem { delegate.generateDocument(tenantKey, generateDocumentRequest) }
    }

    fun generateDocumentWithHttpInfo(
        tenantKey: String,
        generateDocumentRequest: GenerateDocumentRequest,
    ): ResponseEntity<GenerationJobResponse> {
        preflight(tenantKey, generateDocumentRequest)
        return translatingProblem { delegate.generateDocumentWithHttpInfo(tenantKey, generateDocumentRequest) }
    }

    fun generateDocumentBatch(
        tenantKey: String,
        generateBatchRequest: GenerateBatchRequest,
    ): GenerationJobResponse {
        preflightBatch(tenantKey, generateBatchRequest)
        return translatingProblem { delegate.generateDocumentBatch(tenantKey, generateBatchRequest) }
    }

    fun generateDocumentBatchWithHttpInfo(
        tenantKey: String,
        generateBatchRequest: GenerateBatchRequest,
    ): ResponseEntity<GenerationJobResponse> {
        preflightBatch(tenantKey, generateBatchRequest)
        return translatingProblem { delegate.generateDocumentBatchWithHttpInfo(tenantKey, generateBatchRequest) }
    }

    /**
     * A request body leaves `registry` out (or null) for the server's default registry; the
     * validator, which addresses the catalog in a URL path, gets the path segment that means the
     * same thing, `~`. A registry the body names is passed on as is.
     */
    private fun registrySegment(registry: String?): String = registry ?: TemplateDataValidator.DEFAULT_REGISTRY

    private fun preflight(tenantKey: String, request: GenerateDocumentRequest) {
        if (!validator.preflightsGeneration) {
            return
        }
        val errors = validator.validate(tenantKey, registrySegment(request.registry), request.namespace, request.catalogKey, request.templateKey, request.data)
        if (errors.isNotEmpty()) {
            throw TemplateDataValidationException(errors)
        }
    }

    private fun preflightBatch(tenantKey: String, request: GenerateBatchRequest) {
        if (!validator.preflightsGeneration) {
            return
        }
        val allErrors = mutableListOf<TemplateDataValidationException.ValidationError>()
        for ((index, item) in request.items.withIndex()) {
            val errors = validator.validate(tenantKey, registrySegment(item.registry), item.namespace, item.catalogKey, item.templateKey, item.data)
            allErrors += errors.map { error -> error.copy(path = "items[$index]${error.path}") }
        }
        if (allErrors.isNotEmpty()) {
            throw TemplateDataValidationException(allErrors)
        }
    }

    /**
     * Rewrites the server's `template-data-invalid` problem into the exception a caller of this
     * class is already catching. Every other problem propagates untouched — this class narrows one
     * failure mode, it does not swallow failures.
     */
    private inline fun <T> translatingProblem(call: () -> T): T = try {
        call()
    } catch (e: ProblemDetailException) {
        if (e.typeSlug == KnownProblemSlugs.TEMPLATE_DATA_INVALID) {
            throw TemplateDataValidationException(e.templateDataValidationErrors())
        }
        throw e
    }
}
