// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.error

/**
 * One entry of a `release-dependencies-unreleased` problem's `unreleasedDependencies` member: a
 * draft of another catalog that the released draft's dependency declarations name, directly or
 * through such a draft's own declarations. A release pins only releases, so it must be released
 * first, or along with the draft (`releaseDependencies`).
 *
 * Hand-written rather than the generated `app.epistola.client.model.UnreleasedCatalogDependency`
 * so a malformed entry is skipped instead of failing the whole problem, as
 * [MissingReleaseDependency] does.
 */
data class UnreleasedCatalogDependency(
    /** Registry of the catalog depended on, always written in full (never `~`). */
    val registry: String,
    /** Namespace of the catalog depended on, within its registry. */
    val namespace: String,
    /** Key of the catalog depended on. */
    val catalogKey: String,
    /** The draft the declaration names. */
    val releaseId: String,
    /** The draft's name. */
    val releaseName: String,
) {
    internal companion object {
        /** One raw member entry, or `null` when it is not an object with all five string members. */
        fun fromMember(entry: Any?): UnreleasedCatalogDependency? {
            val map = entry as? Map<*, *> ?: return null
            fun text(name: String): String? = map[name] as? String
            return UnreleasedCatalogDependency(
                registry = text("registry") ?: return null,
                namespace = text("namespace") ?: return null,
                catalogKey = text("catalogKey") ?: return null,
                releaseId = text("releaseId") ?: return null,
                releaseName = text("releaseName") ?: return null,
            )
        }
    }
}
