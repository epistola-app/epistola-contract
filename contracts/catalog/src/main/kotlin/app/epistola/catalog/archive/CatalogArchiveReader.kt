// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.archive

import app.epistola.catalog.archive.ArchiveValidationCodes.ARCHIVE_COMPRESSED_SIZE_EXCEEDED
import app.epistola.catalog.archive.ArchiveValidationCodes.ARCHIVE_DOCUMENT_MALFORMED
import app.epistola.catalog.archive.ArchiveValidationCodes.ARCHIVE_ENCRYPTION_FORBIDDEN
import app.epistola.catalog.archive.ArchiveValidationCodes.ARCHIVE_ENTRY_COUNT_EXCEEDED
import app.epistola.catalog.archive.ArchiveValidationCodes.ARCHIVE_EXPANDED_SIZE_EXCEEDED
import app.epistola.catalog.archive.ArchiveValidationCodes.ARCHIVE_EXPANSION_RATIO_EXCEEDED
import app.epistola.catalog.archive.ArchiveValidationCodes.ARCHIVE_INVALID
import app.epistola.catalog.archive.ArchiveValidationCodes.ARCHIVE_PATH_DUPLICATE
import app.epistola.catalog.archive.ArchiveValidationCodes.ARCHIVE_PATH_INVALID
import app.epistola.catalog.archive.ArchiveValidationCodes.ARCHIVE_REQUIRED_FILE_MISSING
import app.epistola.catalog.archive.ArchiveValidationCodes.ARCHIVE_SYMLINK_FORBIDDEN
import app.epistola.catalog.migration.CatalogMigrationCodes
import app.epistola.catalog.migration.CatalogMigrationContext
import app.epistola.catalog.migration.CatalogSchemaMigrator
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.zip.ZipException

/**
 * Safe decoder for portable catalog ZIP streams.
 *
 * Input is copied to bounded temporary storage and expanded entry by entry.
 * Absolute paths, traversal, backslashes, NUL bytes, duplicate normalized
 * paths, symbolic links, and encrypted entries are rejected before a
 * [CatalogArchive] is exposed. Manifest and resource documents pass through
 * [CatalogSchemaMigrator], so wire-version failures are returned as findings.
 *
 * Temporary files live until the returned archive is closed.
 */
