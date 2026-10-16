// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.validation

import app.epistola.catalog.protocol.CatalogKeys
import app.epistola.catalog.protocol.CatalogNamespaces
import app.epistola.catalog.validation.TemplateValidationCodes.NODE_PARAMETER_BINDINGS_INVALID_SHAPE
import app.epistola.catalog.validation.TemplateValidationCodes.NODE_PARAMETER_BINDING_EMPTY
import app.epistola.catalog.validation.TemplateValidationCodes.NODE_PARAMETER_BINDING_MISSING_REQUIRED
import app.epistola.catalog.validation.TemplateValidationCodes.NODE_PARAMETER_BINDING_NAME_INVALID
import app.epistola.catalog.validation.TemplateValidationCodes.NODE_PARAMETER_BINDING_SYNTAX_INVALID
import app.epistola.catalog.validation.TemplateValidationCodes.NODE_PARAMETER_BINDING_UNKNOWN
import app.epistola.catalog.validation.TemplateValidationCodes.NODE_PARAMS_ALIAS_RESERVED
import app.epistola.catalog.validation.TemplateValidationCodes.PAGEBAND_NESTED
import app.epistola.catalog.validation.TemplateValidationCodes.PLACEHOLDER_NAME_DUPLICATE
import app.epistola.catalog.validation.TemplateValidationCodes.PLACEHOLDER_NAME_INVALID
import app.epistola.catalog.validation.TemplateValidationCodes.PLACEHOLDER_NESTED_DEFINITION
import app.epistola.catalog.validation.TemplateValidationCodes.PLACEHOLDER_OUTSIDE_STENCIL
import app.epistola.catalog.validation.TemplateValidationCodes.STENCIL_NESTING_DEPTH_EXCEEDED
import app.epistola.catalog.validation.TemplateValidationCodes.STENCIL_RECURSION
import app.epistola.catalog.validation.TemplateValidationCodes.STENCIL_REFERENCE_INVALID
import app.epistola.catalog.validation.TemplateValidationCodes.STENCIL_REFERENCE_NOT_FOUND
import app.epistola.catalog.validation.TemplateValidationCodes.TEMPLATE_CHILD_TYPE_NOT_ALLOWED
import app.epistola.catalog.validation.TemplateValidationCodes.TEMPLATE_EXPRESSION_INVALID
import app.epistola.catalog.validation.TemplateValidationCodes.TEMPLATE_GRAPH_INVALID
import app.epistola.catalog.validation.TemplateValidationCodes.TEMPLATE_NODE_PROPERTY_INVALID
import app.epistola.catalog.validation.TemplateValidationCodes.TEMPLATE_NODE_TYPE_UNSUPPORTED
import app.epistola.catalog.validation.TemplateValidationCodes.TEMPLATE_SLOT_INVALID
import app.epistola.catalog.validation.TemplateValidationCodes.TEMPLATE_STYLE_NOT_APPLICABLE
import app.epistola.catalog.validation.TemplateValidationCodes.TEMPLATE_STYLE_PRESET_UNKNOWN
import app.epistola.catalog.validation.TemplateValidationCodes.TEMPLATE_STYLE_UNKNOWN
import app.epistola.catalog.validation.TemplateValidationCodes.TEMPLATE_THEME_NOT_FOUND
import app.epistola.template.model.BlockStylePreset
import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRefOverride
import com.dashjoin.jsonata.Jsonata.jsonata
import tools.jackson.databind.JsonNode

/**
 * Portable validator for normalized Epistola template documents.
 *
 * Validation covers graph integrity, component and style registries, slot
 * cardinality and allowed children, property shapes, expressions,
 * placeholders, page header and footer placement, stencil references, parameter bindings, theme
 * references, recursion, and nesting depth. Consumer-owned resource lookups
 * enter only through [TemplateValidationContext].
 *
 * A style key that has no effect is a [ValidationSeverity.WARNING], never an error, wherever a
 * style map appears: node `styles`, `documentStylesOverride`, the style maps a component declares
 * (`cellStyles`, `columnStyles`), and a theme's styles ([validateThemeStyles]). The key is either
 * not in the style registry ([TemplateValidationCodes.TEMPLATE_STYLE_UNKNOWN]) or not accepted
 * where it is set ([TemplateValidationCodes.TEMPLATE_STYLE_NOT_APPLICABLE]). Renderers ignore such
 * keys, so the document stays valid.
 *
 * Ordinary invalid content never throws. Findings are de-duplicated and sorted
 * by path, code, and message so JVM consumers and golden fixtures observe the
 * same deterministic report.
 */
object TemplateValidator {
    private const val MAX_NODES = 500
    private const val MAX_SLOTS = 750
    private const val MAX_DEPTH = 100
    private val slugRegex = Regex("^[a-z][a-z0-9-]{0,63}$")
    private val parameterNameRegex = Regex("^[a-z][a-zA-Z0-9_]{0,63}$")
    private val reservedAliases = setOf("sys", "item", "index")
    private val pageBandTypes = setOf("pageheader", "pagefooter")

