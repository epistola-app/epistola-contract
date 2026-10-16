// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.validation

import app.epistola.catalog.validation.TemplateValidationCodes.TEMPLATE_NODE_PROPERTY_INVALID
import app.epistola.catalog.validation.TemplateValidationCodes.TEMPLATE_STYLE_NOT_APPLICABLE
import app.epistola.catalog.validation.TemplateValidationCodes.TEMPLATE_STYLE_UNKNOWN
import app.epistola.template.model.BlockStylePreset
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #97: minHeight, verticalAlign, the table's cellStyles, the columns' columnStyles, and warnings
 * for style keys that have no effect.
 */
class TemplateValidatorStyleTest {
    private val mapper = JsonMapper.builder().build()

    // -- the registry ------------------------------------------------------------------------

    @Test
    fun `minHeight and verticalAlign are registry keys that all leaves out`() {
        assertTrue("minHeight" in CatalogRegistries.styleKeys)
        assertTrue("verticalAlign" in CatalogRegistries.styleKeys)
        assertEquals(setOf("minHeight", "verticalAlign"), CatalogRegistries.optInStyleKeys)
    }

    @Test
    fun `the container accepts minHeight and still accepts every style it accepted as all`() {
        val accepted = CatalogRegistries.styleKeys - CatalogRegistries.optInStyleKeys + "minHeight"
        val container = Node("box", "container", slots = listOf("box-children"), styles = accepted.associateWith { "1pt" })

        val report = TemplateValidator.validate(documentWith(container, Slot("box-children", "box", "children")))

        assertEquals(emptyList(), report.findings)
    }

    @Test
    fun `minHeight and verticalAlign apply only where they are listed`() {
        val cases = listOf(
            Node("text", "text", styles = mapOf("minHeight" to "72pt")),
            Node("text", "text", styles = mapOf("verticalAlign" to "middle")),
            Node("stencil", "stencil", slots = listOf("stencil-children"), styles = mapOf("verticalAlign" to "middle"), props = mapOf("stencilKey" to "header")),
        )
        cases.forEach { node ->
            val slots = node.slots.map { Slot(it, node.id, "children") }.toTypedArray()
            val report = TemplateValidator.validate(documentWith(node, *slots))

            assertTrue(report.valid, report.findings.toString())
            assertEquals(listOf(TEMPLATE_STYLE_NOT_APPLICABLE), report.findings.map { it.code }, "${node.type}: ${report.findings}")
            assertEquals(ValidationSeverity.WARNING, report.findings.single().severity)
        }
        val container = Node("box", "container", slots = listOf("box-children"), styles = mapOf("verticalAlign" to "middle"))
        val report = TemplateValidator.validate(documentWith(container, Slot("box-children", "box", "children")))
        assertEquals(listOf("nodes.box.styles.verticalAlign"), report.findings.map { it.path })
    }

    @Test
    fun `a datatable column accepts verticalAlign`() {
        val table = Node("dt", "datatable", slots = listOf("dt-columns"), props = mapOf("expression" to mapOf("raw" to "items", "language" to "jsonata")))
        val column = Node("col", "datatable-column", slots = listOf("col-body"), styles = mapOf("verticalAlign" to "bottom"))
        val document = documentWith(table, Slot("dt-columns", "dt", "columns", listOf("col")), Slot("col-body", "col", "body"))
            .let { it.copy(nodes = it.nodes + (column.id to column)) }

        assertEquals(emptyList(), TemplateValidator.validate(document).findings)
    }

    // -- warnings for style keys that have no effect ------------------------------------------

    @Test
    fun `an unknown or inapplicable node style is a warning and the document stays valid`() {
        val text = Node("text", "text", styles = mapOf("font-weight" to "bold", "fontWeight" to "700"))
        val columns = columns("cols", 2, styles = mapOf("fontSize" to "12pt"))

        val report = TemplateValidator.validate(documentWith(text))
        val columnsReport = TemplateValidator.validate(columns)

        assertTrue(report.valid)
        assertEquals(
            listOf(TemplateValidationFinding(TEMPLATE_STYLE_UNKNOWN, ValidationSeverity.WARNING, "nodes.text.styles.font-weight", "style 'font-weight' is not defined by the catalog style registry")),
            report.findings,
        )
        assertTrue(columnsReport.valid)
        assertEquals(
            listOf(TemplateValidationFinding(TEMPLATE_STYLE_NOT_APPLICABLE, ValidationSeverity.WARNING, "nodes.cols.styles.fontSize", "style 'fontSize' is not applicable to component 'columns'")),
            columnsReport.findings,
        )
    }

    @Test
    fun `an unknown document style override is a warning, a known one is not`() {
        val document = documentWith(Node("text", "text")).copy(
            documentStylesOverride = mapOf("fontSize" to "10pt", "backgroundColor" to "#fff", "colour" to "#000"),
        )

        val report = TemplateValidator.validate(document)

        assertTrue(report.valid)
        assertEquals(listOf("documentStylesOverride.colour" to TEMPLATE_STYLE_UNKNOWN), report.findings.map { it.path to it.code })
        assertEquals(ValidationSeverity.WARNING, report.findings.single().severity)
    }

