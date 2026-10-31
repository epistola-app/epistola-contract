// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.validation

import app.epistola.catalog.archive.ArchiveContentProvider
import app.epistola.catalog.archive.CatalogArchive
import app.epistola.catalog.canonical.CatalogCanonicalizer
import app.epistola.catalog.canonical.CatalogFingerprintVersion
import app.epistola.catalog.migration.CatalogWireSchemaCheck
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.QualityBinding
import app.epistola.catalog.protocol.QualityProfileRef
import app.epistola.catalog.protocol.QualityProfileResource
import app.epistola.catalog.protocol.QualityProfiles
import app.epistola.catalog.protocol.QualityRuleOverride
import app.epistola.catalog.protocol.ResourceDetail
import app.epistola.catalog.protocol.ResourceEntry
import app.epistola.catalog.protocol.TemplateResource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Quality profiles in catalog wire v8 (contract ADR 0010): the resource and the quality binding of a
 * catalog and a template, as the model binds them, the schema accepts them, the validator judges them
 * and the fingerprint covers them. Built on the golden wire-v8 fixture.
 */
class QualityProfileContractTest {
    private val mapper = jsonMapper { addModule(kotlinModule()) }

    @Test
    fun `a profile binds, writes back as it was read, and passes the wire schema`() {
        val tree = mapper.readTree(PROFILE) as ObjectNode
        val detail = mapper.treeToValue(tree, ResourceDetail::class.java)

        val profile = detail.resource as QualityProfileResource
        assertEquals(listOf("sentence-length", "plain-words"), profile.rules.map { it.id })
        assertEquals(mapOf("type" to "max-sentence-words", "limit" to 20), profile.rules[0].extras?.get("builtin"))
        assertEquals(listOf("Epistola"), profile.acceptedWords)
        assertEquals(tree, mapper.valueToTree<JsonNode>(detail))
        assertEquals(emptyList(), CatalogWireSchemaCheck.resourceDetailFindings(tree, "resources/qualityProfile/clear-language.json"))
    }

    @Test
    fun `the wire schema refuses an unknown builtin type, a bad severity, and a member a rule does not have`() {
        fun findings(edit: (ObjectNode) -> Unit): List<String> {
            val tree = mapper.readTree(PROFILE) as ObjectNode
            edit(tree["resource"]["rules"][0] as ObjectNode)
            return CatalogWireSchemaCheck.resourceDetailFindings(tree, "p").map { it.path }
        }

        assertTrue(findings { (it["extras"]["builtin"] as ObjectNode).put("type", "spell-everything") }.isNotEmpty())
        assertTrue(findings { it.put("severity", "fatal") }.isNotEmpty())
        assertTrue(findings { it.put("weight", 3) }.isNotEmpty())
        // Another kind's extras are the consumer's to read: any object passes.
        assertEquals(emptyList(), findings { (it["extras"] as ObjectNode).putObject("vale").put("anything", true) })
    }

    @Test
    fun `a catalog and a template binding profiles, with overrides, are valid`() {
        val report = CatalogValidator.validate(archive(catalogQuality = BINDING, templateQuality = BINDING))

        assertEquals(emptyList(), report.findings)
    }

    @Test
    fun `the validator reports duplicate rule ids, unknown builtin types and bad severities`() {
        val profile = profile().let {
            it.copy(
                rules = listOf(
                    it.rules[0],
                    it.rules[0].copy(severity = "fatal"),
                    it.rules[1].copy(extras = mapOf("builtin" to mapOf("type" to "spell-everything"))),
                ),
            )
        }

        val codes = CatalogValidator.validate(archive(profile = profile)).findings.map { it.code to it.path }

        assertTrue(CatalogValidationCodes.QUALITY_RULE_ID_DUPLICATE to "resources/qualityProfile/clear-language.json.resource.rules[1].id" in codes)
        assertTrue(CatalogValidationCodes.QUALITY_SEVERITY_INVALID to "resources/qualityProfile/clear-language.json.resource.rules[1].severity" in codes)
        assertTrue(CatalogValidationCodes.QUALITY_RULE_EXTRA_INVALID to "resources/qualityProfile/clear-language.json.resource.rules[2].extras.builtin" in codes)
    }

