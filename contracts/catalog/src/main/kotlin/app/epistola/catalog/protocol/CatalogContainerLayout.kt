// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.protocol

/**
 * How a catalog release is kept in a container registry (OCI distribution), the `container` kind of
 * release registry (contract ADR 0008, amendment of 2026-10-22), defined once for every producer and
 * consumer.
 *
 * - **Repository**: `<prefix>/<authority>/<namespace>/<catalogKey>`. The prefix is the installation's
 *   configuration (zero or more path components, such as `epistola/catalogs`); the rest is the
 *   catalog's full name, its authority (the `registry` of the name, a domain or `local`) included, so
 *   two catalogs that differ only in their authority never share a repository.
 * - **Tag**: the release number (`1.4.0`, `1.3.1-gemeente-x.1`), the binding "this name at this
 *   number". Every valid release number is a valid tag. A tag is a pointer: a receiver resolves it to
 *   the manifest's digest once and fetches the manifest and its blobs by that digest, so what it
 *   verified is what it installs, and the release verifies itself by its content hash, recomputed from
 *   the archive.
 * - **Artifact**: an OCI image manifest ([MANIFEST_MEDIA_TYPE]) of artifact type [ARTIFACT_TYPE], with a
 *   config blob ([CONFIG_MEDIA_TYPE]) and one layer, the release's catalog archive ([ARCHIVE_MEDIA_TYPE]),
 *   exactly as exported. The manifest's [Annotations] describe the release for listings; they are
 *   labels, never evidence.
 * - **Mirroring** copies the manifest, its blobs and its tag unchanged to the same path under another
 *   registry's prefix: the same content, name and number, so the same digest.
 */
object CatalogContainerLayout {
    /** The manifest's media type: an OCI image manifest. */
    const val MANIFEST_MEDIA_TYPE: String = "application/vnd.oci.image.manifest.v1+json"

    /** The manifest's `artifactType`: a catalog release. */
    const val ARTIFACT_TYPE: String = "application/vnd.epistola.catalog.release.v1"

    /** The config blob's media type. */
    const val CONFIG_MEDIA_TYPE: String = "application/vnd.epistola.catalog.release.config.v1+json"

    /** The one layer's media type: the release's catalog archive. */
    const val ARCHIVE_MEDIA_TYPE: String = "application/vnd.epistola.catalog.archive.v1+zip"

    /**
     * The annotations a release's manifest carries. Labels, not evidence: anyone who may push to the
     * repository can write any of them, so a receiver recomputes what matters from the archive.
     */
    object Annotations {
        const val VERSION: String = "app.epistola.catalog.version"
        const val FINGERPRINT: String = "app.epistola.catalog.fingerprint"

        /** The release's content hash (V6), by which a listing tells releases apart whatever their number. */
        const val CONTENT_HASH: String = "app.epistola.catalog.contentHash"
        const val REGISTRY: String = "app.epistola.catalog.registry"
        const val NAMESPACE: String = "app.epistola.catalog.namespace"
        const val CATALOG_KEY: String = "app.epistola.catalog.catalogKey"
        const val IMAGE_VERSION: String = "org.opencontainers.image.version"
        const val IMAGE_TITLE: String = "org.opencontainers.image.title"
    }

    /** A repository path component, as the OCI distribution specification names it. */
    const val PATH_COMPONENT_PATTERN: String = "^[a-z0-9]+((\\.|_|__|-+)[a-z0-9]+)*$"

    /** A tag, as the OCI distribution specification names it. */
    const val TAG_PATTERN: String = "^[a-zA-Z0-9_][a-zA-Z0-9._-]{0,127}$"

    /** Longest repository name, prefix included, that registries are required to accept. */
    const val REPOSITORY_MAX_LENGTH: Int = 255

    private val component = Regex(PATH_COMPONENT_PATTERN)
    private val tag = Regex(TAG_PATTERN)

    /** Whether [prefix] is empty or path components joined by `/`. */
    @JvmStatic
    fun isValidPrefix(prefix: String): Boolean = prefix.isEmpty() || prefix.split('/').all(component::matches)

    /**
     * The repository that keeps the releases of the catalog `registry/namespace/catalogKey` under
     * [prefix].
     *
     * @throws IllegalArgumentException when a part breaks its rule or the name is too long.
     */
    @JvmStatic
    fun repository(prefix: String, registry: String, namespace: String, catalogKey: String): String {
        require(isValidPrefix(prefix)) { "'$prefix' is not a repository prefix" }
        require(CatalogNamespaces.isValidRegistry(registry)) { "'$registry' is not a registry" }
        require(CatalogNamespaces.isValid(namespace)) { "'$namespace' is not a namespace" }
        require(CatalogKeys.matches(CatalogKeys.CATALOG, catalogKey)) { "'$catalogKey' is not a catalog key" }
        val name = listOf(prefix, registry, namespace, catalogKey).filter { it.isNotEmpty() }.joinToString("/")
        require(name.length <= REPOSITORY_MAX_LENGTH) { "repository name '$name' is longer than $REPOSITORY_MAX_LENGTH characters" }
        return name
    }

    /** The tag of release [version]: the number itself. */
    @JvmStatic
    fun tag(version: String): String {
        require(CatalogReleaseVersions.isValid(version)) { "'$version' is not a release number" }
        check(tag.matches(version)) { "release number '$version' is not a valid tag" }
        return version
    }

    /** `repository:tag`, the reference a release is pushed and listed under; pulls resolve it to a digest. */
    @JvmStatic
    fun reference(prefix: String, registry: String, namespace: String, catalogKey: String, version: String): String = "${repository(prefix, registry, namespace, catalogKey)}:${tag(version)}"
}
