package app.epistola.catalog.archive

import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.ResourceDetail
import java.io.InputStream

fun interface ArchiveContentProvider {
    fun open(path: String): InputStream
}

/**
 * Parsed portable catalog plus a streaming provider for its archive entries.
 *
 * Archives returned by [CatalogArchiveReader] own temporary backing storage and
 * must be closed. Programmatically constructed archives may use the default
 * no-op close action.
 */
class CatalogArchive(
    val manifest: CatalogManifest,
    val resourceDetails: Map<String, ResourceDetail>,
    val paths: Set<String>,
    val content: ArchiveContentProvider,
    private val closeAction: () -> Unit = {},
) : AutoCloseable {
    override fun close() = closeAction()
}

data class ArchiveValidationFinding(
    val code: String,
    val path: String,
    val message: String,
)

data class CatalogArchiveReadResult(
    val archive: CatalogArchive?,
    val findings: List<ArchiveValidationFinding>,
) {
    val valid: Boolean get() = archive != null && findings.isEmpty()
}

data class CatalogArchivePolicy(
    val maxCompressedBytes: Long = 10L * 1024 * 1024,
    val maxExpandedBytes: Long = 20L * 1024 * 1024,
    val maxEntries: Int = 10_000,
    val maxExpansionRatio: Double = 100.0,
)

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
}
