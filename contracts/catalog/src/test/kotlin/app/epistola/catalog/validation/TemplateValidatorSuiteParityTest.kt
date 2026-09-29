// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.validation

import app.epistola.catalog.validation.TemplateValidationCodes.NODE_PARAMETER_BINDINGS_INVALID_SHAPE
import app.epistola.catalog.validation.TemplateValidationCodes.NODE_PARAMETER_BINDING_EMPTY
import app.epistola.catalog.validation.TemplateValidationCodes.NODE_PARAMETER_BINDING_MISSING_REQUIRED
import app.epistola.catalog.validation.TemplateValidationCodes.NODE_PARAMETER_BINDING_NAME_INVALID
import app.epistola.catalog.validation.TemplateValidationCodes.NODE_PARAMETER_BINDING_SYNTAX_INVALID
import app.epistola.catalog.validation.TemplateValidationCodes.NODE_PARAMETER_BINDING_UNKNOWN
import app.epistola.catalog.validation.TemplateValidationCodes.NODE_PARAMS_ALIAS_RESERVED
import app.epistola.catalog.validation.TemplateValidationCodes.PAGEBAND_NESTED
import app.epistola.catalog.validation.TemplateValidationCodes.PAGEFOOTER_NOT_ADJACENT
import app.epistola.catalog.validation.TemplateValidationCodes.PARAMETER_DEFAULT_TYPE_MISMATCH
import app.epistola.catalog.validation.TemplateValidationCodes.PARAMETER_NAME_INVALID
import app.epistola.catalog.validation.TemplateValidationCodes.PARAMETER_NAME_RESERVED
import app.epistola.catalog.validation.TemplateValidationCodes.PARAMETER_REQUIRED_UNKNOWN
import app.epistola.catalog.validation.TemplateValidationCodes.PARAMETER_SCHEMA_INVALID_TYPE
import app.epistola.catalog.validation.TemplateValidationCodes.PARAMETER_TYPE_UNSUPPORTED
import app.epistola.catalog.validation.TemplateValidationCodes.PLACEHOLDER_NAME_DUPLICATE
import app.epistola.catalog.validation.TemplateValidationCodes.PLACEHOLDER_NESTED_DEFINITION
import app.epistola.catalog.validation.TemplateValidationCodes.PLACEHOLDER_OUTSIDE_STENCIL
import app.epistola.catalog.validation.TemplateValidationCodes.STENCIL_RECURSION
import app.epistola.catalog.validation.TemplateValidationCodes.TEMPLATE_GRAPH_INVALID
import app.epistola.catalog.validation.TemplateValidationCodes.TEMPLATE_NODE_TYPE_UNSUPPORTED
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavioral parity cases ported from Suite's former portable validators.
 *
 * Suite intentionally keeps only a context/presentation adapter; changes to
 * these semantics belong here.
 */
class TemplateValidatorSuiteParityTest {
    @Test
    fun `graph integrity rejects every unsafe ownership and reachability shape`() {
        val base = document()
        val child = text("child")
        val withChild = document(child)
        val cases = listOf(
            base.copy(root = "missing"),
            base.copy(nodes = mapOf("wrong-key" to base.nodes.getValue("root"))),
            base.copy(nodes = mapOf("root" to base.nodes.getValue("root").copy(type = "container"))),
            base.copy(nodes = mapOf("root" to base.nodes.getValue("root").copy(slots = listOf("missing"))), slots = emptyMap()),
            base.copy(slots = mapOf("root-slot" to base.slots.getValue("root-slot").copy(nodeId = "missing"))),
            withChild.copy(slots = mapOf("root-slot" to withChild.slots.getValue("root-slot").copy(children = listOf("missing")))),
            withChild.copy(nodes = withChild.nodes + ("orphan" to text("orphan"))),
            withChild.copy(
                nodes = withChild.nodes + ("container" to Node("container", "container", listOf("container-slot"))),
                slots = withChild.slots +
                    ("container-slot" to Slot("container-slot", "container", "children", listOf("child"))),
            ),
            base.copy(nodes = mapOf("root" to base.nodes.getValue("root").copy(slots = listOf("root-slot", "root-slot")))),
            withChild.copy(slots = mapOf("root-slot" to withChild.slots.getValue("root-slot").copy(children = listOf("child", "child")))),
            base.copy(slots = mapOf("root-slot" to base.slots.getValue("root-slot").copy(children = listOf("root")))),
        )

        cases.forEach { invalid ->
            assertFinding(invalid, TEMPLATE_GRAPH_INVALID)
        }
    }

