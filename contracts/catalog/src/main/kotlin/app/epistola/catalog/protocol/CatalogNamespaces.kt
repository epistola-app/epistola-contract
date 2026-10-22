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
 * An archive names a catalog by registry and namespace in two places only: the catalog itself
 * (`catalog`) and each `catalogDependencies` entry. Both members are required there, and the
 * registry is written in full. An archive has no default registry: which registry an installation
 * treats as its default is configuration, and an archive read on another installation must mean the
 * same catalog. Content, and the manifest's index of what the content uses (`dependencies`), name
 * another catalog by the alias its declaration gives it ([CatalogAliases], contract ADR 0003), never by
 * registry or namespace, so that renaming or mirroring that catalog changes no content.
 *
 * The registry is the **authority** of a catalog's name (contract ADR 0003): the domain under which its
 * namespace was granted, by an exchange for the namespaces it grants (`exchange.epistola.app`) or by
 * an organisation for its own (`gemeente-x.nl`). It never says where a release is fetched from. A
 * namespace is flat: an organisation may hold several (`acme`, `acme-hr`), and a name never nests.
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
