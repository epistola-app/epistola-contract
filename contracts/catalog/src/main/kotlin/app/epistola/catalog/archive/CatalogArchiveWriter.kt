// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.archive

import app.epistola.catalog.canonical.CatalogCanonicalizer
import app.epistola.catalog.migration.CatalogWireSchema
import app.epistola.catalog.protocol.FontResource
import app.epistola.catalog.protocol.ImageResource
import com.fasterxml.jackson.annotation.JsonInclude
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.SerializationFeature
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.io.BufferedOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry

/**
 * Deterministic encoder for portable catalog archives.
 *
 * Entries are written in normalized lexical order with stable timestamps,
 * regular-file Unix modes, UTF-8 names, and a stable compression level.
 * Binary assets are copied from [CatalogArchive.content] without loading them
 * in full. Fingerprints deliberately do not depend on these ZIP bytes; use
 * [app.epistola.catalog.canonical.CatalogCanonicalizer] for content identity.
 */
object CatalogArchiveWriter {
    // A null property is left out rather than written as `null`, which the published schemas do
    // not allow for most members; a reader takes the two alike. Only properties: a `null` inside
    // free-form content (a data example, a data model) is a value and stays. The fingerprint is
    // computed over the bound model, not these bytes, so it is the same either way.
    private val mapper = jsonMapper {
        addModule(kotlinModule())
        enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        changeDefaultPropertyInclusion { it.withValueInclusion(JsonInclude.Include.NON_NULL) }
    }

