// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.archive

import app.epistola.catalog.protocol.AssetResource
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
    private val mapper = jsonMapper {
        addModule(kotlinModule())
        enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
    }

    /**
     * Writes [catalog] to [output] subject to [policy].
     *
     * The writer emits `catalog.json`, every resource detail, and every asset
     * referenced by an [AssetResource]. [output] is closed when ZIP encoding
     * completes or fails.
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
        val jsonEntries = buildMap {
            put("catalog.json", mapper.writeValueAsBytes(catalog.manifest))
            catalog.resourceDetails.toSortedMap().forEach { (key, detail) ->
                put("resources/$key.json", mapper.writeValueAsBytes(detail))
            }
        }
        val assets = catalog.resourceDetails.values
            .mapNotNull { it.resource as? AssetResource }
            .map { asset -> normalizedContentPath(asset.contentUrl) }
            .distinct()
            .sorted()
        val paths = (jsonEntries.keys + assets).sorted()
        require(paths.size <= policy.maxEntries) { "catalog archive would exceed ${policy.maxEntries} entries" }

        var expanded = 0L
        ZipArchiveOutputStream(BufferedOutputStream(BoundedOutputStream(output, policy.maxCompressedBytes))).use { zip ->
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
                if (bytes != null) {
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
