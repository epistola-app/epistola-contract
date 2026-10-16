// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.protocol

/**
 * Rules for a template variant's `metadata`, introduced by wire v8 and defined once for every
 * producer and consumer (#96).
 *
 * `metadata` is producer-specific data the contract carries but does not define. Each member is
 * named by a reverse-DNS **namespace** the producer owns (`app.epistola.suite`), and its value is a
 * JSON object the contract does not interpret. Only the producer that owns a namespace writes into
 * it; every other reader keeps it as it is, including one that does not recognise it. A variant
 * carries at most [MAX_NAMESPACES] namespaces and at most [MAX_SERIALIZED_BYTES] of serialized
 * metadata. Empty metadata is omitted rather than written as `{}`.
 *
 * [VariantEntry] deliberately does not enforce these rules; the catalog validator does.
 */
object CatalogVariantMetadata {
    /** A namespace: a lowercase reverse-DNS name with at least two labels. */
    const val NAMESPACE_PATTERN: String = "^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+$"

    /** Longest namespace. */
    const val NAMESPACE_MAX_LENGTH: Int = 100

    /** Fewest namespaces present metadata may hold; empty metadata is omitted. */
    const val MIN_NAMESPACES: Int = 1

    /** Most namespaces one variant may carry. */
    const val MAX_NAMESPACES: Int = 16

    /** Largest serialized metadata of one variant, in UTF-8 bytes of its compact JSON. */
    const val MAX_SERIALIZED_BYTES: Int = 8 * 1024

    private val namespace = Regex(NAMESPACE_PATTERN)

    /** Whether [value] is a valid metadata namespace. */
    fun isValidNamespace(value: String): Boolean = value.length <= NAMESPACE_MAX_LENGTH && namespace.matches(value)
}
