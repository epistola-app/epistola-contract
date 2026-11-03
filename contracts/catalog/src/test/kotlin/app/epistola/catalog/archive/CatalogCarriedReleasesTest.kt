// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.archive

import app.epistola.catalog.canonical.CatalogCanonicalizer
import app.epistola.catalog.canonical.CatalogFingerprintVersion
import app.epistola.catalog.migration.CatalogMigrationOptions
import app.epistola.catalog.protocol.CatalogDependencyArchivePaths
import app.epistola.catalog.validation.CatalogValidationPolicy
import app.epistola.catalog.validation.CatalogValidator
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jsonMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Catalog archives that carry the releases they pin (wire v8), held to the published fixtures. */
class CatalogCarriedReleasesTest {
    private val mapper = jsonMapper()
    private val index: JsonNode = fixture("carried-releases/carried-release-cases.json").let(mapper::readTree)

    @Test
    fun `every published case is read and validated as the fixture says`() {
        index["cases"].forEach { case ->
            val id = case["id"].asString()
            val bytes = outerArchive(case)
            val policy = policy(case["policy"])
            val expected = case["expected"]

            val report = CatalogValidator.validate(ByteArrayInputStream(bytes), CatalogValidationPolicy(archive = policy))
            assertEquals(expected["valid"].asBoolean(), report.valid, id)
            assertEquals(
                expected["findings"].items().map { it["code"].asString() to it["path"].asString() },
                report.findings.map { it.code to it.path },
                id,
            )

            val read = CatalogArchiveReader.read(ByteArrayInputStream(bytes), policy)
            read.archive.use { archive ->
                assertEquals(
                    expected["installOrder"].items().map { it.asString() },
                    archive?.dependencies.orEmpty().map { it.path },
                    id,
                )
            }
        }
    }

    @Test
    fun `every published path case follows the path rule`() {
        index["pathCases"]["valid"].forEach { assertTrue(CatalogDependencyArchivePaths.isValid(it.asString()), it.asString()) }
        index["pathCases"]["invalid"].forEach { assertFalse(CatalogDependencyArchivePaths.isValid(it.asString()), it.asString()) }
        // The npm package's CATALOG_DEPENDENCY_ARCHIVE_PATH_PATTERN is held to the same value.
        assertEquals(index["pathPattern"].asString(), CatalogDependencyArchivePaths.PATTERN)
        val defaults = CatalogArchivePolicy()
        assertEquals(index["defaultPolicy"]["maxDependencies"].asInt(), defaults.maxDependencies)
        assertEquals(index["defaultPolicy"]["maxTotalCompressedBytes"].asLong(), defaults.maxTotalCompressedBytes)
        assertEquals(index["defaultPolicy"]["maxTotalExpandedBytes"].asLong(), defaults.maxTotalExpandedBytes)
        val coordinates = assertNotNull(CatalogDependencyArchivePaths.parse("dependencies/exchange.epistola.app/acme/base/1.0.0.zip"))
        assertEquals(CatalogDependencyArchivePaths.Coordinates("exchange.epistola.app", "acme", "base", "1.0.0"), coordinates)
        assertEquals("dependencies/exchange.epistola.app/acme/base/1.0.0.zip", coordinates.path)
    }

    @Test
    fun `a release exported thin and with its dependencies has the same manifest bytes and fingerprint`() {
        val withDependencies = read(outerArchive(case("flat-transitive")))
        withDependencies.use { carrying ->
            assertEquals(2, carrying.dependencies.size)
            val thinBytes = write(CatalogArchive(carrying.manifest, carrying.resourceDetails, carrying.paths, carrying.content))
            val fatBytes = write(carrying)
            assertContentEquals(entry(thinBytes, "catalog.json"), entry(fatBytes, "catalog.json"))
            assertEquals(
                setOf(
                    "dependencies/exchange.epistola.app/acme/base/1.0.0.zip",
                    "dependencies/exchange.epistola.app/acme/shared/1.0.0.zip",
                ),
                entryNames(fatBytes).filter { it.startsWith("dependencies/") }.toSet(),
            )
            read(thinBytes).use { thin ->
                read(fatBytes).use { fat ->
                    assertEquals(CatalogCanonicalizer.currentQualifiedFingerprint(thin), CatalogCanonicalizer.currentQualifiedFingerprint(fat))
                    assertEquals(carrying.manifest.release.fingerprint, CatalogCanonicalizer.currentQualifiedFingerprint(fat).toString())
                    assertEquals(listOf("base", "shared"), fat.dependencies.map { it.catalogKey })
                }
            }
            // The writer is deterministic with carried releases too.
            assertContentEquals(fatBytes, write(carrying))
        }
    }