    /**
     * Validates [document] using optional catalog resolution [context].
     *
     * [TemplateValidationContext.EMPTY] performs all intrinsic validation and
     * suppresses findings that require authoritative external resolution.
     *
     * @return a complete deterministic report; inspect
     *   [TemplateValidationReport.valid] for acceptance.
     */
    fun validate(
        document: TemplateDocument,
        context: TemplateValidationContext = TemplateValidationContext.EMPTY,
    ): TemplateValidationReport {
        val findings = mutableListOf<TemplateValidationFinding>()
        if (document.modelVersion != 1) {
            findings.error(
                TEMPLATE_GRAPH_INVALID,
                "modelVersion",
                "template document modelVersion ${document.modelVersion} is unsupported; expected 1",
            )
        }
        val safeGraph = validateGraph(document, findings)
        validateRegistryRules(document, findings)
        document.documentStylesOverride?.let { styles ->
            // Document styles cascade or apply at the document level, so only an unknown key is
            // reported; there is no component to be applicable to.
            findings.checkStyleKeys(styles.keys, "documentStylesOverride", target = null, applicable = null)
        }
        validateBindings(document, context, findings)
        validateReferences(document, context, findings)
        if (safeGraph) {
            validatePlaceholders(document, context, findings)
            validatePageBands(document, findings)
        }
        context.resolveStylePresets(document)?.let { presets ->
            document.nodes.values.sortedBy(Node::id).forEach { node ->
                val preset = node.stylePreset
                if (preset != null && preset !in presets) {
                    findings.error(
                        TEMPLATE_STYLE_PRESET_UNKNOWN,
                        "nodes.${node.id}.stylePreset",
                        "style preset '$preset' does not exist in the effective theme",
                    )
                }
            }
        }
        return TemplateValidationReport(
            findings.distinct().sortedWith(compareBy({ it.path }, { it.code }, { it.message })),
        )
    }

