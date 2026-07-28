// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.validation

import app.epistola.catalog.validation.TemplateValidationCodes.STENCIL_NESTING_DEPTH_EXCEEDED
import app.epistola.catalog.validation.TemplateValidationCodes.STENCIL_RECURSION
import app.epistola.catalog.validation.TemplateValidationCodes.STENCIL_REFERENCE_INVALID
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemplateValidatorStencilNestingTest {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `five nested stencil instances are allowed`() {
        val slugs = Array(TemplateValidationLimits.MAX_STENCIL_NESTING_DEPTH) { "stencil-$it" }

        val report = TemplateValidator.validate(nestedStencils(*slugs))

        assertTrue(report.valid, report.findings.toString())
    }

    @Test
    fun `sixth nested stencil instance is rejected at its reference`() {
        val slugs = Array(TemplateValidationLimits.MAX_STENCIL_NESTING_DEPTH + 1) { "stencil-$it" }

        val report = TemplateValidator.validate(nestedStencils(*slugs))

        assertFalse(report.valid)
        assertEquals(
            listOf(
                TemplateValidationFinding(
                    code = STENCIL_NESTING_DEPTH_EXCEEDED,
                    severity = ValidationSeverity.ERROR,
                    path = "nodes.stencil-5.props.stencilId",
                    message = "stencil nesting depth 6 exceeds maximum 5",
                ),
            ),
            report.findings.filter { it.code == STENCIL_NESTING_DEPTH_EXCEEDED },
        )
    }

    @Test
    fun `stencil depth is counted through placeholder fills`() {
        val report = TemplateValidator.validate(
            nestedStencilsThroughFills(TemplateValidationLimits.MAX_STENCIL_NESTING_DEPTH + 1),
        )

        assertEquals(
            listOf("nodes.stencil-5.props.stencilId"),
            report.findings.filter { it.code == STENCIL_NESTING_DEPTH_EXCEEDED }.map { it.path },
        )
    }

    @Test
    fun `different stencil instances may nest directly in a template`() {
        val report = TemplateValidator.validate(nestedStencils("address", "contact", "signature"))

        assertTrue(report.valid, report.findings.toString())
        assertTrue(report.findings.isEmpty())
    }

    @Test
    fun `different stencil may nest in a published stencil placeholder fill`() {
        val outer = stencil("outer", "letter", listOf("outer-children"))
        val placeholder = Node(
            id = "body",
            type = "placeholder",
            slots = listOf("body-default", "body-fill"),
            props = mapOf("name" to "body", "description" to "", "kind" to "block"),
        )
        val inner = stencil("inner", "address", listOf("inner-children"))
        val document = template(
            nodes = listOf(outer, placeholder, inner),
            slots = listOf(
                Slot("outer-children", outer.id, "children", listOf(placeholder.id)),
                Slot("body-default", placeholder.id, "default"),
                Slot("body-fill", placeholder.id, "fill", listOf(inner.id)),
                Slot("inner-children", inner.id, "children"),
            ),
            rootChildren = listOf(outer.id),
        )

        val report = TemplateValidator.validate(document)

        assertTrue(report.valid, report.findings.toString())
        assertTrue(report.findings.isEmpty())
    }

    @Test
    fun `same stencil may appear in separate sibling branches`() {
        val first = stencil("first", "address", listOf("first-children"))
        val second = stencil("second", "address", listOf("second-children"))
        val document = template(
            nodes = listOf(first, second),
            slots = listOf(
                Slot("first-children", first.id, "children"),
                Slot("second-children", second.id, "children"),
            ),
            rootChildren = listOf(first.id, second.id),
        )

        val report = TemplateValidator.validate(document)

        assertTrue(report.valid, report.findings.toString())
    }

    @Test
    fun `direct self nesting is rejected at the nested reference`() {
        val report = TemplateValidator.validate(nestedStencils("address", "address"))

        assertFalse(report.valid)
        assertEquals(
            listOf("nodes.stencil-1.props.stencilId"),
            report.findings.filter { it.code == STENCIL_RECURSION }.map { it.path },
        )
    }

    @Test
    fun `indirect recursion is rejected when an ancestor stencil id repeats`() {
        val report = TemplateValidator.validate(nestedStencils("address", "contact", "address"))

        assertFalse(report.valid)
        assertEquals(
            listOf("nodes.stencil-2.props.stencilId"),
            report.findings.filter { it.code == STENCIL_RECURSION }.map { it.path },
        )
    }

    @Test
    fun `stencil definitions allow distinct embedded stencil references`() {
        val document = nestedStencils("address", "contact", "signature")

        val report = TemplateValidator.validate(
            document,
            TemplateValidationContext.forStencil("letter", "default", 1),
        )

        assertTrue(report.valid, report.findings.toString())
        assertTrue(report.findings.isEmpty())
    }

    @Test
    fun `stencil definition rejects direct reference to its owner`() {
        val document = nestedStencils("letter")

        val report = TemplateValidator.validate(
            document,
            TemplateValidationContext.forStencil("letter", "default", 1),
        )

        assertEquals(
            listOf("nodes.stencil-0.props.stencilId"),
            report.findings.filter { it.code == STENCIL_RECURSION }.map { it.path },
        )
    }

    @Test
    fun `stencil definition rejects transitive reference back to its owner`() {
        val document = nestedStencils("address", "letter")

        val report = TemplateValidator.validate(
            document,
            TemplateValidationContext.forStencil("letter", "default", 1),
        )

        assertEquals(
            listOf("nodes.stencil-1.props.stencilId"),
            report.findings.filter { it.code == STENCIL_RECURSION }.map { it.path },
        )
    }

    @Test
    fun `stencil definition depth includes the containing stencil`() {
        val allowed = TemplateValidator.validate(
            nestedStencils("one", "two", "three", "four"),
            TemplateValidationContext.forStencil("owner", "default", 1),
        )
        val rejected = TemplateValidator.validate(
            nestedStencils("one", "two", "three", "four", "five"),
            TemplateValidationContext.forStencil("owner", "default", 1),
        )

        assertTrue(allowed.valid, allowed.findings.toString())
        assertEquals(
            listOf("nodes.stencil-4.props.stencilId"),
            rejected.findings.filter { it.code == STENCIL_NESTING_DEPTH_EXCEEDED }.map { it.path },
        )
    }

    @Test
    fun `same slug in another catalog is not self reference`() {
        val nested = stencil("nested", "letter", listOf("nested-children")).copy(
            props = mapOf(
                "catalogKey" to "shared",
                "stencilId" to "letter",
                "version" to 1,
                "isDraft" to false,
            ),
        )
        val document = template(
            nodes = listOf(nested),
            slots = listOf(Slot("nested-children", nested.id, "children")),
            rootChildren = listOf(nested.id),
        )

        val report = TemplateValidator.validate(
            document,
            TemplateValidationContext.forStencil("letter", "default", 1),
        )

        assertTrue(report.valid, report.findings.toString())
    }

    @Test
    fun `nested stencil references require a valid exact version and boolean draft flag`() {
        val missingVersion = stencil("missing-version", "address", listOf("missing-children")).copy(
            props = mapOf("stencilId" to "address", "isDraft" to false),
        )
        val zeroVersion = stencil("zero-version", "contact", listOf("zero-children")).copy(
            props = mapOf("stencilId" to "contact", "version" to 0, "isDraft" to false),
        )
        val invalidDraftFlag = stencil("invalid-draft-flag", "footer", listOf("invalid-draft-children")).copy(
            props = mapOf("stencilId" to "footer", "version" to 1, "isDraft" to "false"),
        )
        val document = template(
            nodes = listOf(missingVersion, zeroVersion, invalidDraftFlag),
            slots = listOf(
                Slot("missing-children", missingVersion.id, "children"),
                Slot("zero-children", zeroVersion.id, "children"),
                Slot("invalid-draft-children", invalidDraftFlag.id, "children"),
            ),
            rootChildren = listOf(missingVersion.id, zeroVersion.id, invalidDraftFlag.id),
        )

        val report = TemplateValidator.validate(
            document,
            TemplateValidationContext.forStencil("letter", "default", 1),
        )

        assertEquals(
            listOf(
                "nodes.invalid-draft-flag.props.isDraft",
                "nodes.missing-version.props.stencilId",
                "nodes.zero-version.props.stencilId",
            ),
            report.findings.filter { it.code == STENCIL_REFERENCE_INVALID }.map { it.path },
        )
    }

    @Test
    fun `missing stencil draft flag means published`() {
        val published = stencil("published", "address", listOf("published-children")).copy(
            props = mapOf("stencilId" to "address", "version" to 3),
        )
        val document = template(
            nodes = listOf(published),
            slots = listOf(Slot("published-children", published.id, "children")),
            rootChildren = listOf(published.id),
        )
        var resolved: CatalogResourceReference? = null
        val report = TemplateValidator.validate(
            document,
            object : TemplateValidationContext {
                override val documentKind = TemplateDocumentKind.STENCIL

                override fun resolveResource(reference: CatalogResourceReference): ResourceResolution {
                    resolved = reference
                    return ResourceResolution.PRESENT
                }
            },
        )

        assertTrue(report.valid, report.findings.toString())
        assertEquals(CatalogResourceReference("stencil", "address", version = 3, isDraft = false), resolved)
    }

    @Test
    fun `draft stencil reference is valid during authoring and exposed to the resolver`() {
        val draft = stencil("draft", "address", listOf("draft-children")).copy(
            props = mapOf("stencilId" to "address", "version" to 3, "isDraft" to true),
        )
        val document = template(
            nodes = listOf(draft),
            slots = listOf(Slot("draft-children", draft.id, "children")),
            rootChildren = listOf(draft.id),
        )
        var resolved: CatalogResourceReference? = null
        val report = TemplateValidator.validate(
            document,
            object : TemplateValidationContext {
                override val documentKind = TemplateDocumentKind.STENCIL

                override fun resolveResource(reference: CatalogResourceReference): ResourceResolution {
                    resolved = reference
                    return ResourceResolution.PRESENT
                }
            },
        )

        assertTrue(report.valid, report.findings.toString())
        assertEquals(CatalogResourceReference("stencil", "address", version = 3, isDraft = true), resolved)
    }

    @Test
    fun `golden composition fixture matches standalone validator outcomes`() {
        val fixture = requireNotNull(
            javaClass.getResourceAsStream(
                "/META-INF/epistola-catalog/fixtures/v1/stencil-composition-validation.json",
            ),
        ).use(mapper::readTree)

        assertEquals(
            TemplateValidationLimits.MAX_STENCIL_NESTING_DEPTH,
            fixture["maxStencilNestingDepth"].asInt(),
        )
        fixture["validCases"]
            .filter { it["scope"].asString() == "template" }
            .forEach { valid ->
                val report = compositionScenario(valid["scenario"].asString())
                assertTrue(report.valid, "${valid["scenario"].asString()}: ${report.findings}")
            }
        fixture["invalidCases"]
            .filter { it["scope"].asString() == "template" }
            .forEach { invalid ->
                val report = compositionScenario(invalid["scenario"].asString())
                val expected: List<Pair<String, String>> = invalid["expectedFindings"].toList().map {
                    Pair(it["code"].asString(), it["path"].asString())
                }
                val actual: List<Pair<String, String>> = report.findings.map { it.code to it.path }
                assertEquals(
                    expected,
                    actual,
                    invalid["scenario"].asString(),
                )
            }
    }

    @Test
    fun `nested stencil findings are deterministic across runs`() {
        val document = nestedStencils("address", "contact", "address", "contact")

        val first = TemplateValidator.validate(document)
        repeat(10) {
            assertEquals(first, TemplateValidator.validate(document))
        }
        assertEquals(
            listOf(
                "nodes.stencil-2.props.stencilId",
                "nodes.stencil-3.props.stencilId",
            ),
            first.findings.filter { it.code == STENCIL_RECURSION }.map { it.path },
        )
    }

    private fun nestedStencils(vararg slugs: String): TemplateDocument {
        val nodes = slugs.mapIndexed { index, slug ->
            stencil("stencil-$index", slug, listOf("stencil-$index-children"))
        }
        val slots = nodes.mapIndexed { index, node ->
            Slot(
                id = "stencil-$index-children",
                nodeId = node.id,
                name = "children",
                children = nodes.getOrNull(index + 1)?.let { listOf(it.id) }.orEmpty(),
            )
        }
        return template(nodes, slots, listOf(nodes.first().id))
    }

    private fun compositionScenario(scenario: String): TemplateValidationReport = when (scenario) {
        "STENCIL_DEFINITION_REFERENCES_DISTINCT_STENCIL" ->
            TemplateValidator.validate(
                nestedStencils("address"),
                TemplateValidationContext.forStencil("letter", "default", 1),
            )
        "STENCIL_DEFINITION_REFERENCES_ITSELF" ->
            TemplateValidator.validate(
                nestedStencils("letter"),
                TemplateValidationContext.forStencil("letter", "default", 1),
            )
        "STENCIL_DEFINITION_REFERENCES_ITSELF_TRANSITIVELY" ->
            TemplateValidator.validate(
                nestedStencils("address", "letter"),
                TemplateValidationContext.forStencil("letter", "default", 1),
            )
        "STENCIL_DEFINITION_REFERENCES_DRAFT" -> {
            val document = nestedStencils("address")
            val node = requireNotNull(document.nodes["stencil-0"])
            TemplateValidator.validate(
                document.copy(
                    nodes = document.nodes + (
                        node.id to node.copy(
                            props = node.props.orEmpty() + ("isDraft" to true),
                        )
                        ),
                ),
                TemplateValidationContext.forStencil("letter", "default", 1),
            )
        }
        "STENCIL_DEFINITION_EXCEEDS_DEPTH" ->
            TemplateValidator.validate(
                nestedStencils("one", "two", "three", "four", "five"),
                TemplateValidationContext.forStencil("owner", "default", 1),
            )
        else -> error("Unknown template composition fixture scenario: $scenario")
    }

    private fun nestedStencilsThroughFills(depth: Int): TemplateDocument {
        val stencils = (0 until depth).map { index ->
            stencil("stencil-$index", "stencil-$index", listOf("stencil-$index-children"))
        }
        val placeholders = (0 until depth - 1).map { index ->
            Node(
                id = "placeholder-$index",
                type = "placeholder",
                slots = listOf("placeholder-$index-default", "placeholder-$index-fill"),
                props = mapOf("name" to "body-$index", "description" to "", "kind" to "block"),
            )
        }
        val slots = stencils.mapIndexed { index, node ->
            Slot(
                id = "stencil-$index-children",
                nodeId = node.id,
                name = "children",
                children = placeholders.getOrNull(index)?.let { listOf(it.id) }.orEmpty(),
            )
        } + placeholders.flatMapIndexed { index, placeholder ->
            listOf(
                Slot("placeholder-$index-default", placeholder.id, "default"),
                Slot(
                    "placeholder-$index-fill",
                    placeholder.id,
                    "fill",
                    listOf(stencils[index + 1].id),
                ),
            )
        }
        return template(stencils + placeholders, slots, listOf(stencils.first().id))
    }

    private fun template(
        nodes: List<Node>,
        slots: List<Slot>,
        rootChildren: List<String>,
    ): TemplateDocument = TemplateDocument(
        root = "root",
        nodes = mapOf("root" to Node("root", "root", listOf("root-children"))) + nodes.associateBy(Node::id),
        slots = mapOf("root-children" to Slot("root-children", "root", "children", rootChildren)) +
            slots.associateBy(Slot::id),
    )

    private fun stencil(
        id: String,
        slug: String,
        slots: List<String>,
    ): Node = Node(
        id = id,
        type = "stencil",
        slots = slots,
        props = mapOf(
            "stencilId" to slug,
            "version" to 1,
            "isDraft" to false,
        ),
    )
}
