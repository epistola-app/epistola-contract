// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.protocol

import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `v6 manifest and resource detail round trip through the public Kotlin model`() {
        val manifest = fixture("wire-v6/catalog.json").use { mapper.readValue(it, CatalogManifest::class.java) }
        val detail = fixture("wire-v6/resources/theme/default.json").use { mapper.readValue(it, ResourceDetail::class.java) }

        assertEquals(manifest, mapper.readValue(mapper.writeValueAsBytes(manifest), CatalogManifest::class.java))
        assertEquals(detail, mapper.readValue(mapper.writeValueAsBytes(detail), ResourceDetail::class.java))
    }

    @Test
    fun `catalog info factory normalizes additive v6 metadata`() {
        val info = CatalogInfo.create(
            slug = "fixture",
            name = "Fixture",
            attributes = listOf(AttributeAssignment("system", "locale", "nl-NL")),
            keywords = linkedSetOf("zoning", "documents"),
            presentation = CatalogPresentation("icon", listOf("hero")),
        )

        assertEquals(listOf("documents", "zoning"), info.keywords.toList())
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (info.keywords as MutableSet<String>).add("mutable")
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (info.attributes as MutableList<AttributeAssignment>).add(AttributeAssignment("system", "brand", "epistola"))
        }
        assertEquals(
            "en-GB",
            info.copyWithMetadata(attributes = listOf(AttributeAssignment("system", "locale", "en-GB")))
                .attributes.single().value,
        )
    }

    @Test
    fun `legacy catalog info constructor remains source compatible`() {
        val info = CatalogInfo("fixture", "Fixture")

        assertEquals("fixture", info.slug)
        assertEquals("Fixture", info.name)
        assertEquals(null, info.description)
        assertEquals(emptyList(), info.attributes)
        assertEquals(emptySet(), info.keywords)
        assertEquals(null, info.presentation)
    }

    @Test
    fun `versioned and current wire schemas are published`() {
        listOf(
            "catalog-manifest-v5.schema.json",
            "catalog-manifest.schema.json",
            "resource-detail-v5.schema.json",
            "catalog-manifest-v6.schema.json",
            "resource-detail-v6.schema.json",
            "resource-detail.schema.json",
        ).forEach { name ->
            assertNotNull(javaClass.getResource("/META-INF/epistola-catalog/schemas/$name"), name)
        }
    }

    private fun fixture(path: String) = requireNotNull(
        javaClass.getResourceAsStream("/META-INF/epistola-catalog/fixtures/v1/$path"),
    )
}