object CatalogArchiveReader {
    /**
     * Reads, checks, and binds one catalog archive.
     *
     * Ordinary unsafe or malformed input is represented in
     * [CatalogArchiveReadResult.findings]. A non-null archive must be closed by
     * the caller. The supplied [input] is consumed and closed.
     *
     * @param input ZIP content to consume sequentially.
     * @param policy safety limits applied before and during extraction.
     * @throws IllegalArgumentException when [policy] contains invalid limits.
     * @throws IOException for unrecoverable storage or stream failures.
     */
    fun read(
        input: InputStream,
        policy: CatalogArchivePolicy = CatalogArchivePolicy(),
    ): CatalogArchiveReadResult {
        require(policy.maxCompressedBytes > 0)
        require(policy.maxExpandedBytes > 0)
        require(policy.maxEntries > 0)
        require(policy.maxExpansionRatio > 0)

        val workspace = Files.createTempDirectory("epistola-catalog-")
        val archiveFile = workspace.resolve("catalog.zip")
        val expandedRoot = workspace.resolve("entries")
        Files.createDirectory(expandedRoot)
        val findings = mutableListOf<ArchiveValidationFinding>()
        try {
            if (!copyCompressed(input, archiveFile, policy, findings)) {
                deleteRecursively(workspace)
                return CatalogArchiveReadResult(null, findings.sorted())
            }
            val extraction = extract(archiveFile, expandedRoot, policy, findings)
            if (!extraction.safe) {
                deleteRecursively(workspace)
                return CatalogArchiveReadResult(null, findings.sorted())
            }
            val manifestPath = expandedRoot.resolve("catalog.json")
            if (!Files.isRegularFile(manifestPath)) {
                findings += finding(ARCHIVE_REQUIRED_FILE_MISSING, "catalog.json", "archive does not contain catalog.json")
                deleteRecursively(workspace)
                return CatalogArchiveReadResult(null, findings.sorted())
            }
            val manifestResult = Files.newInputStream(manifestPath).use(CatalogSchemaMigrator::migrateManifest)
            findings += manifestResult.findings.map {
                finding(it.code.archiveCode(), it.path, it.message)
            }
            val manifest = manifestResult.value
            if (manifest == null) {
                deleteRecursively(workspace)
                return CatalogArchiveReadResult(null, findings.sorted())
            }
            val details = linkedMapOf<String, app.epistola.catalog.protocol.ResourceDetail>()
            val entriesByPath = manifest.resources.associateBy { it.detailUrl.removePrefix("./") }
            val migrationContext = CatalogMigrationContext(
                sourceVersion = requireNotNull(manifestResult.sourceVersion),
                manifest = manifest,
            )
            extraction.paths.asSequence()
                .filter { it.startsWith("resources/") && it.endsWith(".json") }
                .sorted()
                .forEach { path ->
                    val declaredType = entriesByPath[path]?.type ?: path.removePrefix("resources/").substringBefore('/')
                    val result = Files.newInputStream(expandedRoot.resolve(path)).use {
                        CatalogSchemaMigrator.migrateResourceDetail(declaredType, it, migrationContext, path)
                    }
                    findings += result.findings.map {
                        finding(it.code.archiveCode(), it.path, it.message)
                    }
                    result.value?.let { detail ->
                        details[path.removePrefix("resources/").removeSuffix(".json")] = detail
                    }
                }
            val archive = CatalogArchive(
                manifest = manifest,
                resourceDetails = details,
                paths = extraction.paths,
                content = ArchiveContentProvider { requested ->
                    val normalized = normalizePath(requested)
                        ?: throw IllegalArgumentException("Invalid archive content path: $requested")
                    val resolved = expandedRoot.resolve(normalized).normalize()
                    require(resolved.startsWith(expandedRoot) && Files.isRegularFile(resolved)) {
                        "Archive content does not exist: $requested"
                    }
                    BufferedInputStream(Files.newInputStream(resolved))
                },
                closeAction = { deleteRecursively(workspace) },
            )
            return CatalogArchiveReadResult(archive, findings.sorted())
        } catch (exception: ZipException) {
            deleteRecursively(workspace)
            return CatalogArchiveReadResult(
                null,
                (findings + finding(ARCHIVE_INVALID, "archive", "archive is not a valid ZIP: ${exception.message}")).sorted(),
            )
        } catch (exception: IOException) {
            deleteRecursively(workspace)
            throw exception
        } catch (exception: RuntimeException) {
            deleteRecursively(workspace)
            throw exception
        }
    }

