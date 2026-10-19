// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.archive

import app.epistola.catalog.migration.CatalogMigrationNotice
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.ResourceDetail
import java.io.InputStream

/**
 * Opens binary content from a portable catalog by its normalized archive path.
 *
 * Implementations must return a new stream for every invocation. The caller
 * owns and closes the returned stream. Paths use forward slashes and never
 * start with `/`; implementations may reject paths that are absent or unsafe.
 */
fun interface ArchiveContentProvider {
    /**
     * Opens [path] for streaming.
     *
     * @throws IllegalArgumentException when [path] is invalid or unavailable.
     * @throws java.io.IOException when the content cannot be opened.
     */
    fun open(path: String): InputStream
}

/**
 * Parsed portable catalog plus a streaming provider for its archive entries.
 *
 * Archives returned by [CatalogArchiveReader] own temporary backing storage and
 * must be closed. Programmatically constructed archives may use the default
 * no-op close action.
 *
 * @property manifest catalog-level metadata and the declared resource index.
 * @property resourceDetails resource documents keyed by `type/key`, without
 *   the `resources/` prefix or `.json` suffix.
 * @property paths every normalized file path present in the archive.
 * @property content repeatable streaming access to files listed in [paths].
 * @property dependencies the releases the archive carries for its pins (wire v8,
 *   `dependencies/{registry}/{namespace}/{catalogKey}/{releaseVersion}.zip`), flat and
 *   transitive, in install order: each before every release that pins it. Empty for a thin
 *   archive. The archive owns them and closes them with itself.
 */
class CatalogArchive
@JvmOverloads
constructor(
    val manifest: CatalogManifest,
    val resourceDetails: Map<String, ResourceDetail>,
    val paths: Set<String>,
    val content: ArchiveContentProvider,
    private val closeAction: () -> Unit = {},
    val dependencies: List<EmbeddedCatalogRelease> = emptyList(),
) : AutoCloseable {
    /** Original wire version before migration; used for legacy fingerprint verification. */
    internal var sourceSchemaVersion: Int = manifest.schemaVersion

    /** Releases backing storage owned by this archive and the releases it carries. Safe to call once. */
    override fun close() {
        try {
            dependencies.forEach { it.archive.close() }
        } finally {
            closeAction()
        }
    }
}

/**
 * A release a catalog archive carries for a pin, at [path]
 * ([app.epistola.catalog.protocol.CatalogDependencyArchivePaths]).
 *
 * [archive] is an ordinary, thin catalog archive of that release. The reader exposes it only when
 * its manifest names this catalog and release; whether it matches every pin's `releaseFingerprint`
 * is reported as findings ([ArchiveValidationCodes.ARCHIVE_DEPENDENCY_FINGERPRINT_MISMATCH]).
 */
class EmbeddedCatalogRelease(
    val registry: String,
    val namespace: String,
    val catalogKey: String,
    val releaseVersion: String,
    val archive: CatalogArchive,
) {
    /** Where the outer archive carries it. */
    val path: String get() = app.epistola.catalog.protocol.CatalogDependencyArchivePaths.pathOf(registry, namespace, catalogKey, releaseVersion)

    override fun toString(): String = "$registry/$namespace/$catalogKey@$releaseVersion"
}

/**
 * Stable diagnostic produced while decoding an archive.
 *
 * Archive findings describe invalid input and are not thrown as exceptions.
 * Their natural presentation order is [path], then [code], then [message].
 */
data class ArchiveValidationFinding(
    /** Machine-readable identifier from [ArchiveValidationCodes]. */
    val code: String,
    /** Deterministic archive-relative path, or `archive` for container errors. */
    val path: String,
    /** Human-readable explanation intended for logs and user interfaces. */
    val message: String,
)

/**
 * Result of safely reading a catalog archive.
 *
 * [archive] may be non-null together with findings when the container was safe
 * enough to decode but individual documents need attention. Callers must close
 * every non-null archive, normally with `use`.
 */
data class CatalogArchiveReadResult(
    val archive: CatalogArchive?,
    val findings: List<ArchiveValidationFinding>,
    val migrationNotices: List<CatalogMigrationNotice> = emptyList(),
) {
    /** True only when an archive was decoded and no findings were produced. */
    val valid: Boolean get() = archive != null && findings.isEmpty()
}

