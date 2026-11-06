// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.error

/**
 * One entry of a `catalog-draft-in-use` problem's `dependents` member: a draft of another catalog
 * whose dependency declarations name the draft that could not be discarded. Change its
 * declarations, or discard it, first.
 *
 * Hand-written rather than the generated `app.epistola.client.model.CatalogDraftRefDto` so a
 * malformed entry is skipped instead of failing the whole problem, as
 * [UnreleasedCatalogDependency] does.
 */
data class CatalogDraftRef(
    /** Registry of the draft's catalog, always written in full (never `~`). */
    val registry: String,
    /** Namespace of the draft's catalog, within its registry. */
    val namespace: String,
    /** Key of the draft's catalog. */
    val catalogKey: String,
    /** The draft. */
    val releaseId: String,
    /** The draft's name, as it is now. */
    val releaseName: String,
) {
    internal companion object {
        /** One raw member entry, or `null` when it is not an object with all five string members. */
        fun fromMember(entry: Any?): CatalogDraftRef? {
            val map = entry as? Map<*, *> ?: return null
            fun text(name: String): String? = map[name] as? String
            return CatalogDraftRef(
                registry = text("registry") ?: return null,
                namespace = text("namespace") ?: return null,
                catalogKey = text("catalogKey") ?: return null,
                releaseId = text("releaseId") ?: return null,
                releaseName = text("releaseName") ?: return null,
            )
        }
    }
}
