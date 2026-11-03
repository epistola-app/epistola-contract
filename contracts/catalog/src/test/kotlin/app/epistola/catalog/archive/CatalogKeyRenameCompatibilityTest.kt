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
import app.epistola.catalog.protocol.StencilResource
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.catalog.protocol.ThemeResource
import app.epistola.catalog.validation.CatalogValidationPolicy
import app.epistola.catalog.validation.CatalogValidator
import app.epistola.template.model.ThemeRefOverride
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Wire v8 calls a resource's address its `key` (#84). These tests hold the rename to what it must not
 * change: archives a catalog-v7 producer signed still verify, documents a consumer stored under the
 * old names still bind, and the legacy fingerprint algorithms keep their values whichever names an
 * archive uses.
 *
 * The golden values were computed by the catalog contract as it stood before the rename, over the
 * catalog-v7 archive [V7_FILES] bound with plain Jackson. They are pinned rather than recomputed so
 * that the legacy algorithms cannot drift along with the model.
 */
class CatalogKeyRenameCompatibilityTest {
    private val mapper = jsonMapper { addModule(kotlinModule()) }

    @Test
    fun `a v7 archive signed over the old names migrates to the new names and still verifies`() {
        val zip = zip(signed = true)

        val read = CatalogArchiveReader.read(ByteArrayInputStream(zip), migration = MIGRATION)

        assertEquals(emptyList(), read.findings)
        // A pure rename: nothing for an author to act on, so it adds no notice of its own.
        assertTrue(read.migrationNotices.none { "slug" in it.message || "Id'" in it.message }, read.migrationNotices.toString())
        assertNotNull(read.archive).use { archive ->
            assertEquals("letters", archive.manifest.catalog.key)
            assertEquals("logo", archive.manifest.catalog.presentation?.iconImageKey)
            val template = archive.resourceDetails.getValue("template/letter").resource as TemplateResource
            assertEquals("corporate", template.themeKey)
            assertEquals("corporate", (template.templateModel.themeRef as ThemeRefOverride).themeKey)
            assertEquals("english", template.variants.single().key)
            assertEquals("address", template.templateModel.nodes.getValue("address").props?.get("stencilKey"))
            assertEquals("logo", template.templateModel.nodes.getValue("logo").props?.get("imageKey"))
            val stencil = archive.resourceDetails.getValue("stencil/address").resource as StencilResource
            assertEquals("logo", stencil.content.nodes.getValue("logo").props?.get("imageKey"))
            val attribute = archive.resourceDetails.getValue("attribute/country").resource as AttributeResource
            assertEquals("countries", attribute.codeListBinding?.key)
            assertEquals(listOf("logo"), archive.manifest.catalog.presentation?.imageKeys)
            assertEquals("system", archive.manifest.catalog.attributes.single().catalogKey)
            val theme = archive.resourceDetails.getValue("theme/house").resource as ThemeResource
            assertEquals(mapOf("catalogKey" to "shared", "key" to "inter"), theme.documentStyles?.get("fontFamily"))
            assertEquals(mapOf("catalogKey" to "shared", "key" to "inter"), theme.blockStylePresets?.getValue("heading")?.styles?.get("fontFamily"))
            assertEquals(mapOf("catalogKey" to "shared", "key" to "inter"), template.templateModel.documentStylesOverride?.get("fontFamily"))
            assertEquals(mapOf("catalogKey" to "shared", "key" to "inter"), template.templateModel.nodes.getValue("address").styles?.get("fontFamily"))
        }
        assertEquals(emptyList(), CatalogValidator.validate(ByteArrayInputStream(zip), POLICY).findings)
    }

    @Test
    fun `a v7 template naming its theme by its own catalog's key still verifies after migration`() {
        // Suite 1.x named a template's theme in its own catalog by that catalog's key. Wire v8 drops
        // the reference to the catalog itself, and the bound template then carries the member as
        // null, which the legacy fingerprint must still read as the key the producer covered.
        val ownTheme = { path: String, body: String ->
            if (path == "resources/template/letter.json") body.replace("\"themeCatalogKey\":\"shared\"", "\"themeCatalogKey\":\"letters\"") else body
        }
        val producerV4 = CatalogCanonicalizer.fingerprint(boundWithPlainJackson { ownTheme("resources/template/letter.json", it) }, CatalogFingerprintVersion.V4).value
        val signed = zip(signed = true) { path, body -> ownTheme(path, body).replace(V7_PRODUCER_V4, producerV4) }

        val findings = CatalogValidator.validate(ByteArrayInputStream(signed), POLICY).findings

        assertTrue(findings.none { it.code == "CATALOG_RELEASE_FINGERPRINT_MISMATCH" }, findings.toString())
    }

