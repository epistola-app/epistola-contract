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
import app.epistola.catalog.validation.CatalogValidationCodes
import app.epistola.catalog.validation.CatalogValidationPolicy
import app.epistola.catalog.validation.CatalogValidator
import app.epistola.catalog.validation.ResourceResolution
import app.epistola.template.model.ThemeRefOverride
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
            // The catalog the resource dependency names is declared under its key as the alias, in the
            // catalog's own registry and namespace; the dependency keeps naming it by that key.
            assertEquals(listOf(CatalogDependency(REGISTRY, NAMESPACE, "shared", null, alias = "shared")), manifest.catalogDependencies)
            assertEquals(listOf("shared"), manifest.dependencies.orEmpty().map { it.catalogKey })

            // No stencil carries a version any more, and no reference pins one.
            val stencil = archive.resourceDetails.values.map { it.resource }.filterIsInstance<StencilResource>().single()
            val template = archive.resourceDetails.values.map { it.resource }.filterIsInstance<TemplateResource>().single()
            val stencilNodes = (stencil.content.nodes.values + template.templateModel.nodes.values + template.variants.flatMap { it.templateModel?.nodes?.values.orEmpty() })
                .filter { it.type == "stencil" }
            assertEquals(3, stencilNodes.size)
            assertTrue(stencilNodes.none { it.props.orEmpty().keys.any { key -> key == "version" || key == "draftVersion" } })
            // The stencil keeps the scope v7 rendered it with.
            assertTrue(stencil.readsTemplateData)
            assertEquals(
                setOf(
                    CatalogMigrationCodes.REGISTRY_ASSIGNED,
                    CatalogMigrationCodes.NAMESPACE_ASSIGNED,
                    CatalogMigrationCodes.DEPENDENCY_CATALOG_DECLARED,
                    CatalogMigrationCodes.STENCIL_VERSION_REMOVED,
                    CatalogMigrationCodes.STENCIL_SCOPE_KEPT,
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

    @Test
    fun `a catalog only the content of a v7 archive names is declared too, under its key as the alias`() {
        // The theme's font is in `system`, which the v7 manifest's dependencies never listed.
        val theme = """
            {"schemaVersion":7,"resource":{"type":"theme","slug":"house","name":"House",
             "documentStyles":{"fontFamily":{"catalogKey":"system","slug":"inter"}}}}
        """.trimIndent()
        val result = CatalogArchiveReader.read(ByteArrayInputStream(v7Archive("resources/theme/house.json" to theme)), migration = MIGRATION)
        val archive = assertNotNull(result.archive, "findings: ${result.findings}")

        archive.use {
            assertEquals(emptyList(), result.findings)
            assertEquals(
                listOf(
                    CatalogDependency(REGISTRY, NAMESPACE, "shared", null, alias = "shared"),
                    CatalogDependency(REGISTRY, NAMESPACE, "system", null, alias = "system"),
                ),
                archive.manifest.catalogDependencies,
            )
            val declared = result.migrationNotices.single { it.path == "catalog.json.catalogDependencies[1]" }
            assertEquals(CatalogMigrationCodes.DEPENDENCY_CATALOG_DECLARED, declared.code)
            assertTrue("resources/theme/house.json.resource.documentStyles.fontFamily.catalogKey" in declared.message, declared.message)
            assertEquals(emptyList(), CatalogValidator.validate(archive).findings.filter { it.code == CatalogValidationCodes.DEPENDENCY_CATALOG_UNDECLARED })
        }
    }

    @Test
    fun `a v7 theme reference qualified with the catalog's own namespace is mapped to the alias, and one in another namespace is a finding`() {
        fun read(themeRef: String) = CatalogArchiveReader.read(
            ByteArrayInputStream(
                v7Archive(
                    "resources/template/memo.json" to """
                        {"schemaVersion":7,"resource":{"type":"template","slug":"memo","name":"Memo","pdfaEnabled":true,
                         "templateModel":{"modelVersion":1,"root":"root","nodes":{"root":{"id":"root","type":"root","slots":[]}},
                          "slots":{},"themeRef":$themeRef},"variants":[]}}
                    """.trimIndent(),
                ),
            ),
            migration = MIGRATION,
        )

        read("""{"type":"override","themeId":"corporate","catalogKey":"shared","namespace":"$NAMESPACE","registry":"$REGISTRY"}""").archive!!.use { archive ->
            val memo = archive.resourceDetails.getValue("template/memo").resource as TemplateResource
            assertEquals(ThemeRefOverride("corporate", "shared"), memo.templateModel.themeRef)
        }
        val aliased = read("""{"type":"override","themeId":"corporate","catalogKey":"shared","namespace":"$NAMESPACE"}""")
        aliased.archive!!.close()
        assertEquals(
            listOf(CatalogMigrationCodes.REFERENCE_ALIASED to "resources/template/memo.json.resource.templateModel.themeRef"),
            aliased.migrationNotices.filter { it.code == CatalogMigrationCodes.REFERENCE_ALIASED }.map { it.code to it.path },
        )

        val elsewhere = read("""{"type":"override","themeId":"corporate","catalogKey":"shared","namespace":"other-corp"}""")
        elsewhere.archive?.close()
        assertEquals(
            listOf(CatalogMigrationCodes.REFERENCE_ALIAS_UNRESOLVED to "resources/template/memo.json.resource.templateModel.themeRef"),
            elsewhere.findings.map { it.code to it.path },
        )
    }

    @Test
    fun `a catalog the v7 content names by a key that cannot be an alias is a finding, not a declaration`() {
        val theme = """
            {"schemaVersion":7,"resource":{"type":"theme","slug":"house","name":"House",
             "documentStyles":{"fontFamily":{"catalogKey":"System","slug":"inter"}}}}
        """.trimIndent()
        val result = CatalogArchiveReader.read(ByteArrayInputStream(v7Archive("resources/theme/house.json" to theme)), migration = MIGRATION)
        result.archive?.use { archive ->
            assertEquals(listOf("shared"), archive.manifest.catalogDependencies.orEmpty().map { it.alias })
        }
        assertEquals(
            listOf(CatalogMigrationCodes.DEPENDENCY_ALIAS_INVALID to "resources/theme/house.json.resource.documentStyles.fontFamily.catalogKey"),
            result.findings.map { it.code to it.path },
        )
    }

    private fun v7Archive(vararg extra: Pair<String, String>): ByteArray {
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
               {"type":"template","slug":"letter","name":"Letter","detailUrl":"./resources/template/letter.json"}${
            extra.joinToString("") { (path, _) ->
                val (type, key) = path.removePrefix("resources/").removeSuffix(".json").split('/')
                """,{"type":"$type","slug":"$key","name":"${key.replaceFirstChar(Char::uppercase)}","detailUrl":"./$path"}"""
            }
        }]}
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
            extra.forEach { (path, body) -> put(path, body) }
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
