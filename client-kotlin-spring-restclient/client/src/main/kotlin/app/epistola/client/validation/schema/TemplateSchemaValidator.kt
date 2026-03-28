package app.epistola.client.validation.schema

import app.epistola.client.api.TemplatesApi
import app.epistola.client.infrastructure.Serializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion

/**
 * Validates template data against the JSON Schema defined on the template.
 *
 * Fetches the template from the server on first use and caches the compiled schema.
 *
 * ```kotlin
 * val validator = TemplateSchemaValidator(templatesApi)
 * validator.validate("my-tenant", "my-template", myDataMap)
 * ```
 *
 * @param templatesApi The generated [TemplatesApi] used to fetch template metadata.
 * @param cache Schema cache implementation. Defaults to [TtlSchemaCache] with 5-minute TTL.
 * @param objectMapper ObjectMapper for converting data to [JsonNode]. Defaults to the client's shared mapper.
 */
class TemplateSchemaValidator(
    private val templatesApi: TemplatesApi,
    private val cache: SchemaCache = TtlSchemaCache(),
    private val objectMapper: ObjectMapper = Serializer.jacksonObjectMapper,
) {
    /**
     * Validates [data] against the schema of the specified template.
     *
     * @param tenantId Tenant identifier.
     * @param templateId Template identifier.
     * @param data The data object (typically a `Map<String, Any?>`) to validate.
     * @throws TemplateDataValidationException if validation fails.
     * @throws org.springframework.web.client.RestClientResponseException if template fetch fails.
     */
    fun validate(tenantId: String, templateId: String, data: Any) {
        val schema = cache.getOrLoad(tenantId, templateId) { loadSchema(tenantId, templateId) }
            ?: return // No schema defined on template -- nothing to validate

        val dataNode: JsonNode = objectMapper.valueToTree(data)
        val messages = schema.validate(dataNode)

        if (messages.isNotEmpty()) {
            val errors = messages.map { msg ->
                TemplateDataValidationException.ValidationError(
                    path = msg.instanceLocation.toString(),
                    message = msg.message,
                    keyword = msg.type,
                )
            }
            throw TemplateDataValidationException(errors)
        }
    }

    private fun loadSchema(tenantId: String, templateId: String): JsonSchema? {
        val template = templatesApi.getTemplate(tenantId, templateId)
        val schemaObj = template.schema ?: return null
        val schemaNode: JsonNode = objectMapper.valueToTree(schemaObj)
        val versionFlag = detectVersion(schemaNode)
        val factory = JsonSchemaFactory.getInstance(versionFlag)
        return factory.getSchema(schemaNode)
    }

    private fun detectVersion(schemaNode: JsonNode): SpecVersion.VersionFlag {
        val schemaUri = schemaNode.path("\$schema").asText(null) ?: return DEFAULT_VERSION
        return when {
            schemaUri.contains("draft-04") -> SpecVersion.VersionFlag.V4
            schemaUri.contains("draft-06") -> SpecVersion.VersionFlag.V6
            schemaUri.contains("draft-07") -> SpecVersion.VersionFlag.V7
            schemaUri.contains("2019-09") -> SpecVersion.VersionFlag.V201909
            schemaUri.contains("2020-12") -> SpecVersion.VersionFlag.V202012
            else -> DEFAULT_VERSION
        }
    }

    private companion object {
        val DEFAULT_VERSION = SpecVersion.VersionFlag.V202012
    }
}
