// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.protocol

import java.text.Normalizer

/**
 * Catalog keyword rules introduced by wire v7, defined once for every producer and consumer.
 *
 * A keyword is lowercase ASCII letters and digits in hyphen-separated parts, at most [MAX_LENGTH]
 * characters, and a catalog lists at most [MAX_COUNT] distinct keywords. Lowercase-only makes the
 * canonical natural-order sort of [CatalogInfo.keywords] identical to a case-insensitive sort, so
 * every consumer can display the wire order as-is.
 *
 * [CatalogInfo] deliberately does not enforce these rules: consumers rebind manifests stored under
 * earlier wire versions. The migrator's wire check and the catalog validator enforce them instead.
 */
object CatalogKeywords {
    /** Maximum characters in one keyword. */
    const val MAX_LENGTH: Int = 30

    /** Maximum keywords one catalog may list. */
    const val MAX_COUNT: Int = 20

    /** Regular expression a keyword must match in full. */
    const val PATTERN: String = "^[a-z0-9]+(?:-[a-z0-9]+)*$"

    private val keyword = Regex(PATTERN)
    private val combiningMarks = Regex("\\p{M}+")
    private val separators = Regex("[^a-z0-9]+")

    /** Lowercase letters that have no canonical or compatibility decomposition to ASCII. */
    private val folds = mapOf(
        'ß' to "ss",
        'æ' to "ae",
        'œ' to "oe",
        'ø' to "o",
        'ł' to "l",
        'đ' to "d",
        'ð' to "d",
        'þ' to "th",
        'ı' to "i",
    )

    /** Whether [value] is a valid wire-v7 keyword. */
    fun isValid(value: String): Boolean = value.length <= MAX_LENGTH && keyword.matches(value)

    /**
     * Converts free text to its wire-v7 keyword, or null when no letter or digit survives.
     *
     * Diacritics and ligatures are reduced to ASCII (`Financiële` → `financiele`, `ĳ` → `ij`), text
     * is lowercased, every run of other characters becomes one hyphen (`Getting Started` →
     * `getting-started`, `WOZ/OZB` → `woz-ozb`), and a result longer than [MAX_LENGTH] is cut to fit
     * without leaving a trailing hyphen. The result of a non-null call always satisfies [isValid],
     * and normalizing a valid keyword returns it unchanged.
     */
    fun normalize(value: String): String? = truncate(hyphenate(value)).ifEmpty { null }

    /** Every normalization step except truncation; may exceed [MAX_LENGTH]. */
    internal fun hyphenate(value: String): String {
        val decomposed = combiningMarks.replace(Normalizer.normalize(value, Normalizer.Form.NFKD), "")
        val folded = buildString {
            decomposed.lowercase().forEach { char -> append(folds[char] ?: char) }
        }
        return separators.replace(folded, "-").trim('-')
    }

    /** Cuts a hyphenated keyword to [MAX_LENGTH] without leaving a trailing hyphen. */
    internal fun truncate(hyphenated: String): String = if (hyphenated.length <= MAX_LENGTH) {
        hyphenated
    } else {
        hyphenated.take(MAX_LENGTH).trimEnd('-')
    }

    /**
     * Every rule [keywords] break, in a stable order: the count first, then each keyword's
     * problems by index.
     */
    internal fun violations(keywords: List<String>): List<KeywordViolation> = buildList {
        if (keywords.size > MAX_COUNT) {
            add(KeywordViolation(null, KeywordRule.LIMIT_EXCEEDED, "a catalog may list at most $MAX_COUNT keywords"))
        }
        val seen = mutableSetOf<String>()
        keywords.forEachIndexed { index, value ->
            if (!keyword.matches(value)) {
                add(
                    KeywordViolation(
                        index,
                        KeywordRule.INVALID,
                        "keyword must be lowercase ASCII letters and digits in hyphen-separated parts",
                    ),
                )
            }
            if (value.length > MAX_LENGTH) {
                add(KeywordViolation(index, KeywordRule.TOO_LONG, "keyword must be at most $MAX_LENGTH characters"))
            }
            if (!seen.add(value)) {
                add(KeywordViolation(index, KeywordRule.DUPLICATE, "keyword '$value' is duplicated"))
            }
        }
    }
}

/** The wire-v7 keyword rule a [KeywordViolation] breaks. */
internal enum class KeywordRule { INVALID, TOO_LONG, DUPLICATE, LIMIT_EXCEEDED }

/** One broken keyword rule; [index] is null for rules about the whole collection. */
internal data class KeywordViolation(
    val index: Int?,
    val rule: KeywordRule,
    val message: String,
)