    @Test
    fun `unsupported document model version is rejected`() {
        val report = TemplateValidator.validate(document().copy(modelVersion = 2))

        assertTrue(
            report.findings.any {
                it.code == TEMPLATE_GRAPH_INVALID && it.path == "modelVersion"
            },
            report.findings.toString(),
        )
    }

    @Test
    fun `unsupported node type remains a distinct portable finding`() {
        assertFinding(document(Node("child", "not-a-component")), TEMPLATE_NODE_TYPE_UNSUPPORTED)
    }

    @Test
    fun `graph size limits match Suite`() {
        val nodes = (1..501).associate { index -> "node-$index" to text("node-$index") }
        assertFinding(document().copy(nodes = document().nodes + nodes), TEMPLATE_GRAPH_INVALID)

        val oversizedChildren = (1..501).map { "child-$it" }
        val childNodes = oversizedChildren.associateWith(::text)
        val oversized = document().copy(
            nodes = document().nodes + childNodes,
            slots = mapOf("root-slot" to Slot("root-slot", "root", "children", oversizedChildren)),
        )
        assertFinding(oversized, TEMPLATE_GRAPH_INVALID)
    }

    @Test
    fun `placeholder names are scoped per stencil instance in templates`() {
        val first = stencil("first", "address")
        val second = stencil("second", "address")
        val firstPlaceholder = placeholder("first-placeholder", "body")
        val secondPlaceholder = placeholder("second-placeholder", "body")
        val valid = document(first, second).copy(
            nodes = document(first, second).nodes +
                (firstPlaceholder.id to firstPlaceholder) +
                (secondPlaceholder.id to secondPlaceholder),
            slots = document(first, second).slots +
                ("first-slot" to Slot("first-slot", first.id, "children", listOf(firstPlaceholder.id))) +
                ("second-slot" to Slot("second-slot", second.id, "children", listOf(secondPlaceholder.id))) +
                placeholderSlots(firstPlaceholder) +
                placeholderSlots(secondPlaceholder),
        )

        assertNoFinding(valid, PLACEHOLDER_NAME_DUPLICATE)

        val duplicate = valid.copy(
            slots = valid.slots +
                ("first-slot" to Slot("first-slot", first.id, "children", listOf(firstPlaceholder.id, secondPlaceholder.id))) +
                ("second-slot" to Slot("second-slot", second.id, "children")),
        )
        assertFinding(duplicate, PLACEHOLDER_NAME_DUPLICATE)
    }

    @Test
    fun `placeholder nesting differs between stencil definitions and templates`() {
        val outer = placeholder("outer", "outer")
        val inner = placeholder("inner", "inner")
        val nested = document(outer).copy(
            nodes = document(outer).nodes + (inner.id to inner),
            slots = document(outer).slots +
                placeholderSlots(outer) +
                placeholderSlots(inner) +
                ("outer-fill" to Slot("outer-fill", outer.id, "fill", listOf(inner.id))),
        )

        assertFinding(nested, PLACEHOLDER_NESTED_DEFINITION, TemplateValidationContext.forStencil())
        assertFinding(nested, PLACEHOLDER_OUTSIDE_STENCIL)
    }

    @Test
    fun `nested stencils are portable in templates and stencil definitions while recursion is rejected`() {
        val outer = stencil("outer", "address")
        val nestedDifferent = stencil("nested", "contact")
        val allowed = document(outer).copy(
            nodes = document(outer).nodes + (nestedDifferent.id to nestedDifferent),
            slots = document(outer).slots +
                ("outer-slot" to Slot("outer-slot", outer.id, "children", listOf(nestedDifferent.id))) +
                ("nested-slot" to Slot("nested-slot", nestedDifferent.id, "children")),
        )
        val allowedReport = TemplateValidator.validate(allowed)
        assertTrue(allowedReport.valid, "Nested, non-recursive stencils must remain valid: ${allowedReport.findings}")

        val nestedSame = nestedDifferent.copy(props = nestedDifferent.props.orEmpty() + ("stencilId" to "address"))
        val recursive = allowed.copy(nodes = allowed.nodes + (nestedSame.id to nestedSame))
        assertFinding(recursive, STENCIL_RECURSION)

        assertNoFinding(
            allowed,
            STENCIL_RECURSION,
            TemplateValidationContext.forStencil("letter", "default", 1),
        )
    }

