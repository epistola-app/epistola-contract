// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.protocol

import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CatalogWireParityTest {
    private val mapper = jsonMapper { addModule(kotlinModule()) }

    @Test
    fun `v5 manifest round trips through the public Kotlin model`() {
        val manifest = fixture("wire-v5/catalog.json").use { mapper.readValue(it, CatalogManifest::class.java) }

        assertEquals(manifest, mapper.readValue(mapper.writeValueAsBytes(manifest), CatalogManifest::class.java))
    }

    @Test
    fun `v5 resource detail round trips through the public Kotlin model`() {
        val detail = fixture("wire-v5/resources/theme/default.json").use { mapper.readValue(it, ResourceDetail::class.java) }

        assertEquals(detail, mapper.readValue(mapper.writeValueAsBytes(detail), ResourceDetail::class.java))
    }

    @Test
    fun `versioned and current wire schemas are published`() {
        listOf(
            "catalog-manifest-v5.schema.json",
            "catalog-manifest.schema.json",
            "resource-detail-v5.schema.json",
            "resource-detail.schema.json",
        ).forEach { name ->
            assertNotNull(javaClass.getResource("/META-INF/epistola-catalog/schemas/$name"), name)
        }
    }

    private fun fixture(path: String) = requireNotNull(
        javaClass.getResourceAsStream("/META-INF/epistola-catalog/fixtures/v1/$path"),
    )
}
