package app.epistola.catalog.validation

import app.epistola.catalog.validation.TemplateValidationCodes.STENCIL_NESTING_DEPTH_EXCEEDED
import app.epistola.catalog.validation.TemplateValidationCodes.STENCIL_RECURSION
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemplateValidatorStencilNestingTest {
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
    fun `stencil definitions reject every embedded stencil reference`() {
        val document = nestedStencils("address", "contact")

        val report = TemplateValidator.validate(document, TemplateValidationContext.forStencil())

        assertFalse(report.valid)
        assertEquals(
            listOf(
                "nodes.stencil-0.props.stencilId",
                "nodes.stencil-1.props.stencilId",
            ),
            report.findings.filter { it.code == STENCIL_RECURSION }.map { it.path },
        )
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
