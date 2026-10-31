// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.protocol

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonInclude

/**
 * A quality profile (wire v8, contract ADR 0010): rules an organisation records about how its
 * documents are written, independent of the systems that check them.
 *
 * Each [QualityRule] has a generic core every checking system and every author reads (its intent,
 * severity, scope and examples) and optional [QualityRule.extras] per kind of system. A consumer
 * applies the rules it can and reports the others as not checked; a profile may carry extras for
 * kinds a consumer does not have, and keeps them.
 *
 * [acceptedWords] are words the organisation uses on purpose (names, product terms) that a spelling
 * check must not report.
 *
 * Rule ids are unique within a profile, and the extras of the `builtin` kind are held to the shapes
 * [QualityProfiles] names; the migrator's wire check and the validator enforce that, not this model.
 */
data class QualityProfileResource(
    @JsonAlias("slug")
    override val key: String,
    override val name: String,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val description: String? = null,
    val rules: List<QualityRule>,
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val acceptedWords: List<String> = emptyList(),
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    override val tags: List<String> = emptyList(),
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    override val metadata: Map<String, Map<String, Any?>>? = null,
) : CatalogResource {
    override val type: String get() = "qualityProfile"
}

/**
 * One rule of a [QualityProfileResource].
 *
 * @property id the rule's address within its profile ([CatalogKeys.RESOURCE] shape); findings and
 *   [QualityRuleOverride]s name it.
 * @property title a short name.
 * @property intent the rule in plain language: what and why. Shown to authors as the explanation of
 *   every finding of the rule, and what a language model is asked to apply.
 * @property severity `info`, `warning` or `error` ([QualityProfiles.SEVERITIES]).
 * @property blocking whether an open finding of the rule stops a variant being marked ready and a
 *   release being cut. Omitted when false.
 * @property scope what the rule reads; null means every prose field.
 * @property examples text that meets the rule and text that does not.
 * @property extras what a kind of checking system needs beyond the core, by kind (`builtin`,
 *   `languagetool`, `llm`, …), each a JSON object. Only `builtin` has a shape the contract fixes.
 */
data class QualityRule(
    val id: String,
    val title: String,
    val intent: String,
    val severity: String,
    @get:JsonInclude(JsonInclude.Include.NON_DEFAULT)
    val blocking: Boolean = false,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val scope: QualityRuleScope? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val examples: QualityRuleExamples? = null,
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val extras: Map<String, Map<String, Any?>>? = null,
)

/**
 * What a [QualityRule] reads. Each list narrows; an empty or absent one does not.
 *
 * @property roles the field roles the rule reads ([QualityProfiles.FIELD_ROLES]); absent means
 *   `prose`.
 * @property components the component types the rule reads (`text`, `image`, …).
 * @property languages the languages the rule applies to, as BCP 47 language tags (`nl`, `en-GB`).
 */
data class QualityRuleScope(
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val roles: List<String> = emptyList(),
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val components: List<String> = emptyList(),
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val languages: List<String> = emptyList(),
)

/** Text that meets a [QualityRule] ([good]) and text that does not ([bad]). */
data class QualityRuleExamples(
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val good: List<String> = emptyList(),
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val bad: List<String> = emptyList(),
)

/**
 * Which quality profiles apply, and how this level changes their rules (wire v8, contract ADR 0010).
 * A catalog's ([CatalogInfo.quality]) applies to every template in it; a template's
 * ([TemplateResource.quality]) adds to it. All their profiles' rules apply together; an override at
 * the template replaces one at the catalog for the same rule.
 */
data class QualityBinding(
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val profiles: List<QualityProfileRef> = emptyList(),
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val overrides: List<QualityRuleOverride> = emptyList(),
)

/**
 * A reference to a [QualityProfileResource]. Like every reference into another catalog, it names the
 * catalog by the alias the dependency list declares ([CatalogAliases], contract ADR 0003); an omitted
 * [catalogKey] means the catalog itself.
 */
