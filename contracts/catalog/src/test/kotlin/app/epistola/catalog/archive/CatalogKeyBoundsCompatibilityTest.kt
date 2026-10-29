// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.archive

import app.epistola.catalog.canonical.CatalogCanonicalizer
import app.epistola.catalog.canonical.CatalogFingerprintVersion
import app.epistola.catalog.migration.CatalogMigrationOptions
import app.epistola.catalog.migration.CatalogWireSchema.CURRENT_VERSION
import app.epistola.catalog.protocol.AttributeResource
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.ResourceDetail
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.catalog.validation.CatalogValidationPolicy
import app.epistola.catalog.validation.CatalogValidator
import app.epistola.template.model.ThemeRefOverride
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire v8 bounds every resource key to 3 to 50 characters, and the v7-to-v8 migration pads a shorter
 * catalog-v7 key on the right with `0` and cuts a longer one, together with every reference to it
 * ([app.epistola.catalog.protocol.CatalogKeys.migrate]). These tests hold that to what it must not
 * change: an archive a catalog-v7 producer signed over the source keys still verifies, and the
 * legacy fingerprint algorithms keep their values, because they are computed over the source keys
 * restored from the archive's raw documents.
 *
 * The golden values were computed by the catalog contract before keys were bounded, over the
 * catalog-v7 archive [V7_FILES] bound with plain Jackson. They are pinned rather than recomputed so
 * that the legacy algorithms cannot drift along with the model.
 */
class CatalogKeyBoundsCompatibilityTest {
    private val mapper = jsonMapper { addModule(kotlinModule()) }

    @Test
    fun `the legacy algorithms reproduce what they computed before keys were bounded`() {
        assertEquals(LEGACY_FINGERPRINTS, legacyFingerprints(boundWithPlainJackson()))
    }

    @Test
    fun `a v7 archive signed over short and long keys migrates with notices, validates clean and still verifies`() {
        val zip = zip(signed = true)

        val read = CatalogArchiveReader.read(ByteArrayInputStream(zip), migration = MIGRATION)

        assertEquals(emptyList(), read.findings)
        assertEquals(
            listOf(
                "CATALOG_RESOURCE_KEY_PADDED" to "catalog.json.resources[0].key",
                "CATALOG_RESOURCE_KEY_TRUNCATED" to "catalog.json.resources[1].key",
                "CATALOG_RESOURCE_KEY_PADDED" to "catalog.json.resources[2].key",
                "CATALOG_RESOURCE_KEY_PADDED" to "catalog.json.resources[3].key",
                "CATALOG_RESOURCE_KEY_PADDED" to "catalog.json.resources[4].key",
                "CATALOG_RESOURCE_KEY_PADDED" to "catalog.json.resources[5].key",
                "CATALOG_RESOURCE_KEY_PADDED" to "resources/template/le.json.resource.variants[0].key",
            ),
            read.migrationNotices.filter { it.code.startsWith("CATALOG_RESOURCE_KEY_") }.map { it.code to it.path }.sortedBy { it.second },
        )
        assertNotNull(read.archive).use { archive ->
            assertEquals(
                setOf("image/100", "codeList/$CUT", "attribute/co0", "stencil/ad0", "theme/h00", "template/le0"),
                archive.resourceDetails.keys,
            )
            assertEquals("100", archive.manifest.catalog.presentation?.iconImageKey)
            assertEquals(listOf("100"), archive.manifest.catalog.presentation?.imageKeys)
            assertEquals("lc0", archive.manifest.catalog.attributes.single().key)
            assertEquals("cs0", archive.manifest.dependencies.orEmpty().single().key)
            val template = archive.resourceDetails.getValue("template/le0").resource as TemplateResource
            assertEquals("h00", template.themeKey)
            assertEquals("cs0", (template.templateModel.themeRef as ThemeRefOverride).themeKey)
            assertEquals(listOf("en0", "english"), template.variants.map { it.key })
            assertEquals(mapOf("co0" to "nl"), template.variants.first().attributes)
            assertEquals("ad0", template.templateModel.nodes.getValue("address").props?.get("stencilKey"))
            assertEquals("100", template.templateModel.nodes.getValue("logo").props?.get("imageKey"))
            assertEquals(mapOf("catalogKey" to "shared", "key" to "in0"), template.templateModel.documentStylesOverride?.get("fontFamily"))
            assertEquals(listOf("a00"), template.dataExamples.orEmpty().map { it.key })
            val attribute = archive.resourceDetails.getValue("attribute/co0").resource as AttributeResource
            assertEquals(CUT, attribute.codeListBinding?.key)
            // Every legacy algorithm covers the source keys.
            assertEquals(LEGACY_FINGERPRINTS, legacyFingerprints(archive))
        }
        assertEquals(emptyList(), CatalogValidator.validate(ByteArrayInputStream(zip), POLICY).findings)
    }

