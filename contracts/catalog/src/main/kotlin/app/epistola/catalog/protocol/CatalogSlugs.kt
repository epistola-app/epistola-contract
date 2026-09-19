// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.protocol

/**
 * Resource slug rules introduced by wire v7, defined once for every producer and consumer.
 *
 * A slug is a resource's public address within its catalog. Until v7 the wire constrained it not at
 * all, while every consumer that stores one does: a catalog naming a theme with 30 characters was
 * publishable and then refused on install, with no diagnosis anywhere in between.
 *
 * The limits mirror the storage they have to survive rather than inventing new ones, which is why
 * they differ per type and why [ASSET] is the odd one. An asset's slug was historically a generated
 * UUID string, so it must admit a leading digit; every other type requires a leading letter. A
 * maximum may be relaxed later without breaking anyone — a wider column accepts every value a
 * narrower one held — but it may never be tightened once catalogs exist that use the extra room.
 *
 * Unlike [CatalogKeywords], a slug cannot be repaired when it does not conform: other resources
 * reference it by name, so rewriting one would break those references. The migrator reports a
 * finding instead. Resource models deliberately do not enforce these rules; consumers rebind
 * manifests stored under earlier wire versions. The migrator's wire check and the catalog validator
 * enforce them.
 */
object CatalogSlugs {
    /** Every type except an asset: a leading letter, then lowercase alphanumeric hyphen-separated parts. */
    const val PATTERN: String = "^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$"

    /** An asset additionally admits a leading digit, because its slug may be a generated UUID string. */
    const val ASSET_PATTERN: String = "^[a-z0-9]+(?:-[a-z0-9]+)*$"

    /** Bounds for one resource type's slug. */
    data class Rule(val pattern: String, val minLength: Int, val maxLength: Int)

    val TEMPLATE = Rule(PATTERN, 3, 50)
    val THEME = Rule(PATTERN, 3, 20)
    val STENCIL = Rule(PATTERN, 3, 50)
    val ATTRIBUTE = Rule(PATTERN, 3, 50)
    val CODE_LIST = Rule(PATTERN, 3, 64)
    val FONT = Rule(PATTERN, 2, 64)
    val ASSET = Rule(ASSET_PATTERN, 1, 50)

    /** The catalog's own slug. */
    val CATALOG = Rule(PATTERN, 3, 50)

    /** Rules by the wire `type` token. */
    val byType: Map<String, Rule> = mapOf(
        "template" to TEMPLATE,
        "theme" to THEME,
        "stencil" to STENCIL,
        "attribute" to ATTRIBUTE,
        "codeList" to CODE_LIST,
        "font" to FONT,
        "asset" to ASSET,
    )

    /**
     * The loosest bound any type allows, for positions where the type is not known from the schema
     * alone -- a manifest resource entry or a dependency reference. Both also appear as a typed
     * resource document, where the exact rule applies, so nothing escapes the tighter check.
     */
    val ANY = Rule(ASSET_PATTERN, 1, 64)

    private val compiled = HashMap<String, Regex>()

    /** Whether [value] is a valid slug for the wire [type]. */
    fun isValid(type: String, value: String): Boolean {
        val rule = byType[type] ?: ANY
        return matches(rule, value)
    }

    /** Whether [value] satisfies [rule]. */
    fun matches(rule: Rule, value: String): Boolean = value.length in rule.minLength..rule.maxLength &&
        compiled.getOrPut(rule.pattern) { Regex(rule.pattern) }.matches(value)
}