    @Test
    fun `an override of a rule the profile does not have, a missing profile, and an undeclared alias are reported`() {
        val unknownRule = QualityBinding(overrides = listOf(QualityRuleOverride(QualityProfileRef("clear-language"), "no-such-rule", enabled = false)))
        val missingProfile = QualityBinding(profiles = listOf(QualityProfileRef("house-style")))
        val undeclared = QualityBinding(profiles = listOf(QualityProfileRef("house-style", catalogKey = "house")))

        fun codes(catalogQuality: QualityBinding? = null, templateQuality: QualityBinding? = null) = CatalogValidator.validate(archive(catalogQuality = catalogQuality, templateQuality = templateQuality))
            .findings.map { it.code to it.path }

        assertTrue(
            CatalogValidationCodes.QUALITY_OVERRIDE_RULE_UNKNOWN to "resources/template/letter.json.resource.quality.overrides[0].ruleId" in codes(templateQuality = unknownRule),
        )
        assertTrue(CatalogValidationCodes.RESOURCE_REFERENCE_MISSING to "catalog.json.catalog.quality.profiles[0].key" in codes(catalogQuality = missingProfile))
        assertTrue(
            CatalogValidationCodes.DEPENDENCY_CATALOG_UNDECLARED to "resources/template/letter.json.resource.quality.profiles[0].catalogKey" in codes(templateQuality = undeclared),
        )
        assertTrue(CatalogValidationCodes.DEPENDENCY_CATALOG_UNDECLARED to "catalog.json.catalog.quality.profiles[0].catalogKey" in codes(catalogQuality = undeclared))
    }

    @Test
    fun `a builtin extra is checked for its type's members`() {
        assertNull(QualityProfiles.builtinViolation(mapOf("type" to "double-space")))
        assertNull(QualityProfiles.builtinViolation(mapOf("type" to "word-list", "entries" to mapOf("nl" to mapOf("middels" to listOf("met"))))))
        assertTrue(QualityProfiles.builtinViolation(mapOf("type" to "max-sentence-words")) != null)
        assertTrue(QualityProfiles.builtinViolation(mapOf("type" to "max-sentence-words", "limit" to 0)) != null)
        assertTrue(QualityProfiles.builtinViolation(mapOf("type" to "double-space", "limit" to 3)) != null)
        assertTrue(QualityProfiles.builtinViolation(mapOf("limit" to 3)) != null)
    }

    @Test
    fun `the schema's severities, roles and builtin types are the ones the model names`() {
        val schema = javaClass.getResourceAsStream("/META-INF/epistola-catalog/schemas/resource-detail-v8.schema.json").use(mapper::readTree)
        val defs = schema["\$defs"]

        assertEquals(QualityProfiles.SEVERITIES, defs["QualityRule"]["properties"]["severity"]["enum"].toList().map { it.asString() })
        assertEquals(QualityProfiles.FIELD_ROLES, defs["QualityRuleScope"]["properties"]["roles"]["items"]["enum"].toList().map { it.asString() })
        assertEquals(QualityProfiles.BUILTIN_TYPES, defs["QualityBuiltinExtra"]["oneOf"].toList().map { it["properties"]["type"]["const"].asString() })
    }

    @Test
    fun `a catalog without quality keeps its fingerprint, and a binding or a profile changes it`() {
        val golden = archive(withProfile = false)
        val declared = golden.manifest.release.fingerprint

        // The golden catalog's own fingerprint, from before quality profiles existed, still holds.
        assertEquals(declared, CatalogCanonicalizer.currentQualifiedFingerprint(golden).toString())

        val withProfile = CatalogCanonicalizer.currentQualifiedFingerprint(archive())
        val withCatalogQuality = CatalogCanonicalizer.currentQualifiedFingerprint(archive(catalogQuality = BINDING))
        val withTemplateQuality = CatalogCanonicalizer.currentQualifiedFingerprint(archive(templateQuality = BINDING))
        assertEquals(4, setOf(declared, withProfile.toString(), withCatalogQuality.toString(), withTemplateQuality.toString()).size)

        // V5 covers both bindings too; the legacy algorithms leave a template's out.
        assertNotEquals(
            CatalogCanonicalizer.fingerprint(archive(), CatalogFingerprintVersion.V5),
            CatalogCanonicalizer.fingerprint(archive(catalogQuality = BINDING), CatalogFingerprintVersion.V5),
        )
        assertEquals(
            CatalogCanonicalizer.fingerprint(archive(), CatalogFingerprintVersion.V4),
            CatalogCanonicalizer.fingerprint(archive(templateQuality = BINDING), CatalogFingerprintVersion.V4),
        )
    }

