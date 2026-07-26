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
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.ResourceDetail
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import tools.jackson.core.JacksonException
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.zip.ZipException

object CatalogArchiveReader {
    private val mapper = jsonMapper { addModule(kotlinModule()) }

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
            val manifest = bind<CatalogManifest>(manifestPath, "catalog.json", findings)
            if (manifest == null) {
                deleteRecursively(workspace)
                return CatalogArchiveReadResult(null, findings.sorted())
            }
            val details = linkedMapOf<String, ResourceDetail>()
            extraction.paths.asSequence()
                .filter { it.startsWith("resources/") && it.endsWith(".json") }
                .sorted()
                .forEach { path ->
                    bind<ResourceDetail>(expandedRoot.resolve(path), path, findings)?.let { detail ->
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
        val paths = linkedSetOf<String>()
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
                    return ExtractionResult(false, paths)
                }
                val encodedName = entry.rawName?.toString(StandardCharsets.UTF_8) ?: entry.name
                val originalName = if (entry.isDirectory) encodedName.removeSuffix("/") else encodedName
                val normalized = normalizePath(originalName)
                if (normalized == null) {
                    findings += finding(ARCHIVE_PATH_INVALID, originalName, "archive entry path is unsafe")
                    return ExtractionResult(false, paths)
                }
                if (!paths.add(normalized)) {
                    findings += finding(ARCHIVE_PATH_DUPLICATE, normalized, "archive contains a duplicate normalized path")
                    return ExtractionResult(false, paths)
                }
                if (entry.isUnixSymlink) {
                    findings += finding(ARCHIVE_SYMLINK_FORBIDDEN, normalized, "symbolic-link entries are forbidden")
                    return ExtractionResult(false, paths)
                }
                if (entry.generalPurposeBit.usesEncryption()) {
                    findings += finding(ARCHIVE_ENCRYPTION_FORBIDDEN, normalized, "encrypted entries are forbidden")
                    return ExtractionResult(false, paths)
                }
                if (entry.isDirectory) continue
                val target = expandedRoot.resolve(normalized).normalize()
                if (!target.startsWith(expandedRoot)) {
                    findings += finding(ARCHIVE_PATH_INVALID, normalized, "archive entry escapes the extraction root")
                    return ExtractionResult(false, paths)
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
                                return ExtractionResult(false, paths)
                            }
                            if (ratio(entryExpanded, entry) > policy.maxExpansionRatio) {
                                findings += finding(
                                    ARCHIVE_EXPANSION_RATIO_EXCEEDED,
                                    normalized,
                                    "entry expansion ratio exceeds ${policy.maxExpansionRatio}:1",
                                )
                                return ExtractionResult(false, paths)
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
        }
        return ExtractionResult(true, paths)
    }

    private inline fun <reified T> bind(
        path: Path,
        archivePath: String,
        findings: MutableList<ArchiveValidationFinding>,
    ): T? = try {
        Files.newInputStream(path).use { mapper.readValue(it, T::class.java) }
    } catch (exception: JacksonException) {
        findings += finding(
            ARCHIVE_DOCUMENT_MALFORMED,
            archivePath,
            "JSON document is malformed or incompatible: ${exception.originalMessage}",
        )
        null
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

    private data class ExtractionResult(
        val safe: Boolean,
        val paths: Set<String>,
    )

    private val DRIVE_PATH = Regex("^[A-Za-z]:.*")
}
