// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.validation

import app.epistola.template.model.TemplateDocument
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #18: the template model guide (`contracts/api/docs/template-model.md`) and the example the API spec
 * shows (`contracts/api/docs/examples/template-letter.json`) stay valid. The example is validated as a
 * template, so a contract change that breaks it fails here, and every JSON excerpt the guide quotes must
 * be the example's node or slot of that id, so the guide cannot drift from it.
 */
class TemplateModelDocsExampleTest {
    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    private val docs = Path.of("../api/docs")
    private val example = docs.resolve("examples/template-letter.json")

    @Test
    fun `the worked example is a valid template with no findings`() {
        val document = mapper.readValue(Files.readString(example), TemplateDocument::class.java)

        assertEquals(emptyList(), TemplateValidator.validate(document).findings)
    }

    @Test
    fun `every node and slot the guide quotes is the example's, verbatim`() {
        val tree = mapper.readTree(Files.readString(example))
        val blocks = Regex("```json\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)
            .findAll(Files.readString(docs.resolve("template-model.md")))
            .map { mapper.readTree(it.groupValues[1]) }
            .toList()

        assertTrue(blocks.isNotEmpty(), "the guide quotes no JSON")
        blocks.forEach { block ->
            val quoted = quotedEntries(block)
            assertTrue(quoted.isNotEmpty(), "a quoted block is neither a node, a slot nor a map of them: $block")
            quoted.forEach { (id, entry) ->
                val inExample = tree["nodes"][id] ?: tree["slots"][id]
                assertEquals(inExample, entry, "the guide's '$id' differs from the example's")
            }
        }
    }

    /** A quoted node or slot by its id, or a quoted map of them. */
    private fun quotedEntries(block: JsonNode): Map<String, JsonNode> = if (block.has("id")) {
        mapOf(block["id"].asString() to block)
    } else {
        block.properties().associate { (key, value) -> key to value }.filterValues { it.isObject && it.has("id") }
    }
}
