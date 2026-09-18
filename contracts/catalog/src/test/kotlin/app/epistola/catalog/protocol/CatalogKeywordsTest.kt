// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.protocol

import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogKeywordsTest {
    private val mapper = jsonMapper()

    @Test
    fun `normalization fixture defines every published case`() {
        cases().forEach { case ->
            val input = case["input"].asString()
            val expected = case["expected"].takeUnless { it.isNull }?.asString()

            assertEquals(expected, CatalogKeywords.normalize(input), input)
        }
    }

    @Test
    fun `normalized keywords are valid and normalizing again changes nothing`() {
        cases().mapNotNull { CatalogKeywords.normalize(it["input"].asString()) }.forEach { keyword ->
            assertTrue(CatalogKeywords.isValid(keyword), keyword)
            assertEquals(keyword, CatalogKeywords.normalize(keyword))
        }
    }

    @Test
    fun `validity follows the pattern and the length limit`() {
        listOf("letters", "1-loket", "getting-started", "a".repeat(30)).forEach { assertTrue(CatalogKeywords.isValid(it), it) }
        listOf("", "Letters", "getting started", "-a", "a-", "a--b", "financiële", "a".repeat(31)).forEach {
            assertFalse(CatalogKeywords.isValid(it), it)
        }
    }

    @Test
    fun `constants match the v7 manifest schema`() {
        val schema = requireNotNull(
            javaClass.getResourceAsStream("/META-INF/epistola-catalog/schemas/catalog-manifest-v7.schema.json"),
        ).use(mapper::readTree)
        val keyword = schema["\$defs"]["CatalogKeyword"]
        val keywords = schema["\$defs"]["CatalogInfo"]["properties"]["keywords"]["oneOf"][0]

        assertEquals(CatalogKeywords.MAX_LENGTH, keyword["maxLength"].asInt())
        assertEquals(CatalogKeywords.PATTERN, keyword["pattern"].asString())
        assertEquals(CatalogKeywords.MAX_COUNT, keywords["maxItems"].asInt())
        assertEquals("#/\$defs/CatalogKeyword", keywords["items"]["\$ref"].asString())
    }

    private fun cases(): List<JsonNode> = requireNotNull(
        javaClass.getResourceAsStream("/META-INF/epistola-catalog/fixtures/v1/migrations/v6-to-v7/keyword-normalization.json"),
    ).use(mapper::readTree)["cases"].toList()
}
