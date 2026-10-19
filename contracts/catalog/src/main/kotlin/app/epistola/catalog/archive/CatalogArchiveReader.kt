// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.archive

import app.epistola.catalog.archive.ArchiveValidationCodes.ARCHIVE_COMPRESSED_SIZE_EXCEEDED
import app.epistola.catalog.archive.ArchiveValidationCodes.ARCHIVE_DEPENDENCY_COUNT_EXCEEDED
import app.epistola.catalog.archive.ArchiveValidationCodes.ARCHIVE_DEPENDENCY_NESTED
import app.epistola.catalog.archive.ArchiveValidationCodes.ARCHIVE_DEPENDENCY_PATH_INVALID
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
import app.epistola.catalog.migration.CatalogMigrationOptions
import app.epistola.catalog.migration.CatalogSchemaMigrator
import app.epistola.catalog.protocol.CatalogDependencyArchivePaths
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
 * No path a document declares is ever resolved outside the archive, at any wire version: resource
 * documents are found by the archive's own `resources/` entries rather than by following a
 * manifest `detailUrl`, and the returned [ArchiveContentProvider] refuses any path that is not a
 * normalized relative path inside the extraction root. Wire v8 additionally constrains the declared
 * paths themselves ([app.epistola.catalog.protocol.CatalogArchivePaths]).
 *
 * From wire v8 an archive may carry the releases it pins under `dependencies/`
 * ([app.epistola.catalog.protocol.CatalogDependencyArchivePaths]). Each is read as an archive of
 * its own under the per-archive limits, checked against the pins that reach it, and exposed in
 * install order as [CatalogArchive.dependencies]; see `docs/catalog-archive.md`.
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
     * @param migration what the caller knows that an archive from before the current wire version
     *   does not say, notably the registry and namespace of a catalog-v7 archive.
     * @throws IllegalArgumentException when [policy] contains invalid limits.
     * @throws IOException for unrecoverable storage or stream failures.
     */
    @JvmOverloads
    fun read(
        input: InputStream,
        policy: CatalogArchivePolicy = CatalogArchivePolicy(),
        migration: CatalogMigrationOptions = CatalogMigrationOptions.DEFAULT,
    ): CatalogArchiveReadResult {
        require(policy.maxCompressedBytes > 0)
        require(policy.maxExpandedBytes > 0)
        require(policy.maxEntries > 0)
        require(policy.maxExpansionRatio > 0)
        require(policy.maxDependencies >= 0)
        require(policy.maxTotalCompressedBytes > 0)
        require(policy.maxTotalExpandedBytes > 0)
        return read(input, policy, migration, carried = false).result
    }

    /**
     * Reads one archive. [carried] is true for a release another archive carries under
     * `dependencies/`: it is bound by the per-archive limits alone, and may not carry releases itself.
     */
    private fun read(
        input: InputStream,
        policy: CatalogArchivePolicy,
        migration: CatalogMigrationOptions,
        carried: Boolean,
    ): ReadOutcome {
        val workspace = Files.createTempDirectory("epistola-catalog-")
        val archiveFile = workspace.resolve("catalog.zip")
        val expandedRoot = workspace.resolve("entries")
        Files.createDirectory(expandedRoot)
        val findings = mutableListOf<ArchiveValidationFinding>()
        try {
            val inputLimit = if (carried) policy.maxCompressedBytes else maxOf(policy.maxCompressedBytes, policy.maxTotalCompressedBytes)
            if (!copyCompressed(input, archiveFile, inputLimit, findings)) {
                deleteRecursively(workspace)
                return ReadOutcome(CatalogArchiveReadResult(null, findings.sorted()))
            }
            val extraction = extract(archiveFile, expandedRoot, policy, carried, findings)
            if (!extraction.safe) {
                deleteRecursively(workspace)
                return ReadOutcome(CatalogArchiveReadResult(null, findings.sorted()))
            }
            if (carried && extraction.paths.any(CatalogDependencyArchivePaths::isDependencyPath)) {
                findings += finding(
                    ARCHIVE_DEPENDENCY_NESTED,
                    CatalogDependencyArchivePaths.DIRECTORY,
                    "a carried release may not carry releases itself: every release lives in the outer archive's dependencies/",
                )
            }
            val manifestPath = expandedRoot.resolve("catalog.json")
            if (!Files.isRegularFile(manifestPath)) {
                findings += finding(ARCHIVE_REQUIRED_FILE_MISSING, "catalog.json", "archive does not contain catalog.json")
                deleteRecursively(workspace)
                return ReadOutcome(CatalogArchiveReadResult(null, findings.sorted()))
            }
            val manifestResult = Files.newInputStream(manifestPath).use { CatalogSchemaMigrator.migrateManifest(it, migration) }
            val migrationNotices = manifestResult.notices.toMutableList()
            findings += manifestResult.findings.map {
                finding(it.code.archiveCode(), it.path, it.message)
            }
            val manifest = manifestResult.value
            if (manifest == null) {
                deleteRecursively(workspace)
                return ReadOutcome(CatalogArchiveReadResult(null, findings.sorted()))
            }
            val sourceVersion = requireNotNull(manifestResult.sourceVersion)
            if (!carried && extraction.dependencyPaths.isNotEmpty() && sourceVersion < CARRIED_RELEASES_SINCE_WIRE_VERSION) {
                // Before wire v8 an archive carries no releases: `dependencies/` is an ordinary
                // directory, held to the per-archive limits as it always was, and not interpreted.
                val legacyLimit = legacyLimitFinding(archiveFile, extraction, policy)
                if (legacyLimit != null) {
                    findings += legacyLimit
                    deleteRecursively(workspace)
                    return ReadOutcome(CatalogArchiveReadResult(null, findings.sorted()))
                }
            }
            val details = linkedMapOf<String, app.epistola.catalog.protocol.ResourceDetail>()
            val entriesByPath = manifest.resources.associateBy { it.detailUrl.removePrefix("./") }
            val contentProvider = ArchiveContentProvider { requested ->
                val normalized = normalizePath(requested)
                    ?: throw IllegalArgumentException("Invalid archive content path: $requested")
                val resolved = expandedRoot.resolve(normalized).normalize()
                require(resolved.startsWith(expandedRoot) && Files.isRegularFile(resolved)) {
                    "Archive content does not exist: $requested"
                }
                Files.newInputStream(resolved)
            }
            val migrationContext = CatalogMigrationContext(
                sourceVersion = sourceVersion,
                manifest = manifest,
                content = contentProvider,
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
                    migrationNotices += result.notices
                    result.value?.let { detail ->
                        // Keyed by what the manifest calls the resource, not by where its file
                        // sits. The two agree for anything written at the current wire version.
                        // They part company for a catalog-v6 archive holding images: its files are
                        // under `resources/asset/`, and a migration renames the type but cannot
                        // move the file. Nor can the v7-to-v8 migration, which pads or cuts a key
                        // outside the wire-v8 bounds (CatalogKeys.migrate): the manifest entry that
                        // declares this file carries the rewritten key.
                        val key = entriesByPath[path]?.key ?: path.removePrefix("resources/").removeSuffix(".json").substringAfterLast('/')
                        details["$declaredType/$key"] = detail
                    }
                }
            val carriedReleases = if (!carried && sourceVersion >= CARRIED_RELEASES_SINCE_WIRE_VERSION) {
                readCarried(manifest, extraction, expandedRoot, policy, findings, migrationNotices)
                    ?: run {
                        deleteRecursively(workspace)
                        return ReadOutcome(CatalogArchiveReadResult(null, findings.sorted()))
                    }
            } else {
                emptyList()
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
                dependencies = carriedReleases,
            ).also { it.sourceSchemaVersion = sourceVersion }
            return ReadOutcome(
                CatalogArchiveReadResult(
                    archive,
                    findings.sorted(),
                    migrationNotices.sortedWith(compareBy({ it.path }, { it.code }, { it.message })),
                ),
                extraction.ownExpanded,
            )
        } catch (exception: ZipException) {
            deleteRecursively(workspace)
            return ReadOutcome(
                CatalogArchiveReadResult(
                    null,
                    (findings + finding(ARCHIVE_INVALID, "archive", "archive is not a valid ZIP: ${exception.message}")).sorted(),
                ),
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
        limit: Long,
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
                    if (total > limit) {
                        findings += finding(
                            ARCHIVE_COMPRESSED_SIZE_EXCEEDED,
                            "archive",
                            "compressed archive exceeds $limit bytes",
                        )
                        return false
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
        return true
    }

    /**
     * Expands every entry. The archive's own content is held to the per-archive limits. Files under
     * `dependencies/` are, for an outer archive, releases it may carry: each is held to
     * [CatalogArchivePolicy.maxCompressedBytes] (its size is the carried archive's compressed size)
     * and their number to [CatalogArchivePolicy.maxDependencies]; the entries inside each are checked
     * when it is read. Whether they are interpreted depends on the wire version, known only once the
     * manifest is read.
     */
    private fun extract(
        archiveFile: Path,
        expandedRoot: Path,
        policy: CatalogArchivePolicy,
        carried: Boolean,
        findings: MutableList<ArchiveValidationFinding>,
    ): ExtractionResult {
        val seenPaths = linkedSetOf<String>()
        val filePaths = linkedSetOf<String>()
        val dependencyPaths = linkedSetOf<String>()
        var ownExpanded = 0L
        var dependencyExpanded = 0L
        var dependencyCompressed = 0L
        var entryCount = 0

        fun unsafe() = ExtractionResult(false, filePaths, dependencyPaths, ownExpanded, dependencyExpanded, dependencyCompressed, entryCount)
        ZipFile.builder().setFile(archiveFile.toFile()).get().use { zip ->
            val entries = zip.entries.asSequence().toList()
            for (entry in entries) {
                val encodedName = entry.rawName?.toString(StandardCharsets.UTF_8) ?: entry.name
                val originalName = if (entry.isDirectory) encodedName.removeSuffix("/") else encodedName
                val normalized = normalizePath(originalName)
                val isCarriedRelease = !carried &&
                    !entry.isDirectory &&
                    normalized != null &&
                    CatalogDependencyArchivePaths.isDependencyPath(normalized)
                if (isCarriedRelease) {
                    if (dependencyPaths.size + 1 > policy.maxDependencies) {
                        findings += finding(
                            ARCHIVE_DEPENDENCY_COUNT_EXCEEDED,
                            "archive",
                            "archive carries more than ${policy.maxDependencies} releases",
                        )
                        return unsafe()
                    }
                } else {
                    entryCount++
                    if (entryCount > policy.maxEntries) {
                        findings += finding(
                            ARCHIVE_ENTRY_COUNT_EXCEEDED,
                            "archive",
                            "archive contains more than ${policy.maxEntries} entries",
                        )
                        return unsafe()
                    }
                }
                if (normalized == null) {
                    findings += finding(ARCHIVE_PATH_INVALID, originalName, "archive entry path is unsafe")
                    return unsafe()
                }
                if (!seenPaths.add(normalized)) {
                    findings += finding(ARCHIVE_PATH_DUPLICATE, normalized, "archive contains a duplicate normalized path")
                    return unsafe()
                }
                if (entry.isUnixSymlink) {
                    findings += finding(ARCHIVE_SYMLINK_FORBIDDEN, normalized, "symbolic-link entries are forbidden")
                    return unsafe()
                }
                if (entry.generalPurposeBit.usesEncryption()) {
                    findings += finding(ARCHIVE_ENCRYPTION_FORBIDDEN, normalized, "encrypted entries are forbidden")
                    return unsafe()
                }
                if (entry.isDirectory) continue
                filePaths += normalized
                if (isCarriedRelease) {
                    dependencyPaths += normalized
                    dependencyCompressed += maxOf(entry.compressedSize, 0L)
                }
                val target = expandedRoot.resolve(normalized).normalize()
                if (!target.startsWith(expandedRoot)) {
                    findings += finding(ARCHIVE_PATH_INVALID, normalized, "archive entry escapes the extraction root")
                    return unsafe()
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
                            if (isCarriedRelease) {
                                dependencyExpanded += read
                                if (entryExpanded > policy.maxCompressedBytes) {
                                    findings += finding(
                                        ARCHIVE_COMPRESSED_SIZE_EXCEEDED,
                                        normalized,
                                        "carried archive exceeds ${policy.maxCompressedBytes} bytes",
                                    )
                                    return unsafe()
                                }
                            } else {
                                ownExpanded += read
                                if (ownExpanded > policy.maxExpandedBytes) {
                                    findings += finding(
                                        ARCHIVE_EXPANDED_SIZE_EXCEEDED,
                                        normalized,
                                        "expanded archive exceeds ${policy.maxExpandedBytes} bytes",
                                    )
                                    return unsafe()
                                }
                            }
                            if (ratio(entryExpanded, entry) > policy.maxExpansionRatio) {
                                findings += finding(
                                    ARCHIVE_EXPANSION_RATIO_EXCEEDED,
                                    normalized,
                                    "entry expansion ratio exceeds ${policy.maxExpansionRatio}:1",
                                )
                                return unsafe()
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
        }
        // The archive's own content, without the releases it carries, stays within the per-archive
        // compressed limit.
        if (Files.size(archiveFile) - dependencyCompressed > policy.maxCompressedBytes) {
            findings += finding(
                ARCHIVE_COMPRESSED_SIZE_EXCEEDED,
                "archive",
                "compressed archive exceeds ${policy.maxCompressedBytes} bytes",
            )
            return unsafe()
        }
        return ExtractionResult(true, filePaths, dependencyPaths, ownExpanded, dependencyExpanded, dependencyCompressed, entryCount)
    }

    /**
     * An archive read before wire v8 carries no releases, so the per-archive limits apply to all of
     * it, `dependencies/` included, exactly as before wire v8.
     */
    private fun legacyLimitFinding(
        archiveFile: Path,
        extraction: ExtractionResult,
        policy: CatalogArchivePolicy,
    ): ArchiveValidationFinding? = when {
        Files.size(archiveFile) > policy.maxCompressedBytes ->
            finding(ARCHIVE_COMPRESSED_SIZE_EXCEEDED, "archive", "compressed archive exceeds ${policy.maxCompressedBytes} bytes")
        extraction.entryCount + extraction.dependencyPaths.size > policy.maxEntries ->
            finding(ARCHIVE_ENTRY_COUNT_EXCEEDED, "archive", "archive contains more than ${policy.maxEntries} entries")
        extraction.ownExpanded + extraction.dependencyExpanded > policy.maxExpandedBytes ->
            finding(ARCHIVE_EXPANDED_SIZE_EXCEEDED, "archive", "expanded archive exceeds ${policy.maxExpandedBytes} bytes")
        else -> null
    }

    /**
     * Reads the releases a wire-v8 archive carries under `dependencies/` and checks them
     * ([CatalogArchiveDependencies]). Returns them in install order, or null when the whole archive
     * exceeds [CatalogArchivePolicy.maxTotalExpandedBytes]. Findings inside a carried release are
     * reported under its path (`dependencies/…/1.0.0.zip!/catalog.json`).
     */
    private fun readCarried(
        manifest: app.epistola.catalog.protocol.CatalogManifest,
        extraction: ExtractionResult,
        expandedRoot: Path,
        policy: CatalogArchivePolicy,
        findings: MutableList<ArchiveValidationFinding>,
        notices: MutableList<app.epistola.catalog.migration.CatalogMigrationNotice>,
    ): List<EmbeddedCatalogRelease>? {
        val carried = linkedMapOf<CatalogDependencyArchivePaths.Coordinates, CatalogArchive?>()
        var totalExpanded = extraction.ownExpanded
        try {
            for (path in extraction.dependencyPaths.sorted()) {
                val coordinates = CatalogDependencyArchivePaths.parse(path)
                if (coordinates == null) {
                    findings += finding(
                        ARCHIVE_DEPENDENCY_PATH_INVALID,
                        path,
                        "a file under dependencies/ must be dependencies/{registry}/{namespace}/{catalogKey}/{releaseVersion}.zip",
                    )
                    continue
                }
                // The path names the catalog: what a carried archive from before wire v8 does not say.
                val migration = CatalogMigrationOptions(namespace = coordinates.namespace, registry = coordinates.registry)
                val outcome = Files.newInputStream(expandedRoot.resolve(path)).use { read(it, policy, migration, carried = true) }
                findings += outcome.result.findings.map {
                    finding(it.code, CatalogArchiveDependencies.nested(path, it.path), it.message)
                }
                notices += outcome.result.migrationNotices.map { it.copy(path = CatalogArchiveDependencies.nested(path, it.path)) }
                carried[coordinates] = outcome.result.archive
                totalExpanded += outcome.expandedBytes
                if (totalExpanded > policy.maxTotalExpandedBytes) {
                    findings += finding(
                        ARCHIVE_EXPANDED_SIZE_EXCEEDED,
                        "archive",
                        "expanded archive and the releases it carries exceed ${policy.maxTotalExpandedBytes} bytes",
                    )
                    carried.values.forEach { it?.close() }
                    return null
                }
            }
            val checked = CatalogArchiveDependencies.check(manifest, carried)
            findings += checked.findings
            val exposed = checked.installOrder.map { coordinates ->
                EmbeddedCatalogRelease(
                    coordinates.registry,
                    coordinates.namespace,
                    coordinates.catalogKey,
                    coordinates.releaseVersion,
                    requireNotNull(carried[coordinates]),
                )
            }
            val exposedArchives = exposed.mapTo(mutableSetOf()) { it.archive }
            carried.values.filterNotNull().filter { it !in exposedArchives }.forEach { it.close() }
            return exposed
        } catch (exception: Exception) {
            carried.values.forEach { it?.close() }
            throw exception
        }
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

    private class ExtractionResult(
        val safe: Boolean,
        val paths: Set<String>,
        /** Files under `dependencies/`, for an outer archive; also in [paths]. */
        val dependencyPaths: Set<String>,
        val ownExpanded: Long,
        val dependencyExpanded: Long,
        val dependencyCompressed: Long,
        /** Entries counted against [CatalogArchivePolicy.maxEntries]. */
        val entryCount: Int,
    )

    private class ReadOutcome(
        val result: CatalogArchiveReadResult,
        /** Uncompressed bytes of the archive's own content. */
        val expandedBytes: Long = 0L,
    )

    /** The wire version from which an archive may carry the releases it pins. */
    private const val CARRIED_RELEASES_SINCE_WIRE_VERSION = 8

    private val DRIVE_PATH = Regex("^[A-Za-z]:.*")
}