    @Test
    fun `a v7 fingerprint over different content still mismatches after migration`() {
        val tampered = zip(signed = true) { path, body ->
            if (path == "resources/template/letter.json") body.replace("\"title\":\"English\"", "\"title\":\"British\"") else body
        }

        assertTrue(
            CatalogValidator.validate(ByteArrayInputStream(tampered), POLICY).findings
                .any { it.code == "CATALOG_RELEASE_FINGERPRINT_MISMATCH" },
        )
    }

    @Test
    fun `the model binds the old names, and the legacy algorithms reproduce what they computed over them`() {
        val archive = boundWithPlainJackson()

        assertEquals("letters", archive.manifest.catalog.key)
        assertEquals("logo", archive.manifest.catalog.presentation?.iconImageKey)
        assertEquals("corporate", archive.manifest.dependencies.orEmpty().single().key)
        assertEquals(LEGACY_FINGERPRINTS, legacyFingerprints(archive))
        assertEquals(LEGACY_RESOURCE_FINGERPRINTS, CatalogCanonicalizer.perResourceFingerprints(archive))
        assertEquals(LEGACY_RESOURCE_FINGERPRINTS, CatalogCanonicalizer.currentPerResourceFingerprints(archive))
    }

    @Test
    fun `the same documents under the new names keep every legacy fingerprint`() {
        val renamed = boundWithPlainJackson { body ->
            RENAMES.fold(body.replace(ATTRIBUTE_CATALOG, ATTRIBUTE_CATALOG_KEY)) { renamedBody, (old, new) -> renamedBody.replace("\"$old\":", "\"$new\":") }
        }

        assertEquals(LEGACY_FINGERPRINTS, legacyFingerprints(renamed))
        assertEquals(LEGACY_RESOURCE_FINGERPRINTS, CatalogCanonicalizer.perResourceFingerprints(renamed))
        assertEquals(LEGACY_RESOURCE_FINGERPRINTS, CatalogCanonicalizer.currentPerResourceFingerprints(renamed))
    }

    @Test
    fun `a catalog written with the new names keeps its v4 fingerprint, but for the own key wire v8 drops`() {
        val migrated = assertNotNull(CatalogArchiveReader.read(ByteArrayInputStream(zip(signed = false)), migration = MIGRATION).archive)
        val written = migrated.use { write(it) }
        val files = unzip(written)
        assertTrue("\"key\"" in files.getValue("catalog.json"))
        assertFalse(files.values.any { body -> RENAMES.any { (old, _) -> Regex("\"$old\"\\s*:").containsMatchIn(body) } })
        val attribute = mapper.readTree(files.getValue("catalog.json"))["catalog"]["attributes"][0]
        assertTrue(attribute.has("catalogKey") && !attribute.has("catalog"), attribute.toString())

        assertNotNull(CatalogArchiveReader.read(ByteArrayInputStream(written)).archive).use { current ->
            assertEquals(CURRENT_VERSION, current.manifest.schemaVersion)
            // The logo named the catalog itself by its own key, which wire v8 drops: a reference to
            // the catalog itself names no catalog. A re-export has no raw v7 documents left to put it
            // back from, so V4 no longer carries over, as with the stencil versions wire v8 removes;
            // the re-export is released under the current algorithm.
            val template = current.resourceDetails.getValue("template/letter")
            val logo = (template.resource as TemplateResource).templateModel.nodes.getValue("logo")
            assertFalse("catalogKey" in logo.props.orEmpty(), logo.toString())
            assertTrue(CatalogCanonicalizer.fingerprint(current, CatalogFingerprintVersion.V4).value != V7_PRODUCER_V4)
            // Put back, V4 is what the v7 producer computed: the renames alone move nothing. V1 to V3
            // cover the documents as written, which the writer fills out beyond what the v7 producer
            // wrote, so only V4, which covers the model, is compared.
            val tree = mapper.valueToTree<tools.jackson.databind.node.ObjectNode>(template)
            ((tree["resource"]["templateModel"]["nodes"]["logo"]["props"]) as tools.jackson.databind.node.ObjectNode).put("catalogKey", "letters")
            val restored = CatalogArchive(
                current.manifest,
                current.resourceDetails + ("template/letter" to mapper.treeToValue(tree, ResourceDetail::class.java)),
                current.paths,
                current.content,
            )
            assertEquals(V7_PRODUCER_V4, CatalogCanonicalizer.fingerprint(restored, CatalogFingerprintVersion.V4).value)
        }
    }

