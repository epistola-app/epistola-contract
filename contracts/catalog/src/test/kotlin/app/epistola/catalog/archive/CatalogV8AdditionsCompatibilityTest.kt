// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.archive

import app.epistola.catalog.canonical.CatalogCanonicalizer
import app.epistola.catalog.canonical.CatalogFingerprintVersion
import app.epistola.catalog.migration.CatalogMigrationCodes
import app.epistola.catalog.migration.CatalogMigrationOptions
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.ResourceDetail
import app.epistola.catalog.protocol.StencilResource
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.catalog.validation.CatalogValidationCodes
import app.epistola.catalog.validation.CatalogValidationPolicy
import app.epistola.catalog.validation.CatalogValidator
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Wire v8 gives a data example a key, which the v7-to-v8 migration derives from its name. These tests
 * hold that to what it must not change: a catalog-v7 archive with data examples still verifies
 * against the fingerprint its producer computed, and the legacy algorithms keep their values.
 *
 * The golden values were computed by the catalog contract before data examples had a key, over the
 * catalog-v7 archive [V7_FILES] bound with plain Jackson. They are pinned rather than recomputed so
 * that the legacy algorithms cannot drift along with the model.
 *
 * The last test holds the archive reader to never resolving a path outside the archive, whatever
 * wire version declared it (#95).
 */
class CatalogV8AdditionsCompatibilityTest {
    private val mapper = jsonMapper { addModule(kotlinModule()) }

    @Test
    fun `the legacy algorithms reproduce what they computed before data examples had keys`() {
        val archive = boundWithPlainJackson()

        assertEquals(LEGACY_FINGERPRINTS, legacyFingerprints(archive))
        assertEquals(LEGACY_RESOURCE_FINGERPRINTS, CatalogCanonicalizer.perResourceFingerprints(archive))
        assertEquals(LEGACY_RESOURCE_FINGERPRINTS, CatalogCanonicalizer.currentPerResourceFingerprints(archive))
    }

    @Test
    fun `a v7 archive signed over examples without keys migrates, gains keys, and still verifies`() {
        val zip = zip(V7_FILES, signedWith = V7_PRODUCER_V4)

        val read = CatalogArchiveReader.read(ByteArrayInputStream(zip), migration = MIGRATION)

        assertEquals(emptyList(), read.findings)
        assertEquals(
            listOf(0, 1, 2, 3).map { "resources/template/letter.json.resource.dataExamples[$it].key" },
            read.migrationNotices.filter { it.code == CatalogMigrationCodes.DATA_EXAMPLE_KEY_ASSIGNED }.map { it.path },
        )
        assertNotNull(read.archive).use { archive ->
            val template = archive.resourceDetails.getValue("template/letter").resource as TemplateResource
            assertEquals(listOf("standaard-brief", "arger-co", "standaard-brief-2", "example"), template.dataExamples.orEmpty().map { it.key })
            // The derived keys are new content: the legacy algorithms leave them out.
            assertEquals(LEGACY_FINGERPRINTS, legacyFingerprints(archive))
        }
        assertEquals(emptyList(), CatalogValidator.validate(ByteArrayInputStream(zip), POLICY).findings)
    }

    @Test
    fun `a v7 archive signed over a stencil with a placeholder migrates, keeps the v7 scope, and still verifies`() {
        val zip = zip(V7_STENCIL_FILES, signedWith = V7_STENCIL_PRODUCER_V4)

        val read = CatalogArchiveReader.read(ByteArrayInputStream(zip), migration = MIGRATION)

        assertEquals(emptyList(), read.findings)
        assertNotNull(read.archive).use { archive ->
            val stencil = archive.resourceDetails.getValue("stencil/letterhead").resource as StencilResource
            assertTrue(stencil.readsTemplateData)
            assertEquals(true, stencil.content.nodes.getValue("greeting").props?.get("passParameters"))
            // The scope the migration gives is new content: the legacy algorithms leave it out.
            assertEquals(V7_STENCIL_PRODUCER_V4, CatalogCanonicalizer.fingerprint(archive, CatalogFingerprintVersion.V4).value)
        }
        assertEquals(emptyList(), CatalogValidator.validate(ByteArrayInputStream(zip), POLICY).findings)
    }

    @Test
    fun `a re-export with derived keys keeps its v4 fingerprint and binds at the current version`() {
        val migrated = assertNotNull(CatalogArchiveReader.read(ByteArrayInputStream(zip(V7_FILES)), migration = MIGRATION).archive)
        val written = migrated.use { ByteArrayOutputStream().also { output -> CatalogArchiveWriter.write(it, output) }.toByteArray() }

        val reread = CatalogArchiveReader.read(ByteArrayInputStream(written))
        assertEquals(emptyList(), reread.findings)
        assertTrue(reread.migrationNotices.isEmpty(), reread.migrationNotices.toString())
        assertNotNull(reread.archive).use { current ->
            val template = current.resourceDetails.getValue("template/letter").resource as TemplateResource
            assertEquals(listOf("standaard-brief", "arger-co", "standaard-brief-2", "example"), template.dataExamples.orEmpty().map { it.key })
            assertEquals(V7_PRODUCER_V4, CatalogCanonicalizer.fingerprint(current, CatalogFingerprintVersion.V4).value)
        }
    }

    @Test
    fun `the reader never resolves a path outside the archive, at any wire version`() {
        val hash = "6ca6e2b588e6eac72bbddfe9a172818a9dce1fe141b5645912838bdec2f9ca98"
        val unsafe = listOf("../outside.png", "../../etc/passwd", "/etc/passwd", "bin/../../outside.png", "C:/outside.png", "bin\\..\\..\\outside.png")
        unsafe.forEach { contentUrl ->
            val files = V7_FILES + mapOf(
                "catalog.json" to V7_FILES.getValue("catalog.json").replace(
                    "\"resources\":[",
                    "\"resources\":[{\"type\":\"image\",\"slug\":\"logo\",\"name\":\"Logo\",\"detailUrl\":\"../../escape/logo.json\"},",
                ),
                "resources/image/logo.json" to mapper.writeValueAsString(
                    mapOf(
                        "schemaVersion" to 7,
                        "resource" to mapOf("type" to "image", "slug" to "logo", "name" to "Logo", "mediaType" to "image/png", "contentHash" to hash, "contentUrl" to contentUrl),
                    ),
                ),
            )
            // Earlier wire versions did not constrain these paths, so the archive still reads.
            val read = CatalogArchiveReader.read(ByteArrayInputStream(zip(files)), migration = MIGRATION)
            assertEquals(emptyList(), read.findings, contentUrl)
            assertNotNull(read.archive).use { archive ->
                assertFailsWith<IllegalArgumentException>(contentUrl) { archive.content.open(contentUrl) }
                assertFailsWith<IllegalArgumentException>(contentUrl) { archive.content.open("../../escape/logo.json") }
                // The canonicalizer only opens paths the archive holds.
                CatalogCanonicalizer.currentFingerprint(archive)
                val findings = CatalogValidator.validate(archive, POLICY).findings
                assertTrue(findings.any { it.code == CatalogValidationCodes.ASSET_PATH_INVALID }, "$contentUrl: $findings")
                assertTrue(findings.any { it.code == CatalogValidationCodes.MANIFEST_DETAIL_PATH_INVALID }, "$contentUrl: $findings")
            }
        }
    }

    private fun legacyFingerprints(archive: CatalogArchive): Map<CatalogFingerprintVersion, String> = listOf(
        CatalogFingerprintVersion.V1,
        CatalogFingerprintVersion.V2,
        CatalogFingerprintVersion.V3,
        CatalogFingerprintVersion.V4,
    ).associateWith { CatalogCanonicalizer.fingerprint(archive, it).value }

    private fun boundWithPlainJackson(): CatalogArchive {
        val manifest = mapper.readValue(V7_FILES.getValue("catalog.json"), CatalogManifest::class.java)
        val details = manifest.resources.associate { entry ->
            "${entry.type}/${entry.key}" to mapper.readValue(V7_FILES.getValue(entry.detailUrl.removePrefix("./")), ResourceDetail::class.java)
        }
        return CatalogArchive(manifest, details, V7_FILES.keys, ArchiveContentProvider { path -> ByteArrayInputStream(V7_FILES.getValue(path).toByteArray()) })
    }

    private fun zip(
        files: Map<String, String>,
        signedWith: String? = null,
    ): ByteArray = ByteArrayOutputStream().also { output ->
        ZipArchiveOutputStream(output).use { zip ->
            files.forEach { (name, body) ->
                val signed = if (signedWith != null && name == "catalog.json") body.replace("\"fingerprint\":null", "\"fingerprint\":\"$signedWith\"") else body
                zip.putArchiveEntry(ZipArchiveEntry(name))
                zip.write(signed.toByteArray())
                zip.closeArchiveEntry()
            }
        }
    }.toByteArray()

    private companion object {
        val MIGRATION = CatalogMigrationOptions("acme-corp", "exchange.epistola.app")
        val POLICY = CatalogValidationPolicy(migration = MIGRATION)

        /** The V4 fingerprint a catalog-v7 producer computed for [V7_FILES]. */
        const val V7_PRODUCER_V4 = "649c623b74436baf6685d74cf503993a91dec06f594272bcfb9701435e50c948"

        /** V1 to V4 of [V7_FILES], computed before data examples had a key. */
        val LEGACY_FINGERPRINTS = mapOf(
            CatalogFingerprintVersion.V1 to "ca102881df6d47417d37c5b92a04b71063e64909a7ad86247b1a5b69ebabe523",
            CatalogFingerprintVersion.V2 to "a0f13cfb1a954c5ad92bb05edd41908d60560ebac9d75fb3f4134c0f81ad5d18",
            CatalogFingerprintVersion.V3 to "a0f13cfb1a954c5ad92bb05edd41908d60560ebac9d75fb3f4134c0f81ad5d18",
            CatalogFingerprintVersion.V4 to V7_PRODUCER_V4,
        )

        /** Per-resource fingerprints of [V7_FILES], computed before data examples had a key. */
        val LEGACY_RESOURCE_FINGERPRINTS = mapOf(
            "template/letter" to "654477219cb838e1820fb22bd798cf7068f25f465536a7aa2440646370951f2c",
        )

        /** The V4 fingerprint a catalog-v7 producer computed for [V7_STENCIL_FILES]. */
        const val V7_STENCIL_PRODUCER_V4 = "801d895c874339c6e6caf9247d197c20ec5d7b9698498e3cd9053e13f9a748ae"

        /** A catalog-v7 archive with a stencil that has a placeholder, which v7 gave no scope of its own. */
        val V7_STENCIL_FILES: Map<String, String> = linkedMapOf(
            "catalog.json" to """
            {"schemaVersion":7,"catalog":{"slug":"letters","name":"Letters","keywords":[]},
             "publisher":{"name":"Acme"},"release":{"version":"1.0.0","fingerprint":null},
             "resources":[{"type":"stencil","slug":"letterhead","name":"Letterhead","detailUrl":"./resources/stencil/letterhead.json"}]}
            """.trimIndent(),
            "resources/stencil/letterhead.json" to """
            {"schemaVersion":7,"resource":{"type":"stencil","slug":"letterhead","name":"Letterhead","version":2,"tags":[],
             "content":{"modelVersion":1,"root":"root",
              "nodes":{"root":{"id":"root","type":"root","slots":["children"]},
               "greeting":{"id":"greeting","type":"placeholder","slots":["greeting-default"],"props":{"name":"greeting","kind":"block"}}},
              "slots":{"children":{"id":"children","nodeId":"root","name":"children","children":["greeting"]},
               "greeting-default":{"id":"greeting-default","nodeId":"greeting","name":"default","children":[]}},
              "themeRef":{"type":"inherit"}}}}
            """.trimIndent(),
        )

        /** A catalog-v7 archive whose template has data examples, which v7 gave no key. */
        val V7_FILES: Map<String, String> = linkedMapOf(
            "catalog.json" to """
            {"schemaVersion":7,"catalog":{"slug":"letters","name":"Letters","keywords":[]},
             "publisher":{"name":"Acme"},"release":{"version":"1.0.0","fingerprint":null},
             "resources":[{"type":"template","slug":"letter","name":"Letter","detailUrl":"./resources/template/letter.json"}]}
            """.trimIndent(),
            "resources/template/letter.json" to """
            {"schemaVersion":7,"resource":{"type":"template","slug":"letter","name":"Letter","pdfaEnabled":true,
             "dataModel":{"type":"object","properties":{"name":{"type":"string"}}},
             "dataExamples":[{"name":"Standaard brief","data":{"name":"Jan"}},{"name":"Ärger & Co.","data":{"name":"Piet"}},{"name":"standaard-brief","data":{"name":"Klaas"}},{"name":"!!!","data":{}}],
             "templateModel":{"modelVersion":1,"root":"root","nodes":{"root":{"id":"root","type":"root","slots":[]}},"slots":{},"themeRef":{"type":"inherit"}},
             "variants":[{"slug":"english","title":"English","attributes":{},"isDefault":true}]}}
            """.trimIndent(),
        )
    }
}