    @Test
    fun `theme styles report unknown keys and preset keys no target component accepts`() {
        val report = TemplateValidator.validateThemeStyles(
            documentStyles = mapOf("fontSize" to "10pt", "fontsize" to "10pt"),
            blockStylePresets = mapOf(
                "card" to BlockStylePreset("Card", mapOf("minHeight" to "72pt", "padding" to "2sp")),
                "label" to BlockStylePreset("Label", mapOf("minHeight" to "72pt", "fontWeight" to "700"), applicableTo = listOf("text")),
                "typo" to BlockStylePreset("Typo", mapOf("colr" to "#000")),
            ),
        )

        assertTrue(report.valid)
        assertTrue(report.findings.all { it.severity == ValidationSeverity.WARNING })
        assertEquals(
            listOf(
                "blockStylePresets.label.styles.minHeight" to TEMPLATE_STYLE_NOT_APPLICABLE,
                "blockStylePresets.typo.styles.colr" to TEMPLATE_STYLE_UNKNOWN,
                "documentStyles.fontsize" to TEMPLATE_STYLE_UNKNOWN,
            ),
            report.findings.map { it.path to it.code },
        )
        assertEquals(
            "style 'minHeight' is not applicable to any component preset 'label' applies to (text)",
            report.findings.first().message,
        )
    }

    // -- cellStyles ----------------------------------------------------------------------------

    @Test
    fun `cellStyles inside the table with the allowed keys is valid`() {
        val document = table(
            "t",
            rows = 2,
            columns = 3,
            cellStyles = mapOf(
                "0-0" to mapOf("backgroundColor" to "#eee", "verticalAlign" to "middle"),
                "1-2" to mapOf("padding" to "2sp", "paddingTop" to "1sp", "border" to "1pt solid #000", "borderBottom" to "2pt solid #000"),
            ),
        )

        assertEquals(emptyList(), TemplateValidator.validate(document).findings)
    }

    @Test
    fun `a cell outside rows by columns is an error naming the cell`() {
        val document = table("t", rows = 2, columns = 2, cellStyles = mapOf("2-0" to mapOf("backgroundColor" to "#eee"), "0-2" to emptyMap<String, Any>()))

        val report = TemplateValidator.validate(document)

        assertFalse(report.valid)
        assertEquals(
            listOf(
                TemplateValidationFinding(TEMPLATE_NODE_PROPERTY_INVALID, ValidationSeverity.ERROR, "nodes.t.props.cellStyles.0-2", "cell '0-2' of table 't' does not exist: the table has 2 rows and 2 columns, counted from 0"),
                TemplateValidationFinding(TEMPLATE_NODE_PROPERTY_INVALID, ValidationSeverity.ERROR, "nodes.t.props.cellStyles.2-0", "cell '2-0' of table 't' does not exist: the table has 2 rows and 2 columns, counted from 0"),
            ),
            report.findings,
        )
    }

    @Test
    fun `a cell style the cell does not accept is a warning naming the cell`() {
        val document = table("t", rows = 1, columns = 1, cellStyles = mapOf("0-0" to mapOf("fontSize" to "8pt", "valign" to "top", "marginTop" to "1sp")))

        val report = TemplateValidator.validate(document)

        assertTrue(report.valid)
        assertEquals(
            listOf(
                TemplateValidationFinding(TEMPLATE_STYLE_NOT_APPLICABLE, ValidationSeverity.WARNING, "nodes.t.props.cellStyles.0-0.fontSize", "style 'fontSize' is not applicable to cell '0-0' of table 't'"),
                TemplateValidationFinding(TEMPLATE_STYLE_NOT_APPLICABLE, ValidationSeverity.WARNING, "nodes.t.props.cellStyles.0-0.marginTop", "style 'marginTop' is not applicable to cell '0-0' of table 't'"),
                TemplateValidationFinding(TEMPLATE_STYLE_UNKNOWN, ValidationSeverity.WARNING, "nodes.t.props.cellStyles.0-0.valign", "style 'valign' is not defined by the catalog style registry"),
            ),
            report.findings,
        )
    }

    @Test
    fun `malformed cellStyles are errors`() {
        val cases = mapOf(
            "nodes.t.props.cellStyles" to "not an object",
            "nodes.t.props.cellStyles.a-1" to mapOf("a-1" to mapOf("backgroundColor" to "#eee")),
            "nodes.t.props.cellStyles.0" to mapOf("0" to mapOf("backgroundColor" to "#eee")),
            "nodes.t.props.cellStyles.0-0" to mapOf("0-0" to "#eee"),
        )
        cases.forEach { (path, cellStyles) ->
            val report = TemplateValidator.validate(table("t", rows = 1, columns = 1, cellStyles = cellStyles))

            assertFalse(report.valid, path)
            assertEquals(listOf(path to TEMPLATE_NODE_PROPERTY_INVALID), report.findings.map { it.path to it.code }, path)
        }
    }

