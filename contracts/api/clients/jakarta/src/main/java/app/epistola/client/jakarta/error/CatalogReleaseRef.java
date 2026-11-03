// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.error;

import java.util.Objects;

/**
 * One release a problem names in full, such as each release of another catalog a
 * {@code release-in-use} problem says pins the release ({@code pinnedBy}).
 *
 * <p>Hand-written rather than bound to the generated {@code CatalogReleaseRefDto}, so that one
 * malformed entry does not cost the caller the whole problem, as with {@link MissingDependency}.
 */
public final class CatalogReleaseRef {

    private final String registry;
    private final String namespace;
    private final String catalogKey;
    private final String version;
    private final String releaseId;

    public CatalogReleaseRef(String registry, String namespace, String catalogKey, String version, String releaseId) {
        this.registry = registry;
        this.namespace = namespace;
        this.catalogKey = catalogKey;
        this.version = version;
        this.releaseId = releaseId;
    }

    /** Registry of the catalog, always in full (never {@code ~}). */
    public String getRegistry() {
        return registry;
    }

    /** Namespace of the catalog, within its registry. */
    public String getNamespace() {
        return namespace;
    }

    /** Key of the catalog, within its registry and namespace. */
    public String getCatalogKey() {
        return catalogKey;
    }

    /** The release's version, as sent. */
    public String getVersion() {
        return version;
    }

    /** The release's id, as sent. */
    public String getReleaseId() {
        return releaseId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CatalogReleaseRef)) {
            return false;
        }
        CatalogReleaseRef that = (CatalogReleaseRef) other;
        return Objects.equals(registry, that.registry)
                && Objects.equals(namespace, that.namespace)
                && Objects.equals(catalogKey, that.catalogKey)
                && Objects.equals(version, that.version)
                && Objects.equals(releaseId, that.releaseId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(registry, namespace, catalogKey, version, releaseId);
    }

    /** {@code registry/namespace/catalogKey@version#releaseId}. */
    @Override
    public String toString() {
        return registry + "/" + namespace + "/" + catalogKey + "@" + version + "#" + releaseId;
    }
}
