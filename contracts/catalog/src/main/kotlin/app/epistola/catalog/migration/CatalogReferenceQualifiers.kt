// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.migration

import app.epistola.catalog.protocol.CatalogDependency
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * The registry and namespace a reference was qualified with before wire v8 named another catalog by
 * alias (contract ADR 0003): a reference names a catalog by the alias its dependency list declares, and the
 * registry and namespace are the declaration's.
 *
 * | Where | The catalog named by | Qualifiers no longer in wire v8 |
 * | --- | --- | --- |
 * | `catalog.json` resource dependencies, the catalog's `defaultTheme` | `catalogKey` | `registry`, `namespace` |
 * | a template | `themeCatalogKey` | `themeRegistry`, `themeNamespace` |
 * | a template document's theme reference, a stencil node's props | `catalogKey` | `registry`, `namespace` |
 * | a `fontFamily` font reference in theme, preset, document or node styles | `catalogKey` | `registry`, `namespace` |
 * | an attribute's code-list binding | `catalogKey` | `registry`, `namespace` |
 *
 * The positions are listed once here and used two ways:
 * - the migrator's wire check refuses a qualifier left in a v8 document (an earlier draft of wire v8
 *   wrote them), rather than letting the model, which ignores them when binding so that stored
 *   documents still load, silently read the reference as another catalog;
 * - the v7-to-v8 migration maps a qualified reference to an alias ([toAliases]). Released wire
 *   versions never wrote one, but the template document schema shared with wire v5 to v7 allowed a
 *   theme reference's `registry` and `namespace`.
 */
internal object CatalogReferenceQualifiers {
    /** One reference position: [owner] names a catalog in [catalogMember], qualified by [registryMember] and [namespaceMember]. */
    class Site(
        val owner: ObjectNode,
        val catalogMember: String,
        val registryMember: String,
        val namespaceMember: String,
        val path: String,
        val what: String,
    ) {
        /** The qualifiers [owner] still carries. */
        val qualifiers: List<String> get() = listOf(registryMember, namespaceMember).filter(owner::has)
    }

    /** The reference positions in a `catalog.json` tree. */
    fun manifestSites(tree: ObjectNode): List<Site> {
        val sites = mutableListOf<Site>()
        (tree["dependencies"] as? ArrayNode)?.forEachIndexed { index, entry ->
            site(entry, "catalogKey", "registry", "namespace", "catalog.json.dependencies[$index]", "a resource dependency", sites)
        }
        site(tree["catalog"]?.get("defaultTheme"), "catalogKey", "registry", "namespace", "catalog.json.catalog.defaultTheme", "a default theme", sites)
        return sites
    }

    /** The reference positions in a resource payload at [path] (`resources/template/letter.json.resource`). */
    fun resourceSites(
        resource: ObjectNode,
        path: String,
    ): List<Site> {
        val sites = mutableListOf<Site>()
        when (resource["type"]?.takeIf { it.isString }?.asString()) {
            "template" -> {
                site(resource, "themeCatalogKey", "themeRegistry", "themeNamespace", path, "a template's theme", sites)
                document(resource["templateModel"], "$path.templateModel", sites)
                (resource["variants"] as? ArrayNode)?.forEachIndexed { index, variant ->
                    document(variant?.get("templateModel"), "$path.variants[$index].templateModel", sites)
                }
            }
            "stencil" -> document(resource["content"], "$path.content", sites)
            "theme" -> {
                styles(resource["documentStyles"], "$path.documentStyles", sites)
                (resource["blockStylePresets"] as? ObjectNode)?.let { presets ->
                    presets.propertyNames().toList().sorted().forEach { name ->
                        styles(presets[name]?.get("styles"), "$path.blockStylePresets.$name.styles", sites)
                    }
                }
            }
            "attribute" -> site(resource["codeListBinding"], "catalogKey", "registry", "namespace", "$path.codeListBinding", "a code-list binding", sites)
        }
        return sites
    }

