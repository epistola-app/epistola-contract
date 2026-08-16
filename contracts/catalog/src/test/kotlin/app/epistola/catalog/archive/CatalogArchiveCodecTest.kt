// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.archive

import app.epistola.catalog.canonical.CatalogCanonicalizer
import app.epistola.catalog.canonical.CatalogFingerprintVersion
import app.epistola.catalog.protocol.AssetResource
import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.PublisherInfo
import app.epistola.catalog.protocol.ReleaseInfo
import app.epistola.catalog.protocol.ResourceDetail
import app.epistola.catalog.protocol.ResourceEntry
import app.epistola.catalog.protocol.ThemeResource
import app.epistola.catalog.validation.CatalogValidator
import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import tools.jackson.module.kotlin.jsonMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogArchiveCodecTest {
    @Test
    fun `writer rejects non-current catalog versions`() {
        val current = validCatalog()
        val legacy = CatalogArchive(
            manifest = current.manifest.copy(schemaVersion = 4),
            resourceDetails = current.resourceDetails.mapValues { (_, detail) -> detail.copy(schemaVersion = 4) },
            paths = current.paths,
            content = current.content,
        )

        assertFailsWith<IllegalArgumentException> { write(legacy) }
    }

    @Test
    fun `finding code registry covers every stable archive finding code`() {
        val fixtureCodes = requireNotNull(
            javaClass.getResourceAsStream("/META-INF/epistola-catalog/fixtures/v1/archive-validation-cases.json"),
        ).use(jsonMapper()::readTree).propertyNames().toSet()
        val publishedCodes = ArchiveValidationCodes::class.java.declaredFields
            .filter { it.type == String::class.java }
            .map { it.get(null) as String }
            .toSet()

        assertEquals(publishedCodes, fixtureCodes)
    }

    @Test
    fun `path normalization rejects absolute drive backslash traversal and nul paths`() {
        assertNull(CatalogArchiveReader.normalizePath("/catalog.json"))
        assertNull(CatalogArchiveReader.normalizePath("C:/catalog.json"))
        assertNull(CatalogArchiveReader.normalizePath("resources\\asset\\x"))
        assertNull(CatalogArchiveReader.normalizePath("resources/../catalog.json"))
        assertNull(CatalogArchiveReader.normalizePath("resources/\u0000asset"))
    }

    @Test
    fun `writer output is deterministic and reader round trips it`() {
        val first = write(validCatalog())
        val second = write(validCatalog())

        assertContentEquals(first, second)
        val result = CatalogArchiveReader.read(ByteArrayInputStream(first))
        val archive = assertNotNull(result.archive)
        archive.use {
            assertTrue(result.findings.isEmpty())
            assertEquals("example", archive.manifest.catalog.slug)
            assertEquals(setOf("catalog.json", "resources/theme/default.json"), archive.paths)
            assertEquals("default", archive.resourceDetails.getValue("theme/default").resource.slug)
        }
    }

    @Test
    fun `re-exporting a migrated catalog replaces its legacy fingerprint with v4`() {
        val legacyZip = zip(
            "catalog.json" to fixtureBytes("wire-v5/catalog.json"),
            "resources/theme/default.json" to fixtureBytes("wire-v5/resources/theme/default.json"),
        )
        val migrated = assertNotNull(CatalogArchiveReader.read(ByteArrayInputStream(legacyZip)).archive)
        migrated.use { source ->
            val legacyFingerprint = CatalogCanonicalizer.fingerprint(source, CatalogFingerprintVersion.V3).value
            val withLegacyFingerprint = CatalogArchive(
                manifest = source.manifest.copy(release = source.manifest.release.copy(fingerprint = legacyFingerprint)),
                resourceDetails = source.resourceDetails,
                paths = source.paths,
                content = source.content,
            ).also { it.sourceSchemaVersion = 5 }

            CatalogArchiveReader.read(ByteArrayInputStream(write(withLegacyFingerprint))).archive!!.use { rewritten ->
                assertEquals(6, rewritten.sourceSchemaVersion)
                assertEquals(CatalogCanonicalizer.currentFingerprint(rewritten).value, rewritten.manifest.release.fingerprint)
                assertTrue(CatalogValidator.validate(rewritten).valid)
            }
        }
    }

    @Test
    fun `reader accepts explicit directories but exposes regular files only`() {
        val input = zip(
            "resources/" to byteArrayOf(),
            "resources/theme/" to byteArrayOf(),
            "catalog.json" to fixtureBytes("wire-v4/catalog.json"),
            "resources/theme/default.json" to fixtureBytes("wire-v4/resources/theme/default.json"),
        )

        val result = CatalogArchiveReader.read(ByteArrayInputStream(input))
        val archive = assertNotNull(result.archive)
        archive.use {
            assertTrue(result.findings.isEmpty())
            assertEquals(
                setOf("catalog.json", "resources/theme/default.json"),
                archive.paths,
            )
        }
    }

    @Test
    fun `writer streams deterministic asset content and reader exposes it`() {
        val content = "portable asset".toByteArray()
        val first = write(assetCatalog("./assets/logo.bin", content))
        val second = write(assetCatalog("./assets/logo.bin", content))

        assertContentEquals(first, second)
        CatalogArchiveReader.read(ByteArrayInputStream(first)).archive!!.use { archive ->
            assertEquals(
                setOf("assets/logo.bin", "catalog.json", "resources/asset/logo.json"),
                archive.paths,
            )
            assertContentEquals(
                content,
                archive.content.open("assets/logo.bin").use { it.readAllBytes() },
            )
        }
    }

    @Test
    fun `writer rejects unsafe asset paths and configured output limits`() {
        assertFailsWith<IllegalArgumentException> {
            write(assetCatalog("../logo.bin", byteArrayOf(1)))
        }
        assertFailsWith<IllegalArgumentException> {
            write(validCatalog(), CatalogArchivePolicy(maxEntries = 1))
        }
        assertFailsWith<IllegalArgumentException> {
            write(validCatalog(), CatalogArchivePolicy(maxExpandedBytes = 1))
        }
        assertFailsWith<IllegalArgumentException> {
            write(validCatalog(), CatalogArchivePolicy(maxCompressedBytes = 1))
        }
    }

    @Test
    fun `reader rejects unsafe paths and duplicate normalized paths`() {
        val traversal = CatalogArchiveReader.read(ByteArrayInputStream(zip("../catalog.json" to "{}".toByteArray())))
        val backslashBytes = zip("resources/asset/x" to byteArrayOf(1)).copyOf().also { bytes ->
            bytes.indices.filter { bytes[it] == '/'.code.toByte() }.forEach { bytes[it] = '\\'.code.toByte() }
        }
        val backslash = CatalogArchiveReader.read(ByteArrayInputStream(backslashBytes))
        val duplicate = CatalogArchiveReader.read(
            ByteArrayInputStream(zip("catalog.json" to "{}".toByteArray(), "catalog.json" to "{}".toByteArray())),
        )

        assertCode(traversal, ArchiveValidationCodes.ARCHIVE_PATH_INVALID)
        assertCode(backslash, ArchiveValidationCodes.ARCHIVE_PATH_INVALID)
        assertCode(duplicate, ArchiveValidationCodes.ARCHIVE_PATH_DUPLICATE)
    }

    @Test
    fun `reader rejects symlink and encrypted entries`() {
        val symlink = zipEntry("catalog.json", "{}".toByteArray(), UnixStat.LINK_FLAG or 0b111101101)
        val encrypted = markEncrypted(zip("catalog.json" to "{}".toByteArray()))

        assertCode(CatalogArchiveReader.read(ByteArrayInputStream(symlink)), ArchiveValidationCodes.ARCHIVE_SYMLINK_FORBIDDEN)
        assertCode(CatalogArchiveReader.read(ByteArrayInputStream(encrypted)), ArchiveValidationCodes.ARCHIVE_ENCRYPTION_FORBIDDEN)
    }

    @Test
    fun `reader enforces entry compressed expanded and ratio limits`() {
        val twoEntries = zip("catalog.json" to "{}".toByteArray(), "extra" to byteArrayOf(1))
        val expanded = zip("catalog.json" to ByteArray(256) { 1 })
        val ratio = zip("catalog.json" to ByteArray(10_000))

        assertCode(
            CatalogArchiveReader.read(ByteArrayInputStream(twoEntries), CatalogArchivePolicy(maxEntries = 1)),
            ArchiveValidationCodes.ARCHIVE_ENTRY_COUNT_EXCEEDED,
        )
        assertCode(
            CatalogArchiveReader.read(ByteArrayInputStream(twoEntries), CatalogArchivePolicy(maxCompressedBytes = 10)),
            ArchiveValidationCodes.ARCHIVE_COMPRESSED_SIZE_EXCEEDED,
        )
        assertCode(
            CatalogArchiveReader.read(ByteArrayInputStream(expanded), CatalogArchivePolicy(maxExpandedBytes = 100)),
            ArchiveValidationCodes.ARCHIVE_EXPANDED_SIZE_EXCEEDED,
        )
        assertCode(
            CatalogArchiveReader.read(ByteArrayInputStream(ratio), CatalogArchivePolicy(maxExpansionRatio = 2.0)),
            ArchiveValidationCodes.ARCHIVE_EXPANSION_RATIO_EXCEEDED,
        )
    }

    @Test
    fun `reader reports required and malformed documents`() {
        val invalid = CatalogArchiveReader.read(ByteArrayInputStream("not a zip".toByteArray()))
        val missing = CatalogArchiveReader.read(ByteArrayInputStream(zip("other" to byteArrayOf(1))))
        val malformed = CatalogArchiveReader.read(ByteArrayInputStream(zip("catalog.json" to "{".toByteArray())))

        assertCode(invalid, ArchiveValidationCodes.ARCHIVE_INVALID)
        assertCode(missing, ArchiveValidationCodes.ARCHIVE_REQUIRED_FILE_MISSING)
        assertCode(malformed, ArchiveValidationCodes.ARCHIVE_DOCUMENT_MALFORMED)
        assertNull(malformed.archive)
    }

    private fun validCatalog(): CatalogArchive {
        val detail = ResourceDetail(6, ThemeResource(slug = "default", name = "Default"))
        val manifest = CatalogManifest(
            schemaVersion = 6,
            catalog = CatalogInfo("example", "Example"),
            publisher = PublisherInfo("Example"),
            release = ReleaseInfo("1.0.0"),
            resources = listOf(
                ResourceEntry(
                    type = "theme",
                    slug = "default",
                    name = "Default",
                    detailUrl = "./resources/theme/default.json",
                ),
            ),
        )
        return CatalogArchive(
            manifest = manifest,
            resourceDetails = mapOf("theme/default" to detail),
            paths = emptySet(),
            content = ArchiveContentProvider { error("No binary content in fixture") },
        )
    }

    private fun assetCatalog(
        contentUrl: String,
        bytes: ByteArray,
    ): CatalogArchive {
        val detail = ResourceDetail(
            6,
            AssetResource(
                slug = "logo",
                name = "Logo",
                mediaType = "application/octet-stream",
                contentUrl = contentUrl,
            ),
        )
        val manifest = CatalogManifest(
            schemaVersion = 6,
            catalog = CatalogInfo("example", "Example"),
            publisher = PublisherInfo("Example"),
            release = ReleaseInfo("1.0.0"),
            resources = listOf(
                ResourceEntry(
                    type = "asset",
                    slug = "logo",
                    name = "Logo",
                    detailUrl = "./resources/asset/logo.json",
                ),
            ),
        )
        return CatalogArchive(
            manifest = manifest,
            resourceDetails = mapOf("asset/logo" to detail),
            paths = setOf("assets/logo.bin"),
            content = ArchiveContentProvider { ByteArrayInputStream(bytes) },
        )
    }

    private fun write(
        catalog: CatalogArchive,
        policy: CatalogArchivePolicy = CatalogArchivePolicy(),
    ): ByteArray = ByteArrayOutputStream().also { output ->
        CatalogArchiveWriter.write(catalog, output, policy)
    }.toByteArray()

    private fun assertCode(
        result: CatalogArchiveReadResult,
        code: String,
    ) {
        assertNull(result.archive)
        assertTrue(result.findings.any { it.code == code }, "Expected $code; got ${result.findings}")
    }

    private fun fixtureBytes(path: String): ByteArray = requireNotNull(
        javaClass.getResourceAsStream("/META-INF/epistola-catalog/fixtures/v1/$path"),
    ).use { it.readAllBytes() }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipArchiveOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putArchiveEntry(ZipArchiveEntry(name))
                zip.write(bytes)
                zip.closeArchiveEntry()
            }
        }
    }.toByteArray()

    private fun zipEntry(
        name: String,
        bytes: ByteArray,
        unixMode: Int,
    ): ByteArray = ByteArrayOutputStream().also { output ->
        ZipArchiveOutputStream(output).use { zip ->
            val entry = ZipArchiveEntry(name).apply {
                method = ZipEntry.DEFLATED
                this.unixMode = unixMode
            }
            zip.putArchiveEntry(entry)
            zip.write(bytes)
            zip.closeArchiveEntry()
        }
    }.toByteArray()

    private fun markEncrypted(zip: ByteArray): ByteArray = zip.copyOf().also { bytes ->
        var index = 0
        while (index <= bytes.size - 4) {
            val signature = littleEndianInt(bytes, index)
            val flagOffset = when (signature) {
                0x04034b50 -> index + 6
                0x02014b50 -> index + 8
                else -> null
            }
            if (flagOffset != null) {
                bytes[flagOffset] = (bytes[flagOffset].toInt() or 1).toByte()
            }
            index++
        }
    }

    private fun littleEndianInt(
        bytes: ByteArray,
        offset: Int,
    ): Int = (bytes[offset].toInt() and 0xff) or
        ((bytes[offset + 1].toInt() and 0xff) shl 8) or
        ((bytes[offset + 2].toInt() and 0xff) shl 16) or
        ((bytes[offset + 3].toInt() and 0xff) shl 24)
}