    private fun validateGraph(
        document: TemplateDocument,
        findings: MutableList<TemplateValidationFinding>,
    ): Boolean {
        var safe = true
        if (document.nodes.size > MAX_NODES) {
            findings.error(TEMPLATE_GRAPH_INVALID, "nodes", "template document has ${document.nodes.size} nodes; maximum is $MAX_NODES")
        }
        if (document.slots.size > MAX_SLOTS) {
            findings.error(TEMPLATE_GRAPH_INVALID, "slots", "template document has ${document.slots.size} slots; maximum is $MAX_SLOTS")
        }
        val root = document.nodes[document.root]
        if (document.root.isBlank()) {
            findings.error(TEMPLATE_GRAPH_INVALID, "root", "template document root is required")
            safe = false
        } else if (root == null) {
            findings.error(TEMPLATE_GRAPH_INVALID, "root", "root node '${document.root}' is missing from nodes")
            safe = false
        } else if (root.type != "root") {
            findings.error(TEMPLATE_GRAPH_INVALID, "root", "root node '${document.root}' must have type 'root'")
        }

        var rootCount = 0
        document.nodes.toSortedMap().forEach { (key, node) ->
            if (key != node.id) findings.error(TEMPLATE_GRAPH_INVALID, "nodes.$key.id", "node map key '$key' does not match node id '${node.id}'")
            if (node.id.isBlank()) findings.error(TEMPLATE_GRAPH_INVALID, "nodes.$key.id", "node id must not be blank")
            if (node.type.isBlank()) findings.error(TEMPLATE_GRAPH_INVALID, "nodes.$key.type", "node '${node.id}' type must not be blank")
            if (node.type !in CatalogRegistries.components) {
                findings.error(TEMPLATE_NODE_TYPE_UNSUPPORTED, "nodes.$key.type", "node '${node.id}' uses unsupported type '${node.type}'")
            }
            if (node.type == "root") rootCount++
            duplicates(node.slots).forEach { duplicate ->
                findings.error(TEMPLATE_GRAPH_INVALID, "nodes.$key.slots", "node '${node.id}' references slot '$duplicate' more than once")
            }
            if (node.slots.size > MAX_SLOTS) {
                findings.error(TEMPLATE_GRAPH_INVALID, "nodes.$key.slots", "node '$key' has ${node.slots.size} slot references; maximum is $MAX_SLOTS")
            }
        }
        if (rootCount != 1) findings.error(TEMPLATE_GRAPH_INVALID, "nodes", "template document must contain exactly one root node, found $rootCount")

        document.slots.toSortedMap().forEach { (key, slot) ->
            if (key != slot.id) findings.error(TEMPLATE_GRAPH_INVALID, "slots.$key.id", "slot map key '$key' does not match slot id '${slot.id}'")
            if (slot.id.isBlank()) findings.error(TEMPLATE_GRAPH_INVALID, "slots.$key.id", "slot id must not be blank")
            val owner = document.nodes[slot.nodeId]
            if (owner == null) {
                findings.error(TEMPLATE_GRAPH_INVALID, "slots.$key.nodeId", "slot '${slot.id}' owner node '${slot.nodeId}' is missing")
                safe = false
            } else if (slot.id !in owner.slots) {
                findings.error(TEMPLATE_GRAPH_INVALID, "slots.$key.nodeId", "slot '${slot.id}' is not listed by owner node '${slot.nodeId}'")
            }
            duplicates(slot.children).forEach { duplicate ->
                findings.error(TEMPLATE_GRAPH_INVALID, "slots.$key.children", "slot '${slot.id}' references child node '$duplicate' more than once")
            }
            slot.children.forEach { child ->
                if (child !in document.nodes) {
                    findings.error(TEMPLATE_GRAPH_INVALID, "slots.$key.children", "slot '${slot.id}' references missing child node '$child'")
                    safe = false
                }
            }
            if (slot.children.size > MAX_NODES) {
                findings.error(TEMPLATE_GRAPH_INVALID, "slots.$key.children", "slot '$key' has ${slot.children.size} child references; maximum is $MAX_NODES")
            }
        }
        document.nodes.values.sortedBy(Node::id).forEach { node ->
            node.slots.forEach { slotId ->
                val slot = document.slots[slotId]
                if (slot == null) {
                    findings.error(TEMPLATE_GRAPH_INVALID, "nodes.${node.id}.slots", "node '${node.id}' references missing slot '$slotId'")
                    safe = false
                } else if (slot.nodeId != node.id) {
                    findings.error(TEMPLATE_GRAPH_INVALID, "nodes.${node.id}.slots", "node '${node.id}' references slot '$slotId' owned by '${slot.nodeId}'")
                }
            }
        }
        if (!safe || root == null) return false

        val parents = mutableMapOf<String, String>()
        document.slots.values.sortedBy { it.id }.forEach { slot ->
            slot.children.forEach { child ->
                parents.put(child, slot.id)?.let { previous ->
                    findings.error(TEMPLATE_GRAPH_INVALID, "slots.${slot.id}.children", "node '$child' has multiple parents: '$previous' and '${slot.id}'")
                    safe = false
                }
            }
        }
        if (document.root in parents) {
            findings.error(TEMPLATE_GRAPH_INVALID, "root", "root node '${document.root}' must not be a child of any slot")
            safe = false
        }

        val reachableNodes = mutableSetOf<String>()
        val reachableSlots = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()
        fun visit(nodeId: String, depth: Int) {
            if (depth > MAX_DEPTH) {
                findings.error(TEMPLATE_GRAPH_INVALID, "nodes.$nodeId", "template document exceeds maximum depth $MAX_DEPTH")
                safe = false
                return
            }
            if (!visiting.add(nodeId)) {
                findings.error(TEMPLATE_GRAPH_INVALID, "nodes.$nodeId", "template document contains a cycle through node '$nodeId'")
                safe = false
                return
            }
            if (!reachableNodes.add(nodeId)) {
                visiting.remove(nodeId)
                return
            }
            document.nodes[nodeId]?.slots.orEmpty().forEach { slotId ->
                reachableSlots += slotId
                document.slots[slotId]?.children.orEmpty().forEach { visit(it, depth + 1) }
            }
            visiting.remove(nodeId)
        }
        visit(document.root, 0)
        (document.nodes.keys - reachableNodes).sorted().takeIf { it.isNotEmpty() }?.let {
            findings.error(TEMPLATE_GRAPH_INVALID, "nodes", "unreachable node(s): ${it.joinToString()}")
        }
        (document.slots.keys - reachableSlots).sorted().takeIf { it.isNotEmpty() }?.let {
            findings.error(TEMPLATE_GRAPH_INVALID, "slots", "unreachable slot(s): ${it.joinToString()}")
        }
        return safe
    }

    private fun validateRegistryRules(
        document: TemplateDocument,
        findings: MutableList<TemplateValidationFinding>,
    ) {
        document.nodes.values.sortedBy(Node::id).forEach { node ->
            val component = CatalogRegistries.components[node.type] ?: return@forEach
            val actualSlots = node.slots.mapNotNull(document.slots::get)
            component.slots.filter(SlotRule::dynamic).forEach { dynamic ->
                val expectedNames = expectedDynamicSlots(node, dynamic)
                expectedNames.forEach { expected ->
                    if (actualSlots.none { it.name == expected }) {
                        findings.error(TEMPLATE_SLOT_INVALID, "nodes.${node.id}.slots", "component '${node.type}' is missing required slot '$expected'")
                    }
                }
            }
            actualSlots.forEach { slot ->
                if (component.slots.none { it.matches(slot.name) }) {
                    findings.error(TEMPLATE_SLOT_INVALID, "slots.${slot.id}.name", "slot name '${slot.name}' is not declared by component '${node.type}'")
                }
                slot.children.mapNotNull(document.nodes::get).forEach { child ->
                    val allowed = when (component.allowedChildrenMode) {
                        "none" -> false
                        "allowlist" -> child.type in component.allowedChildren
                        "denylist" -> child.type !in component.allowedChildren
                        else -> true
                    }
                    if (!allowed) {
                        findings.error(
                            TEMPLATE_CHILD_TYPE_NOT_ALLOWED,
                            "slots.${slot.id}.children",
                            "component '${node.type}' does not allow child type '${child.type}'",
                        )
                    }
                }
            }
            validateProperties(node, component, findings)
            validateStyleMaps(node, component, findings)
            node.styles?.let { styles ->
                findings.checkStyleKeys(styles.keys, "nodes.${node.id}.styles", "component '${node.type}'") { key ->
                    isStyleApplicable(key, component.applicableStyles)
                }
            }
        }
    }