    /**
     * Removes the catalog's own key, [ownKey], wherever [resource] names it as the catalog a
     * reference is to, in place, with a notice each: in wire v8 a reference to the catalog itself
     * names no catalog, and the catalog's own key is never an alias. Covers every reference site
     * ([resourceSites]), an image node's, and a variant's attribute key (`letters.locale` becomes
     * `locale`). Run after [toAliases], which has already removed any registry and namespace.
     *
     * Content that names its own catalog by key would otherwise make the release depend on its
     * name, which the V6 fingerprint deliberately leaves out: the same release mirrored or renamed
     * would name a catalog it does not declare.
     */
    fun dropOwnKey(
        resource: ObjectNode,
        path: String,
        ownKey: String,
        notices: MutableList<CatalogMigrationNotice>,
    ) {
        fun drop(owner: JsonNode?, member: String, sitePath: String, what: String) {
            if (owner !is ObjectNode || owner[member]?.takeIf { it.isString }?.asString() != ownKey) return
            owner.remove(member)
            notices += CatalogMigrationNotice(
                CatalogMigrationCodes.REFERENCE_ALIASED,
                "$sitePath.$member",
                "$what named the catalog itself by its own key '$ownKey'; it now names no catalog",
            )
        }
        resourceSites(resource, path).forEach { drop(it.owner, it.catalogMember, it.path, it.what) }
        if (resource["type"]?.takeIf { it.isString }?.asString() == "template") {
            imageSites(resource["templateModel"], "$path.templateModel").forEach { (props, sitePath) -> drop(props, "catalogKey", sitePath, "an image reference") }
            (resource["variants"] as? ArrayNode)?.forEachIndexed { index, variant ->
                if (variant !is ObjectNode) return@forEachIndexed
                imageSites(variant["templateModel"], "$path.variants[$index].templateModel").forEach { (props, sitePath) ->
                    drop(props, "catalogKey", sitePath, "an image reference")
                }
                val attributes = variant["attributes"] as? ObjectNode ?: return@forEachIndexed
                val prefix = ownKey + "."
                val members = attributes.properties().map { (name, value) -> name to value }
                if (members.none { (name, _) -> name.startsWith(prefix) }) return@forEachIndexed
                attributes.removeAll()
                members.forEach { (name, value) ->
                    val local = name.removePrefix(prefix)
                    if (local != name && !members.any { (other, _) -> other == local }) {
                        attributes.set(local, value)
                        notices += CatalogMigrationNotice(
                            CatalogMigrationCodes.REFERENCE_ALIASED,
                            "$path.variants[$index].attributes.$name",
                            "a variant's attribute '$name' named the catalog itself by its own key '$ownKey'; it is now '$local'",
                        )
                    } else {
                        attributes.set(name, value)
                    }
                }
            }
        }
        if (resource["type"]?.takeIf { it.isString }?.asString() == "stencil") {
            imageSites(resource["content"], "$path.content").forEach { (props, sitePath) -> drop(props, "catalogKey", sitePath, "an image reference") }
        }
    }

    /** The props of each image node in [document], with their paths. */
    private fun imageSites(
        document: JsonNode?,
        path: String,
    ): List<Pair<ObjectNode, String>> {
        val nodes = (document as? ObjectNode)?.get("nodes") as? ObjectNode ?: return emptyList()
        return nodes.propertyNames().toList().sorted().mapNotNull { id ->
            val node = nodes[id] as? ObjectNode ?: return@mapNotNull null
            if (node["type"]?.takeIf { it.isString }?.asString() != "image") return@mapNotNull null
            (node["props"] as? ObjectNode)?.let { it to "$path.nodes.$id.props" }
        }
    }

    /** A finding for every qualifier still present at [sites]: wire v8 has none. */
    fun findings(sites: List<Site>): List<CatalogMigrationFinding> = sites.flatMap { site ->
        site.qualifiers.map { member ->
            CatalogMigrationFinding(
                CatalogMigrationCodes.SCHEMA_UNKNOWN,
                "${site.path}.$member",
                "'$member' is not a catalog-v8 member of ${site.what}: a reference names another catalog by the alias " +
                    "catalogDependencies declares, and the registry and namespace are that declaration's",
            )
        }
    }