    @Test
    fun `sibling instances of the same stencil are not recursion`() {
        assertNoFinding(document(stencil("first", "address"), stencil("second", "address")), STENCIL_RECURSION)
    }

    @Test
    fun `binding shape rules match Suite`() {
        val cases = listOf(
            bindingNode(parameterBindings = "not-an-object") to NODE_PARAMETER_BINDINGS_INVALID_SHAPE,
            bindingNode(parameterBindings = mapOf(1 to "value")) to NODE_PARAMETER_BINDING_NAME_INVALID,
            bindingNode(parameterBindings = mapOf("Bad name" to "value")) to NODE_PARAMETER_BINDING_NAME_INVALID,
            bindingNode(parameterBindings = mapOf("value" to " ")) to NODE_PARAMETER_BINDING_EMPTY,
            bindingNode(parameterBindings = emptyMap<String, String>(), paramsAlias = "sys") to NODE_PARAMS_ALIAS_RESERVED,
        )

        cases.forEach { (node, code) -> assertFinding(document(node), code) }
    }

    @Test
    fun `binding schema and syntax rules match Suite`() {
        assertFinding(
            document(bindingNode(parameterBindings = mapOf("value" to "1 +"))),
            NODE_PARAMETER_BINDING_SYNTAX_INVALID,
        )
        assertFinding(
            document(bindingNode(parameterBindings = mapOf("unknown" to "value"))),
            NODE_PARAMETER_BINDING_UNKNOWN,
            schemaContext(parameterSchema()),
        )
        assertFinding(
            document(bindingNode(parameterBindings = emptyMap<String, String>())),
            NODE_PARAMETER_BINDING_MISSING_REQUIRED,
            schemaContext(parameterSchema(required = listOf("value"))),
        )
        assertNoFinding(
            document(bindingNode(parameterBindings = emptyMap<String, String>())),
            NODE_PARAMETER_BINDING_MISSING_REQUIRED,
            schemaContext(parameterSchema(required = listOf("value"), default = "fallback")),
        )
    }

    @Test
    fun `parameter schema accepts Suite supported primitives and arrays`() {
        listOf("string", "number", "integer", "boolean").forEach { type ->
            assertNoParameterSchemaFinding(parameterSchema(type = type))
        }
        assertNoParameterSchemaFinding(parameterSchema(type = "array", itemType = "string"))
        assertNoParameterSchemaFinding(parameterSchema(type = "string", format = "date-time"))
    }

    @Test
    fun `parameter schema rejects every unsupported Suite shape`() {
        val cases = listOf(
            mapOf<String, Any?>() to PARAMETER_SCHEMA_INVALID_TYPE,
            mapOf("type" to "array") to PARAMETER_SCHEMA_INVALID_TYPE,
            mapOf("type" to "object", "properties" to "invalid") to PARAMETER_SCHEMA_INVALID_TYPE,
            parameterSchema(name = "kebab-case") to PARAMETER_NAME_INVALID,
            parameterSchema(name = "sys") to PARAMETER_NAME_RESERVED,
            parameterSchema(type = "object") to PARAMETER_TYPE_UNSUPPORTED,
            parameterSchema(type = "string", format = "email") to PARAMETER_TYPE_UNSUPPORTED,
            parameterSchema(type = "array") to PARAMETER_TYPE_UNSUPPORTED,
            parameterSchema(type = "array", itemType = "object") to PARAMETER_TYPE_UNSUPPORTED,
            parameterSchema(required = listOf("missing")) to PARAMETER_REQUIRED_UNKNOWN,
            parameterSchema(type = "integer", default = 1.5) to PARAMETER_DEFAULT_TYPE_MISMATCH,
            parameterSchema(type = "array", itemType = "boolean", default = listOf("false")) to PARAMETER_DEFAULT_TYPE_MISMATCH,
        )

        cases.forEach { (schema, code) -> assertParameterSchemaFinding(schema, code) }
    }

