// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.protocol

import java.text.Normalizer

/**
 * The key rule, defined once for every producer and consumer.
 *
 * A key is a resource's public address within its catalog (until wire v8 the member was called
 * `slug`, and this object `CatalogSlugs`). From wire v8 every resource key follows ONE rule
 * ([RESOURCE]): the keys of templates, variants, stencils, themes, attributes, code lists, fonts,
 * images and data examples, and every position that names one -- a reference in content, a resource
 * dependency, a code-list binding, a `fontFamily` key, the presentation's `iconImageKey` and
 * `imageKeys`, an attribute assignment's or a variant's attribute key. Lowercase ASCII letters and
 * digits in hyphen-separated parts, 3 to 50 characters. A leading digit is allowed for every type,
 * because an image's key may be a generated UUID string.
 *
 * Wire v7 bounded keys per type (1 to 64 characters, a leading letter except for images). The
 * v7-to-v8 migration brings a key that conforms otherwise within the bounds by [migrate]: a key
 * shorter than 3 is padded on the right with `0`, a key longer than 50 is cut. The rewrite is a pure
 * function of the key, so references -- into this catalog or another -- follow it without knowing
 * what they point at, and catalogs migrated independently still agree. A key with characters outside
 * the pattern is not repaired: it stays a finding.
 *
 * The catalog's own key ([CATALOG]) is not a resource key and keeps its rule: 3 to 50 characters,
 * starting with a letter.
 *
 * Resource models deliberately do not enforce these rules; consumers rebind manifests stored under
 * earlier wire versions. The migrator and the catalog validator enforce them.
 */
object CatalogKeys {
    /** Every resource key: lowercase alphanumeric hyphen-separated parts, a leading digit allowed. */
    const val PATTERN: String = "^[a-z0-9]+(-[a-z0-9]+)*$"

    /** Shortest resource key. */
    const val MIN_LENGTH: Int = 3

    /** Longest resource key. */
    const val MAX_LENGTH: Int = 50

    /** The character [migrate] pads a short key with. */
    const val PAD_CHARACTER: Char = '0'

    /** The catalog's own key: a leading letter, then lowercase alphanumeric hyphen-separated parts. */
    const val CATALOG_PATTERN: String = "^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$"

    /** Bounds for a key. */
    data class Rule(val pattern: String, val minLength: Int, val maxLength: Int)

    /** The one rule for every resource key, and every position that names one. */
    @JvmField
    val RESOURCE = Rule(PATTERN, MIN_LENGTH, MAX_LENGTH)

    /** The catalog's own key; not a resource key. */
    @JvmField
    val CATALOG = Rule(CATALOG_PATTERN, 3, 50)

    /** How [migrate] changed a key. */
    enum class Rewrite {
        /** Shorter than [MIN_LENGTH], padded on the right with [PAD_CHARACTER]. */
        PADDED,

        /** Longer than [MAX_LENGTH], cut to it without a trailing hyphen. */
        TRUNCATED,
    }

    /**
     * How the v7-to-v8 migration rewrites [key], or null when it leaves it as it is: because it
     * already fits the bounds, or because it does not match [PATTERN] (that is a finding, not a
     * repair). A key longer than [MAX_LENGTH] is cut to it and loses trailing hyphens; a key shorter
     * than [MIN_LENGTH] (or a cut key left that short) is padded on the right with [PAD_CHARACTER].
     * `a` becomes `a00`, `nl` `nl0`.
     */
    fun rewrite(key: String): Rewrite? = when {
        !patternRegex.matches(key) -> null
        key.length > MAX_LENGTH -> Rewrite.TRUNCATED
        key.length < MIN_LENGTH -> Rewrite.PADDED
        else -> null
    }

    /** [key] as the v7-to-v8 migration rewrites it ([rewrite]); unchanged when it needs none. */
    fun migrate(key: String): String = when (rewrite(key)) {
        null -> key
        Rewrite.TRUNCATED -> pad(fit(key, MAX_LENGTH))
        Rewrite.PADDED -> pad(key)
    }

    private fun pad(value: String): String = value.padEnd(MIN_LENGTH, PAD_CHARACTER)

    private val patternRegex = Regex(PATTERN)

    /** The key [deriveDataExampleKey] falls back to when a name has no letter or digit. */
    const val DATA_EXAMPLE_FALLBACK_KEY: String = "example"

    private val combiningMarks = Regex("\\p{M}+")
    private val separators = Regex("[^a-z0-9]+")

    /**
     * Derives a data example key from its display [name], deterministically, avoiding [taken].
     *
     * The name is decomposed (Unicode NFD) and stripped of combining marks, lowercased, and every
     * run of characters outside `a-z0-9` becomes one hyphen; leading and trailing hyphens are
     * trimmed, and the result is cut to [MAX_LENGTH] without leaving a trailing hyphen. An empty
     * result becomes [DATA_EXAMPLE_FALLBACK_KEY]; a result shorter than [MIN_LENGTH] is padded on the
     * right with [PAD_CHARACTER], as [migrate] pads a short key. A key already in [taken] gets the
     * first free suffix `-2`, `-3`, …, cutting the base so that the whole stays within the limit.
     * `Ärger & Co.` becomes `arger-co`, a second `Standaard brief` `standaard-brief-2`, `A` `a00`.
     *
     * The v7-to-v8 migration gives each catalog-v7 example its key this way, in document order.
     */
    fun deriveDataExampleKey(
        name: String,
        taken: Set<String> = emptySet(),
    ): String {
        val max = MAX_LENGTH
        val decomposed = combiningMarks.replace(Normalizer.normalize(name, Normalizer.Form.NFD), "")
        val base = pad(fit(separators.replace(decomposed.lowercase(), "-").trim('-'), max).ifEmpty { DATA_EXAMPLE_FALLBACK_KEY })
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

    /** Whether [value] is a valid resource key ([RESOURCE]), of whatever type. */
    fun isValid(value: String): Boolean = matches(RESOURCE, value)

    /** Whether [value] satisfies [rule]. */
    fun matches(rule: Rule, value: String): Boolean = value.length in rule.minLength..rule.maxLength &&
        compiled.getOrPut(rule.pattern) { Regex(rule.pattern) }.matches(value)
}
