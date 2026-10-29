// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.migration

import app.epistola.catalog.protocol.CatalogAliases
import app.epistola.catalog.protocol.CatalogDependency
import app.epistola.catalog.protocol.CatalogKeys
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.CatalogNamespaces
import app.epistola.catalog.protocol.CatalogReferences
import app.epistola.catalog.protocol.CatalogReleaseVersions
import app.epistola.catalog.protocol.ResourceDetail
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
 * - **References by alias** (contract ADR 0003). A v7 catalog's content lived in one tenant, so every catalog
 *   it referenced sat next to it, in the catalog's own registry and namespace, and was named by its
 *   key. Each such catalog is declared in `catalogDependencies` with that registry and namespace,
 *   under its key as the alias, so that every reference -- a resource dependency, a reference inside
 *   content -- keeps its value and now names the alias. A reference qualified with a registry or
 *   namespace (which the template document schema shared with v5 to v7 allowed in a theme
 *   reference) is mapped to the alias of the catalog it names ([CatalogReferenceQualifiers.toAliases]);
 *   one naming a catalog in another namespace, which no v7 catalog declares, cannot be, and is a
 *   finding.
 * - **Catalog dependencies** are declared, one per other catalog the resource dependencies name (and,
 *   when the archive is read whole, the content: [declareContentDependencies]), pinning no release
 *   (`releaseVersion` and `releaseFingerprint` null): v7 never said which version was meant, and the
 *   author pins one rather than the migrator guessing one.
 * - **Stencil versions** are removed, both a stencil's own `version` and a stencil reference's
 *   `version` and `draftVersion`. A stencil reference resolves through its catalog's dependency.
 * - **Stencil scope.** A v7 stencil saw the template's data, and every fill saw the instance's
 *   parameters; v8 isolates a stencil unless it says otherwise. So a stencil gets
 *   `readsTemplateData`, and each of its placeholders `passParameters`
 *   ([CatalogMigrationCodes.STENCIL_SCOPE_KEPT]).
 * - **Keys.** A resource's public address is called its `key`, and so are references to one:
 *   `slug` becomes `key` (a font reference's too), `themeId` `themeKey`, a stencil node's
 *   `stencilId` `stencilKey`, an image node's `assetId` `imageKey`, the presentation's
 *   `iconAssetSlug` and `imageAssetSlugs` `iconImageKey` and `imageKeys`, and an attribute
 *   assignment's `catalog` `catalogKey` ([CatalogKeyRenames] lists every position).
 *
 * - **Data example keys.** A data example gains a `key`, its address within its template, derived
 *   from its name ([CatalogKeys.deriveDataExampleKey]) and unique within the template.
 * - **Key bounds.** Wire v8 bounds every resource key to 3 to 50 characters ([CatalogKeys]). A key
 *   outside them that otherwise conforms is rewritten by [CatalogKeys.migrate] -- padded on the right
 *   with `0` when shorter, cut when longer -- and so is every reference to it, into this catalog or
 *   another ([CatalogKeyRewrites]). A rewrite that collides with another key of its scope is a
 *   finding; the migration never chooses another key.
 *
 * - **Release number.** Wire v8 numbers a release `MAJOR.MINOR.PATCH`, or a patch release with a
 *   namespace label ([CatalogReleaseVersions]); earlier versions accepted full SemVer, where a
 *   pre-release meant a pre-release. A `release.version` with any pre-release or build part
 *   (`1.0.0-beta.1`, `1.0.0+5`) is a finding ([CatalogMigrationCodes.RELEASE_VERSION_INVALID]),
 *   never stripped (`1.0.0` would name another release) and never read as a patch release
 *   ([CatalogReleaseVersions.isCore]). A v7 archive has no catalog dependencies,
 *   so the release is the only number it carries.
 *
 * Each repair is reported with a [CatalogMigrationNotice], except the key renames and the rewritten
 * references to a padded or cut key: like catalog
 * v7's rename of a variant's `id` to `slug`, the value is unchanged, so there is nothing for an
 * author to act on. A stencil reference's `isDraft`, which catalog v5 already rejected, is left in
 * place and remains a validation finding.
 */
internal class CatalogV7ToV8Migration : CatalogSchemaMigration {
    override val fromVersion: Int = 7
    override val toVersion: Int = 8

    companion object {
        /**
         * Declares every other catalog the migrated content of a catalog read from before wire v8
         * names by alias ([CatalogReferences]) that [manifest] does not declare yet: a v7 manifest
         * listed the resources its content used in other catalogs (`dependencies`), but nothing
         * required it to list all of them. Each is declared like those the manifest names, in the
         * catalog's own registry and namespace under its key as the alias, pinning no release, with
         * a [CatalogMigrationCodes.DEPENDENCY_CATALOG_DECLARED] notice. A key that cannot be an alias
         * is not declared: it is a [CatalogMigrationCodes.DEPENDENCY_ALIAS_INVALID] finding at the
         * first reference that names it. Returns [manifest] itself when nothing is added.
         *
         * The archive reader calls it once every resource is migrated, since a manifest migrated on
         * its own cannot see the content.
         */
        fun declareContentDependencies(
            manifest: CatalogManifest,
            details: Map<String, ResourceDetail>,
            findings: MutableList<CatalogMigrationFinding>,
            notices: MutableList<CatalogMigrationNotice>,
        ): CatalogManifest {
            val own = manifest.catalog
            val registry = own.registry ?: return manifest
            val namespace = own.namespace ?: return manifest
            val declared = manifest.catalogDependencies.orEmpty()
            val aliases = declared.mapTo(mutableSetOf()) { it.alias }
            val named = sortedMapOf<String, String>()
            details.toSortedMap().forEach { (key, detail) ->
                CatalogReferences.inResource(detail.resource, "resources/$key.json.resource")
                    .filter { it.alias != own.key && it.alias !in aliases }
                    .forEach { named.putIfAbsent(it.alias, it.path) }
            }
            if (named.isEmpty()) return manifest
            val added = mutableListOf<CatalogDependency>()
            named.forEach { (alias, path) ->
                if (!CatalogAliases.isValid(alias)) {
                    findings += CatalogMigrationFinding(
                        CatalogMigrationCodes.DEPENDENCY_ALIAS_INVALID,
                        path,
                        "the content names catalog '$alias', which cannot be declared as a dependency: its key is not a valid alias " +
                            "(${CatalogAliases.MIN_LENGTH} to ${CatalogAliases.MAX_LENGTH} characters matching ${CatalogAliases.PATTERN})",
                    )
                    return@forEach
                }
                notices += CatalogMigrationNotice(
                    CatalogMigrationCodes.DEPENDENCY_CATALOG_DECLARED,
                    "catalog.json.catalogDependencies[${declared.size + added.size}]",
                    "catalog '$alias' in namespace '$namespace' of registry '$registry', which the content names at $path, was " +
                        "declared under the alias '$alias' as a dependency that pins no release; pin the release this catalog renders with",
                )
                added += CatalogDependency(registry, namespace, alias, alias = alias)
            }
            return if (added.isEmpty()) manifest else manifest.copy(catalogDependencies = declared + added)
        }
    }

    override fun migrateManifest(
        tree: ObjectNode,
        options: CatalogMigrationOptions,
    ): CatalogMigrationStepResult {
        tree.put("schemaVersion", toVersion)
        val identityFindings = identityFindings(options) + releaseVersionFindings(tree)
        if (identityFindings.isNotEmpty()) return CatalogMigrationStepResult(identityFindings)
        val registry = checkNotNull(options.registry)
        val namespace = checkNotNull(options.namespace)
        CatalogKeyRenames.toCurrent(CatalogKeyRenames.manifestSites(tree))
        val catalog = tree["catalog"] as? ObjectNode ?: return CatalogMigrationStepResult()
        val ownKey = catalog["key"]?.takeIf { it.isString }?.asString()
        val notices = mutableListOf<CatalogMigrationNotice>()

        val keyFindings = mutableListOf<CatalogMigrationFinding>()
        rewriteManifestKeys(tree, keyFindings, notices)
        if (keyFindings.isNotEmpty()) return CatalogMigrationStepResult(keyFindings, notices)

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

        // A resource dependency names its catalog by key, which becomes the alias the catalog is
        // declared under: the value stays, and now means the alias.
        val referencedKeys = sortedSetOf<String>()
        (tree["dependencies"] as? ArrayNode)?.forEach { dependency ->
            (dependency as? ObjectNode)?.get("catalogKey")?.takeIf { it.isString }?.asString()
                ?.takeIf { it != ownKey }
                ?.let(referencedKeys::add)
        }

        val declared = tree.putArray("catalogDependencies")
        referencedKeys.forEachIndexed { index, catalogKey ->
            declared.add(declaration(declared, registry, namespace, catalogKey))
            notices += declaredNotice(index, catalogKey, namespace, registry, "a resource dependency")
        }
        return CatalogMigrationStepResult(notices = notices)
    }

    /**
     * Rewrites the keys of the manifest's resource entries, one notice each, checked for collisions
     * per type; and the references the manifest makes: resource dependencies, attribute assignments,
     * the presentation's icon and gallery images.
     */
    private fun rewriteManifestKeys(
        tree: ObjectNode,
        findings: MutableList<CatalogMigrationFinding>,
        notices: MutableList<CatalogMigrationNotice>,
    ) {
        val entries = (tree["resources"] as? ArrayNode)?.mapIndexedNotNull { index, entry -> (entry as? ObjectNode)?.let { index to it } }.orEmpty()
        val scopes = entries
            .groupBy({ (_, entry) -> entry["type"]?.takeIf { it.isString }?.asString() }, { (_, entry) -> entry["key"]?.takeIf { it.isString }?.asString() })
            .mapValues { (type, keys) -> CatalogKeyRewrites.Scope("the catalog's ${type ?: "untyped"} resources", "${type ?: "resource"} key").declare(keys.filterNotNull()) }
        entries.forEach { (index, entry) ->
            val key = entry["key"]?.takeIf { it.isString }?.asString() ?: return@forEach
            val scope = scopes.getValue(entry["type"]?.takeIf { it.isString }?.asString())
            val rewritten = scope.claim(key, "catalog.json.resources[$index].key", findings, notices)
            if (rewritten != key) entry.put("key", rewritten)
        }
        (tree["dependencies"] as? ArrayNode)?.forEach { if (it is ObjectNode) CatalogKeyRewrites.rewriteReference(it, "key") }
        val catalog = tree["catalog"] as? ObjectNode ?: return
        (catalog["attributes"] as? ArrayNode)?.forEach { if (it is ObjectNode) CatalogKeyRewrites.rewriteReference(it, "key") }
        (catalog["presentation"] as? ObjectNode)?.let { presentation ->
            CatalogKeyRewrites.rewriteReference(presentation, "iconImageKey")
            CatalogKeyRewrites.rewriteReferences(presentation["imageKeys"])
        }
    }

    /**
     * The release number, when it is a string that wire v8 does not accept: a pre-release or build
     * part, which earlier wire versions allowed. Anything else wrong with it is left to the wire
     * check and the binding, as for a catalog-v8 archive.
     */
    private fun releaseVersionFindings(tree: ObjectNode): List<CatalogMigrationFinding> {
        val version = tree["release"]?.get("version")?.takeIf { it.isString }?.asString() ?: return emptyList()
        // A v7 pre-release was a pre-release, not a patch release by another namespace's holder.
        if (CatalogReleaseVersions.isCore(version)) return emptyList()
        return listOf(
            CatalogMigrationFinding(
                CatalogMigrationCodes.RELEASE_VERSION_INVALID,
                "catalog.json.release.version",
                "release version '$version' must be MAJOR.MINOR.PATCH from catalog v8; a pre-release or " +
                    "build part is not stripped, because the number would then name another release",
            ),
        )
    }

    /** A catalog dependency on [catalogKey] in [registry] and [namespace], under its key as the alias, pinning no release. */
    private fun declaration(
        array: ArrayNode,
        registry: String,
        namespace: String,
        catalogKey: String,
    ): ObjectNode = array.objectNode()
        .put("alias", catalogKey)
        .put("registry", registry)
        .put("namespace", namespace)
        .put("catalogKey", catalogKey)
        .putNull("releaseVersion")
        .putNull("releaseFingerprint")

    private fun declaredNotice(
        index: Int,
        catalogKey: String,
        namespace: String?,
        registry: String?,
        namedBy: String,
    ) = CatalogMigrationNotice(
        CatalogMigrationCodes.DEPENDENCY_CATALOG_DECLARED,
        "catalog.json.catalogDependencies[$index]",
        "catalog '$catalogKey' in namespace '$namespace' of registry '$registry', which $namedBy names, was declared " +
            "under the alias '$catalogKey' as a dependency that pins no release; pin the release this catalog renders with",
    )

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
        val sites = CatalogKeyRenames.resourceSites(resource, "$path.resource")
        CatalogKeyRenames.toCurrent(sites)
        val notices = mutableListOf<CatalogMigrationNotice>()
        val keyFindings = mutableListOf<CatalogMigrationFinding>()
        rewriteResourceKeys(resource, sites, "$path.resource", keyFindings, notices)
        if (keyFindings.isNotEmpty()) return CatalogMigrationStepResult(keyFindings, notices)
        val catalog = context.manifest.catalog
        CatalogReferenceQualifiers.toAliases(
            CatalogReferenceQualifiers.resourceSites(resource, "$path.resource"),
            catalog.key,
            catalog.namespace,
            catalog.registry,
            context.manifest.catalogDependencies.orEmpty(),
            keyFindings,
            notices,
        )
        if (keyFindings.isNotEmpty()) return CatalogMigrationStepResult(keyFindings, notices)
        CatalogReferenceQualifiers.dropOwnKey(resource, "$path.resource", catalog.key, notices)
        if (resource["type"]?.asString() == "stencil" && resource.remove("version") != null) {
            notices += CatalogMigrationNotice(
                CatalogMigrationCodes.STENCIL_VERSION_REMOVED,
                "$path.resource.version",
                "stencil version was removed; catalog v8 versions catalogs as a whole",
            )
        }
        StencilReferenceVersions.strip(resource, "$path.resource", notices)
        if (resource["type"]?.takeIf { it.isString }?.asString() == "template") assignExampleKeys(resource, "$path.resource", notices)
        if (resource["type"]?.takeIf { it.isString }?.asString() == "stencil") keepStencilScope(resource, "$path.resource", notices)
        return CatalogMigrationStepResult(notices = notices)
    }

    /**
     * Gives a v7 stencil the scope v7 rendered it with: one scope per instance, holding the
     * template's data and the instance's parameters, seen by the stencil's content and by every fill
     * alike. Catalog v8 isolates a stencil unless it reads the template's data, and shows a fill the
     * parameters only through a placeholder that passes them (ADR 0006), so the stencil reads the
     * template's data and every placeholder passes the parameters. Which fills read them is not in the
     * stencil's archive, since the templates filling its placeholders are often in another one.
     */
    private fun keepStencilScope(resource: ObjectNode, path: String, notices: MutableList<CatalogMigrationNotice>) {
        (resource["content"]?.get("nodes") as? ObjectNode)?.let { nodes ->
            nodes.propertyNames().forEach { id ->
                val node = nodes[id] as? ObjectNode ?: return@forEach
                if (node["type"]?.takeIf { it.isString }?.asString() != "placeholder") return@forEach
                val props = node["props"] as? ObjectNode ?: node.putObject("props")
                props.put("passParameters", true)
            }
        }
        resource.put("readsTemplateData", true)
        notices += CatalogMigrationNotice(
            CatalogMigrationCodes.STENCIL_SCOPE_KEPT,
            "$path.readsTemplateData",
            "the stencil reads the template's data and passes its parameters to every placeholder, as catalog v7 rendered it",
        )
    }

    /**
     * Rewrites every key a resource names ([CatalogKeys.migrate]): its own (reported with the
     * manifest entry), the references at the renamed positions ([CatalogKeyRenames]: a theme, a
     * stencil or image node, a font, a code list), a template's variant keys and data example keys
     * (one notice each, checked for collisions within the template), and a variant's attribute keys.
     */
    private fun rewriteResourceKeys(
        resource: ObjectNode,
        sites: List<CatalogKeyRenames.Site>,
        path: String,
        findings: MutableList<CatalogMigrationFinding>,
        notices: MutableList<CatalogMigrationNotice>,
    ) {
        val variants = (resource["variants"] as? ArrayNode)?.takeIf { resource["type"]?.asString() == "template" }
        val variantOwners = variants?.filterIsInstance<ObjectNode>().orEmpty()
        sites
            .filter { site -> variantOwners.none { it === site.owner } }
            .forEach { site -> CatalogKeyRewrites.rewriteReference(site.owner, site.newName) }
        if (variants != null) {
            val scope = CatalogKeyRewrites.Scope("the template's variants", "variant key")
                .declare(variantOwners.mapNotNull { it["key"]?.takeIf { key -> key.isString }?.asString() })
            variants.forEachIndexed { index, node ->
                val variant = node as? ObjectNode ?: return@forEachIndexed
                variant["key"]?.takeIf { it.isString }?.asString()?.let { key ->
                    val rewritten = scope.claim(key, "$path.variants[$index].key", findings, notices)
                    if (rewritten != key) variant.put("key", rewritten)
                }
                CatalogKeyRewrites.rewritePropertyNames(variant["attributes"], "$path.variants[$index].attributes", findings)
            }
        }
        val examples = (resource["dataExamples"] as? ArrayNode)?.takeIf { resource["type"]?.asString() == "template" } ?: return
        val exampleObjects = examples.filterIsInstance<ObjectNode>()
        val scope = CatalogKeyRewrites.Scope("the template's data examples", "data example key")
            .declare(exampleObjects.mapNotNull { it["key"]?.takeIf { key -> key.isString }?.asString() })
        examples.forEachIndexed { index, node ->
            val example = node as? ObjectNode ?: return@forEachIndexed
            val key = example["key"]?.takeIf { it.isString }?.asString() ?: return@forEachIndexed
            val rewritten = scope.claim(key, "$path.dataExamples[$index].key", findings, notices)
            if (rewritten != key) example.put("key", rewritten)
        }
    }

    /**
     * Gives every data example without a `key` one derived from its name
     * ([CatalogKeys.deriveDataExampleKey]), in document order, unique within the template. An
     * example whose name is not a string is left alone: catalog v7 already required one, so that is
     * a finding for the validator, not something to repair.
     */
    private fun assignExampleKeys(
        resource: ObjectNode,
        path: String,
        notices: MutableList<CatalogMigrationNotice>,
    ) {
        val examples = resource["dataExamples"] as? ArrayNode ?: return
        val taken = examples.mapNotNullTo(mutableSetOf()) { (it as? ObjectNode)?.get("key")?.takeIf { key -> key.isString }?.asString() }
        examples.forEachIndexed { index, node ->
            val example = node as? ObjectNode ?: return@forEachIndexed
            if (example.has("key")) return@forEachIndexed
            val name = example["name"]?.takeIf { it.isString }?.asString() ?: return@forEachIndexed
            val key = CatalogKeys.deriveDataExampleKey(name, taken)
            taken += key
            val members = example.properties().map { (member, value) -> member to value }
            example.removeAll()
            example.put("key", key)
            members.forEach { (member, value) -> example.set(member, value) }
            notices += CatalogMigrationNotice(
                CatalogMigrationCodes.DATA_EXAMPLE_KEY_ASSIGNED,
                "$path.dataExamples[$index].key",
                "data example '$name' was given the key '$key'",
            )
        }
    }
}