    private fun profile(): QualityProfileResource = mapper.readValue(PROFILE, ResourceDetail::class.java).resource as QualityProfileResource

    /**
     * The golden wire-v8 catalog, with the profile [profile] added unless [withProfile] is false, and
     * the catalog's and the template's bindings. Declares no fingerprint unless it is the golden one.
     */
    private fun archive(
        profile: QualityProfileResource = profile(),
        withProfile: Boolean = true,
        catalogQuality: QualityBinding? = null,
        templateQuality: QualityBinding? = null,
    ): CatalogArchive {
        val manifest = fixture("wire-v8/catalog.json").use { mapper.readValue(it, CatalogManifest::class.java) }
        val details = listOf("theme/default", "template/letter").associateWith { key ->
            fixture("wire-v8/resources/$key.json").use { mapper.readValue(it, ResourceDetail::class.java) }
        }.toMutableMap()
        if (!withProfile && catalogQuality == null && templateQuality == null) {
            return CatalogArchive(manifest, details, paths(details), CONTENT)
        }
        val template = details.getValue("template/letter").let { it.copy(resource = (it.resource as TemplateResource).copy(quality = templateQuality)) }
        details["template/letter"] = template
        val resources = manifest.resources.toMutableList()
        if (withProfile) {
            details["qualityProfile/${profile.key}"] = ResourceDetail(manifest.schemaVersion, profile)
            resources += ResourceEntry("qualityProfile", profile.key, profile.name, profile.description, detailUrl = "./resources/qualityProfile/${profile.key}.json")
        }
        val changed = manifest.copy(
            catalog = manifest.catalog.copyWithMetadata(quality = catalogQuality),
            resources = resources,
            release = manifest.release.copy(fingerprint = null),
        )
        val unsigned = CatalogArchive(changed, details, paths(details), CONTENT)
        val fingerprint = CatalogCanonicalizer.currentQualifiedFingerprint(unsigned).toString()
        return CatalogArchive(changed.copy(release = changed.release.copy(fingerprint = fingerprint)), details, paths(details), CONTENT)
    }

    private fun paths(details: Map<String, ResourceDetail>) = details.keys.mapTo(mutableSetOf("catalog.json")) { "resources/$it.json" }

    private fun fixture(path: String) = requireNotNull(javaClass.getResourceAsStream("/META-INF/epistola-catalog/fixtures/v1/$path")) { "no fixture $path" }

    private companion object {
        val CONTENT = ArchiveContentProvider { ByteArrayInputStream("{}".toByteArray()) }

        val BINDING = QualityBinding(
            profiles = listOf(QualityProfileRef("clear-language")),
            overrides = listOf(QualityRuleOverride(QualityProfileRef("clear-language"), "sentence-length", severity = "info")),
        )

        const val PROFILE = """
            {
              "schemaVersion": 8,
              "resource": {
                "type": "qualityProfile",
                "key": "clear-language",
                "name": "Clear language",
                "description": "Plain, short writing for public communication.",
                "rules": [
                  {
                    "id": "sentence-length",
                    "title": "Short sentences",
                    "intent": "Keep sentences to at most 20 words: citizens read at language level B1.",
                    "severity": "warning",
                    "blocking": true,
                    "scope": { "roles": ["prose"], "languages": ["nl"] },
                    "examples": { "good": ["Wij sturen u de stukken."], "bad": ["Middels deze brief, die u heden ontvangt, delen wij u mede dat de stukken zijn verzonden."] },
                    "extras": {
                      "builtin": { "type": "max-sentence-words", "limit": 20 },
                      "llm": { "instruction": "Count words as a reader would; a placeholder is one word." }
                    }
                  },
                  {
                    "id": "plain-words",
                    "title": "Plain words",
                    "intent": "Prefer everyday words to official ones.",
                    "severity": "info",
                    "extras": {
                      "builtin": { "type": "word-list", "entries": { "nl": { "middels": ["met", "via"] } } },
                      "languagetool": { "categories": ["STYLE"] }
                    }
                  }
                ],
                "acceptedWords": ["Epistola"]
              }
            }
        """
    }
}