    private fun copyCompressed(
        input: InputStream,
        target: Path,
        policy: CatalogArchivePolicy,
        findings: MutableList<ArchiveValidationFinding>,
    ): Boolean {
        var total = 0L
        BufferedInputStream(input).use { source ->
            BufferedOutputStream(Files.newOutputStream(target)).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > policy.maxCompressedBytes) {
                        findings += finding(
                            ARCHIVE_COMPRESSED_SIZE_EXCEEDED,
                            "archive",
                            "compressed archive exceeds ${policy.maxCompressedBytes} bytes",
                        )
                        return false
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
        return true
    }

    private fun extract(
        archiveFile: Path,
        expandedRoot: Path,
        policy: CatalogArchivePolicy,
        findings: MutableList<ArchiveValidationFinding>,
    ): ExtractionResult {
        val seenPaths = linkedSetOf<String>()
        val filePaths = linkedSetOf<String>()
        var totalExpanded = 0L
        var entryCount = 0
        ZipFile.builder().setFile(archiveFile.toFile()).get().use { zip ->
            val entries = zip.entries.asSequence().toList()
            for (entry in entries) {
                entryCount++
                if (entryCount > policy.maxEntries) {
                    findings += finding(
                        ARCHIVE_ENTRY_COUNT_EXCEEDED,
                        "archive",
                        "archive contains more than ${policy.maxEntries} entries",
                    )
                    return ExtractionResult(false, filePaths)
                }
                val encodedName = entry.rawName?.toString(StandardCharsets.UTF_8) ?: entry.name
                val originalName = if (entry.isDirectory) encodedName.removeSuffix("/") else encodedName
                val normalized = normalizePath(originalName)
                if (normalized == null) {
                    findings += finding(ARCHIVE_PATH_INVALID, originalName, "archive entry path is unsafe")
                    return ExtractionResult(false, filePaths)
                }
                if (!seenPaths.add(normalized)) {
                    findings += finding(ARCHIVE_PATH_DUPLICATE, normalized, "archive contains a duplicate normalized path")
                    return ExtractionResult(false, filePaths)
                }
                if (entry.isUnixSymlink) {
                    findings += finding(ARCHIVE_SYMLINK_FORBIDDEN, normalized, "symbolic-link entries are forbidden")
                    return ExtractionResult(false, filePaths)
                }
                if (entry.generalPurposeBit.usesEncryption()) {
                    findings += finding(ARCHIVE_ENCRYPTION_FORBIDDEN, normalized, "encrypted entries are forbidden")
                    return ExtractionResult(false, filePaths)
                }
                if (entry.isDirectory) continue
                filePaths += normalized
                val target = expandedRoot.resolve(normalized).normalize()
                if (!target.startsWith(expandedRoot)) {
                    findings += finding(ARCHIVE_PATH_INVALID, normalized, "archive entry escapes the extraction root")
                    return ExtractionResult(false, filePaths)
                }
                Files.createDirectories(target.parent)
                var entryExpanded = 0L
                zip.getInputStream(entry).use { source ->
                    BufferedOutputStream(Files.newOutputStream(target)).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = source.read(buffer)
                            if (read < 0) break
                            entryExpanded += read
                            totalExpanded += read
                            if (totalExpanded > policy.maxExpandedBytes) {
                                findings += finding(
                                    ARCHIVE_EXPANDED_SIZE_EXCEEDED,
                                    normalized,
                                    "expanded archive exceeds ${policy.maxExpandedBytes} bytes",
                                )
                                return ExtractionResult(false, filePaths)
                            }
                            if (ratio(entryExpanded, entry) > policy.maxExpansionRatio) {
                                findings += finding(
                                    ARCHIVE_EXPANSION_RATIO_EXCEEDED,
                                    normalized,
                                    "entry expansion ratio exceeds ${policy.maxExpansionRatio}:1",
                                )
                                return ExtractionResult(false, filePaths)
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
        }
        return ExtractionResult(true, filePaths)
    }

    private fun ratio(
        expanded: Long,
        entry: ZipArchiveEntry,
    ): Double {
        val compressed = entry.compressedSize
        return if (compressed <= 0) {
            if (expanded == 0L) 0.0 else Double.POSITIVE_INFINITY
        } else {
            expanded.toDouble() / compressed
        }
    }

    internal fun normalizePath(value: String): String? {
        if (value.isEmpty() || value.indexOf('\u0000') >= 0 || '\\' in value) return null
        if (value.startsWith('/') || DRIVE_PATH.matches(value)) return null
        val segments = value.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        return segments.joinToString("/")
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun List<ArchiveValidationFinding>.sorted(): List<ArchiveValidationFinding> = sortedWith(compareBy({ it.path }, { it.code }, { it.message }))

    private fun finding(
        code: String,
        path: String,
        message: String,
    ) = ArchiveValidationFinding(code, path, message)

    private fun String.archiveCode(): String = if (this == CatalogMigrationCodes.SCHEMA_UNKNOWN) {
        ARCHIVE_DOCUMENT_MALFORMED
    } else {
        this
    }

    private data class ExtractionResult(
        val safe: Boolean,
        val paths: Set<String>,
    )

    private val DRIVE_PATH = Regex("^[A-Za-z]:.*")
}