    @Test
    fun `v5 canonicalizes the new names`() {
        val archive = boundWithPlainJackson()
        val canonical = mapper.valueToTree<JsonNode>(archive.resourceDetails.getValue("template/letter").resource)

        assertTrue(canonical.has("key") && canonical.has("themeKey"), canonical.toString())
        assertFalse(canonical.has("slug") || canonical.has("themeId"), canonical.toString())
    }

    private fun legacyFingerprints(archive: CatalogArchive): Map<CatalogFingerprintVersion, String> = listOf(
        CatalogFingerprintVersion.V1,
        CatalogFingerprintVersion.V2,
        CatalogFingerprintVersion.V3,
        CatalogFingerprintVersion.V4,
    ).associateWith { CatalogCanonicalizer.fingerprint(archive, it).value }

    private fun boundWithPlainJackson(edit: (String) -> String = { it }): CatalogArchive {
        val documents = V7_FILES.mapValues { edit(it.value) }
        val manifest = mapper.readValue(documents.getValue("catalog.json"), CatalogManifest::class.java)
        val details = manifest.resources.associate { entry ->
            "${entry.type}/${entry.key}" to mapper.readValue(documents.getValue(entry.detailUrl.removePrefix("./")), ResourceDetail::class.java)
        }
        val files = documents.mapValues { it.value.toByteArray() } + (LOGO_PATH to LOGO)
        return CatalogArchive(manifest, details, files.keys, ArchiveContentProvider { path -> ByteArrayInputStream(files.getValue(path)) })
    }