    /**
     * Validates the style maps of a theme: its `documentStyles` and the `styles` of each block
     * style preset. Every finding is a [ValidationSeverity.WARNING], so the report is always valid.
     *
     * A document style key is reported only when the style registry does not define it. A preset
     * style key is also reported when no component the preset applies to accepts it: the
     * components in its `applicableTo`, or every component when that is empty.
     *
     * Paths are relative to the theme: `documentStyles.<key>` and
     * `blockStylePresets.<name>.styles.<key>`.
     */
    fun validateThemeStyles(
        documentStyles: Map<String, Any?>?,
        blockStylePresets: Map<String, BlockStylePreset>?,
    ): TemplateValidationReport {
        val findings = mutableListOf<TemplateValidationFinding>()
        documentStyles?.let { findings.checkStyleKeys(it.keys, "documentStyles", target = null, applicable = null) }
        blockStylePresets.orEmpty().toSortedMap().forEach { (name, preset) ->
            val named = preset.applicableTo.orEmpty()
            val candidates = if (named.isEmpty()) {
                CatalogRegistries.components.values.toList()
            } else {
                named.mapNotNull(CatalogRegistries.components::get)
            }
            val target = if (named.isEmpty()) {
                "any component"
            } else {
                "any component preset '$name' applies to (${named.joinToString()})"
            }
            findings.checkStyleKeys(
                preset.styles.keys,
                "blockStylePresets.$name.styles",
                target,
                applicable = if (candidates.isEmpty()) null else { key -> candidates.any { isStyleApplicable(key, it.applicableStyles) } },
            )
        }
        return TemplateValidationReport(
            findings.distinct().sortedWith(compareBy({ it.path }, { it.code }, { it.message })),
        )
    }

    /**
     * Component style allowlists use family prefixes, matching the editor:
     * `padding` includes `paddingTop`, and `border` includes `borderRadius`.
     * `"all"` (null) includes every registry property except those marked
     * `includedInAll: false`, which apply only where they are listed by key.
     * Unknown keys are reported by the style registry check before this one.
     */
    private fun isStyleApplicable(
        key: String,
        applicableStyles: Set<String>?,
    ): Boolean = if (applicableStyles == null) {
        key !in CatalogRegistries.optInStyleKeys
    } else {
        applicableStyles.any(key::startsWith)
    }

    /**
     * Reports each style key that has no effect, as a warning: one the style registry does not
     * define, or one [applicable] rejects. A null [applicable] checks only the registry.
     */
    private fun MutableList<TemplateValidationFinding>.checkStyleKeys(
        keys: Collection<String>,
        path: String,
        target: String?,
        applicable: ((String) -> Boolean)?,
    ) {
        keys.sorted().forEach { key ->
            if (key !in CatalogRegistries.styleKeys) {
                warning(TEMPLATE_STYLE_UNKNOWN, "$path.$key", "style '$key' is not defined by the catalog style registry")
            } else if (applicable != null && !applicable(key)) {
                warning(TEMPLATE_STYLE_NOT_APPLICABLE, "$path.$key", "style '$key' is not applicable to $target")
            }
        }
    }

    /**
     * Validates the style maps a component declares (`styleMaps` in the component registry).
     *
     * The shape is an error: a keyed map that is not an object, an indexed map that is not an
     * array, a part key that does not match the map's `keyPattern`, an entry that is not an
     * object, and a part the node does not have (a cell outside the table's `rows` × `columns`, a
     * column beyond the `columnSizes` count). The style keys inside an entry are warnings, like
     * node styles, checked against the map's own `applicableStyles`.
     */
    private fun validateStyleMaps(
        node: Node,
        component: ComponentRule,
        findings: MutableList<TemplateValidationFinding>,
    ) {
        val props = node.props ?: return
        component.styleMaps.forEach { rule ->
            val value = props[rule.key] ?: return@forEach
            val path = "nodes.${node.id}.props.${rule.key}"
            val parts: List<Pair<String, Any?>> = when (rule.shape) {
                "keyed" -> objectEntries(value)?.sortedBy { it.first } ?: run {
                    findings.error(TEMPLATE_NODE_PROPERTY_INVALID, path, "property '${rule.key}' must be an object of styles keyed by part")
                    return@forEach
                }
                else -> arrayItems(value)?.mapIndexed { index, item -> index.toString() to item } ?: run {
                    findings.error(TEMPLATE_NODE_PROPERTY_INVALID, path, "property '${rule.key}' must be an array of styles, one per part")
                    return@forEach
                }
            }
            parts.forEach { (part, styles) ->
                val partPath = if (rule.shape == "keyed") "$path.$part" else "$path[$part]"
                val partName = styleMapPartName(node, rule, part)
                if (rule.keyPattern != null && !rule.keyPattern.matches(part)) {
                    findings.error(TEMPLATE_NODE_PROPERTY_INVALID, partPath, "property '${rule.key}' key '$part' does not match ${rule.keyPattern.pattern}")
                    return@forEach
                }
                styleMapPartMissing(node, rule, part)?.let { reason ->
                    findings.error(TEMPLATE_NODE_PROPERTY_INVALID, partPath, "$partName does not exist: $reason")
                }
                val entry = objectEntries(styles)
                if (entry == null) {
                    findings.error(TEMPLATE_NODE_PROPERTY_INVALID, partPath, "styles for $partName must be an object")
                    return@forEach
                }
                findings.checkStyleKeys(entry.map { it.first }, partPath, partName) { key ->
                    rule.applicableStyles.any(key::startsWith)
                }
            }
        }
    }

