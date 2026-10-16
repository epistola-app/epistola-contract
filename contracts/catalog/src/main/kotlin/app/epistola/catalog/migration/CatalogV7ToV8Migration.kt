// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.migration

import app.epistola.catalog.protocol.CatalogNamespaces
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * Moves a catalog-v7 archive to catalog v8, where a catalog is identified by registry, namespace and
 * key and versioned as a whole.
 *
 * - **Registry and namespace.** A v7 archive names neither, and nothing in it says which it belongs
 *   to, so the caller supplies both ([CatalogMigrationOptions.registry],
 *   [CatalogMigrationOptions.namespace]). Without one the manifest fails with
 *   [CatalogMigrationCodes.REGISTRY_MISSING] or [CatalogMigrationCodes.NAMESPACE_MISSING]; the
 *   migrator never invents an identity.
 * - **Resource dependencies** take the catalog's own registry and namespace. A v7 catalog's content
 *   lived in one tenant, so every catalog it referenced sat next to it.
 * - **Catalog dependencies** are declared, one per catalog the resource dependencies name, with the
 *   unpinned selector `*` and no recorded release: v7 never said which version was meant, and the
 *   author sets a selector rather than the migrator guessing one.
 * - **Stencil versions** are removed, both a stencil's own `version` and a stencil reference's
 *   `version` and `draftVersion`. A stencil reference resolves through its catalog's dependency.
 *
 * Each repair is reported with a [CatalogMigrationNotice]. A stencil reference's `isDraft`, which
 * catalog v5 already rejected, is left in place and remains a validation finding.
 */
internal class CatalogV7ToV8Migration : CatalogSchemaMigration {
    override val fromVersion: Int = 7
    override val toVersion: Int = 8

    override fun migrateManifest(
        tree: ObjectNode,
        options: CatalogMigrationOptions,
    ): CatalogMigrationStepResult {
        tree.put("schemaVersion", toVersion)
        val identityFindings = identityFindings(options)
        if (identityFindings.isNotEmpty()) return CatalogMigrationStepResult(identityFindings)
        val registry = checkNotNull(options.registry)
        val namespace = checkNotNull(options.namespace)
        val catalog = tree["catalog"] as? ObjectNode ?: return CatalogMigrationStepResult()
        val ownKey = catalog["slug"]?.takeIf { it.isString }?.asString()
        val notices = mutableListOf<CatalogMigrationNotice>()

        catalog.put("registry", registry)
        catalog.put("namespace", namespace)
        notices += CatalogMigrationNotice(
            CatalogMigrationCodes.REGISTRY_ASSIGNED,
            "catalog.json.catalog.registry",
            "catalog was given the registry '$registry'",
        )
        notices += CatalogMigrationNotice(
            CatalogMigrationCodes.NAMESPACE_ASSIGNED,
            "catalog.json.catalog.namespace",
            "catalog was given the namespace '$namespace'",
        )

        val referencedKeys = sortedSetOf<String>()
        (tree["dependencies"] as? ArrayNode)?.forEachIndexed { index, dependency ->
            val entry = dependency as? ObjectNode ?: return@forEachIndexed
            if (!entry.has("registry")) {
                entry.put("registry", registry)
                notices += CatalogMigrationNotice(
                    CatalogMigrationCodes.DEPENDENCY_REGISTRY_ASSIGNED,
                    "catalog.json.dependencies[$index].registry",
                    "dependency was given the catalog's registry '$registry'",
                )
            }
            if (entry.has("namespace")) return@forEachIndexed
            entry.put("namespace", namespace)
            notices += CatalogMigrationNotice(
                CatalogMigrationCodes.DEPENDENCY_NAMESPACE_ASSIGNED,
                "catalog.json.dependencies[$index].namespace",
                "dependency was given the catalog's namespace '$namespace'",
            )
            entry["catalogKey"]?.takeIf { it.isString }?.asString()
                ?.takeIf { it != ownKey }
                ?.let(referencedKeys::add)
        }

        val declared = tree.putArray("catalogDependencies")
        referencedKeys.forEachIndexed { index, catalogKey ->
            declared.addObject()
                .put("registry", registry)
                .put("namespace", namespace)
                .put("catalogKey", catalogKey)
                .put("version", UNPINNED)
                .putNull("releaseVersion")
                .putNull("releaseFingerprint")
            notices += CatalogMigrationNotice(
                CatalogMigrationCodes.DEPENDENCY_CATALOG_DECLARED,
                "catalog.json.catalogDependencies[$index]",
                "catalog '$catalogKey' in namespace '$namespace' of registry '$registry' was declared as a " +
                    "dependency with the unpinned selector '$UNPINNED'; set the version range this catalog requires",
            )
        }
        return CatalogMigrationStepResult(notices = notices)
    }

