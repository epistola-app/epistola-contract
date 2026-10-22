// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.protocol

/**
 * The alias rule of wire v8 (contract ADR 0003), defined once for every producer and consumer.
 *
 * A catalog's content refers to a resource in another catalog by an **alias** and the resource's
 * key: "theme `corporate` of `shared`". The catalog's dependency list maps each alias to the catalog
 * it stands for, by name (registry, namespace and key) and by the release it pins
 * ([CatalogDependency]). Within the catalog, content refers by key alone, as before. Because content
 * names an alias and never a registry or namespace, moving a dependency to another release changes
 * one line of the dependency list, and renaming or mirroring the catalog depended on changes nothing
 * in the content at all.
 *
 * - An alias follows the catalog key rule ([CatalogKeys.CATALOG]: a leading letter, lowercase letters
 *   and digits in hyphen-separated parts, 3 to 50 characters), because content has always named
 *   another catalog in the member `catalogKey`, which now holds the alias.
 * - It defaults to the depended-on catalog's own key: a declaration that names none uses
 *   [CatalogDependency.catalogKey], and the v7-to-v8 migration declares every catalog under its key.
 *   A producer chooses another one only when two catalogs it depends on share a key.
 * - It is unique within the dependency list.
 * - It is never the catalog's own key: content that names the catalog's own key, as content has
 *   always been allowed to, refers to the catalog itself.
 * - A reference that names no alias is to the catalog itself.
 *
 * Models deliberately do not enforce these rules; consumers rebind manifests stored before aliases
 * existed. The migrator's wire check (an alias is present and well-formed) and the catalog
 * validator (all of them) enforce them.
 */
object CatalogAliases {
    /** An alias: the catalog key rule. */
    const val PATTERN: String = CatalogKeys.CATALOG_PATTERN

    /** Shortest alias. */
    const val MIN_LENGTH: Int = 3

    /** Longest alias. */
    const val MAX_LENGTH: Int = 50

    /** The rule as a [CatalogKeys.Rule]: the catalog key's. */
    @JvmField
    val RULE: CatalogKeys.Rule = CatalogKeys.Rule(PATTERN, MIN_LENGTH, MAX_LENGTH)

    /** Whether [value] is a well-formed alias. */
    @JvmStatic
    fun isValid(value: String): Boolean = CatalogKeys.matches(RULE, value)
}
