// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.error

/**
 * One entry of a `release-dependencies-missing` problem's `missingDependencies` member: a release
 * of another catalog that the deployed release renders with (one it pins, directly or through
 * another dependency) and that cannot be had.
 *
 * Hand-written rather than the generated `app.epistola.client.model.MissingReleaseDependency`
 * because [reason] is a plain string here: the generated enum would reject a reason a later
 * contract adds, and the entry carrying it would be lost. Compare it against the constants in
 * [Reasons].
 */
data class MissingReleaseDependency(
    /** Registry of the catalog depended on, always written in full (never `~`). */
    val registry: String,
    /** Namespace of the catalog depended on, within its registry. */
    val namespace: String,
    /** Key of the catalog depended on. */
    val catalogKey: String,
    /** The release of the catalog depended on that the deployed release pins. */
    val releaseVersion: String,
    /** Why the release is missing, as the server sent it; see [Reasons]. */
    val reason: String,
) {
    /** The reasons the contract names today. Keep an `else` branch: the set may grow. */
    object Reasons {
        /**
         * The tenant does not have the release, and it could not be fetched from its registry or
         * any configured mirror, or the copy did not match its recorded fingerprint.
         */
        const val UNAVAILABLE: String = "unavailable"
    }

    internal companion object {
        /** One raw member entry, or `null` when it is not an object with all five string members. */
        fun fromMember(entry: Any?): MissingReleaseDependency? {
            val map = entry as? Map<*, *> ?: return null
            fun text(name: String): String? = map[name] as? String
            return MissingReleaseDependency(
                registry = text("registry") ?: return null,
                namespace = text("namespace") ?: return null,
                catalogKey = text("catalogKey") ?: return null,
                releaseVersion = text("releaseVersion") ?: return null,
                reason = text("reason") ?: return null,
            )
        }
    }
}
