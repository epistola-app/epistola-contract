// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.migration

import app.epistola.catalog.protocol.CatalogKeys
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * The v7-to-v8 rewrite of keys outside the wire-v8 bounds ([CatalogKeys.migrate]): a key shorter than
 * 3 characters is padded on the right with `0`, a key longer than 50 is cut.
 *
 * The rewrite is a pure function of the key, so it is applied to a key and to every reference to it
 * alike, without resolving what a reference points at. A reference into another catalog follows the
 * same rule, because that catalog's own migration rewrites its key the same way.
 *
 * Each key this catalog defines -- a resource's, a variant's, a data example's -- gets one notice
 * ([CatalogMigrationCodes.RESOURCE_KEY_PADDED], [CatalogMigrationCodes.RESOURCE_KEY_TRUNCATED]). A
 * rewritten reference gets none: it says nothing the notice for its target does not. When a rewritten
 * key would equal another key of the same scope, the migration does not choose a third: it reports
 * [CatalogMigrationCodes.RESOURCE_KEY_PADDING_COLLISION] or
 * [CatalogMigrationCodes.RESOURCE_KEY_TRUNCATION_COLLISION] and the archive is refused.
 */
internal object CatalogKeyRewrites {
    /** Rewrites the string member [name] of [owner] when it needs it; a reference, so without notice. */
    fun rewriteReference(
        owner: ObjectNode,
        name: String,
    ) {
        val value = owner[name]?.takeIf { it.isString }?.asString() ?: return
        val rewritten = CatalogKeys.migrate(value)
        if (rewritten != value) owner.put(name, rewritten)
    }

    /** Rewrites every string element of [array]; references, so without notice. */
    fun rewriteReferences(array: JsonNode?) {
        if (array !is ArrayNode) return
        for (index in 0 until array.size()) {
            val value = array[index]?.takeIf { it.isString }?.asString() ?: continue
            val rewritten = CatalogKeys.migrate(value)
            if (rewritten != value) array.set(index, array.stringNode(rewritten))
        }
    }

    /**
     * Rewrites the property names of [map] (a variant's attributes, keyed by attribute key), keeping
     * their order. A rewritten name that equals another name of [map] is a collision at [path].
     */
    fun rewritePropertyNames(
        map: JsonNode?,
        path: String,
        findings: MutableList<CatalogMigrationFinding>,
    ) {
        if (map !is ObjectNode) return
        val names = map.propertyNames().toList()
        if (names.none { CatalogKeys.migrate(it) != it }) return
        val scope = Scope("the attributes at $path", "attribute key").declare(names)
        val rewritten = names.map { name -> scope.claim(name, "$path.$name", findings) to map[name] }
        if (findings.isNotEmpty()) return
        map.removeAll()
        rewritten.forEach { (name, value) -> map.set(name, value) }
    }

    /**
     * The keys one scope defines -- a catalog's resources of one type, a template's variants or data
     * examples -- so that a rewritten key can be checked against every other key of the scope, the
     * source keys and the rewritten ones alike.
     */
    class Scope(
        private val description: String,
        private val what: String,
    ) {
        private val sourceKeys = mutableSetOf<String>()
        private val claimed = mutableMapOf<String, String>()

        /** Registers every source key of the scope before any is rewritten. */
        fun declare(keys: Iterable<String>) = apply { sourceKeys += keys }

        /**
         * The key [key] becomes. A rewritten key that is a source key of the scope, or that another
         * key of the scope was already rewritten to, is a collision: it is reported at [path] and
         * [key] is returned unchanged.
         */
        fun claim(
            key: String,
            path: String,
            findings: MutableList<CatalogMigrationFinding>,
            notices: MutableList<CatalogMigrationNotice>? = null,
        ): String {
            val rewrite = CatalogKeys.rewrite(key) ?: return key.also { claimed.putIfAbsent(it, it) }
            val rewritten = CatalogKeys.migrate(key)
            val other = claimed[rewritten] ?: rewritten.takeIf { it in sourceKeys }
            if (other != null) {
                findings += CatalogMigrationFinding(
                    when (rewrite) {
                        CatalogKeys.Rewrite.PADDED -> CatalogMigrationCodes.RESOURCE_KEY_PADDING_COLLISION
                        CatalogKeys.Rewrite.TRUNCATED -> CatalogMigrationCodes.RESOURCE_KEY_TRUNCATION_COLLISION
                    },
                    path,
                    "$what '$key' would become '$rewritten', which collides with '$other' in $description; " +
                        "rename one of them and export again",
                )
                return key
            }
            claimed[rewritten] = key
            notices?.add(
                CatalogMigrationNotice(
                    when (rewrite) {
                        CatalogKeys.Rewrite.PADDED -> CatalogMigrationCodes.RESOURCE_KEY_PADDED
                        CatalogKeys.Rewrite.TRUNCATED -> CatalogMigrationCodes.RESOURCE_KEY_TRUNCATED
                    },
                    path,
                    "$what '$key' became '$rewritten'",
                ),
            )
            return rewritten
        }
    }
}