    @Test
    fun `cellStyles is declared by the table only`() {
        val container = Node("box", "container", slots = listOf("box-children"), props = mapOf("cellStyles" to emptyMap<String, Any>()))

        val report = TemplateValidator.validate(documentWith(container, Slot("box-children", "box", "children")))

        assertEquals(listOf("nodes.box.props.cellStyles" to TEMPLATE_NODE_PROPERTY_INVALID), report.findings.map { it.path to it.code })
    }

    @Test
    fun `cellStyles read from a Jackson tree validate the same`() {
        val cellStyles = mapper.readTree("""{"0-0": {"verticalAlign": "bottom"}, "5-5": {"fontSize": "8pt"}}""")
        val rows = mapper.readTree("1")

        val report = TemplateValidator.validate(table("t", rows = rows, columns = 1, cellStyles = cellStyles))

        assertEquals(
            listOf(
                "nodes.t.props.cellStyles.5-5" to TEMPLATE_NODE_PROPERTY_INVALID,
                "nodes.t.props.cellStyles.5-5.fontSize" to TEMPLATE_STYLE_NOT_APPLICABLE,
            ),
            report.findings.map { it.path to it.code },
        )
    }

    // -- columnStyles --------------------------------------------------------------------------

    @Test
    fun `columnStyles up to the number of columns with the allowed keys is valid`() {
        val full = columns("c", 2, columnStyles = listOf(mapOf("backgroundColor" to "#1e3a8a", "verticalAlign" to "middle"), mapOf("borderLeft" to "1pt solid #ccc", "padding" to "2sp")))
        val shorter = columns("c", 3, columnStyles = listOf(emptyMap<String, Any>()))

        assertEquals(emptyList(), TemplateValidator.validate(full).findings)
        assertEquals(emptyList(), TemplateValidator.validate(shorter).findings)
    }

    @Test
    fun `more columnStyles than columns is an error naming the column`() {
        val report = TemplateValidator.validate(columns("c", 2, columnStyles = listOf(emptyMap<String, Any>(), emptyMap<String, Any>(), mapOf("backgroundColor" to "#eee"))))

        assertFalse(report.valid)
        assertEquals(
            listOf(TemplateValidationFinding(TEMPLATE_NODE_PROPERTY_INVALID, ValidationSeverity.ERROR, "nodes.c.props.columnStyles[2]", "column 2 of columns 'c' does not exist: columnSizes declares 2 columns, counted from 0")),
            report.findings,
        )
    }

    @Test
    fun `malformed columnStyles are errors and inapplicable keys warnings`() {
        val notArray = TemplateValidator.validate(columns("c", 2, columnStyles = mapOf("0" to emptyMap<String, Any>())))
        val notObject = TemplateValidator.validate(columns("c", 2, columnStyles = listOf(null)))
        val inapplicable = TemplateValidator.validate(columns("c", 2, columnStyles = listOf(mapOf("width" to "50%"))))

        assertEquals(listOf("nodes.c.props.columnStyles" to TEMPLATE_NODE_PROPERTY_INVALID), notArray.findings.map { it.path to it.code })
        assertEquals(listOf("nodes.c.props.columnStyles[0]" to TEMPLATE_NODE_PROPERTY_INVALID), notObject.findings.map { it.path to it.code })
        assertTrue(inapplicable.valid)
        assertEquals(
            listOf(TemplateValidationFinding(TEMPLATE_STYLE_NOT_APPLICABLE, ValidationSeverity.WARNING, "nodes.c.props.columnStyles[0].width", "style 'width' is not applicable to column 0 of columns 'c'")),
            inapplicable.findings,
        )
    }

    // -- helpers -------------------------------------------------------------------------------

    private fun documentWith(node: Node, vararg slots: Slot) = TemplateDocument(
        root = "root",
        nodes = mapOf("root" to Node("root", "root", listOf("root-slot")), node.id to node),
        slots = mapOf("root-slot" to Slot("root-slot", "root", "children", listOf(node.id))) + slots.associateBy(Slot::id),
    )

    private fun table(id: String, rows: Any, columns: Int, cellStyles: Any?): TemplateDocument {
        val rowCount = (rows as? Number)?.toInt() ?: (rows as tools.jackson.databind.JsonNode).asInt()
        val cells = (0 until rowCount).flatMap { r -> (0 until columns).map { c -> Slot("$id-$r-$c", id, "cell-$r-$c") } }
        val node = Node(
            id,
            "table",
            slots = cells.map(Slot::id),
            props = mapOf("rows" to rows, "columns" to columns, "cellStyles" to cellStyles),
        )
        return documentWith(node, *cells.toTypedArray())
    }

    private fun columns(id: String, count: Int, columnStyles: Any? = null, styles: Map<String, Any?>? = null): TemplateDocument {
        val slots = (0 until count).map { Slot("$id-$it", id, "column-$it") }
        val props = buildMap<String, Any?> {
            put("columnSizes", List(count) { 1 })
            if (columnStyles != null) put("columnStyles", columnStyles)
        }
        return documentWith(Node(id, "columns", slots = slots.map(Slot::id), styles = styles, props = props), *slots.toTypedArray())
    }
}