    @Test
    fun `a v7 fingerprint over different keys still mismatches after migration`() {
        // The tampered archive says `ab` where it was signed over `ad`; both migrate to keys of
        // their own, so the source fingerprint no longer matches.
        val tampered = zip(signed = true) { _, body -> body.replace("\"ad\"", "\"ab\"").replace("stencil/ad.json", "stencil/ab.json") }

        assertTrue(
            CatalogValidator.validate(ByteArrayInputStream(tampered), POLICY).findings
                .any { it.code == "CATALOG_RELEASE_FINGERPRINT_MISMATCH" },
        )
    }

    @Test
    fun `a migrated catalog re-exports under its new keys and reads back clean at the current version`() {
        val migrated = assertNotNull(CatalogArchiveReader.read(ByteArrayInputStream(zip(signed = true)), migration = MIGRATION).archive)
        val written = migrated.use { write(it) }
        val files = unzip(written)
        assertTrue("resources/template/le0.json" in files, files.keys.toString())
        assertTrue("resources/codeList/$CUT.json" in files, files.keys.toString())
        assertEquals(
            "./resources/template/le0.json",
            mapper.readTree(files.getValue("catalog.json"))["resources"].single { it["type"].asString() == "template" }["detailUrl"].asString(),
        )

        val reread = CatalogArchiveReader.read(ByteArrayInputStream(written))
        assertEquals(emptyList(), reread.findings)
        assertTrue(reread.migrationNotices.isEmpty(), reread.migrationNotices.toString())
        assertNotNull(reread.archive).use { current ->
            assertEquals(CURRENT_VERSION, current.manifest.schemaVersion)
            assertEquals(setOf("image/100", "codeList/$CUT", "attribute/co0", "stencil/ad0", "theme/h00", "template/le0"), current.resourceDetails.keys)
            // Read back clean, but not yet a complete v8 release: wire v7 had no pins, and a v8
            // release pins every catalog it declares, so it is released again before it is shared.
            val findings = CatalogValidator.validate(current).findings
            assertTrue(findings.isNotEmpty() && findings.all { it.code == "CATALOG_DEPENDENCY_CATALOG_VERSION_INVALID" }, findings.toString())
        }
    }

