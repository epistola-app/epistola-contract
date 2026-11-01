// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.api.model

import app.epistola.catalog.protocol.QualityBinding
import app.epistola.catalog.protocol.QualityProfileRef
import app.epistola.catalog.protocol.QualityProfiles
import app.epistola.catalog.protocol.QualityRule
import app.epistola.catalog.protocol.QualityRuleOverride
import org.yaml.snakeyaml.Yaml
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * The API writes quality profiles and bindings as the catalog wire format does (contract ADR 0010).
 *
 * The API defines its quality schemas itself rather than referencing the wire schema, because a
 * rule's `extras` must stay an open map for every generated client: the wire schema types the
 * `builtin` kind, and generators drop or refuse members a typed object does not name. This test holds
 * the two to the same members, so neither can gain or lose one alone; the server binds the catalog's
 * own classes (schemaMappings), so it reads exactly the wire shape.
 */
class QualityProfileSchemaParityTest {
    private val mapper = jsonMapper { addModule(kotlinModule()) }

    @Suppress("UNCHECKED_CAST")
    private val api: Map<String, Map<String, Any>> by lazy {
        val resource = assertNotNull(javaClass.getResourceAsStream("/openapi/epistola-contract.yaml"), "bundled spec missing from classpath")
        val spec = resource.use { Yaml().load<Map<String, Any>>(it) }
        (spec["components"] as Map<String, Any>)["schemas"] as Map<String, Map<String, Any>>
    }

    @Suppress("UNCHECKED_CAST")
    private val wire: Map<String, Map<String, Any>> by lazy {
        val resource = assertNotNull(
            javaClass.getResourceAsStream("/META-INF/epistola-catalog/schemas/resource-detail-v8.schema.json"),
            "catalog wire schema missing from classpath",
        )
        resource.use { mapper.readValue(it, Map::class.java) }["\$defs"] as Map<String, Map<String, Any>>
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any>.members(): Set<String> = (this["properties"] as Map<String, Any>).keys

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any>.required(): Set<String> = (this["required"] as? List<String>).orEmpty().toSet()

    @Test
    fun `each quality schema has the wire schema's members, and requires the same`() {
        listOf("QualityRule", "QualityRuleScope", "QualityRuleExamples", "QualityBinding", "QualityProfileRef", "QualityRuleOverride").forEach { name ->
            val apiSchema = assertNotNull(api[name], "the API has no $name")
            val wireSchema = assertNotNull(wire[name], "the wire schema has no $name")
            assertEquals(wireSchema.members(), apiSchema.members(), "$name members")
            assertEquals(wireSchema.required(), apiSchema.required(), "$name required members")
        }
    }

    @Test
    fun `a profile has the wire resource's content, beside the API's own bookkeeping`() {
        val bookkeeping = setOf("tenantKey", "createdAt", "lastModified")
        val wireOnly = setOf("type", "tags", "metadata")

        assertEquals(wire.getValue("QualityProfileResource").members() - wireOnly, api.getValue("QualityProfileDto").members() - bookkeeping)
        assertEquals(wire.getValue("QualityProfileResource").members() - wireOnly, api.getValue("CreateQualityProfileRequest").members())
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `severities and field roles are the catalog's`() {
        assertEquals(QualityProfiles.SEVERITIES, api.getValue("QualitySeverity")["enum"])
        // Roles are a string in the API (an enum constant `value` breaks the Kotlin client), so the
        // description names them.
        val roles = (api.getValue("QualityRuleScope")["properties"] as Map<String, Map<String, Any>>).getValue("roles")
        val named = Regex("`([a-z]+)`").findAll(roles["description"] as String).map { it.groupValues[1] }.toList()
        assertEquals(QualityProfiles.FIELD_ROLES + "prose", named)
    }

    @Test
    fun `the generated server binds the catalog's own quality types, and keeps every kind of extra`() {
        val rule = QualityRule(
            id = "sentence-length",
            title = "Short sentences",
            intent = "Keep sentences short.",
            severity = "warning",
            extras = mapOf("builtin" to mapOf("type" to "max-sentence-words", "limit" to 20), "llm" to mapOf("instruction" to "Count as a reader would.")),
        )
        val binding = QualityBinding(
            profiles = listOf(QualityProfileRef("clear-language")),
            overrides = listOf(QualityRuleOverride(QualityProfileRef("clear-language"), "sentence-length", enabled = false)),
        )
        val request = CreateQualityProfileRequest(key = "clear-language", name = "Clear language", rules = listOf(rule))

        assertSame(rule, request.rules.single())
        assertSame(binding, UpdateTemplateRequest(quality = binding).quality)
        assertSame(binding, UpdateCatalogReleaseRequest(quality = binding).quality)

        val decoded = mapper.readValue(mapper.writeValueAsString(request), CreateQualityProfileRequest::class.java)
        assertEquals(request, decoded)
        assertEquals(mapOf("instruction" to "Count as a reader would."), decoded.rules.single().extras?.get("llm"))
    }
}