    private fun zip(
        signed: Boolean,
        edit: (String, String) -> String = { _, body -> body },
    ): ByteArray {
        val files = V7_FILES.mapValues { (path, body) ->
            val signedBody = if (signed && path == "catalog.json") body.replace("\"fingerprint\":null", "\"fingerprint\":\"$V7_PRODUCER_V4\"") else body
            edit(path, signedBody).toByteArray()
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

        /**
         * Every member wire v8 renamed, old name to new, except an attribute's `catalog`
         * ([ATTRIBUTE_CATALOG]); only these words occur as members in [V7_FILES].
         */
        val RENAMES = listOf(
            "slug" to "key",
            "themeId" to "themeKey",
            "stencilId" to "stencilKey",
            "assetId" to "imageKey",
            "iconAssetSlug" to "iconImageKey",
            "imageAssetSlugs" to "imageKeys",
        )

        /**
         * An attribute assignment's `catalog` became `catalogKey`. Renamed by its exact text, because
         * the manifest's own `catalog` member keeps its name.
         */
        const val ATTRIBUTE_CATALOG = "{\"catalog\":\"system\""
        const val ATTRIBUTE_CATALOG_KEY = "{\"catalogKey\":\"system\""

        val LOGO = "logo-bytes".toByteArray()
        const val LOGO_PATH = "bin/6ca6e2b588e6eac72bbddfe9a172818a9dce1fe141b5645912838bdec2f9ca98"

        /** The V4 fingerprint a catalog-v7 producer computed for [V7_FILES]. */
        const val V7_PRODUCER_V4 = "10a8fc961fd745780012243ad70694a388dfacc455d682de4b49f6336644d2de"

        /** V1 to V4 of [V7_FILES], computed before the rename. */
        val LEGACY_FINGERPRINTS = mapOf(
            CatalogFingerprintVersion.V1 to "cda602a4e6407ea485c5e76fb630bc39b5975d335a714b7e18bd866ff0969a97",
            CatalogFingerprintVersion.V2 to "4f1d39e12953a6c5659d3cfb467c93af74bf4019fffc96c375ef9f341e460b3b",
            CatalogFingerprintVersion.V3 to "4f1d39e12953a6c5659d3cfb467c93af74bf4019fffc96c375ef9f341e460b3b",
            CatalogFingerprintVersion.V4 to V7_PRODUCER_V4,
        )

        /** Per-resource fingerprints of [V7_FILES], computed before the rename. */
        val LEGACY_RESOURCE_FINGERPRINTS = mapOf(
            "attribute/country" to "c55480e65ac7eb40c3034aca9630ae61afc62eb91949aec21fd7e0690f5a28b2",
            "codeList/countries" to "4be3de597590cc82a180d6d9695e415471721e33022896da03865b923f95c951",
            "image/logo" to "d63c89df0e8e9477baa418fcb8949be6fa3e1638c39426ca74af87183220d2b8",
            "stencil/address" to "9fc46a30d820591418bfd357c7a99fefd9df7994b782f3f4ef2a59a3305534b6",
            "template/letter" to "c13f41f36b6a02dff18243259e6a95f74bffab852be1ffff952b266e01280132",
            "theme/house" to "1038f7c77fcf3ef6a597a3e76e2a46633c56571d3231b0f1fedfc807ff7c4784",
        )

        /** A catalog-v7 archive using every member wire v8 renamed. */
        val V7_FILES: Map<String, String> = linkedMapOf(
            "catalog.json" to """
            {"schemaVersion":7,"catalog":{"slug":"letters","name":"Letters","attributes":[{"catalog":"system","key":"locale","value":"nl-NL"}],"keywords":[],"presentation":{"iconAssetSlug":"logo","imageAssetSlugs":["logo"]}},
             "publisher":{"name":"Acme"},"release":{"version":"1.0.0","fingerprint":null},
             "dependencies":[{"type":"theme","catalogKey":"shared","slug":"corporate"}],
             "resources":[
               {"type":"image","slug":"logo","name":"Logo","detailUrl":"./resources/image/logo.json"},
               {"type":"codeList","slug":"countries","name":"Countries","detailUrl":"./resources/codeList/countries.json"},
               {"type":"attribute","slug":"country","name":"Country","detailUrl":"./resources/attribute/country.json"},
               {"type":"stencil","slug":"address","name":"Address","detailUrl":"./resources/stencil/address.json"},
               {"type":"theme","slug":"house","name":"House","detailUrl":"./resources/theme/house.json"},
               {"type":"template","slug":"letter","name":"Letter","detailUrl":"./resources/template/letter.json"}]}
            """.trimIndent(),
            "resources/image/logo.json" to """
            {"schemaVersion":7,"resource":{"type":"image","slug":"logo","name":"Logo","mediaType":"image/png","contentHash":"6ca6e2b588e6eac72bbddfe9a172818a9dce1fe141b5645912838bdec2f9ca98"}}
            """.trimIndent(),
            "resources/codeList/countries.json" to """
            {"schemaVersion":7,"resource":{"type":"codeList","slug":"countries","name":"Countries","entries":[{"code":"nl","label":"Netherlands"}]}}
            """.trimIndent(),
            "resources/attribute/country.json" to """
            {"schemaVersion":7,"resource":{"type":"attribute","slug":"country","name":"Country","codeListBinding":{"slug":"countries"}}}
            """.trimIndent(),
            "resources/theme/house.json" to """
            {"schemaVersion":7,"resource":{"type":"theme","slug":"house","name":"House",
             "documentStyles":{"fontFamily":{"catalogKey":"shared","slug":"inter"}},
             "blockStylePresets":{"heading":{"label":"Heading","styles":{"fontFamily":{"catalogKey":"shared","slug":"inter"},"fontWeight":"700"}}}}}
            """.trimIndent(),
            "resources/stencil/address.json" to """
            {"schemaVersion":7,"resource":{"type":"stencil","slug":"address","name":"Address","tags":[],
             "content":{"modelVersion":1,"root":"root",
              "nodes":{"root":{"id":"root","type":"root","slots":["children"]},
                       "logo":{"id":"logo","type":"image","slots":[],"props":{"assetId":"logo","alt":"Logo"}}},
              "slots":{"children":{"id":"children","nodeId":"root","name":"children","children":["logo"]}},
              "themeRef":{"type":"inherit"}}}}
            """.trimIndent(),
            "resources/template/letter.json" to """
            {"schemaVersion":7,"resource":{"type":"template","slug":"letter","name":"Letter","themeId":"corporate","themeCatalogKey":"shared","pdfaEnabled":true,
             "templateModel":{"modelVersion":1,"root":"root",
              "nodes":{"root":{"id":"root","type":"root","slots":["children"]},
                       "address":{"id":"address","type":"stencil","slots":["address-children"],"styles":{"fontFamily":{"catalogKey":"shared","slug":"inter"}},"props":{"stencilId":"address"}},
                       "logo":{"id":"logo","type":"image","slots":[],"props":{"assetId":"logo","catalogKey":"letters","alt":"Logo"}}},
              "slots":{"children":{"id":"children","nodeId":"root","name":"children","children":["address","logo"]},
                       "address-children":{"id":"address-children","nodeId":"address","name":"children","children":[]}},
              "themeRef":{"type":"override","themeId":"corporate","catalogKey":"shared"},
              "documentStylesOverride":{"fontFamily":{"catalogKey":"shared","slug":"inter"}}},
             "variants":[{"slug":"english","title":"English","attributes":{},"isDefault":true,
               "templateModel":{"modelVersion":1,"root":"root",
                "nodes":{"root":{"id":"root","type":"root","slots":[]}},
                "slots":{},
                "themeRef":{"type":"override","themeId":"corporate","catalogKey":"shared"}}}]}}
            """.trimIndent(),
        )
    }
}