    private fun styleMapPartName(
        node: Node,
        rule: StyleMapRule,
        part: String,
    ): String = when (node.type) {
        "table" -> "cell '$part' of table '${node.id}'"
        "columns" -> "column $part of columns '${node.id}'"
        else -> "'$part' in ${rule.key} of ${node.type} '${node.id}'"
    }

    /**
     * Why the node has no part [part], or null when it has one or its size is not declared. A
     * table counts its cells by its `rows` and `columns` props (0-based `<row>-<col>`); a columns
     * node counts its columns by the length of `columnSizes`.
     */
    private fun styleMapPartMissing(
        node: Node,
        rule: StyleMapRule,
        part: String,
    ): String? {
        val props = node.props ?: return null
        return when (node.type to rule.key) {
            "table" to "cellStyles" -> {
                val rows = intValue(props["rows"]) ?: return null
                val columns = intValue(props["columns"]) ?: return null
                val (row, column) = part.split('-').map(String::toIntOrNull)
                if (row == null || column == null || row >= rows || column >= columns) {
                    "the table has $rows rows and $columns columns, counted from 0"
                } else {
                    null
                }
            }
            "columns" to "columnStyles" -> {
                val count = arrayItems(props["columnSizes"] ?: return null)?.size ?: return null
                if (part.toInt() >= count) "columnSizes declares $count columns, counted from 0" else null
            }
            else -> null
        }
    }

    private fun objectEntries(value: Any?): List<Pair<String, Any?>>? = when {
        value is Map<*, *> -> value.entries.map { (key, item) -> key.toString() to item }
        value is JsonNode && value.isObject -> value.properties().map { (key, item) -> key to item }
        else -> null
    }

    private fun arrayItems(value: Any?): List<Any?>? = when {
        value is List<*> -> value
        value is JsonNode && value.isArray -> value.toList()
        else -> null
    }

    private fun intValue(value: Any?): Int? = when {
        value is Number -> value.toInt()
        value is JsonNode && value.isNumber -> value.asInt()
        else -> null
    }

    private fun expectedDynamicSlots(
        node: Node,
        rule: SlotRule,
    ): List<String> = when (node.type) {
        "columns" -> (node.props?.get("columnSizes") as? List<*>).orEmpty().indices.map { rule.name.replace("{i}", it.toString()) }
        "table" -> {
            val rows = (node.props?.get("rows") as? Number)?.toInt()?.coerceAtLeast(0) ?: 0
            val columns = (node.props?.get("columns") as? Number)?.toInt()?.coerceAtLeast(0) ?: 0
            (0 until rows).flatMap { row ->
                (0 until columns).map { column ->
                    rule.name.replace("{r}", row.toString()).replace("{c}", column.toString())
                }
            }
        }
        else -> emptyList()
    }