    /** The registry and namespace the caller supplied, each present and well-formed, or why not. */
    private fun identityFindings(options: CatalogMigrationOptions): List<CatalogMigrationFinding> = listOfNotNull(
        when (val registry = options.registry) {
            null -> CatalogMigrationFinding(
                CatalogMigrationCodes.REGISTRY_MISSING,
                "catalog.json.catalog.registry",
                "a catalog-v7 archive names no registry; supply the registry to import it into",
            )
            else -> if (CatalogNamespaces.isValidRegistry(registry)) {
                null
            } else {
                CatalogMigrationFinding(
                    CatalogMigrationCodes.REGISTRY_INVALID,
                    "catalog.json.catalog.registry",
                    "registry '$registry' must be a DNS name written in full",
                )
            }
        },
        when (val namespace = options.namespace) {
            null -> CatalogMigrationFinding(
                CatalogMigrationCodes.NAMESPACE_MISSING,
                "catalog.json.catalog.namespace",
                "a catalog-v7 archive names no namespace; supply the namespace to import it into",
            )
            else -> if (CatalogNamespaces.isValid(namespace)) {
                null
            } else {
                CatalogMigrationFinding(
                    CatalogMigrationCodes.NAMESPACE_INVALID,
                    "catalog.json.catalog.namespace",
                    "namespace '$namespace' must be a lowercase slug",
                )
            }
        },
    )

    override fun migrateResource(
        tree: ObjectNode,
        path: String,
        context: CatalogMigrationContext,
    ): CatalogMigrationStepResult {
        tree.put("schemaVersion", toVersion)
        val resource = tree["resource"] as? ObjectNode ?: return CatalogMigrationStepResult()
        val notices = mutableListOf<CatalogMigrationNotice>()
        if (resource["type"]?.asString() == "stencil" && resource.remove("version") != null) {
            notices += CatalogMigrationNotice(
                CatalogMigrationCodes.STENCIL_VERSION_REMOVED,
                "$path.resource.version",
                "stencil version was removed; catalog v8 versions catalogs as a whole",
            )
        }
        stripStencilReferenceVersions(resource, "$path.resource", notices)
        return CatalogMigrationStepResult(notices = notices)
    }

    /** Removes `version` and `draftVersion` from every stencil node, wherever a document embeds it. */
    private fun stripStencilReferenceVersions(
        node: JsonNode,
        path: String,
        notices: MutableList<CatalogMigrationNotice>,
    ) {
        when (node) {
            is ObjectNode -> {
                val props = node["props"] as? ObjectNode
                if (node["type"]?.takeIf { it.isString }?.asString() == "stencil" && props != null) {
                    for (name in listOf("draftVersion", "version")) {
                        if (props.remove(name) != null) {
                            notices += CatalogMigrationNotice(
                                CatalogMigrationCodes.STENCIL_VERSION_REMOVED,
                                "$path.props.$name",
                                "stencil reference $name was removed; the stencil resolves through its catalog's dependency",
                            )
                        }
                    }
                }
                node.propertyNames().toList().sorted().forEach { name ->
                    stripStencilReferenceVersions(node[name], "$path.$name", notices)
                }
            }
            is ArrayNode -> node.forEachIndexed { index, child -> stripStencilReferenceVersions(child, "$path[$index]", notices) }
            else -> Unit
        }
    }

    private companion object {
        /** The selector a migrated declaration carries: any release, until the author pins one. */
        const val UNPINNED = "*"
    }
}
