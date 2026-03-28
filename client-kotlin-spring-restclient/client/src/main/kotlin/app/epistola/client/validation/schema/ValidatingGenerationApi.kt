package app.epistola.client.validation.schema

import app.epistola.client.api.GenerationApi
import app.epistola.client.api.TemplatesApi
import app.epistola.client.model.GenerateBatchRequest
import app.epistola.client.model.GenerateDocumentRequest
import app.epistola.client.model.GenerationJobResponse
import org.springframework.http.ResponseEntity

/**
 * A wrapper around [GenerationApi] that validates request data against
 * the template's JSON Schema before sending to the server.
 *
 * For single-document requests, validation errors are thrown immediately.
 * For batch requests, all items are validated and errors are collected
 * into a single [TemplateDataValidationException].
 *
 * ```kotlin
 * val generationApi = GenerationApi(restClient)
 * val templatesApi = TemplatesApi(restClient)
 * val validating = ValidatingGenerationApi(generationApi, templatesApi)
 * // Validates data against the template's schema before calling the server:
 * validating.generateDocument("my-tenant", request)
 * ```
 */
class ValidatingGenerationApi(
    private val delegate: GenerationApi,
    templatesApi: TemplatesApi,
    cache: SchemaCache = TtlSchemaCache(),
) {
    private val validator = TemplateSchemaValidator(
        templatesApi = templatesApi,
        cache = cache,
    )

    fun generateDocument(
        tenantId: String,
        generateDocumentRequest: GenerateDocumentRequest,
    ): GenerationJobResponse {
        validator.validate(tenantId, generateDocumentRequest.templateId, generateDocumentRequest.data)
        return delegate.generateDocument(tenantId, generateDocumentRequest)
    }

    fun generateDocumentWithHttpInfo(
        tenantId: String,
        generateDocumentRequest: GenerateDocumentRequest,
    ): ResponseEntity<GenerationJobResponse> {
        validator.validate(tenantId, generateDocumentRequest.templateId, generateDocumentRequest.data)
        return delegate.generateDocumentWithHttpInfo(tenantId, generateDocumentRequest)
    }

    fun generateDocumentBatch(
        tenantId: String,
        generateBatchRequest: GenerateBatchRequest,
    ): GenerationJobResponse {
        validateBatch(tenantId, generateBatchRequest)
        return delegate.generateDocumentBatch(tenantId, generateBatchRequest)
    }

    fun generateDocumentBatchWithHttpInfo(
        tenantId: String,
        generateBatchRequest: GenerateBatchRequest,
    ): ResponseEntity<GenerationJobResponse> {
        validateBatch(tenantId, generateBatchRequest)
        return delegate.generateDocumentBatchWithHttpInfo(tenantId, generateBatchRequest)
    }

    private fun validateBatch(tenantId: String, request: GenerateBatchRequest) {
        val allErrors = mutableListOf<TemplateDataValidationException.ValidationError>()
        for ((index, item) in request.items.withIndex()) {
            try {
                validator.validate(tenantId, item.templateId, item.data)
            } catch (e: TemplateDataValidationException) {
                allErrors += e.errors.map { error ->
                    error.copy(path = "items[$index]${error.path}")
                }
            }
        }
        if (allErrors.isNotEmpty()) {
            throw TemplateDataValidationException(allErrors)
        }
    }
}