    @Test
    fun `page headers and footers are accepted in any number and anywhere in the flow`() {
        val header = pageHeader("header")
        val footer = pageFooter("footer")
        val cases = listOf(
            document(pageHeader("first"), pageHeader("second"), pageHeader("third")),
            nestedIn("container", header, footer),
            nestedIn("conditional", header),
            nestedIn("loop", footer),
            nestedIn("stencil", header, text("letter"), footer),
            document(footer, text("body"), header),
        )

        cases.forEach { document ->
            val report = TemplateValidator.validate(document)
            assertTrue(report.findings.isEmpty(), "expected no findings, got ${report.findings}")
        }
    }

    @Test
    fun `a page header or footer inside another is an error`() {
        val header = pageHeader("header")
        val footer = pageFooter("footer")
        val nested = document(header).let {
            it.copy(
                nodes = it.nodes + (footer.id to footer),
                slots = it.slots +
                    ("header-slot" to Slot("header-slot", header.id, "children", listOf(footer.id))) +
                    ("footer-slot" to Slot("footer-slot", footer.id, "children")),
            )
        }

        val finding = TemplateValidator.validate(nested).findings.single()
        assertTrue(finding.code == PAGEBAND_NESTED && finding.path == "nodes.footer", "got $finding")
    }

    @Test
    fun `footers that share a page section warn unless they are adjacent in one slot`() {
        val first = pageFooter("first")
        val second = pageFooter("second")

        assertNoFinding(document(first, second, text("body")), PAGEFOOTER_NOT_ADJACENT)
        assertNoFinding(document(first, text("body"), Node("break", "pagebreak"), second), PAGEFOOTER_NOT_ADJACENT)

        val scattered = TemplateValidator.validate(document(first, text("body"), second))
        assertTrue(scattered.valid, "a warning must not make the document invalid")
        assertTrue(
            scattered.findings.any { it.code == PAGEFOOTER_NOT_ADJACENT && it.severity == ValidationSeverity.WARNING },
            "got ${scattered.findings}",
        )

        // A letter-shell stencil ending in a footer, followed by the template's own footer.
        val shellAndOwn = nestedIn("stencil", text("letter"), first).let { shell ->
            shell.copy(
                nodes = shell.nodes + (second.id to second),
                slots = shell.slots +
                    ("root-slot" to shell.slots.getValue("root-slot").let { it.copy(children = it.children + second.id) }) +
                    ("second-slot" to Slot("second-slot", second.id, "children")),
            )
        }
        val shellReport = TemplateValidator.validate(shellAndOwn)
        assertTrue(shellReport.valid && shellReport.findings.single().code == PAGEFOOTER_NOT_ADJACENT, "got ${shellReport.findings}")
    }

    private fun assertParameterSchemaFinding(schema: Map<String, Any?>, code: String) {
        assertFinding(
            document(bindingNode(parameterBindings = emptyMap<String, String>())),
            code,
            schemaContext(schema),
        )
    }

    private fun assertNoParameterSchemaFinding(schema: Map<String, Any?>) {
        val schemaCodes = setOf(
            PARAMETER_SCHEMA_INVALID_TYPE,
            PARAMETER_NAME_INVALID,
            PARAMETER_NAME_RESERVED,
            PARAMETER_TYPE_UNSUPPORTED,
            PARAMETER_REQUIRED_UNKNOWN,
            PARAMETER_DEFAULT_TYPE_MISMATCH,
        )
        val report = TemplateValidator.validate(
            document(bindingNode(parameterBindings = emptyMap<String, String>())),
            schemaContext(schema),
        )
        assertTrue(report.findings.none { it.code in schemaCodes }, report.findings.toString())
    }

    private fun assertFinding(
        document: TemplateDocument,
        code: String,
        context: TemplateValidationContext = TemplateValidationContext.EMPTY,
    ) {
        val report = TemplateValidator.validate(document, context)
        assertFalse(report.valid)
        assertTrue(report.findings.any { it.code == code }, "Expected $code; got ${report.findings}")
    }