/**
 * Resource limits shared by archive readers and writers.
 *
 * Defaults intentionally cap compressed input at 10 MiB, expanded content at
 * 20 MiB, entries at 10,000, and per-entry expansion at 100:1. Consumers may
 * use stricter limits, but non-positive values are programmer errors.
 *
 * @property maxCompressedBytes maximum ZIP bytes accepted or emitted.
 * @property maxExpandedBytes maximum combined uncompressed content bytes.
 * @property maxEntries maximum number of normalized archive entries.
 * @property maxExpansionRatio maximum uncompressed-to-compressed ratio for one
 *   input entry; this is a reader-side ZIP-bomb control.
 * @property maxDependencies maximum number of releases one archive carries under `dependencies/`
 *   (wire v8). The limits above apply to the archive's own content and, separately, to each
 *   release it carries.
 * @property maxTotalCompressedBytes maximum ZIP bytes of an archive together with the releases it
 *   carries. A thin archive stays bound by [maxCompressedBytes].
 * @property maxTotalExpandedBytes maximum combined uncompressed content of an archive and every
 *   release it carries.
 */
data class CatalogArchivePolicy
@JvmOverloads
constructor(
    val maxCompressedBytes: Long = 10L * 1024 * 1024,
    val maxExpandedBytes: Long = 20L * 1024 * 1024,
    val maxEntries: Int = 10_000,
    val maxExpansionRatio: Double = 100.0,
    val maxDependencies: Int = 50,
    val maxTotalCompressedBytes: Long = 100L * 1024 * 1024,
    val maxTotalExpandedBytes: Long = 200L * 1024 * 1024,
)

/** Stable finding codes emitted by [CatalogArchiveReader]. */
object ArchiveValidationCodes {
    const val ARCHIVE_INVALID = "CATALOG_ARCHIVE_INVALID"
    const val ARCHIVE_COMPRESSED_SIZE_EXCEEDED = "CATALOG_ARCHIVE_COMPRESSED_SIZE_EXCEEDED"
    const val ARCHIVE_EXPANDED_SIZE_EXCEEDED = "CATALOG_ARCHIVE_EXPANDED_SIZE_EXCEEDED"
    const val ARCHIVE_ENTRY_COUNT_EXCEEDED = "CATALOG_ARCHIVE_ENTRY_COUNT_EXCEEDED"
    const val ARCHIVE_EXPANSION_RATIO_EXCEEDED = "CATALOG_ARCHIVE_EXPANSION_RATIO_EXCEEDED"
    const val ARCHIVE_PATH_INVALID = "CATALOG_ARCHIVE_PATH_INVALID"
    const val ARCHIVE_PATH_DUPLICATE = "CATALOG_ARCHIVE_PATH_DUPLICATE"
    const val ARCHIVE_SYMLINK_FORBIDDEN = "CATALOG_ARCHIVE_SYMLINK_FORBIDDEN"
    const val ARCHIVE_ENCRYPTION_FORBIDDEN = "CATALOG_ARCHIVE_ENCRYPTION_FORBIDDEN"
    const val ARCHIVE_REQUIRED_FILE_MISSING = "CATALOG_ARCHIVE_REQUIRED_FILE_MISSING"
    const val ARCHIVE_DOCUMENT_MALFORMED = "CATALOG_ARCHIVE_DOCUMENT_MALFORMED"

    /**
     * An entry under `dependencies/` is not `dependencies/{registry}/{namespace}/{catalogKey}/{releaseVersion}.zip`
     * with each segment valid ([app.epistola.catalog.protocol.CatalogDependencyArchivePaths]).
     */
    const val ARCHIVE_DEPENDENCY_PATH_INVALID = "CATALOG_ARCHIVE_DEPENDENCY_PATH_INVALID"

    /** The archive carries more releases than [CatalogArchivePolicy.maxDependencies]. */
    const val ARCHIVE_DEPENDENCY_COUNT_EXCEEDED = "CATALOG_ARCHIVE_DEPENDENCY_COUNT_EXCEEDED"

    /**
     * A carried release itself contains `dependencies/`. Carried releases are flat: every release
     * reachable through the pins lives in the outer archive's `dependencies/`, once.
     */
    const val ARCHIVE_DEPENDENCY_NESTED = "CATALOG_ARCHIVE_DEPENDENCY_NESTED"

    /** A carried release's manifest does not name the registry, namespace, key and release its path names. */
    const val ARCHIVE_DEPENDENCY_IDENTITY_MISMATCH = "CATALOG_ARCHIVE_DEPENDENCY_IDENTITY_MISMATCH"

    /** A carried release's content does not match the `releaseFingerprint` of a pin that reaches it. */
    const val ARCHIVE_DEPENDENCY_FINGERPRINT_MISMATCH = "CATALOG_ARCHIVE_DEPENDENCY_FINGERPRINT_MISMATCH"

    /** A carried release no pin reaches, from the archive's own pins or, transitively, a carried release's. */
    const val ARCHIVE_DEPENDENCY_UNREFERENCED = "CATALOG_ARCHIVE_DEPENDENCY_UNREFERENCED"

    /** Two pins reachable from the archive name different releases of one catalog. */
    const val ARCHIVE_DEPENDENCY_CONFLICT = "CATALOG_ARCHIVE_DEPENDENCY_CONFLICT"
}
