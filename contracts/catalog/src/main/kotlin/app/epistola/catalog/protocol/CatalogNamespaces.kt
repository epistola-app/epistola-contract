// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.protocol

/**
 * Catalog namespace rules introduced by wire v8, defined once for every producer and consumer.
 *
 * A catalog is identified by its namespace and its key together. A namespace is either a domain
 * (`acme.com`) or a namespace on an exchange, written `host/name`
 * (`exchange.epistola.app/acme-corp`). The API also accepts a **short form**, a bare name on the
 * installation's default exchange (`acme-corp`), but which exchange is the default is installation
 * configuration and not part of any contract.
 *
 * That is why the two positions differ:
 * - Where a catalog archive names a catalog at the top level -- `catalog.namespace`, a dependency's
 *   `namespace`, a `catalogDependencies` entry -- the namespace is written **in full**
 *   ([FULL_PATTERN]). An archive read on another installation must mean the same catalog.
 * - A reference inside resource content may use either form ([PATTERN]), and may omit the namespace
 *   to mean the owning catalog's. Content is read relative to a catalog whose namespace is explicit.
 *
 * Models deliberately do not enforce these rules; consumers rebind manifests stored under earlier
 * wire versions, which have no namespace at all. The migrator's wire check and the catalog
 * validator enforce them.
 */
object CatalogNamespaces {
    /** Shortest namespace either form allows. */
    const val MIN_LENGTH: Int = 3

    /** Longest namespace either form allows. */
    const val MAX_LENGTH: Int = 320

    /** A namespace written in full: a domain, optionally followed by `/name`. */
    const val FULL_PATTERN: String =
        "^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+(/[a-z][a-z0-9]*(-[a-z0-9]+)*)?$"

    /** A namespace in either form: a bare name on the default exchange, or [FULL_PATTERN]. */
    const val PATTERN: String =
        "^([a-z][a-z0-9]*(-[a-z0-9]+)*|[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+(/[a-z][a-z0-9]*(-[a-z0-9]+)*)?)$"

    private val full = Regex(FULL_PATTERN)
    private val any = Regex(PATTERN)

    /** Whether [value] is a namespace written in full, as an archive's top level requires. */
    fun isValidFull(value: String): Boolean = value.length in MIN_LENGTH..MAX_LENGTH && full.matches(value)

    /** Whether [value] is a namespace in either form, as a reference inside content allows. */
    fun isValid(value: String): Boolean = value.length in MIN_LENGTH..MAX_LENGTH && any.matches(value)
}