    private fun validateProperties(
        node: Node,
        component: ComponentRule,
        findings: MutableList<TemplateValidationFinding>,
    ) {
        val props = node.props ?: return
        val allowedTopLevel = component.properties.map { it.path.substringBefore('.') }.toMutableSet()
        allowedTopLevel += component.styleMaps.map(StyleMapRule::key)
        if (component.type == "stencil") {
            allowedTopLevel += setOf(
                "stencilKey",
                "catalogKey",
                "namespace",
                "registry",
                "parameterBindings",
                "paramsAlias",
                "parameterSchemaSnapshot",
                // Carried on an expanded instance from its stencil (ADR 0006, part 5).
                "readsTemplateData",
            )
        }
        if (component.type == "placeholder") {
            // ADR 0006, part 4: a stencil's placeholder may pass the stencil's parameters to its fill,
            // under a default name; a fill may choose its own name.
            allowedTopLevel += setOf("passParameters", "parametersAs")
        }
        props.keys.sorted().filterNot(allowedTopLevel::contains).forEach { key ->
            findings.error(TEMPLATE_NODE_PROPERTY_INVALID, "nodes.${node.id}.props.$key", "property '$key' is not declared by component '${node.type}'")
        }
        component.properties.forEach { rule ->
            val value = valueAt(props, rule.path) ?: return@forEach
            val valid = if (component.type == "text" && rule.path == "content") {
                validTextContent(value)
            } else {
                when (rule.type) {
                    "number" -> value is Number || value is JsonNode && value.isNumber
                    "boolean" -> value is Boolean || value is JsonNode && value.isBoolean
                    "array" -> value is List<*> || value is JsonNode && value.isArray
                    "object" -> value is Map<*, *> || value is JsonNode && value.isObject
                    "select" -> stringValue(value)?.let { rule.options.isEmpty() || it in rule.options } == true
                    "expression" -> stringValue(value) != null
                    "text", "unit", "color" -> stringValue(value) != null
                    else -> true
                }
            }
            if (!valid) {
                findings.error(TEMPLATE_NODE_PROPERTY_INVALID, "nodes.${node.id}.props.${rule.path}", "property '${rule.path}' has an invalid ${rule.type ?: "value"} shape")
            }
            val expression = stringValue(value)
            if (rule.type == "expression" && expression != null && expression.isNotBlank() && !validExpression(expression)) {
                findings.error(TEMPLATE_EXPRESSION_INVALID, "nodes.${node.id}.props.${rule.path}", "expression '${rule.path}' is not valid JSONata")
            }
        }
    }

    private fun validTextContent(value: Any?): Boolean = when (value) {
        is Map<*, *> -> value["type"] == "doc" && value["content"] is List<*>
        is JsonNode -> value.isObject && value["type"]?.asString() == "doc" && value["content"]?.isArray == true
        else -> false
    }

    private fun validateReferences(
        document: TemplateDocument,
        context: TemplateValidationContext,
        findings: MutableList<TemplateValidationFinding>,
    ) {
        (document.themeRef as? ThemeRefOverride)?.let { theme ->
            val reference = CatalogResourceReference("theme", theme.themeKey, theme.catalogKey, theme.namespace, theme.registry)
            if (context.resolveResource(reference) == ResourceResolution.MISSING) {
                findings.error(TEMPLATE_THEME_NOT_FOUND, "themeRef.themeKey", "theme '${theme.themeKey}' does not exist in the catalog context")
            }
        }
        document.nodes.values.filter { it.type == "stencil" }.sortedBy(Node::id).forEach { node ->
            val stencilKey = stringValue(node.props?.get("stencilKey"))
            val catalogKey = stringValue(node.props?.get("catalogKey"))
            val namespace = node.props?.get("namespace")
            val registry = node.props?.get("registry")
            when {
                stencilKey == null || !CatalogKeys.isValid(stencilKey) -> findings.error(
                    STENCIL_REFERENCE_INVALID,
                    "nodes.${node.id}.props.stencilKey",
                    "stencil reference requires a valid stencilKey",
                )
                namespace != null && stringValue(namespace)?.let(CatalogNamespaces::isValid) != true -> findings.error(
                    STENCIL_REFERENCE_INVALID,
                    "nodes.${node.id}.props.namespace",
                    "stencil reference namespace must be a catalog namespace",
                )
                registry != null && stringValue(registry)?.let(CatalogNamespaces::isValidRegistry) != true -> findings.error(
                    STENCIL_REFERENCE_INVALID,
                    "nodes.${node.id}.props.registry",
                    "stencil reference registry must be a DNS name written in full",
                )
                registry != null && namespace == null -> findings.error(
                    STENCIL_REFERENCE_INVALID,
                    "nodes.${node.id}.props.namespace",
                    "stencil reference names a registry but no namespace",
                )
                else -> {
                    val reference = CatalogResourceReference("stencil", stencilKey, catalogKey, stringValue(namespace), stringValue(registry))
                    if (context.resolveResource(reference) == ResourceResolution.MISSING) {
                        findings.error(
                            STENCIL_REFERENCE_NOT_FOUND,
                            "nodes.${node.id}.props.stencilKey",
                            "stencil '$stencilKey' does not exist in the catalog context",
                        )
                    }
                }
            }
        }
    }

