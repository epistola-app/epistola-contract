// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.archive

import app.epistola.catalog.migration.CatalogMigrationCodes
import app.epistola.catalog.migration.CatalogMigrationOptions
import app.epistola.catalog.migration.CatalogWireSchema
import app.epistola.catalog.protocol.CatalogDependency
import app.epistola.catalog.protocol.StencilResource
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.catalog.validation.CatalogDependencyResolver
import app.epistola.catalog.validation.CatalogResourceReference
import app.epistola.catalog.validation.CatalogValidationPolicy
import app.epistola.catalog.validation.CatalogValidator
import app.epistola.catalog.validation.ResourceResolution
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A whole catalog-v7 archive, shaped the way a v7 publisher wrote one, read by the current reader.
 *
 * The migration tests work on one document at a time; this one holds the steps together. A v7
 * catalog names no registry or namespace, depends on other catalogs only through resource dependencies, and
 * pins every stencil reference to a version, inside its own catalog and across catalogs.
 */
class CatalogV7ArchiveCompatibilityTest {
    @Test
    fun `a v7 archive binds at the current version under the registry and namespace its importer chooses`() {
        val result = CatalogArchiveReader.read(ByteArrayInputStream(v7Archive()), migration = MIGRATION)
        val archive = assertNotNull(result.archive, "findings: ${result.findings}")

        archive.use {
            assertTrue(result.findings.isEmpty(), "unexpected findings: ${result.findings}")
            val manifest = archive.manifest
            assertEquals(CatalogWireSchema.CURRENT_VERSION, manifest.schemaVersion)
            assertEquals(REGISTRY, manifest.catalog.registry)
            assertEquals(NAMESPACE, manifest.catalog.namespace)
            assertEquals(listOf(CatalogDependency(REGISTRY, NAMESPACE, "shared", null)), manifest.catalogDependencies)
            assertEquals(listOf(REGISTRY to NAMESPACE), manifest.dependencies.orEmpty().map { it.registry to it.namespace })

            // No stencil carries a version any more, and no reference pins one.
            val stencil = archive.resourceDetails.values.map { it.resource }.filterIsInstance<StencilResource>().single()
            val template = archive.resourceDetails.values.map { it.resource }.filterIsInstance<TemplateResource>().single()
            val stencilNodes = (stencil.content.nodes.values + template.templateModel.nodes.values + template.variants.flatMap { it.templateModel?.nodes?.values.orEmpty() })
                .filter { it.type == "stencil" }
            assertEquals(3, stencilNodes.size)
            assertTrue(stencilNodes.none { it.props.orEmpty().keys.any { key -> key == "version" || key == "draftVersion" } })
            assertEquals(
                setOf(
                    CatalogMigrationCodes.REGISTRY_ASSIGNED,
                    CatalogMigrationCodes.NAMESPACE_ASSIGNED,
                    CatalogMigrationCodes.DEPENDENCY_REGISTRY_ASSIGNED,
                    CatalogMigrationCodes.DEPENDENCY_NAMESPACE_ASSIGNED,
                    CatalogMigrationCodes.DEPENDENCY_CATALOG_DECLARED,
                    CatalogMigrationCodes.STENCIL_VERSION_REMOVED,
                ),
                result.migrationNotices.mapTo(mutableSetOf()) { it.code },
            )
        }
    }

    @Test
    fun `a migrated v7 archive validates, resolving its cross-catalog stencil through the dependency`() {
        val resolved = mutableListOf<CatalogResourceReference>()
        val policy = CatalogValidationPolicy(
            dependencyResolver = CatalogDependencyResolver { reference ->
                resolved += reference
                ResourceResolution.PRESENT
            },
            migration = MIGRATION,
        )

        val report = CatalogValidator.validate(ByteArrayInputStream(v7Archive()), policy)

        assertEquals(emptyList(), report.findings)
        assertTrue(CatalogResourceReference("stencil", "footer", "shared", NAMESPACE, REGISTRY) in resolved, resolved.toString())
    }

    private fun v7Archive(): ByteArray {
        val manifest = """
            {"schemaVersion":7,
             "catalog":{"slug":"letters","name":"Letters","description":null,"attributes":[],"keywords":["letters"],
                        "presentation":null,"license":null},
             "publisher":{"name":"Acme","url":null},
             "release":{"version":"1.0.0","releasedAt":null,"fingerprint":null},
             "compatibility":null,"includes":null,
             "dependencies":[{"type":"stencil","catalogKey":"shared","slug":"footer"}],
             "resources":[
               {"type":"stencil","slug":"address","name":"Address","detailUrl":"./resources/stencil/address.json"},
               {"type":"template","slug":"letter","name":"Letter","detailUrl":"./resources/template/letter.json"}]}
        """.trimIndent()
        val stencil = """
            {"schemaVersion":7,"resource":{"type":"stencil","slug":"address","name":"Address","version":4,"tags":[],
             "content":${document("shared-footer" to """{"stencilId":"footer","catalogKey":"shared","version":2}""")}}}
        """.trimIndent()
        val template = """
            {"schemaVersion":7,"resource":{"type":"template","slug":"letter","name":"Letter","pdfaEnabled":true,
             "templateModel":${document("address" to """{"stencilId":"address","version":4}""")},
             "variants":[{"slug":"english","title":"English","attributes":{},"isDefault":true,
               "templateModel":${document("address" to """{"stencilId":"address","version":4}""")}}]}}
        """.trimIndent()

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
            put("catalog.json", manifest)
            put("resources/stencil/address.json", stencil)
            put("resources/template/letter.json", template)
        }
        return out.toByteArray()
    }

    /** A document whose root holds one stencil node [nested] with the given props. */
    private fun document(nested: Pair<String, String>): String {
        val (id, props) = nested
        return """
            {"modelVersion":1,"root":"root",
             "nodes":{"root":{"id":"root","type":"root","slots":["children"]},
                      "$id":{"id":"$id","type":"stencil","slots":["$id-children"],"props":$props}},
             "slots":{"children":{"id":"children","nodeId":"root","name":"children","children":["$id"]},
                      "$id-children":{"id":"$id-children","nodeId":"$id","name":"children","children":[]}},
             "themeRef":{"type":"inherit"}}
        """.trimIndent()
    }

    private companion object {
        const val REGISTRY = "exchange.epistola.app"
        const val NAMESPACE = "acme-corp"

        /** A v7 archive names no registry or namespace; the reader's caller supplies both, as an importer would. */
        val MIGRATION = CatalogMigrationOptions(NAMESPACE, REGISTRY)
    }
}
