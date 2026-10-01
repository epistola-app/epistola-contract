// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.validation.schema.local

import app.epistola.client.api.TemplatesApi
import app.epistola.client.infrastructure.Serializer
import app.epistola.client.validation.schema.TemplateDataValidationException.ValidationError
import app.epistola.client.validation.schema.TemplateDataValidator
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.PathType
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * A [TemplateDataValidator] that validates in-process, on `com.networknt:json-schema-validator`.
 *
 * **This lives in test sources on purpose.** The published client ships no JSON Schema engine, so
 * nothing here lands on a consumer's classpath. It exists to prove the interface is implementable
 * against a real engine rather than a stub, to drive the conformance scenario, and to be copied by
 * a consumer who wants local validation — pre-flighting a large batch is the case that justifies
 * it, since [preflightsGeneration] stays `true` and one cached schema then answers every item
 * without a request per item.
 *
 * Two details are the whole reason a copyable example is worth keeping:
 *
 * - **The path format has to be asked for.** networknt's default renders an instance location as
 *   `$.customer.email`, a JSONPath, while [TemplateDataValidator] pins `path` to a JSON Pointer.
 *   `PathType.JSON_POINTER` is what makes `instanceLocation.toString()` emit `/customer/email`.
 *   The validator this replaced did not set it, so it reported JSONPath under a KDoc promising
 *   pointers — the exact drift the interface's contract exists to prevent.
 * - **The schema is fetched, so it is cached.** A validator is only "local" after a round trip for
 *   the template; without a cache this is slower than asking the server outright.
 *
 * And one thing a copy cannot fix, which is the argument for the default being the server:
 * **a local engine's verdict is its own.** Under 2019-09 and 2020-12 `format` is an annotation
 * rather than an assertion, so this validator accepts an `email` field holding `not-an-email`
 * unless format assertion is switched on; an engine's draft coverage, regex flavour and
 * `format` policy are all its own choices. Epistola decides what it will render, and only asking
 * it cannot disagree with that.
 */
class NetworkntTemplateDataValidator(
    private val templatesApi: TemplatesApi,
    private val objectMapper: ObjectMapper = Serializer.jacksonObjectMapper,
    private val ttl: Duration = Duration.ofMinutes(5),
) : TemplateDataValidator {

    private data class Key(val tenantId: String, val catalogId: String, val templateId: String)
    private data class Entry(val schema: JsonSchema?, val storedAt: Instant)

    private val cache = ConcurrentHashMap<Key, Entry>()

    override fun validate(
        tenantId: String,
        catalogId: String,
        templateId: String,
        data: Any,
    ): List<ValidationError> {
        // No schema on the template means nothing is claimed about the data, so nothing is wrong
        // with it. Same answer the server gives.
        val schema = schemaFor(Key(tenantId, catalogId, templateId)) ?: return emptyList()

        return schema.validate(objectMapper.valueToTree<JsonNode>(data)).map { message ->
            ValidationError(
                path = message.instanceLocation.toString(),
                message = message.message,
                keyword = message.type,
            )
        }
    }

    private fun schemaFor(key: Key): JsonSchema? {
        val existing = cache[key]
        if (existing != null && Instant.now().isBefore(existing.storedAt.plus(ttl))) {
            return existing.schema
        }
        val schema = loadSchema(key)
        cache[key] = Entry(schema, Instant.now())
        return schema
    }

    private fun loadSchema(key: Key): JsonSchema? {
        val template = templatesApi.getTemplate(key.tenantId, key.catalogId, key.templateId)
        val schemaObject = template.schema ?: return null
        val schemaNode: JsonNode = objectMapper.valueToTree(schemaObject)
        val config = SchemaValidatorsConfig.builder().pathType(PathType.JSON_POINTER).build()
        return JsonSchemaFactory.getInstance(dialectOf(schemaNode)).getSchema(schemaNode, config)
    }

    private fun dialectOf(schemaNode: JsonNode): SpecVersion.VersionFlag {
        val declared = schemaNode.path("\$schema").asText(null) ?: return SpecVersion.VersionFlag.V202012
        return when {
            declared.contains("draft-04") -> SpecVersion.VersionFlag.V4
            declared.contains("draft-06") -> SpecVersion.VersionFlag.V6
            declared.contains("draft-07") -> SpecVersion.VersionFlag.V7
            declared.contains("2019-09") -> SpecVersion.VersionFlag.V201909
            else -> SpecVersion.VersionFlag.V202012
        }
    }
}
