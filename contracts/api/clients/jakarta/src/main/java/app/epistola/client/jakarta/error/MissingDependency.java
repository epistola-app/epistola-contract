// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.error;

import app.epistola.client.jakarta.model.MissingReleaseDependencyReason;
import java.util.Objects;

/**
 * One release a {@code release-dependencies-missing} problem lists: a release of another catalog
 * the deployed release was cut with, which the environment does not serve, and why.
 *
 * <p>Hand-written rather than the generated {@code MissingReleaseDependency}, because that binds
 * {@code reason} to an enum and fails on a value it does not know. The contract may add reasons
 * without a client release, so {@link #getReason()} keeps the value as sent;
 * {@link #getKnownReason()} maps it onto the generated enum where it can.
 */
public final class MissingDependency {

    private final String registry;
    private final String namespace;
    private final String catalogKey;
    private final String releaseVersion;
    private final String reason;

    public MissingDependency(
            String registry, String namespace, String catalogKey, String releaseVersion, String reason) {
        this.registry = registry;
        this.namespace = namespace;
        this.catalogKey = catalogKey;
        this.releaseVersion = releaseVersion;
        this.reason = reason;
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

    /** The release the deployed release was cut with. */
    public String getReleaseVersion() {
        return releaseVersion;
    }

    /**
     * Why the release is missing, as the server sent it: {@code NOT_DEPLOYED},
     * {@code NOT_INSTALLED}, {@code UNAVAILABLE}, or a reason a later contract added.
     */
    public String getReason() {
        return reason;
    }

    /** {@link #getReason()} as the generated enum, or {@code null} when this client does not know it. */
    public MissingReleaseDependencyReason getKnownReason() {
        for (MissingReleaseDependencyReason known : MissingReleaseDependencyReason.values()) {
            if (known.toString().equals(reason)) {
                return known;
            }
        }
        return null;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MissingDependency)) {
            return false;
        }
        MissingDependency that = (MissingDependency) other;
        return Objects.equals(registry, that.registry)
                && Objects.equals(namespace, that.namespace)
                && Objects.equals(catalogKey, that.catalogKey)
                && Objects.equals(releaseVersion, that.releaseVersion)
                && Objects.equals(reason, that.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(registry, namespace, catalogKey, releaseVersion, reason);
    }

    /** {@code registry/namespace/catalogKey@releaseVersion:reason}. */
    @Override
    public String toString() {
        return registry + "/" + namespace + "/" + catalogKey + "@" + releaseVersion + ":" + reason;
    }
}