data class QualityProfileRef(
    val key: String,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val catalogKey: String? = null,
)

/**
 * A change to one rule of a profile, at the level of a [QualityBinding]: switch it off ([enabled]
 * false) or give it another [severity]. Absent members leave the rule as its profile says.
 */
data class QualityRuleOverride(
    val profile: QualityProfileRef,
    val ruleId: String,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val enabled: Boolean? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val severity: String? = null,
)

/**
 * The rules of quality profiles that consumers enforce too (contract ADR 0010), defined once and held
 * to the schema by tests. The migrator's wire check and the validator apply them, not the model.
 */
object QualityProfiles {
    /** A rule's severity. */
    @JvmField
    val SEVERITIES: List<String> = listOf("info", "warning", "error")

    /** The role of a component field (suite ADR 0045): what a rule's scope may name. */
    @JvmField
    val FIELD_ROLES: List<String> = listOf("prose", "identifier", "expression", "value", "reference")

    /** The extras kind whose shape the contract fixes. */
    const val BUILTIN_KIND: String = "builtin"

    /**
     * The check types of the `builtin` kind, each with the members it takes beside `type`:
     *
     * - `repeated-word`: `allowed`, language to the words that may repeat (`{"nl": ["dat"]}`);
     * - `max-sentence-words`: `limit`, a positive whole number;
     * - `double-space`: nothing;
     * - `space-before-punctuation`: `exceptLanguages`, the languages that put a space there;
     * - `word-list`: `entries`, language to phrase to its alternatives.
     */
    @JvmField
    val BUILTIN_TYPES: List<String> = listOf("repeated-word", "max-sentence-words", "double-space", "space-before-punctuation", "word-list")

    /** Rule ids follow the resource key shape. */
    @JvmStatic
    fun isValidRuleId(id: String): Boolean = CatalogKeys.isValid(id)

    /**
     * What is wrong with a `builtin` extra, or null when nothing is: an unknown type, a missing or
     * mistyped parameter, or a member the type does not take.
     */
    @JvmStatic
    fun builtinViolation(extra: Map<String, Any?>): String? {
        val type = extra["type"] as? String ?: return "builtin extra names no type"
        val members = extra.keys - "type"
        fun only(vararg allowed: String): String? = (members - allowed.toSet()).takeIf { it.isNotEmpty() }?.let { "builtin $type takes no member ${it.sorted().joinToString()}" }
        return when (type) {
            "repeated-word" -> only("allowed") ?: extra["allowed"]?.let { languageMap(it, "allowed") { words -> stringList(words) } }
            "max-sentence-words" -> only("limit") ?: when (val limit = extra["limit"]) {
                null -> "max-sentence-words needs a limit"
                is Number -> if (limit.toDouble() >= 1 && limit.toDouble() % 1 == 0.0) null else "max-sentence-words limit must be a positive whole number"
                else -> "max-sentence-words limit must be a positive whole number"
            }
            "double-space" -> only()
            "space-before-punctuation" -> only("exceptLanguages") ?: extra["exceptLanguages"]?.let { if (stringList(it)) null else "exceptLanguages must be a list of language tags" }
            "word-list" -> only("entries") ?: (extra["entries"] ?: return "word-list needs entries").let { entries ->
                languageMap(entries, "entries") { phrases -> (phrases as? Map<*, *>)?.all { (phrase, alternatives) -> phrase is String && stringList(alternatives) } == true }
            }
            else -> "unknown builtin type '$type'; one of ${BUILTIN_TYPES.joinToString()}"
        }
    }

    private fun stringList(value: Any?): Boolean = value is List<*> && value.all { it is String }

    private fun languageMap(value: Any, member: String, valid: (Any?) -> Boolean): String? = if (value is Map<*, *> && value.all { (language, entry) -> language is String && valid(entry) }) {
        null
    } else {
        "$member must map a language to its entries"
    }
}
