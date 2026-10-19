// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.archive

import app.epistola.catalog.canonical.CatalogCanonicalizer
import app.epistola.catalog.protocol.CatalogDependencyArchivePaths
import app.epistola.catalog.protocol.CatalogDependencyArchivePaths.Coordinates
import app.epistola.catalog.protocol.CatalogManifest

/**
 * The rules for the releases a catalog archive carries (wire v8), shared by
 * [CatalogArchiveReader] and [app.epistola.catalog.validation.CatalogValidator].
 *
 * Every pin is resolved, at any depth, against the outer archive's `dependencies/`: the archive's
 * own pins, and the pins of each release it carries. A pin with no carried release is left for the
 * installation to resolve. A carried release must name the catalog and release its path names and
 * match every reaching pin's `releaseFingerprint`; one no pin reaches, or two pins naming different
 * releases of one catalog, refuse the archive.
 */
internal object CatalogArchiveDependencies {
    /** Separates a carried release's path from a path inside it in findings: `dependencies/…/1.0.0.zip!/catalog.json`. */
    const val NESTED_PATH_SEPARATOR: String = "!/"

    class Result(
        val findings: List<ArchiveValidationFinding>,
        /** The carried releases that pass the identity check and a pin reaches, each before every release that pins it. */
        val installOrder: List<Coordinates>,
    )

    /**
     * Checks the carried releases [carried] of an archive with [manifest]. A null archive is one that
     * could not be read; it counts as present, so it is not also reported as unreferenced.
     */
    fun check(
        manifest: CatalogManifest,
        carried: Map<Coordinates, CatalogArchive?>,
    ): Result {
        val findings = mutableListOf<ArchiveValidationFinding>()
        val identityValid = mutableSetOf<Coordinates>()
        carried.forEach { (coordinates, archive) ->
            if (archive == null) return@forEach
            val catalog = archive.manifest.catalog
            val named = Coordinates(catalog.registry.orEmpty(), catalog.namespace.orEmpty(), catalog.key, archive.manifest.release.version)
            if (named == coordinates) {
                identityValid += coordinates
            } else {
                findings += ArchiveValidationFinding(
                    ArchiveValidationCodes.ARCHIVE_DEPENDENCY_IDENTITY_MISMATCH,
                    nested(coordinates.path, "catalog.json"),
                    "the carried archive is release $named, not $coordinates as its path names",
                )
            }
        }

        // The release of each catalog the graph pins, and where it was first pinned. The archive's own
        // catalog is pinned at its own release.
        val pinned = linkedMapOf<Triple<String, String, String>, Pair<String, String>>()
        manifest.catalog.let { pinned[Triple(it.registry.orEmpty(), it.namespace.orEmpty(), it.key)] = manifest.release.version to "catalog.json.release.version" }
        val reached = linkedSetOf<Coordinates>()
        val edges = linkedMapOf<Coordinates?, MutableList<Coordinates>>()

        // Breadth first, so that the archive's own pins are recorded before the pins of the releases
        // it carries, and a conflict is reported at the deeper pin.
        val queue = ArrayDeque<Triple<Coordinates?, CatalogManifest, String>>()
        queue += Triple(null, manifest, "catalog.json")
        while (queue.isNotEmpty()) {
            val (from, pinsOf, at) = queue.removeFirst()
            pinsOf.catalogDependencies.orEmpty().forEachIndexed { index, pin ->
                val release = pin.releaseVersion ?: return@forEachIndexed
                val path = "$at.catalogDependencies[$index]"
                val catalog = Triple(pin.registry, pin.namespace, pin.catalogKey)
                val earlier = pinned.getOrPut(catalog) { release to path }
                if (earlier.first != release) {
                    findings += ArchiveValidationFinding(
                        ArchiveValidationCodes.ARCHIVE_DEPENDENCY_CONFLICT,
                        path,
                        "pins ${pin.registry}/${pin.namespace}/${pin.catalogKey}@$release, but ${earlier.second} pins ${earlier.first}",
                    )
                }
                val coordinates = Coordinates(pin.registry, pin.namespace, pin.catalogKey, release)
                if (coordinates !in carried) return@forEachIndexed
                val archive = carried[coordinates]
                if (archive != null &&
                    pin.releaseFingerprint != null &&
                    !CatalogCanonicalizer.matchesQualifiedFingerprint(archive, pin.releaseFingerprint)
                ) {
                    findings += ArchiveValidationFinding(
                        ArchiveValidationCodes.ARCHIVE_DEPENDENCY_FINGERPRINT_MISMATCH,
                        coordinates.path,
                        "the carried release does not match $path.releaseFingerprint ${pin.releaseFingerprint}",
                    )
                }
                edges.getOrPut(from) { mutableListOf() } += coordinates
                if (reached.add(coordinates) && archive != null) {
                    queue += Triple(coordinates, archive.manifest, nested(coordinates.path, "catalog.json"))
                }
            }
        }

        carried.keys.filter { it !in reached }.forEach {
            findings += ArchiveValidationFinding(
                ArchiveValidationCodes.ARCHIVE_DEPENDENCY_UNREFERENCED,
                it.path,
                "no pin of the archive, or of a release it carries, names release $it",
            )
        }

        val order = mutableListOf<Coordinates>()
        val placed = mutableSetOf<Coordinates>()

        fun place(node: Coordinates) {
            if (!placed.add(node)) return
            edges[node].orEmpty().forEach(::place)
            if (node in identityValid) order += node
        }
        edges[null].orEmpty().forEach(::place)
        return Result(findings, order)
    }

    /** The path of [inner] inside the carried release at [outer]. */
    fun nested(
        outer: String,
        inner: String,
    ): String = "$outer$NESTED_PATH_SEPARATOR$inner"

    /** Whether [path] is under the carried-releases directory. */
    fun isDependencyPath(path: String): Boolean = CatalogDependencyArchivePaths.isDependencyPath(path)
}