    /**
     * Maps each qualified reference at [sites] to an alias, in place, for a catalog named
     * [ownKey] in [ownNamespace] of [ownRegistry] that declares [declared]. An omitted qualifier
     * means the catalog's own, as it did. A reference to the catalog itself loses its qualifiers; one
     * to a declared catalog names that declaration's alias; one to a catalog [declared] does not
     * name cannot be mapped, and is a finding ([CatalogMigrationCodes.REFERENCE_ALIAS_UNRESOLVED]):
     * no other catalog sits in another namespace without being declared.
     */
    fun toAliases(
        sites: List<Site>,
        ownKey: String?,
        ownNamespace: String?,
        ownRegistry: String?,
        declared: List<CatalogDependency>,
        findings: MutableList<CatalogMigrationFinding>,
        notices: MutableList<CatalogMigrationNotice>,
    ) {
        sites.filter { it.qualifiers.isNotEmpty() }.forEach { site ->
            val owner = site.owner
            val registry = owner[site.registryMember]?.takeIf { it.isString }?.asString() ?: ownRegistry
            val namespace = owner[site.namespaceMember]?.takeIf { it.isString }?.asString() ?: ownNamespace
            val catalogKey = owner[site.catalogMember]?.takeIf { it.isString }?.asString() ?: ownKey
            val sameNamespace = registry == ownRegistry && namespace == ownNamespace
            val alias = when {
                sameNamespace && catalogKey == ownKey -> null
                else -> declared.firstOrNull { it.registry == registry && it.namespace == namespace && it.catalogKey == catalogKey }?.alias
                    ?: catalogKey.takeIf { sameNamespace }
            }
            if (alias == null && !(sameNamespace && catalogKey == ownKey)) {
                findings += CatalogMigrationFinding(
                    CatalogMigrationCodes.REFERENCE_ALIAS_UNRESOLVED,
                    site.path,
                    "${site.what} names catalog '$catalogKey' in namespace '$namespace' of registry '$registry', which the " +
                        "catalog does not declare as a dependency, so it cannot be named by an alias",
                )
                return@forEach
            }
            site.qualifiers.forEach(owner::remove)
            if (alias != null && owner[site.catalogMember]?.takeIf { it.isString }?.asString() != alias) owner.put(site.catalogMember, alias)
            notices += CatalogMigrationNotice(
                CatalogMigrationCodes.REFERENCE_ALIASED,
                site.path,
                if (alias == null) {
                    "${site.what} named the catalog itself by registry and namespace; it now names no catalog"
                } else {
                    "${site.what} named catalog '$catalogKey' by registry and namespace; it now names the alias '$alias'"
                },
            )
        }
    }

    private fun document(
        document: JsonNode?,
        path: String,
        sites: MutableList<Site>,
    ) {
        if (document !is ObjectNode) return
        site(document["themeRef"], "catalogKey", "registry", "namespace", "$path.themeRef", "a theme reference", sites)
        styles(document["documentStylesOverride"], "$path.documentStylesOverride", sites)
        val nodes = document["nodes"] as? ObjectNode ?: return
        nodes.propertyNames().toList().sorted().forEach { id ->
            val node = nodes[id] as? ObjectNode ?: return@forEach
            styles(node["styles"], "$path.nodes.$id.styles", sites)
            if (node["type"]?.takeIf { it.isString }?.asString() == "stencil") {
                site(node["props"], "catalogKey", "registry", "namespace", "$path.nodes.$id.props", "a stencil reference", sites)
            }
        }
    }

    private fun styles(
        styles: JsonNode?,
        path: String,
        sites: MutableList<Site>,
    ) {
        site((styles as? ObjectNode)?.get("fontFamily"), "catalogKey", "registry", "namespace", "$path.fontFamily", "a font reference", sites)
    }

    private fun site(
        owner: JsonNode?,
        catalogMember: String,
        registryMember: String,
        namespaceMember: String,
        path: String,
        what: String,
        sites: MutableList<Site>,
    ) {
        if (owner is ObjectNode) sites += Site(owner, catalogMember, registryMember, namespaceMember, path, what)
    }
}
