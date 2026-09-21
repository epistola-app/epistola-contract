// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.archive

import app.epistola.catalog.protocol.FontResource
import app.epistola.catalog.protocol.ImageResource
import app.epistola.catalog.protocol.TemplateResource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A whole catalog-v6 archive, read by the current reader.
 *
 * Every other migration test works on one document. That is what let the v6-to-v7 step ship
 * reading `type` off the document root -- where a resource payload is nested under `resource` --
 * with its own fixtures shaped the same wrong way, so it passed while never firing on a real
 * archive. This builds a v6 archive the way a publisher's ZIP is actually shaped: the manifest
 * naming types and detail paths, the details at the v6 locations, the binaries where v6 put them.
 */
class CatalogV6ArchiveCompatibilityTest {
    @Test
    fun `a v6 archive binds at v7 with its binaries resolvable`() {
        val result = CatalogArchiveReader.read(ByteArrayInputStream(v6Archive()))
        val archive = assertNotNull(result.archive, "findings: ${result.findings}")

        archive.use {
            assertTrue(result.findings.isEmpty(), "unexpected findings: ${result.findings}")

            // An asset became an image and kept its slug, so `props.assetId` still resolves.
            // Both v6 assets do, the face's backing one included: a migration does not remove a
            // resource the archive listed, so anything referencing it keeps resolving. Only a
            // catalog authored at v7 has no separately addressable font binary.
            val images = archive.resourceDetails.values.map { it.resource }.filterIsInstance<ImageResource>()
            assertEquals(listOf("inter-regular", "municipality-mark"), images.map { it.slug }.sorted())
            val image = images.single { it.slug == "municipality-mark" }
            assertEquals(sha256(SVG), image.contentHash)
            // A migration rewrites documents and cannot move files, so the path v6 wrote stands.
            assertEquals("binaries/municipality-mark.svg", image.contentPath())

            // A face was given the binary of the asset it used to name, and says what it is.
            val font = archive.resourceDetails.values.map { it.resource }.filterIsInstance<FontResource>().single()
            val face = font.variants.single()
            assertEquals(sha256(TTF), face.contentHash)
            assertEquals("binaries/inter-regular.ttf", face.contentPath())
            assertEquals("font/ttf", face.mediaType)

            // A variant is addressed by slug, value unchanged.
            val template = archive.resourceDetails.values.map { it.resource }.filterIsInstance<TemplateResource>().single()
            assertEquals(listOf("default"), template.variants.map { it.slug })

            // Every binary the bound archive names is actually in the archive at that path.
            for (path in listOf(image.contentPath(), face.contentPath())) {
                assertTrue(archive.content.open(path).use { it.readBytes() }.isNotEmpty(), "missing $path")
            }
        }
    }

    /**
     * A v6 name that wire v7's schema no longer admits still reads, and this says so.
     *
     * v7 bounds every slug per type -- a variant is 3 to 50 characters -- but those bounds live in
     * the published JSON schema, and neither the reader nor `ResourceValidator` applies them:
     * the validator checks one loose regex for every type, and `CatalogSlugs` has no consumers.
     * So an existing v6 archive keeps installing, and a consumer that validates against the
     * published schema is the one that refuses it. Pinning this means a later decision to enforce
     * the bounds has to decide deliberately what it does to archives already published.
     */
    @Test
    fun `a v6 variant named too short for v7 still reads, because the bounds are schema-only`() {
        val result = CatalogArchiveReader.read(ByteArrayInputStream(v6Archive(variantSlug = "nl")))
        val archive = assertNotNull(result.archive, "findings: ${result.findings}")

        archive.use {
            assertTrue(result.findings.isEmpty(), "unexpected findings: ${result.findings}")
            val template = archive.resourceDetails.values.map { it.resource }.filterIsInstance<TemplateResource>().single()
            assertEquals(listOf("nl"), template.variants.map { it.slug })
        }
    }

    private fun sha256(body: ByteArray) = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }

    private fun v6Archive(variantSlug: String = "default"): ByteArray {
        val manifest = """
            {"schemaVersion":6,
             "catalog":{"slug":"town-hall","name":"Town Hall","description":null,"attributes":[],
                        "keywords":["Correspondence"],"presentation":null,"license":null},
             "publisher":{"name":"Town Hall","url":null},
             "release":{"version":"1.0.0","releasedAt":null,"fingerprint":null},
             "compatibility":null,"includes":null,"dependencies":[],
             "resources":[
               {"type":"asset","slug":"municipality-mark","name":"Municipality mark","description":null,
                "updatedAt":null,"detailUrl":"./resources/asset/municipality-mark.json","compatibility":null},
               {"type":"asset","slug":"inter-regular","name":"Inter Regular","description":null,
                "updatedAt":null,"detailUrl":"./resources/asset/inter-regular.json","compatibility":null},
               {"type":"font","slug":"inter","name":"Inter","description":null,
                "updatedAt":null,"detailUrl":"./resources/font/inter.json","compatibility":null},
               {"type":"template","slug":"letter","name":"Letter","description":null,
                "updatedAt":null,"detailUrl":"./resources/template/letter.json","compatibility":null}]}
        """.trimIndent()
        val asset = """
            {"schemaVersion":6,"resource":{"type":"asset","slug":"municipality-mark","name":"Municipality mark",
             "mediaType":"image/svg+xml","width":96,"height":96,
             "contentUrl":"./binaries/municipality-mark.svg"}}
        """.trimIndent()
        val font = """
            {"schemaVersion":6,"resource":{"type":"font","slug":"inter","name":"Inter","kind":"sansSerif",
             "variants":[{"weight":400,"italic":false,"assetSlug":"inter-regular"}]}}
        """.trimIndent()
        // A v6 face named a separate asset; that asset is an ordinary archive entry.
        val faceAsset = """
            {"schemaVersion":6,"resource":{"type":"asset","slug":"inter-regular","name":"Inter Regular",
             "mediaType":"font/ttf","width":null,"height":null,
             "contentUrl":"./binaries/inter-regular.ttf"}}
        """.trimIndent()
        val template = """
            {"schemaVersion":6,"resource":{"type":"template","slug":"letter","name":"Letter",
             "templateModel":{"modelVersion":1,"root":"n-root",
               "nodes":{"n-root":{"id":"n-root","type":"root","slots":["s-root"]}},
               "slots":{"s-root":{"id":"s-root","nodeId":"n-root","name":"children","children":[]}}},"themeId":null,"themeCatalogKey":null,"schema":null,"dataModel":null,"dataExamples":null,
             "pdfaEnabled":false,
             "variants":[{"id":"$variantSlug","title":"Default","attributes":{},"templateModel":{"modelVersion":1,"root":"n-root",
               "nodes":{"n-root":{"id":"n-root","type":"root","slots":["s-root"]}},
               "slots":{"s-root":{"id":"s-root","nodeId":"n-root","name":"children","children":[]}}},"isDefault":true}]}}
        """.trimIndent()

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, body: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body)
                zip.closeEntry()
            }
            put("catalog.json", manifest.toByteArray())
            put("resources/asset/municipality-mark.json", asset.toByteArray())
            put("resources/asset/inter-regular.json", faceAsset.toByteArray())
            put("resources/font/inter.json", font.toByteArray())
            put("resources/template/letter.json", template.toByteArray())
            put("binaries/municipality-mark.svg", SVG)
            put("binaries/inter-regular.ttf", TTF)
        }
        return out.toByteArray()
    }

    private companion object {
        val SVG = """<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96"></svg>""".toByteArray()
        val TTF = byteArrayOf(0, 1, 0, 0, 0, 13, 0, -128)
    }
}