    @Test
    fun `a carried release is fingerprinted with V6, and one that pins nothing keeps the V5 value it was published with`() {
        // Published as v5:<hex> before V6 existed; aliases added to the fixtures move neither.
        mapOf(
            "base-1.0.0" to "v5:04625ca6d3e808b5a05e50513e0934f0db5e5b76bb6498364bc42b1f8691fc9d",
            "base-2.0.0" to "v5:fea0a290719328605f1d45ec16d55e0f14746c48e0d46f41c7829719aefdf14b",
        ).forEach { (name, v5) ->
            read(zip(*catalogEntries(name).toTypedArray())).use { release ->
                assertEquals(v5, CatalogCanonicalizer.qualifiedFingerprint(release, CatalogFingerprintVersion.V5).toString(), name)
                assertEquals(release.manifest.release.fingerprint, CatalogCanonicalizer.currentQualifiedFingerprint(release).toString(), name)
                assertTrue(release.manifest.release.fingerprint!!.startsWith("v6:"), name)
            }
        }
    }

    @Test
    fun `the writer refuses a carried release that carries releases itself or is not the release it claims`() {
        read(outerArchive(case("flat-transitive"))).use { carrying ->
            val base = carrying.dependencies.first { it.catalogKey == "base" }
            val shared = carrying.dependencies.first { it.catalogKey == "shared" }
            val nestedShared = CatalogArchive(
                shared.archive.manifest,
                shared.archive.resourceDetails,
                shared.archive.paths,
                shared.archive.content,
                dependencies = listOf(base),
            )
            val nested = EmbeddedCatalogRelease("exchange.epistola.app", "acme", "shared", "1.0.0", nestedShared)
            assertTrue(runCatching { write(withDependencies(carrying, listOf(nested))) }.exceptionOrNull() is IllegalArgumentException)
            val misnamed = EmbeddedCatalogRelease("exchange.epistola.app", "acme", "base", "2.0.0", base.archive)
            assertTrue(runCatching { write(withDependencies(carrying, listOf(misnamed))) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun `the validator applies the same rules to an archive assembled in memory`() {
        read(outerArchive(case("flat-transitive"))).use { carrying ->
            assertTrue(CatalogValidator.validate(carrying).valid)
            val base = carrying.dependencies.first { it.catalogKey == "base" }
            val stray = EmbeddedCatalogRelease("exchange.epistola.app", "acme", "other", "1.0.0", base.archive)
            val report = CatalogValidator.validate(withDependencies(carrying, carrying.dependencies + stray))
            val codes = report.findings.map { it.code to it.path }
            assertTrue(ArchiveValidationCodes.ARCHIVE_DEPENDENCY_UNREFERENCED to stray.path in codes, codes.toString())
            assertTrue(ArchiveValidationCodes.ARCHIVE_DEPENDENCY_IDENTITY_MISMATCH to "${stray.path}!/catalog.json" in codes, codes.toString())
        }
    }

    @Test
    fun `an archive from before wire v8 carries no releases and its dependencies directory is left alone`() {
        val bytes = zip(
            "catalog.json" to fixtureBytes("wire-v7/catalog.json"),
            "resources/theme/default.json" to fixtureBytes("wire-v7/resources/theme/default.json"),
            "dependencies/notes.txt" to "anything".toByteArray(),
        )
        val result = CatalogArchiveReader.read(
            ByteArrayInputStream(bytes),
            migration = CatalogMigrationOptions(namespace = "acme", registry = "exchange.epistola.app"),
        )
        result.archive.use { archive ->
            assertNotNull(archive)
            assertTrue(result.findings.isEmpty(), result.findings.toString())
            assertTrue(archive.dependencies.isEmpty())
            assertTrue("dependencies/notes.txt" in archive.paths)
        }
    }

    @Test
    fun `the total limits bound an archive with the releases it carries`() {
        val bytes = outerArchive(case("flat-transitive"))
        val expanded = CatalogArchiveReader.read(ByteArrayInputStream(bytes), CatalogArchivePolicy(maxTotalExpandedBytes = 1500))
        assertEquals(null, expanded.archive)
        assertTrue(expanded.findings.any { it.code == ArchiveValidationCodes.ARCHIVE_EXPANDED_SIZE_EXCEEDED && it.path == "archive" })

        // A carried release is held to the per-archive compressed limit on its own.
        val noise = ByteArray(8_000).also { java.util.Random(1).nextBytes(it) }
        val large = zip(*(catalogEntries("base-1.0.0") + ("noise.bin" to noise)).toTypedArray())
        val outer = zip(
            *(
                catalogEntries("letters-1.0.0") + catalogEntries("shared-1.0.0").let { listOf("dependencies/exchange.epistola.app/acme/shared/1.0.0.zip" to zip(*it.toTypedArray())) } +
                    ("dependencies/exchange.epistola.app/acme/base/1.0.0.zip" to large)
                ).toTypedArray(),
        )
        val oneCarriedTooLarge = CatalogArchiveReader.read(ByteArrayInputStream(outer), CatalogArchivePolicy(maxCompressedBytes = 6_000))
        assertEquals(
            listOf(ArchiveValidationCodes.ARCHIVE_COMPRESSED_SIZE_EXCEEDED to "dependencies/exchange.epistola.app/acme/base/1.0.0.zip"),
            oneCarriedTooLarge.findings.map { it.code to it.path },
        )

        val total = CatalogArchiveReader.read(ByteArrayInputStream(bytes), CatalogArchivePolicy(maxTotalCompressedBytes = 100))
        // The input limit is the larger of the per-archive and the total limit.
        assertTrue(total.valid, total.findings.toString())
        val both = CatalogArchiveReader.read(ByteArrayInputStream(bytes), CatalogArchivePolicy(maxCompressedBytes = 100, maxTotalCompressedBytes = 100))
        assertTrue(both.findings.any { it.code == ArchiveValidationCodes.ARCHIVE_COMPRESSED_SIZE_EXCEEDED && it.path == "archive" })
        total.archive?.close()
    }

    private fun JsonNode.items(): List<JsonNode> = (this as Iterable<JsonNode>).toList()

    private fun withDependencies(
        archive: CatalogArchive,
        dependencies: List<EmbeddedCatalogRelease>,
    ) = CatalogArchive(archive.manifest, archive.resourceDetails, archive.paths, archive.content, dependencies = dependencies)

    private fun case(id: String): JsonNode = index["cases"].first { it["id"].asString() == id }

    private fun policy(node: JsonNode?): CatalogArchivePolicy {
        val defaults = CatalogArchivePolicy()
        if (node == null || node.isNull) return defaults
        return defaults.copy(maxDependencies = node["maxDependencies"]?.asInt() ?: defaults.maxDependencies)
    }

    /** The case's catalog, with each carried release and extra file, as a ZIP. */
    private fun outerArchive(case: JsonNode): ByteArray {
        val entries = catalogEntries(case["catalog"].asString()).toMutableList()
        entries += carriedEntries(case["carries"])
        case["extraFiles"]?.properties()?.forEach { (path, text) -> entries += path to text.asString().toByteArray() }
        return zip(*entries.toTypedArray())
    }

    private fun carriedEntries(carries: JsonNode?): List<Pair<String, ByteArray>> = carries?.items()?.map { carried ->
        val inner = catalogEntries(carried["catalog"].asString()) + carriedEntries(carried["carries"])
        carried["path"].asString() to zip(*inner.toTypedArray())
    }.orEmpty()

    private fun catalogEntries(name: String): List<Pair<String, ByteArray>> {
        val root = index["catalogsRoot"].asString()
        return index["catalogs"][name].items().map { file -> file.asString() to fixtureBytes("$root/$name/${file.asString()}") }
    }

    private fun read(bytes: ByteArray): CatalogArchive {
        val result = CatalogArchiveReader.read(ByteArrayInputStream(bytes))
        assertTrue(result.valid, result.findings.toString())
        return assertNotNull(result.archive)
    }

    private fun write(catalog: CatalogArchive): ByteArray = ByteArrayOutputStream().also { CatalogArchiveWriter.write(catalog, it) }.toByteArray()

    private fun entry(
        zip: ByteArray,
        name: String,
    ): ByteArray = ZipFile.builder().setSeekableByteChannel(SeekableInMemoryByteChannel(zip)).get().use { file ->
        file.getInputStream(file.getEntry(name)).use { it.readAllBytes() }
    }

    private fun entryNames(zip: ByteArray): List<String> = ZipFile.builder().setSeekableByteChannel(SeekableInMemoryByteChannel(zip)).get().use { file ->
        file.entries.asSequence().map { it.name }.toList()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipArchiveOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putArchiveEntry(ZipArchiveEntry(name))
                zip.write(bytes)
                zip.closeArchiveEntry()
            }
        }
    }.toByteArray()

    private fun fixtureBytes(path: String): ByteArray = fixture(path)

    private fun fixture(path: String): ByteArray = requireNotNull(
        javaClass.getResourceAsStream("/META-INF/epistola-catalog/fixtures/v1/$path"),
    ) { "no fixture $path" }.use { it.readAllBytes() }
}
