// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.protocol

/**
 * Registry and namespace rules introduced by wire v8, defined once for every producer and consumer.
 *
 * A catalog is identified by three separate parts: its **registry**, its **namespace** within that
 * registry, and its key within the namespace. A registry is an exchange (`exchange.epistola.app`)
 * or a domain that publishes its own catalogs (`acme.com`), written as a DNS name in normal order.
 * A namespace is a slug (`acme-corp`); the same namespace name in two registries names two
 * different namespaces.
 *
 * The two positions an archive names a catalog in differ:
 * - Where an archive names a catalog at the top level -- `catalog`, a resource dependency, a
 *   `catalogDependencies` entry -- both `registry` and `namespace` are required, and the registry is
 *   written in full. An archive has no default registry: which registry an installation treats as
 *   its default is configuration, and an archive read on another installation must mean the same
 *   catalog.
 * - A reference inside resource content may omit either: an omitted `registry` or `namespace` means
 *   the **owning catalog's**, never an installation default, so content means the same thing
 *   wherever it is installed. A reference naming a `registry` without a `namespace` is invalid.
 *
 * Models deliberately do not enforce these rules; consumers rebind manifests stored under earlier
 * wire versions, which name neither. The migrator's wire check and the catalog validator enforce
 * them.
 */
object CatalogNamespaces {
    /** Shortest namespace. */
    const val MIN_LENGTH: Int = 3

    /** Longest namespace. */
    const val MAX_LENGTH: Int = 63

    /** A namespace: a lowercase kebab-case slug. */
    const val PATTERN: String = "^[a-z][a-z0-9]*(-[a-z0-9]+)*$"

    /** Longest registry, the longest DNS name. */
    const val REGISTRY_MAX_LENGTH: Int = 253

    /** A registry: a lowercase DNS name with at least one dot, in normal (not reversed) order. */
    const val REGISTRY_PATTERN: String =
        "^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$"

    private val namespace = Regex(PATTERN)
    private val registry = Regex(REGISTRY_PATTERN)

    /** Whether [value] is a valid namespace. */
    fun isValid(value: String): Boolean = value.length in MIN_LENGTH..MAX_LENGTH && namespace.matches(value)

    /** Whether [value] is a valid registry. */
    fun isValidRegistry(value: String): Boolean = value.length <= REGISTRY_MAX_LENGTH && registry.matches(value)
}
