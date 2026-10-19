// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.protocol

/**
 * Where a catalog archive carries a release it pins (wire v8).
 *
 * A catalog archive may contain, for a pin in `catalogDependencies` (and, transitively, for a pin of
 * a release it carries), an ordinary catalog archive of that pinned release at
 *
 * ```
 * dependencies/{registry}/{namespace}/{catalogKey}/{releaseVersion}.zip
 * ```
 *
 * The manifest has no member for it: the pin says where to look, so a release's manifest and
 * fingerprint are the same whether it is exported thin or with its dependencies. Each segment
 * follows its own rule ([CatalogNamespaces.REGISTRY_PATTERN], [CatalogNamespaces.PATTERN],
 * [CatalogKeys.CATALOG_PATTERN], [CatalogReleaseVersions.PATTERN]) and its length limits, so a
 * path that passes cannot traverse or name anything else. Anything else under [DIRECTORY] is refused.
 */
object CatalogDependencyArchivePaths {
    /** The directory, at the archive root, that holds the releases an archive carries. */
    const val DIRECTORY: String = "dependencies/"

    /** The suffix of an embedded catalog archive. */
    const val SUFFIX: String = ".zip"

    private const val REGISTRY: String = "[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+"
    private const val NAMESPACE: String = "[a-z][a-z0-9]*(?:-[a-z0-9]+)*"
    private const val CATALOG_KEY: String = "[a-z][a-z0-9]*(?:-[a-z0-9]+)*"
    private const val RELEASE: String = "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)"

    /**
     * Regular expression an embedded archive's path must match in full. The groups are, in order,
     * the registry, namespace, catalog key and release number; the length limits of each segment
     * apply as well ([parse]).
     */
    const val PATTERN: String = "^dependencies/($REGISTRY)/($NAMESPACE)/($CATALOG_KEY)/($RELEASE)\\.zip$"

    private val path = Regex(PATTERN)

    /** The release an embedded archive's path names. */
    data class Coordinates(
        val registry: String,
        val namespace: String,
        val catalogKey: String,
        val releaseVersion: String,
    ) {
        /** The path this release is carried at. */
        val path: String get() = pathOf(registry, namespace, catalogKey, releaseVersion)

        override fun toString(): String = "$registry/$namespace/$catalogKey@$releaseVersion"
    }

    /** Whether [value] is under [DIRECTORY], and so must be an embedded archive's path. */
    fun isDependencyPath(value: String): Boolean = value.startsWith(DIRECTORY)

    /**
     * The release [value] names, or null when it is not a valid embedded archive path: a segment
     * outside its rule or its length limits, a missing or extra segment, or another suffix.
     */
    fun parse(value: String): Coordinates? {
        val match = path.matchEntire(value) ?: return null
        val (registry, namespace, catalogKey, releaseVersion) = match.destructured
        if (!CatalogNamespaces.isValidRegistry(registry)) return null
        if (!CatalogNamespaces.isValid(namespace)) return null
        if (!CatalogKeys.matches(CatalogKeys.CATALOG, catalogKey)) return null
        if (!CatalogReleaseVersions.isValid(releaseVersion)) return null
        return Coordinates(registry, namespace, catalogKey, releaseVersion)
    }

    /** Whether [value] is a valid embedded archive path ([parse]). */
    fun isValid(value: String): Boolean = parse(value) != null

    /**
     * The path the release [releaseVersion] of the catalog [registry] / [namespace] / [catalogKey]
     * is carried at.
     *
     * @throws IllegalArgumentException when a segment breaks its rule.
     */
    fun pathOf(
        registry: String,
        namespace: String,
        catalogKey: String,
        releaseVersion: String,
    ): String {
        val value = "$DIRECTORY$registry/$namespace/$catalogKey/$releaseVersion$SUFFIX"
        require(isValid(value)) { "not a valid embedded catalog archive path: $value" }
        return value
    }
}
