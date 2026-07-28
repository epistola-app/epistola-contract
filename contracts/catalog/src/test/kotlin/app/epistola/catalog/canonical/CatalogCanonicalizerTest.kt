// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.canonical

import app.epistola.catalog.archive.ArchiveContentProvider
import app.epistola.catalog.archive.CatalogArchive
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.CompatibilityInfo
import app.epistola.catalog.protocol.IncludeEntry
import app.epistola.catalog.protocol.ResourceDetail
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CatalogCanonicalizerTest {
    private val mapper = jsonMapper { addModule(kotlinModule()) }

    @Test
    fun `golden canonical fingerprints match`() {
        val archive = goldenArchive()
        val expected = resource("fingerprints.json").use(mapper::readTree)

        assertEquals(
            expected["catalogFingerprint"].asString(),
            CatalogCanonicalizer.fingerprint(archive).value,
        )
        assertEquals(
            expected["currentCatalogFingerprint"].asString(),
            CatalogCanonicalizer.currentFingerprint(archive).value,
        )
        assertEquals(
            expected["resourceFingerprints"]["theme/default"].asString(),
            CatalogCanonicalizer.perResourceFingerprints(archive).getValue("theme/default"),
        )
    }

    @Test
    fun `fingerprint ignores release metadata manifest URLs and JSON key order`() {
        val original = goldenArchive()
        val changedManifest = original.manifest.copy(
            release = original.manifest.release.copy(version = "9.9.9", releasedAt = "2099-01-01T00:00:00Z"),
            resources = original.manifest.resources.map { it.copy(detailUrl = "ignored/layout.json", updatedAt = "now") },
        )
        val reorderedDetail = """
            {"resource":{"spacingUnit":1.20,"slug":"default","type":"theme","name":"Default",
            "pageSettings":null,"documentStyles":{"color":"#111111","fontSize":"10pt"},
            "description":null,"blockStylePresets":null},"schemaVersion":4}
        """.trimIndent().toByteArray()
        val reordered = CatalogArchive(
            manifest = changedManifest,
            resourceDetails = original.resourceDetails,
            paths = original.paths,
            content = ArchiveContentProvider { path ->
                if (path.endsWith("default.json")) ByteArrayInputStream(reorderedDetail) else error(path)
            },
        )

        assertEquals(CatalogCanonicalizer.currentFingerprint(original), CatalogCanonicalizer.currentFingerprint(reordered))
        assertEquals(
            CatalogCanonicalizer.perResourceFingerprints(original),
            CatalogCanonicalizer.perResourceFingerprints(reordered),
        )
    }

    @Test
    fun `asset content participates in resource and catalog fingerprints`() {
        val first = assetArchive("first".toByteArray())
        val second = assetArchive("second".toByteArray())

        assertNotEquals(CatalogCanonicalizer.currentFingerprint(first), CatalogCanonicalizer.currentFingerprint(second))
        assertNotEquals(
            CatalogCanonicalizer.perResourceFingerprints(first),
            CatalogCanonicalizer.perResourceFingerprints(second),
        )
    }

    @Test
    fun `compatibility and includes participate in catalog fingerprints`() {
        val original = goldenArchive()
        val changedCompatibility = original.copyWithManifest(
            original.manifest.copy(compatibility = CompatibilityInfo(">=2.0.0")),
        )
        val changedIncludes = original.copyWithManifest(
            original.manifest.copy(includes = listOf(IncludeEntry("https://example.test/catalog.json"))),
        )

        assertNotEquals(CatalogCanonicalizer.currentFingerprint(original), CatalogCanonicalizer.currentFingerprint(changedCompatibility))
        assertNotEquals(CatalogCanonicalizer.currentFingerprint(original), CatalogCanonicalizer.currentFingerprint(changedIncludes))
        assertEquals(
            CatalogCanonicalizer.fingerprint(original),
            CatalogCanonicalizer.fingerprint(changedCompatibility),
        )
        assertEquals(
            CatalogCanonicalizer.fingerprint(original),
            CatalogCanonicalizer.fingerprint(changedIncludes),
        )
    }

    private fun goldenArchive(): CatalogArchive {
        val manifest = resource("wire-v4/catalog.json").use { mapper.readValue(it, CatalogManifest::class.java) }
        val detail = resource("wire-v4/resources/theme/default.json").use { mapper.readValue(it, ResourceDetail::class.java) }
        return CatalogArchive(
            manifest = manifest,
            resourceDetails = mapOf("theme/default" to detail),
            paths = setOf("resources/theme/default.json"),
            content = ArchiveContentProvider { path -> resource("wire-v4/$path") },
        )
    }

    private fun assetArchive(bytes: ByteArray): CatalogArchive {
        val json = """
            {"schemaVersion":4,"resource":{"type":"asset","slug":"logo","name":"Logo",
            "mediaType":"image/png","contentUrl":"./resources/asset/logo.png"}}
        """.trimIndent().toByteArray()
        val detail = mapper.readValue(json, ResourceDetail::class.java)
        val base = goldenArchive()
        return CatalogArchive(
            manifest = base.manifest.copy(resources = emptyList()),
            resourceDetails = mapOf("asset/logo" to detail),
            paths = setOf("resources/asset/logo.json", "resources/asset/logo.png"),
            content = ArchiveContentProvider { path ->
                ByteArrayInputStream(if (path.endsWith(".json")) json else bytes)
            },
        )
    }

    private fun CatalogArchive.copyWithManifest(manifest: CatalogManifest) = CatalogArchive(
        manifest = manifest,
        resourceDetails = resourceDetails,
        paths = paths,
        content = content,
    )

    private fun resource(path: String) = requireNotNull(javaClass.getResourceAsStream("/META-INF/epistola-catalog/fixtures/v1/$path"))
}
