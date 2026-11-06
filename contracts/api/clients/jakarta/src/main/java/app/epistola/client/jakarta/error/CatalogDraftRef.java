// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.error;

import java.util.Objects;

/**
 * One draft a {@code catalog-draft-in-use} problem lists in {@code dependents}: a draft of another
 * catalog whose dependency declarations name the draft that could not be discarded. Change its
 * declarations, or discard it, first.
 *
 * <p>Hand-written rather than bound to the generated {@code CatalogDraftRefDto}, so that one
 * malformed entry does not cost the caller the whole problem, as with {@link UnreleasedDependency}.
 */
public final class CatalogDraftRef {

    private final String registry;
    private final String namespace;
    private final String catalogKey;
    private final String releaseId;
    private final String releaseName;

    public CatalogDraftRef(
            String registry, String namespace, String catalogKey, String releaseId, String releaseName) {
        this.registry = registry;
        this.namespace = namespace;
        this.catalogKey = catalogKey;
        this.releaseId = releaseId;
        this.releaseName = releaseName;
    }

    /** Registry of the draft's catalog, always in full (never {@code ~}). */
    public String getRegistry() {
        return registry;
    }

    /** Namespace of the draft's catalog, within its registry. */
    public String getNamespace() {
        return namespace;
    }

    /** Key of the draft's catalog, within its registry and namespace. */
    public String getCatalogKey() {
        return catalogKey;
    }

    /** The draft's id, as sent. */
    public String getReleaseId() {
        return releaseId;
    }

    /** The draft's name as it is now, as sent. */
    public String getReleaseName() {
        return releaseName;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CatalogDraftRef)) {
            return false;
        }
        CatalogDraftRef that = (CatalogDraftRef) other;
        return Objects.equals(registry, that.registry)
                && Objects.equals(namespace, that.namespace)
                && Objects.equals(catalogKey, that.catalogKey)
                && Objects.equals(releaseId, that.releaseId)
                && Objects.equals(releaseName, that.releaseName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(registry, namespace, catalogKey, releaseId, releaseName);
    }

    /** {@code registry/namespace/catalogKey#releaseId=releaseName}. */
    @Override
    public String toString() {
        return registry + "/" + namespace + "/" + catalogKey + "#" + releaseId + "=" + releaseName;
    }
}