    /**
     * Writes [catalog] to [output] subject to [policy].
     *
     * The writer emits `catalog.json`, every resource detail, and every asset
     * referenced by an [AssetResource]. [output] is closed when ZIP encoding
     * completes or fails.
     *
     * When [CatalogArchive.dependencies] is not empty, each carried release is
     * written, thin, at `dependencies/{registry}/{namespace}/{catalogKey}/{releaseVersion}.zip`
     * (wire v8). They change neither `catalog.json` nor the fingerprint: an
     * archive written with its dependencies and one written without have the
     * same manifest bytes. The archive's own content stays within the
     * per-archive limits, each carried release within them too, and the whole
     * within [CatalogArchivePolicy.maxTotalCompressedBytes].
     *
     * @throws IllegalArgumentException when limits are invalid or exceeded,
     *   an asset path is unsafe, or referenced content cannot be provided.
     * @throws java.io.IOException when an input asset or [output] fails.
     */
    fun write(
        catalog: CatalogArchive,
        output: OutputStream,
        policy: CatalogArchivePolicy = CatalogArchivePolicy(),
    ) {
        require(policy.maxExpandedBytes > 0)
        require(catalog.manifest.schemaVersion == CatalogWireSchema.CURRENT_VERSION) {
            "catalog archive writer only emits schemaVersion ${CatalogWireSchema.CURRENT_VERSION}"
        }
        require(catalog.resourceDetails.values.all { it.schemaVersion == CatalogWireSchema.CURRENT_VERSION }) {
            "catalog resource details must all use schemaVersion ${CatalogWireSchema.CURRENT_VERSION}"
        }
        val fingerprinted = if (catalog.sourceSchemaVersion < CatalogWireSchema.CURRENT_VERSION && catalog.manifest.release.fingerprint != null) {
            catalog.manifest.copy(
                release = catalog.manifest.release.copy(
                    fingerprint = CatalogCanonicalizer.currentQualifiedFingerprint(catalog).toString(),
                ),
            )
        } else {
            catalog.manifest
        }
        // A detail is written where its key files it. A migrated catalog may still declare where
        // its source filed it -- under a key the v7-to-v8 migration padded or cut -- so the entry is
        // pointed at the file this writer actually writes.
        val manifest = fingerprinted.copy(
            resources = fingerprinted.resources.map { entry ->
                val written = "resources/${entry.type}/${entry.key}.json"
                if ("${entry.type}/${entry.key}" in catalog.resourceDetails && entry.detailUrl.removePrefix("./") != written) {
                    entry.copy(detailUrl = "./$written")
                } else {
                    entry
                }
            },
        )
        require(catalog.dependencies.size <= policy.maxDependencies) {
            "catalog archive would carry more than ${policy.maxDependencies} releases"
        }
        val carried = catalog.dependencies.associate { release ->
            require(release.archive.dependencies.isEmpty()) {
                "carried release $release carries releases itself; carried releases are flat"
            }
            val identity = release.archive.manifest
            require(
                identity.catalog.registry == release.registry &&
                    identity.catalog.namespace == release.namespace &&
                    identity.catalog.key == release.catalogKey &&
                    identity.release.version == release.releaseVersion,
            ) { "carried archive at ${release.path} is not release $release" }
            val bytes = java.io.ByteArrayOutputStream().also { write(release.archive, it, policy) }.toByteArray()
            release.path to bytes
        }
        require(carried.size == catalog.dependencies.size) { "a release is carried more than once" }
        val jsonEntries = buildMap {
            put("catalog.json", mapper.writeValueAsBytes(manifest))
            catalog.resourceDetails.toSortedMap().forEach { (key, detail) ->
                put("resources/$key.json", mapper.writeValueAsBytes(detail))
            }
        }
        // Every binary the catalog carries: an image's own bytes, and each font face's. A face
        // holds its binary directly since wire v7, so the writer no longer finds them all by
        // walking one resource type.
        val binaries = catalog.resourceDetails.values
            .flatMap { detail ->
                when (val resource = detail.resource) {
                    is ImageResource -> listOf(resource.contentPath())
                    is FontResource -> resource.variants.map { it.contentPath() }
                    else -> emptyList()
                }
            }
            .map(::normalizedContentPath)
            .distinct()
            .sorted()
        val ownPaths = (jsonEntries.keys + binaries)
        require(ownPaths.size <= policy.maxEntries) { "catalog archive would exceed ${policy.maxEntries} entries" }
        require(ownPaths.none { it.startsWith(app.epistola.catalog.protocol.CatalogDependencyArchivePaths.DIRECTORY) }) {
            "a catalog's own content may not be under ${app.epistola.catalog.protocol.CatalogDependencyArchivePaths.DIRECTORY}"
        }
        val paths = (ownPaths + carried.keys).sorted()
        val outputLimit = if (carried.isEmpty()) policy.maxCompressedBytes else policy.maxTotalCompressedBytes

        var expanded = 0L
        ZipArchiveOutputStream(BufferedOutputStream(BoundedOutputStream(output, outputLimit))).use { zip ->
            zip.setEncoding("UTF-8")
            zip.setUseZip64(Zip64Mode.AsNeeded)
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            paths.forEach { path ->
                val entry = ZipArchiveEntry(path).apply {
                    time = 0L
                    method = ZipEntry.DEFLATED
                    unixMode = REGULAR_FILE_MODE
                }
                zip.putArchiveEntry(entry)
                val bytes = jsonEntries[path]
                val carriedBytes = carried[path]
                if (carriedBytes != null) {
                    zip.write(carriedBytes)
                } else if (bytes != null) {
                    expanded += bytes.size
                    require(expanded <= policy.maxExpandedBytes) {
                        "catalog archive content exceeds ${policy.maxExpandedBytes} bytes"
                    }
                    zip.write(bytes)
                } else {
                    catalog.content.open(path).use { source ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = source.read(buffer)
                            if (read < 0) break
                            expanded += read
                            require(expanded <= policy.maxExpandedBytes) {
                                "catalog archive content exceeds ${policy.maxExpandedBytes} bytes"
                            }
                            zip.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeArchiveEntry()
            }
            zip.finish()
        }
    }

    private fun normalizedContentPath(value: String): String {
        val withoutRelativePrefix = value.removePrefix("./")
        return requireNotNull(CatalogArchiveReader.normalizePath(withoutRelativePrefix)) {
            "Asset contentUrl is not a safe archive path: $value"
        }
    }

    private const val REGULAR_FILE_MODE = 0b1000000110100100

    private class BoundedOutputStream(
        output: OutputStream,
        private val maximum: Long,
    ) : FilterOutputStream(output) {
        private var written = 0L

        override fun write(value: Int) {
            require(++written <= maximum) { "compressed catalog archive exceeds $maximum bytes" }
            out.write(value)
        }

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            written += length
            require(written <= maximum) { "compressed catalog archive exceeds $maximum bytes" }
            out.write(bytes, offset, length)
        }
    }
}