    private fun validatePlaceholders(
        document: TemplateDocument,
        context: TemplateValidationContext,
        findings: MutableList<TemplateValidationFinding>,
    ) {
        val kind = context.documentKind
        val parentByNode = document.slots.values.flatMap { slot -> slot.children.map { it to slot.nodeId } }.toMap()
        fun ancestors(nodeId: String): List<Node> {
            val result = mutableListOf<Node>()
            val visited = mutableSetOf<String>()
            var current = nodeId
            while (visited.add(current)) {
                val parent = parentByNode[current]?.let(document.nodes::get) ?: break
                result += parent
                current = parent.id
            }
            return result
        }
        val placeholders = document.nodes.values.filter { it.type == "placeholder" }.sortedBy(Node::id)
        val seen = mutableMapOf<String, MutableSet<String>>()
        placeholders.forEach { node ->
            val name = node.props?.get("name") as? String
            if (name == null || !slugRegex.matches(name)) {
                findings.error(PLACEHOLDER_NAME_INVALID, "nodes.${node.id}.props.name", "placeholder name must be a kebab-case slug")
            }
            val ancestorNodes = ancestors(node.id)
            if (kind == TemplateDocumentKind.STENCIL && ancestorNodes.any { it.type == "placeholder" }) {
                findings.error(PLACEHOLDER_NESTED_DEFINITION, "nodes.${node.id}", "placeholder '$name' is nested inside another placeholder")
            }
            val stencil = ancestorNodes.firstOrNull { it.type == "stencil" }
            if (kind == TemplateDocumentKind.TEMPLATE && stencil == null) {
                findings.error(PLACEHOLDER_OUTSIDE_STENCIL, "nodes.${node.id}", "placeholder '$name' must be a descendant of a stencil node")
            }
            val scope = if (kind == TemplateDocumentKind.STENCIL) "document" else stencil?.id ?: node.id
            if (name != null && !seen.getOrPut(scope) { mutableSetOf() }.add(name)) {
                findings.error(PLACEHOLDER_NAME_DUPLICATE, "nodes.${node.id}.props.name", "placeholder name '$name' is used more than once in the same stencil")
            }
        }

        fun recurse(
            nodeId: String,
            stencilIds: Set<StencilIdentity>,
            stencilDepth: Int,
        ) {
            val node = document.nodes[nodeId] ?: return
            val nextDepth = if (node.type == "stencil") stencilDepth + 1 else stencilDepth
            if (nextDepth > TemplateValidationLimits.MAX_STENCIL_NESTING_DEPTH) {
                findings.error(
                    STENCIL_NESTING_DEPTH_EXCEEDED,
                    "nodes.${node.id}.props.stencilKey",
                    "stencil nesting depth $nextDepth exceeds maximum ${TemplateValidationLimits.MAX_STENCIL_NESTING_DEPTH}",
                )
            }
            val nextIds = if (node.type == "stencil") {
                val id = node.props?.get("stencilKey") as? String
                val identity = id?.let {
                    StencilIdentity(
                        registry = node.props["registry"] as? String ?: context.currentRegistry,
                        namespace = node.props["namespace"] as? String ?: context.currentNamespace,
                        catalogKey = node.props["catalogKey"] as? String ?: context.currentCatalogKey,
                        key = it,
                    )
                }
                if (identity != null && identity in stencilIds) {
                    findings.error(STENCIL_RECURSION, "nodes.${node.id}.props.stencilKey", "stencil '$id' would contain itself transitively")
                }
                if (identity == null) stencilIds else stencilIds + identity
            } else {
                stencilIds
            }
            node.slots
                .mapNotNull(document.slots::get)
                .flatMap { it.children }
                .forEach { recurse(it, nextIds, nextDepth) }
        }
        val containingStencil = context.containingStencil
        val initialIdentities = containingStencil?.let {
            setOf(
                StencilIdentity(
                    it.registry ?: context.currentRegistry,
                    it.namespace ?: context.currentNamespace,
                    it.catalogKey ?: context.currentCatalogKey,
                    it.key,
                ),
            )
        }.orEmpty()
        val initialDepth = if (kind == TemplateDocumentKind.STENCIL) 1 else 0
        recurse(document.root, initialIdentities, initialDepth)
    }

    private data class StencilIdentity(
        val registry: String?,
        val namespace: String?,
        val catalogKey: String?,
        val key: String,
    )

    /**
     * Page headers and footers may appear in any number, anywhere in the flow; where one sits
     * decides which pages it applies to, which the renderer resolves. One shape is still an
     * error: a header or footer nested inside another.
     */
    private fun validatePageBands(
        document: TemplateDocument,
        findings: MutableList<TemplateValidationFinding>,
    ) {
        fun walk(nodeId: String, insideBand: Boolean) {
            val node = document.nodes[nodeId] ?: return
            val isBand = node.type in pageBandTypes
            if (isBand && insideBand) {
                findings.error(PAGEBAND_NESTED, "nodes.$nodeId", "${node.type} node '$nodeId' must not be inside a page header or footer")
            }
            node.slots.mapNotNull(document.slots::get).forEach { slot ->
                slot.children.forEach { walk(it, insideBand || isBand) }
            }
        }
        walk(document.root, false)
    }

    /**
     * ADR 0006: `readsTemplateData` and `passParameters` are booleans; `parametersAs` names the
     * passed parameters like a `paramsAlias`, so it follows the same rules.
     */
    private fun validatePassedParameters(
        node: Node,
        props: Map<String, Any?>,
        findings: MutableList<TemplateValidationFinding>,
    ) {
        for (flag in listOf("readsTemplateData", "passParameters")) {
            val value = props[flag]
            if (value != null && value !is Boolean) {
                findings.error(TEMPLATE_NODE_PROPERTY_INVALID, "nodes.${node.id}.props.$flag", "$flag must be a boolean")
            }
        }
        val name = props["parametersAs"] ?: return
        when {
            name !is String || !parameterNameRegex.matches(name) -> findings.error(
                NODE_PARAMETER_BINDINGS_INVALID_SHAPE,
                "nodes.${node.id}.props.parametersAs",
                "parametersAs must match ^[a-z][a-zA-Z0-9_]{0,63}$",
            )
            name in reservedAliases -> findings.error(
                NODE_PARAMS_ALIAS_RESERVED,
                "nodes.${node.id}.props.parametersAs",
                "parametersAs '$name' collides with a reserved scope name",
            )
        }
    }

