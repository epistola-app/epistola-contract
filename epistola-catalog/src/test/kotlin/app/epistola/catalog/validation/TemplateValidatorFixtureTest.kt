package app.epistola.catalog.validation

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRefOverride
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemplateValidatorFixtureTest {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `valid fixture has no findings`() {
        val report = TemplateValidator.validate(validDocument())

        assertTrue(report.valid)
        assertTrue(report.findings.isEmpty())
    }

    @Test
    fun `every fixture produces its stable finding code`() {
        val fixture = javaClass.getResourceAsStream("/META-INF/epistola-catalog/fixtures/v1/template-validation.json")!!.use(mapper::readTree)
        val fixtureCodes = fixture["invalidCases"].mapTo(mutableSetOf<String>()) { it["code"].asString() }

        assertEquals(
            TemplateValidationLimits.MAX_STENCIL_NESTING_DEPTH,
            fixture["limits"]["maxStencilNestingDepth"].asInt(),
        )
        assertEquals(
            TemplateValidationCodes.ALL,
            fixtureCodes,
            "versioned fixtures must contain exactly one focused case for every public finding code",
        )

        fixture["invalidCases"].forEach { invalid ->
            val code = invalid["code"].asString()
            val scenario = invalid["scenario"].asString()
            val (document, context) = scenario(scenario)
            val report = TemplateValidator.validate(document, context)

            assertFalse(report.valid, scenario)
            assertTrue(report.findings.any { it.code == code }, "$scenario should produce $code; got ${report.findings}")
            assertEquals(
                report.findings.sortedWith(compareBy({ it.path }, { it.code }, { it.message })),
                report.findings,
                "$scenario findings must be deterministic",
            )
        }
    }

    private fun scenario(name: String): Pair<TemplateDocument, TemplateValidationContext> = when (name) {
        "ROOT_MISSING" -> validDocument().copy(root = "missing") to TemplateValidationContext.EMPTY
        "UNSUPPORTED_NODE" -> withChild(Node("n-child", "unsupported")) to TemplateValidationContext.EMPTY
        "INVALID_SLOT" -> validDocument().copy(slots = mapOf("s-root" to Slot("s-root", "n-root", "wrong"))) to TemplateValidationContext.EMPTY
        "CHILD_NOT_ALLOWED" -> datatableWith(Node("n-child", "text")) to TemplateValidationContext.EMPTY
        "INVALID_PROPERTY" -> withChild(Node("n-child", "text", props = mapOf("content" to 42))) to TemplateValidationContext.EMPTY
        "UNKNOWN_STYLE" -> withChild(Node("n-child", "text", styles = mapOf("unknown" to "x"))) to TemplateValidationContext.EMPTY
        "STYLE_NOT_APPLICABLE" -> validDocument().copy(
            nodes = mapOf("n-root" to validDocument().nodes.getValue("n-root").copy(styles = mapOf("color" to "#fff"))),
        ) to TemplateValidationContext.EMPTY
        "UNKNOWN_STYLE_PRESET" -> withChild(Node("n-child", "text", stylePreset = "missing")) to presets(emptySet())
        "THEME_NOT_FOUND" -> validDocument().copy(themeRef = ThemeRefOverride("missing")) to missingResources()
        "INVALID_EXPRESSION" -> withChild(Node("n-child", "richTextVariable", props = mapOf("binding" to "1 +"))) to TemplateValidationContext.EMPTY
        "DUPLICATE_PLACEHOLDER" -> stencilDefinition(placeholder("p-one", "same"), placeholder("p-two", "same")) to TemplateValidationContext.forStencil()
        "INVALID_PLACEHOLDER_NAME" -> stencilDefinition(placeholder("p-one", "Not Valid")) to TemplateValidationContext.forStencil()
        "NESTED_PLACEHOLDER" -> nestedPlaceholders() to TemplateValidationContext.forStencil()
        "PLACEHOLDER_OUTSIDE_STENCIL" -> stencilDefinition(placeholder("p-one", "body")) to TemplateValidationContext.EMPTY
        "STENCIL_DEPTH_EXCEEDED" -> stencilChain(TemplateValidationLimits.MAX_STENCIL_NESTING_DEPTH + 1) to TemplateValidationContext.EMPTY
        "STENCIL_RECURSION" -> recursiveStencils() to TemplateValidationContext.EMPTY
        "INVALID_STENCIL_REFERENCE" -> withChild(stencil("n-stencil", "")) to TemplateValidationContext.EMPTY
        "STENCIL_NOT_FOUND" -> withChild(stencil("n-stencil", "invoice")) to missingResources()
        "UNKNOWN_BINDING" -> bindingDocument(mapOf("extra" to "value"), parameterSchema()) to TemplateValidationContext.EMPTY
        "INVALID_BINDING_SYNTAX" -> bindingDocument(mapOf("value" to "1 +"), parameterSchema()) to TemplateValidationContext.EMPTY
        "MISSING_REQUIRED_BINDING" -> bindingDocument(emptyMap<String, String>(), parameterSchema(required = listOf("value"))) to TemplateValidationContext.EMPTY
        "INVALID_BINDINGS_SHAPE" -> bindingDocument("value", parameterSchema()) to TemplateValidationContext.EMPTY
        "INVALID_BINDING_NAME" -> bindingDocument(mapOf("Bad name" to "value"), parameterSchema()) to TemplateValidationContext.EMPTY
        "EMPTY_BINDING" -> bindingDocument(mapOf("value" to " "), parameterSchema()) to TemplateValidationContext.EMPTY
        "RESERVED_ALIAS" -> bindingDocument(emptyMap<String, String>(), parameterSchema(), "sys") to TemplateValidationContext.EMPTY
        "INVALID_PARAMETER_SCHEMA" -> bindingDocument(emptyMap<String, String>(), mapOf("type" to "array")) to TemplateValidationContext.EMPTY
        "UNKNOWN_REQUIRED_PARAMETER" -> bindingDocument(emptyMap<String, String>(), parameterSchema(required = listOf("missing"))) to TemplateValidationContext.EMPTY
        "INVALID_PARAMETER_NAME" -> bindingDocument(emptyMap<String, String>(), parameterSchema("Bad name")) to TemplateValidationContext.EMPTY
        "RESERVED_PARAMETER_NAME" -> bindingDocument(emptyMap<String, String>(), parameterSchema("sys")) to TemplateValidationContext.EMPTY
        "UNSUPPORTED_PARAMETER_TYPE" -> bindingDocument(emptyMap<String, String>(), parameterSchema(type = "object")) to TemplateValidationContext.EMPTY
        "PARAMETER_DEFAULT_MISMATCH" -> bindingDocument(emptyMap<String, String>(), parameterSchema(default = 42)) to TemplateValidationContext.EMPTY
        "TOO_MANY_PAGEHEADERS" -> withChildren(pageHeader("h-1"), pageHeader("h-2"), pageHeader("h-3")) to TemplateValidationContext.EMPTY
        "PAGEHEADER_WITHOUT_ROOT" -> withChild(pageHeader("h-1")).copy(root = "missing") to TemplateValidationContext.EMPTY
        "PAGEHEADER_NOT_AT_ROOT" -> nestedPageHeader() to TemplateValidationContext.EMPTY
        else -> error("Unknown fixture scenario: $name")
    }

    private fun validDocument(): TemplateDocument = TemplateDocument(
        root = "n-root",
        nodes = mapOf("n-root" to Node("n-root", "root", slots = listOf("s-root"))),
        slots = mapOf("s-root" to Slot("s-root", "n-root", "children")),
    )

    private fun withChild(child: Node): TemplateDocument = withChildren(child)

    private fun withChildren(vararg children: Node): TemplateDocument {
        val base = validDocument()
        return base.copy(
            nodes = base.nodes + children.associateBy(Node::id),
            slots = base.slots + ("s-root" to base.slots.getValue("s-root").copy(children = children.map(Node::id))),
        )
    }

    private fun datatableWith(child: Node): TemplateDocument {
        val table = Node("n-table", "datatable", slots = listOf("s-columns"))
        return withChild(table).copy(
            nodes = withChild(table).nodes + (child.id to child),
            slots = withChild(table).slots + ("s-columns" to Slot("s-columns", table.id, "columns", listOf(child.id))),
        )
    }

    private fun placeholder(id: String, name: String): Node = Node(
        id,
        "placeholder",
        slots = listOf("$id-default", "$id-fill"),
        props = mapOf("name" to name, "description" to "", "kind" to "block"),
    )

    private fun pageHeader(id: String): Node = Node(id, "pageheader", slots = listOf("$id-children"))

    private fun stencil(id: String, slug: String): Node = Node(
        id,
        "stencil",
        slots = listOf("$id-children"),
        props = mapOf("stencilId" to slug, "version" to 1, "isDraft" to false),
    )

    private fun stencilDefinition(vararg placeholders: Node): TemplateDocument {
        val base = withChildren(*placeholders)
        return base.copy(
            slots = base.slots + placeholders.flatMap { node ->
                listOf(
                    "${node.id}-default" to Slot("${node.id}-default", node.id, "default"),
                    "${node.id}-fill" to Slot("${node.id}-fill", node.id, "fill"),
                )
            },
        )
    }

    private fun nestedPlaceholders(): TemplateDocument {
        val outer = placeholder("p-outer", "outer")
        val inner = placeholder("p-inner", "inner")
        val base = stencilDefinition(outer).copy(nodes = stencilDefinition(outer).nodes + (inner.id to inner))
        return base.copy(
            slots = base.slots +
                ("p-outer-fill" to Slot("p-outer-fill", outer.id, "fill", listOf(inner.id))) +
                ("p-inner-default" to Slot("p-inner-default", inner.id, "default")) +
                ("p-inner-fill" to Slot("p-inner-fill", inner.id, "fill")),
        )
    }

    private fun recursiveStencils(): TemplateDocument {
        val outer = stencil("s-outer", "invoice")
        val inner = stencil("s-inner", "invoice")
        val base = withChild(outer)
        return base.copy(
            nodes = base.nodes + (inner.id to inner),
            slots = base.slots +
                ("s-outer-children" to Slot("s-outer-children", outer.id, "children", listOf(inner.id))) +
                ("s-inner-children" to Slot("s-inner-children", inner.id, "children")),
        )
    }

    private fun stencilChain(depth: Int): TemplateDocument {
        val stencils = (0 until depth).map { index -> stencil("s-$index", "stencil-$index") }
        val base = withChild(stencils.first())
        return base.copy(
            nodes = base.nodes + stencils.drop(1).associateBy(Node::id),
            slots = base.slots + stencils.mapIndexed { index, node ->
                "${node.id}-children" to Slot(
                    "${node.id}-children",
                    node.id,
                    "children",
                    stencils.getOrNull(index + 1)?.let { listOf(it.id) }.orEmpty(),
                )
            },
        )
    }

    private fun bindingDocument(
        bindings: Any,
        schema: Map<String, Any?>,
        alias: String? = null,
    ): TemplateDocument {
        val props = mutableMapOf<String, Any?>(
            "stencilId" to "invoice",
            "version" to 1,
            "isDraft" to false,
            "parameterBindings" to bindings,
            "parameterSchemaSnapshot" to schema,
        )
        alias?.let { props["paramsAlias"] = it }
        val node = Node("n-stencil", "stencil", slots = listOf("s-stencil"), props = props)
        val base = withChild(node)
        return base.copy(slots = base.slots + ("s-stencil" to Slot("s-stencil", node.id, "children")))
    }

    private fun parameterSchema(
        name: String = "value",
        type: String = "string",
        required: List<String> = emptyList(),
        default: Any? = null,
    ): Map<String, Any?> {
        val property = mutableMapOf<String, Any?>("type" to type)
        if (default != null) property["default"] = default
        return mapOf("type" to "object", "properties" to mapOf(name to property), "required" to required)
    }

    private fun nestedPageHeader(): TemplateDocument {
        val container = Node("n-container", "container", slots = listOf("s-container"))
        val header = pageHeader("h-1")
        val base = withChild(container)
        return base.copy(
            nodes = base.nodes + (header.id to header),
            slots = base.slots +
                ("s-container" to Slot("s-container", container.id, "children", listOf(header.id))) +
                ("h-1-children" to Slot("h-1-children", header.id, "children")),
        )
    }

    private fun presets(values: Set<String>): TemplateValidationContext = object : TemplateValidationContext {
        override fun resolveStylePresets(document: TemplateDocument): Set<String> = values
    }

    private fun missingResources(): TemplateValidationContext = object : TemplateValidationContext {
        override fun resolveResource(reference: CatalogResourceReference): ResourceResolution = ResourceResolution.MISSING
    }
}
