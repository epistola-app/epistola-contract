// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.error;

import java.util.Objects;

/**
 * One draft a {@code release-dependencies-unreleased} problem lists: a draft of another catalog
 * that the released draft's dependency declarations name, directly or through another such draft.
 * A release pins only releases, so it must be released first, or along with the release
 * ({@code releaseDependencies}).
 *
 * <p>Hand-written rather than bound to the generated {@code UnreleasedCatalogDependency}, so that
 * one malformed entry does not cost the caller the whole problem, as with {@link MissingDependency}.
 */
public final class UnreleasedDependency {

    private final String registry;
    private final String namespace;
    private final String catalogKey;
    private final String releaseId;
    private final String releaseName;

    public UnreleasedDependency(
            String registry, String namespace, String catalogKey, String releaseId, String releaseName) {
        this.registry = registry;
        this.namespace = namespace;
        this.catalogKey = catalogKey;
        this.releaseId = releaseId;
        this.releaseName = releaseName;
    }

    /** Registry of the catalog depended on, always in full (never {@code ~}). */
    public String getRegistry() {
        return registry;
    }

    /** Namespace of the catalog depended on, within its registry. */
    public String getNamespace() {
        return namespace;
    }

    /** Key of the catalog depended on, within its registry and namespace. */
    public String getCatalogKey() {
        return catalogKey;
    }

    /** The id of the draft the declaration names, as sent. */
    public String getReleaseId() {
        return releaseId;
    }

    /** The draft's name, as sent. */
    public String getReleaseName() {
        return releaseName;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof UnreleasedDependency)) {
            return false;
        }
        UnreleasedDependency that = (UnreleasedDependency) other;
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