    private fun assertNoFinding(
        document: TemplateDocument,
        code: String,
        context: TemplateValidationContext = TemplateValidationContext.EMPTY,
    ) {
        val report = TemplateValidator.validate(document, context)
        assertTrue(report.findings.none { it.code == code }, "Unexpected $code; got ${report.findings}")
    }

    private fun document(vararg children: Node): TemplateDocument {
        val childSlots = children.flatMap { node ->
            node.slots.map { slotId ->
                val name = when {
                    slotId.endsWith("-default") -> "default"
                    slotId.endsWith("-fill") -> "fill"
                    else -> "children"
                }
                slotId to Slot(slotId, node.id, name)
            }
        }.toMap()
        return TemplateDocument(
            root = "root",
            nodes = mapOf("root" to Node("root", "root", listOf("root-slot"))) + children.associateBy(Node::id),
            slots = mapOf("root-slot" to Slot("root-slot", "root", "children", children.map(Node::id))) + childSlots,
        )
    }

    private fun text(id: String): Node = Node(
        id = id,
        type = "text",
        props = mapOf("content" to mapOf("type" to "doc", "content" to emptyList<Any>())),
    )

    private fun stencil(id: String, slug: String): Node = Node(
        id = id,
        type = "stencil",
        slots = listOf("$id-slot"),
        props = mapOf("stencilId" to slug, "version" to 1),
    )

    private fun placeholder(id: String, name: String): Node = Node(
        id = id,
        type = "placeholder",
        slots = listOf("$id-default", "$id-fill"),
        props = mapOf("name" to name, "description" to "", "kind" to "block"),
    )

    private fun placeholderSlots(node: Node): Map<String, Slot> = mapOf(
        "${node.id}-default" to Slot("${node.id}-default", node.id, "default"),
        "${node.id}-fill" to Slot("${node.id}-fill", node.id, "fill"),
    )

    private fun pageHeader(id: String): Node = Node(id, "pageheader", listOf("$id-slot"))

    private fun pageFooter(id: String): Node = Node(id, "pagefooter", listOf("$id-slot"))

    /** [children] inside a single wrapper node of [wrapperType] under the root. */
    private fun nestedIn(wrapperType: String, vararg children: Node): TemplateDocument {
        val (props, slotName) = when (wrapperType) {
            "conditional" -> mapOf("condition" to mapOf("raw" to "true", "language" to "jsonata")) to "body"
            "loop" -> mapOf("expression" to mapOf("raw" to "items", "language" to "jsonata"), "itemAlias" to "item") to "body"
            "stencil" -> mapOf("stencilId" to "letter-shell", "version" to 1) to "children"
            else -> null to "children"
        }
        val wrapper = Node("wrapper", wrapperType, listOf("wrapper-slot"), props = props)
        val base = document(wrapper, *children)
        return base.copy(
            slots = base.slots +
                ("root-slot" to base.slots.getValue("root-slot").copy(children = listOf(wrapper.id))) +
                ("wrapper-slot" to Slot("wrapper-slot", wrapper.id, slotName, children.map(Node::id))),
        )
    }

    private fun bindingNode(
        parameterBindings: Any,
        paramsAlias: Any? = null,
    ): Node {
        val props = mutableMapOf<String, Any?>(
            "stencilId" to "address",
            "version" to 1,
            "parameterBindings" to parameterBindings,
        )
        paramsAlias?.let { props["paramsAlias"] = it }
        return Node("binding", "stencil", props = props)
    }

    private fun schemaContext(schema: Map<String, Any?>): TemplateValidationContext = object : TemplateValidationContext {
        override fun resolveParameterSchema(node: Node, document: TemplateDocument): Map<String, Any?> = schema
    }

    private fun parameterSchema(
        name: String = "value",
        type: String = "string",
        itemType: String? = null,
        format: String? = null,
        required: List<String> = emptyList(),
        default: Any? = null,
    ): Map<String, Any?> {
        val property = mutableMapOf<String, Any?>("type" to type)
        itemType?.let { property["items"] = mapOf("type" to it) }
        format?.let { property["format"] = it }
        default?.let { property["default"] = it }
        return mapOf(
            "type" to "object",
            "properties" to mapOf(name to property),
            "required" to required,
        )
    }
}
