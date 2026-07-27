package app.epistola.catalog.validation

import app.epistola.catalog.validation.TemplateValidationCodes.NODE_PARAMETER_BINDINGS_INVALID_SHAPE
import app.epistola.catalog.validation.TemplateValidationCodes.NODE_PARAMETER_BINDING_EMPTY
import app.epistola.catalog.validation.TemplateValidationCodes.NODE_PARAMETER_BINDING_MISSING_REQUIRED
import app.epistola.catalog.validation.TemplateValidationCodes.NODE_PARAMETER_BINDING_NAME_INVALID
import app.epistola.catalog.validation.TemplateValidationCodes.NODE_PARAMETER_BINDING_SYNTAX_INVALID
import app.epistola.catalog.validation.TemplateValidationCodes.NODE_PARAMETER_BINDING_UNKNOWN
import app.epistola.catalog.validation.TemplateValidationCodes.NODE_PARAMS_ALIAS_RESERVED
import app.epistola.catalog.validation.TemplateValidationCodes.PAGEHEADER_NOT_AT_ROOT
import app.epistola.catalog.validation.TemplateValidationCodes.PAGEHEADER_ROOT_MISSING
import app.epistola.catalog.validation.TemplateValidationCodes.PAGEHEADER_TOO_MANY
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
import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRefOverride
import com.dashjoin.jsonata.Jsonata.jsonata
import tools.jackson.databind.JsonNode

object TemplateValidator {
    private const val MAX_NODES = 500
    private const val MAX_SLOTS = 750
    private const val MAX_DEPTH = 100
    private val slugRegex = Regex("^[a-z][a-z0-9-]{0,63}$")
    private val parameterNameRegex = Regex("^[a-z][a-zA-Z0-9_]{0,63}$")
    private val reservedAliases = setOf("sys", "item", "index")

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
        validateBindings(document, context, findings)
        validateReferences(document, context, findings)
        validatePageHeaders(document, findings)
        if (safeGraph) {
            validatePlaceholders(document, context, findings)
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
            node.styles.orEmpty().toSortedMap().forEach { (key, _) ->
                if (key !in CatalogRegistries.styleKeys) {
                    findings.error(TEMPLATE_STYLE_UNKNOWN, "nodes.${node.id}.styles.$key", "style '$key' is not defined by the catalog style registry")
                } else if (component.applicableStyles != null && key !in component.applicableStyles) {
                    findings.error(TEMPLATE_STYLE_NOT_APPLICABLE, "nodes.${node.id}.styles.$key", "style '$key' is not applicable to component '${node.type}'")
                }
            }
        }
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
        if (component.type == "stencil") {
            allowedTopLevel += setOf("parameterBindings", "paramsAlias", "parameterSchemaSnapshot")
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

    private fun booleanValue(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is JsonNode -> if (value.isBoolean) value.asBoolean() else null
        else -> null
    }

    private fun validateReferences(
        document: TemplateDocument,
        context: TemplateValidationContext,
        findings: MutableList<TemplateValidationFinding>,
    ) {
        (document.themeRef as? ThemeRefOverride)?.let { theme ->
            val reference = CatalogResourceReference("theme", theme.themeId, theme.catalogKey)
            if (context.resolveResource(reference) == ResourceResolution.MISSING) {
                findings.error(TEMPLATE_THEME_NOT_FOUND, "themeRef.themeId", "theme '${theme.themeId}' does not exist in the catalog context")
            }
        }
        document.nodes.values.filter { it.type == "stencil" }.sortedBy(Node::id).forEach { node ->
            val stencilId = node.props?.get("stencilId") as? String
            val catalogKey = node.props?.get("catalogKey") as? String
            val version = (node.props?.get("version") as? Number)?.toInt()
            val isDraft = booleanValue(node.props?.get("isDraft"))
            if (stencilId == null || !slugRegex.matches(stencilId) || version == null || version <= 0) {
                findings.error(
                    STENCIL_REFERENCE_INVALID,
                    "nodes.${node.id}.props.stencilId",
                    "stencil reference requires a valid stencilId and positive version",
                )
            } else if (isDraft == null) {
                findings.error(
                    STENCIL_REFERENCE_INVALID,
                    "nodes.${node.id}.props.isDraft",
                    "stencil reference must declare whether it targets a draft",
                )
            } else if (isDraft && !context.allowDraftStencilReferences) {
                findings.error(
                    STENCIL_REFERENCE_INVALID,
                    "nodes.${node.id}.props.isDraft",
                    "portable catalog content cannot reference draft stencils",
                )
            } else {
                val reference = CatalogResourceReference("stencil", stencilId, catalogKey, version, isDraft)
                if (context.resolveResource(reference) == ResourceResolution.MISSING) {
                    findings.error(
                        STENCIL_REFERENCE_NOT_FOUND,
                        "nodes.${node.id}.props.stencilId",
                        "stencil '$stencilId' version $version does not exist in the catalog context",
                    )
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
                    "nodes.${node.id}.props.stencilId",
                    "stencil nesting depth $nextDepth exceeds maximum ${TemplateValidationLimits.MAX_STENCIL_NESTING_DEPTH}",
                )
            }
            val nextIds = if (node.type == "stencil") {
                val id = node.props?.get("stencilId") as? String
                val identity = id?.let {
                    StencilIdentity(
                        catalogKey = node.props["catalogKey"] as? String ?: context.currentCatalogKey,
                        slug = it,
                    )
                }
                if (identity != null && identity in stencilIds) {
                    findings.error(STENCIL_RECURSION, "nodes.${node.id}.props.stencilId", "stencil '$id' would contain itself transitively")
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
            setOf(StencilIdentity(it.catalogKey ?: context.currentCatalogKey, it.slug))
        }.orEmpty()
        val initialDepth = if (kind == TemplateDocumentKind.STENCIL) 1 else 0
        recurse(document.root, initialIdentities, initialDepth)
    }

    private data class StencilIdentity(
        val catalogKey: String?,
        val slug: String,
    )

    private fun validatePageHeaders(
        document: TemplateDocument,
        findings: MutableList<TemplateValidationFinding>,
    ) {
        val headers = document.nodes.values.filter { it.type == "pageheader" }.sortedBy(Node::id)
        if (headers.size > 2) findings.error(PAGEHEADER_TOO_MANY, "nodes", "a template may declare at most two 'pageheader' nodes, found ${headers.size}")
        val root = document.nodes[document.root]
        if (headers.isNotEmpty() && root == null) {
            findings.error(PAGEHEADER_ROOT_MISSING, "root", "cannot validate pageheader placement without a root node")
            return
        }
        val rootChildren = root?.slots.orEmpty().mapNotNull(document.slots::get).flatMap { it.children }.toSet()
        headers.filterNot { it.id in rootChildren }.forEach { header ->
            findings.error(PAGEHEADER_NOT_AT_ROOT, "nodes.${header.id}", "pageheader node '${header.id}' must be a direct child of the root slot")
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
}
