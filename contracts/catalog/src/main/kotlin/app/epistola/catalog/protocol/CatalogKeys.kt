// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.protocol

import java.text.Normalizer

/**
 * Resource key rules introduced by wire v7, defined once for every producer and consumer.
 *
 * A key is a resource's public address within its catalog (until wire v8 the member was called
 * `slug`, and this object `CatalogSlugs`). Until v7 the wire constrained it not at
 * all, while every consumer that stores one does: a catalog naming a theme with 30 characters was
 * publishable and then refused on install, with no diagnosis anywhere in between.
 *
 * The limits mirror the storage they have to survive rather than inventing new ones, which is why
 * they differ per type and why [IMAGE] is the odd one. An image's key may be a generated
 * UUID string, so it must admit a leading digit; every other type requires a leading letter. A
 * maximum may be relaxed later without breaking anyone — a wider column accepts every value a
 * narrower one held — but it may never be tightened once catalogs exist that use the extra room.
 *
 * Unlike [CatalogKeywords], a key cannot be repaired when it does not conform: other resources
 * reference it by name, so rewriting one would break those references. The migrator reports a
 * finding instead. Resource models deliberately do not enforce these rules; consumers rebind
 * manifests stored under earlier wire versions. The migrator's wire check and the catalog validator
 * enforce them.
 */
object CatalogKeys {
    /** Every type except an image: a leading letter, then lowercase alphanumeric hyphen-separated parts. */
    const val PATTERN: String = "^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$"

    /** An image additionally admits a leading digit, because its key may be a generated UUID string. */
    const val IMAGE_PATTERN: String = "^[a-z0-9]+(?:-[a-z0-9]+)*$"

    /** Bounds for one resource type's key. */
    data class Rule(val pattern: String, val minLength: Int, val maxLength: Int)

    val TEMPLATE = Rule(PATTERN, 3, 50)
    val THEME = Rule(PATTERN, 3, 50)
    val STENCIL = Rule(PATTERN, 3, 50)
    val ATTRIBUTE = Rule(PATTERN, 3, 50)
    val CODE_LIST = Rule(PATTERN, 3, 64)
    val FONT = Rule(PATTERN, 2, 64)
    val IMAGE = Rule(IMAGE_PATTERN, 1, 50)

    /** The catalog's own key. */
    val CATALOG = Rule(PATTERN, 3, 50)

    /** Rules by the wire `type` token. */
    val byType: Map<String, Rule> = mapOf(
        "template" to TEMPLATE,
        "theme" to THEME,
        "stencil" to STENCIL,
        "attribute" to ATTRIBUTE,
        "codeList" to CODE_LIST,
        "font" to FONT,
        "image" to IMAGE,
    )

    /**
     * The loosest bound any type allows, for positions where the type is not known from the schema
     * alone -- a manifest resource entry or a dependency reference. Both also appear as a typed
     * resource document, where the exact rule applies, so nothing escapes the tighter check.
     */
    val ANY = Rule(IMAGE_PATTERN, 1, 64)

    /**
     * A template data example's key (wire v8): its address within its template. The same bounds as
     * a manifest resource entry's key ([ANY]), so a leading digit is allowed.
     */
    val DATA_EXAMPLE = Rule(IMAGE_PATTERN, 1, 64)

    /** The key [deriveDataExampleKey] falls back to when a name has no letter or digit. */
    const val DATA_EXAMPLE_FALLBACK_KEY: String = "example"

    private val combiningMarks = Regex("\\p{M}+")
    private val separators = Regex("[^a-z0-9]+")

    /**
     * Derives a data example key from its display [name], deterministically, avoiding [taken].
     *
     * The name is decomposed (Unicode NFD) and stripped of combining marks, lowercased, and every
     * run of characters outside `a-z0-9` becomes one hyphen; leading and trailing hyphens are
     * trimmed, and the result is cut to [DATA_EXAMPLE]'s maximum length without leaving a trailing
     * hyphen. An empty result becomes [DATA_EXAMPLE_FALLBACK_KEY]. A key already in [taken] gets the
     * first free suffix `-2`, `-3`, …, cutting the base so that the whole stays within the limit.
     * `Ärger & Co.` becomes `arger-co`, a second `Standaard brief` `standaard-brief-2`.
     *
     * The v7-to-v8 migration gives each catalog-v7 example its key this way, in document order.
     */
    fun deriveDataExampleKey(
        name: String,
        taken: Set<String> = emptySet(),
    ): String {
        val max = DATA_EXAMPLE.maxLength
        val decomposed = combiningMarks.replace(Normalizer.normalize(name, Normalizer.Form.NFD), "")
        val base = fit(separators.replace(decomposed.lowercase(), "-").trim('-'), max).ifEmpty { DATA_EXAMPLE_FALLBACK_KEY }
        if (base !in taken) return base
        var suffix = 2
        while (true) {
            val candidate = fit(base, max - "-$suffix".length).ifEmpty { DATA_EXAMPLE_FALLBACK_KEY.take(max - "-$suffix".length) } + "-$suffix"
            if (candidate !in taken) return candidate
            suffix++
        }
    }

    /** Cuts [value] to [length] characters without leaving a trailing hyphen. */
    private fun fit(
        value: String,
        length: Int,
    ): String = if (value.length <= length) value else value.take(length).trimEnd('-')

    private val compiled = HashMap<String, Regex>()

    /** Whether [value] is a valid key for the wire [type]. */
    fun isValid(type: String, value: String): Boolean {
        val rule = byType[type] ?: ANY
        return matches(rule, value)
    }

    /** Whether [value] satisfies [rule]. */
    fun matches(rule: Rule, value: String): Boolean = value.length in rule.minLength..rule.maxLength &&
        compiled.getOrPut(rule.pattern) { Regex(rule.pattern) }.matches(value)
}