    @Test
    fun `a migrated catalog without a fingerprint is written with its current one`() {
        val migrated = assertNotNull(CatalogArchiveReader.read(ByteArrayInputStream(zip(signed = false)), migration = MIGRATION).archive)
        assertNull(migrated.manifest.release.fingerprint, "wire v7 allowed a release without one")
        val expected = CatalogCanonicalizer.currentQualifiedFingerprint(migrated).toString()
        val written = migrated.use { write(it) }

        assertNotNull(CatalogArchiveReader.read(ByteArrayInputStream(written)).archive).use { current ->
            assertEquals(expected, current.manifest.release.fingerprint)
            // Its identity verifies; what is left is the pins wire v7 never had.
            val findings = CatalogValidator.validate(current).findings
            assertTrue(findings.none { it.code.startsWith("CATALOG_RELEASE_FINGERPRINT") }, findings.toString())
            assertTrue(findings.all { it.code == "CATALOG_DEPENDENCY_CATALOG_VERSION_INVALID" }, findings.toString())
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
        val files = V7_FILES.mapValues { it.value.toByteArray() } + (LOGO_PATH to LOGO)
        return CatalogArchive(manifest, details, files.keys, ArchiveContentProvider { path -> ByteArrayInputStream(files.getValue(path)) })
    }

    private fun zip(
        signed: Boolean,
        edit: (String, String) -> String = { _, body -> body },
    ): ByteArray {
        val files = V7_FILES.entries.associate { (path, body) ->
            val signedBody = if (signed && path == "catalog.json") body.replace("\"fingerprint\":null", "\"fingerprint\":\"$V7_PRODUCER_V4\"") else body
            edit(path, path) to edit(path, signedBody).toByteArray()
        } + (LOGO_PATH to LOGO)
        return ByteArrayOutputStream().also { output ->
            ZipArchiveOutputStream(output).use { zip ->
                files.forEach { (name, bytes) ->
                    zip.putArchiveEntry(ZipArchiveEntry(name))
                    zip.write(bytes)
                    zip.closeArchiveEntry()
                }
            }
        }.toByteArray()
    }

    private fun write(catalog: CatalogArchive): ByteArray = ByteArrayOutputStream().also { CatalogArchiveWriter.write(catalog, it) }.toByteArray()

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val files = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.filter { it.name.endsWith(".json") }.forEach { files[it.name] = zip.readBytes().decodeToString() }
        }
        return files
    }

    private companion object {
        const val REGISTRY = "exchange.epistola.app"
        const val NAMESPACE = "acme-corp"
        val MIGRATION = CatalogMigrationOptions(NAMESPACE, REGISTRY)
        val POLICY = CatalogValidationPolicy(migration = MIGRATION)

        /** A 55-character code list key, which wire v8 cuts to [CUT]. */
        const val LONG = "customer-segments-for-the-quarterly-retention-report-q1"

        /** [LONG] cut to 50 characters without a trailing hyphen. */
        const val CUT = "customer-segments-for-the-quarterly-retention-repo"

        val LOGO = "logo-bytes".toByteArray()
        const val LOGO_PATH = "bin/6ca6e2b588e6eac72bbddfe9a172818a9dce1fe141b5645912838bdec2f9ca98"

        /** The V4 fingerprint a catalog-v7 producer computed for [V7_FILES]. */
        const val V7_PRODUCER_V4 = "1ca1ff037eb49ab7232a040df56a48c5955190e8bb1aa5c2e0de8220dc684681"

        /** V1 to V4 of [V7_FILES], computed before keys were bounded. */
        val LEGACY_FINGERPRINTS = mapOf(
            CatalogFingerprintVersion.V1 to "3e91df2450fe3ae1e1594ec08fd8a5c9a3f5b4cabd5488b3f44d1a57921a9106",
            CatalogFingerprintVersion.V2 to "6481be1167aea56b7c2265e48ddef263031322c836803a05b67f21238afe3a77",
            CatalogFingerprintVersion.V3 to "6481be1167aea56b7c2265e48ddef263031322c836803a05b67f21238afe3a77",
            CatalogFingerprintVersion.V4 to V7_PRODUCER_V4,
        )

        /**
         * A catalog-v7 archive with keys of 1, 2 and 55 characters across the types, and references
         * to them -- and to short keys in another catalog -- in every position wire v8 rewrites.
         */
        val V7_FILES: Map<String, String> = linkedMapOf(
            "catalog.json" to """
            {"schemaVersion":7,"catalog":{"slug":"letters","name":"Letters","attributes":[{"catalog":"system","key":"lc","value":"nl-NL"}],"keywords":[],"presentation":{"iconAssetSlug":"1","imageAssetSlugs":["1"]}},
             "publisher":{"name":"Acme"},"release":{"version":"1.0.0","fingerprint":null},
             "dependencies":[{"type":"theme","catalogKey":"shared","slug":"cs"}],
             "resources":[
               {"type":"image","slug":"1","name":"Logo","detailUrl":"./resources/image/1.json"},
               {"type":"codeList","slug":"customer-segments-for-the-quarterly-retention-report-q1","name":"Segments","detailUrl":"./resources/codeList/customer-segments-for-the-quarterly-retention-report-q1.json"},
               {"type":"attribute","slug":"co","name":"Country","detailUrl":"./resources/attribute/co.json"},
               {"type":"stencil","slug":"ad","name":"Address","detailUrl":"./resources/stencil/ad.json"},
               {"type":"theme","slug":"h","name":"House","detailUrl":"./resources/theme/h.json"},
               {"type":"template","slug":"le","name":"Letter","detailUrl":"./resources/template/le.json"}]}
            """.trimIndent(),
            "resources/image/1.json" to """
            {"schemaVersion":7,"resource":{"type":"image","slug":"1","name":"Logo","mediaType":"image/png","contentHash":"6ca6e2b588e6eac72bbddfe9a172818a9dce1fe141b5645912838bdec2f9ca98"}}
            """.trimIndent(),
            "resources/codeList/customer-segments-for-the-quarterly-retention-report-q1.json" to """
            {"schemaVersion":7,"resource":{"type":"codeList","slug":"customer-segments-for-the-quarterly-retention-report-q1","name":"Segments","entries":[{"code":"nl","label":"Netherlands"}]}}
            """.trimIndent(),
            "resources/attribute/co.json" to """
            {"schemaVersion":7,"resource":{"type":"attribute","slug":"co","name":"Country","codeListBinding":{"slug":"customer-segments-for-the-quarterly-retention-report-q1"}}}
            """.trimIndent(),
            "resources/theme/h.json" to """
            {"schemaVersion":7,"resource":{"type":"theme","slug":"h","name":"House",
             "documentStyles":{"fontFamily":{"catalogKey":"shared","slug":"in"}}}}
            """.trimIndent(),
            "resources/stencil/ad.json" to """
            {"schemaVersion":7,"resource":{"type":"stencil","slug":"ad","name":"Address","tags":[],
             "content":{"modelVersion":1,"root":"root",
              "nodes":{"root":{"id":"root","type":"root","slots":["children"]},
                       "logo":{"id":"logo","type":"image","slots":[],"props":{"assetId":"1","alt":"Logo"}}},
              "slots":{"children":{"id":"children","nodeId":"root","name":"children","children":["logo"]}},
              "themeRef":{"type":"inherit"}}}}
            """.trimIndent(),
            "resources/template/le.json" to """
            {"schemaVersion":7,"resource":{"type":"template","slug":"le","name":"Letter","themeId":"h","pdfaEnabled":true,
             "dataExamples":[{"name":"A","data":{}}],
             "templateModel":{"modelVersion":1,"root":"root",
              "nodes":{"root":{"id":"root","type":"root","slots":["children"]},
                       "address":{"id":"address","type":"stencil","slots":["address-children"],"props":{"stencilId":"ad"}},
                       "logo":{"id":"logo","type":"image","slots":[],"props":{"assetId":"1","catalogKey":"letters","alt":"Logo"}}},
              "slots":{"children":{"id":"children","nodeId":"root","name":"children","children":["address","logo"]},
                       "address-children":{"id":"address-children","nodeId":"address","name":"children","children":[]}},
              "themeRef":{"type":"override","themeId":"cs","catalogKey":"shared"},
              "documentStylesOverride":{"fontFamily":{"catalogKey":"shared","slug":"in"}}},
             "variants":[{"slug":"en","title":"English","attributes":{"co":"nl"},"isDefault":true},
                         {"slug":"english","title":"British","attributes":{"co":"gb"}}]}}
            """.trimIndent(),
        )
    }
}