    private fun validateBindings(
        document: TemplateDocument,
        context: TemplateValidationContext,
        findings: MutableList<TemplateValidationFinding>,
    ) {
        document.nodes.values.sortedBy(Node::id).forEach { node ->
            val props = node.props ?: return@forEach
            val alias = props["paramsAlias"]
            if (alias != null && alias !is String) {
                findings.error(NODE_PARAMETER_BINDINGS_INVALID_SHAPE, "nodes.${node.id}.props.paramsAlias", "paramsAlias must be a string")
            } else if (alias in reservedAliases) {
                findings.error(NODE_PARAMS_ALIAS_RESERVED, "nodes.${node.id}.props.paramsAlias", "paramsAlias '$alias' collides with a reserved scope name")
            }
            validatePassedParameters(node, props, findings)
            val raw = props["parameterBindings"]
            if (raw != null && raw !is Map<*, *>) {
                findings.error(NODE_PARAMETER_BINDINGS_INVALID_SHAPE, "nodes.${node.id}.props.parameterBindings", "parameterBindings must be an object of paramName to expression entries")
                return@forEach
            }
            val bindings = raw
            bindings.orEmpty().entries.sortedBy { it.key.toString() }.forEach { (rawName, rawExpression) ->
                val name = rawName as? String
                if (name == null || !parameterNameRegex.matches(name)) {
                    findings.error(NODE_PARAMETER_BINDING_NAME_INVALID, "nodes.${node.id}.props.parameterBindings", "parameter binding names must match ^[a-z][a-zA-Z0-9_]{0,63}$")
                    return@forEach
                }
                val expression = rawExpression as? String
                if (expression.isNullOrBlank()) {
                    findings.error(NODE_PARAMETER_BINDING_EMPTY, "nodes.${node.id}.props.parameterBindings.$name", "parameter binding '$name' must be a non-blank JSONata expression")
                } else if (!validExpression(expression)) {
                    findings.error(NODE_PARAMETER_BINDING_SYNTAX_INVALID, "nodes.${node.id}.props.parameterBindings.$name", "parameter binding '$name' expression is invalid")
                }
            }
            val schema = context.resolveParameterSchema(node, document) ?: return@forEach
            ParameterSchemaValidator.appendFindings(schema, "nodes.${node.id}.props.parameterSchemaSnapshot", findings)
            val properties = schema["properties"] as? Map<*, *> ?: return@forEach
            val declared = properties.keys.filterIsInstance<String>().toSet()
            bindings.orEmpty().keys.filterIsInstance<String>().sorted().filterNot(declared::contains).forEach { name ->
                findings.error(NODE_PARAMETER_BINDING_UNKNOWN, "nodes.${node.id}.props.parameterBindings.$name", "parameter '$name' is not declared in the node's schema")
            }
            (schema["required"] as? List<*>)?.filterIsInstance<String>().orEmpty().sorted().forEach { name ->
                val hasBinding = (bindings?.get(name) as? String)?.isNotBlank() == true
                val hasDefault = (properties[name] as? Map<*, *>)?.containsKey("default") == true
                if (!hasBinding && !hasDefault) {
                    findings.error(NODE_PARAMETER_BINDING_MISSING_REQUIRED, "nodes.${node.id}.props.parameterBindings.$name", "required parameter '$name' has no binding and no default")
                }
            }
        }
    }

    private fun valueAt(
        root: Map<String, Any?>,
        path: String,
    ): Any? {
        var current: Any? = root
        path.split('.').forEach { segment ->
            current = when (val value = current) {
                is Map<*, *> -> value[segment]
                is JsonNode -> value[segment]
                else -> null
            } ?: return null
        }
        return current
    }

    private fun stringValue(value: Any?): String? = when (value) {
        is String -> value
        is JsonNode -> value.takeIf(JsonNode::isString)?.asString()
        else -> null
    }

    private fun validExpression(value: String): Boolean = try {
        jsonata(value)
        true
    } catch (_: Exception) {
        false
    }

    private fun duplicates(values: List<String>): List<String> = values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()

    private fun MutableList<TemplateValidationFinding>.error(
        code: String,
        path: String,
        message: String,
    ) {
        add(TemplateValidationFinding(code, ValidationSeverity.ERROR, path, message))
    }

    private fun MutableList<TemplateValidationFinding>.warning(
        code: String,
        path: String,
        message: String,
    ) {
        add(TemplateValidationFinding(code, ValidationSeverity.WARNING, path, message))
    }
}
