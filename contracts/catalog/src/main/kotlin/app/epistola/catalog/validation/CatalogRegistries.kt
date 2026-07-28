// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.validation

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

internal data class SlotRule(
    val name: String,
    val dynamic: Boolean,
) {
    private val regex: Regex? = if (dynamic) {
        val pattern = listOf("{i}", "{r}", "{c}").fold(Regex.escape(name)) { value, token ->
            value.replace(token, "\\E\\d+\\Q")
        }
        Regex("^$pattern$")
    } else {
        null
    }

    fun matches(value: String): Boolean = if (dynamic) regex!!.matches(value) else name == value
}

internal data class PropertyRule(
    val path: String,
    val type: String?,
    val options: Set<String>,
)

internal data class ComponentRule(
    val type: String,
    val slots: List<SlotRule>,
    val allowedChildrenMode: String,
    val allowedChildren: Set<String>,
    val applicableStyles: Set<String>?,
    val properties: List<PropertyRule>,
)

internal object CatalogRegistries {
    private val mapper = JsonMapper.builder().build()

    val components: Map<String, ComponentRule> by lazy {
        val root = read("component-registry.json")
        root["components"].associate { component ->
            val type = component["type"].asString()
            type to ComponentRule(
                type = type,
                slots = component["slots"].orEmpty().map { slot ->
                    SlotRule(
                        name = slot["name"].asString(),
                        dynamic = slot["dynamic"]?.asBoolean() == true,
                    )
                },
                allowedChildrenMode = component["allowedChildren"]?.get("mode")?.asString() ?: "all",
                allowedChildren = component["allowedChildren"]?.get("types").orEmpty().map { it.asString() }.toSet(),
                applicableStyles = applicableStyles(component),
                properties = propertyRules(component),
            )
        }
    }

    val styleKeys: Set<String> by lazy {
        read("style-registry.json")["groups"]
            .orEmpty()
            .flatMap { group -> group["properties"].orEmpty().map { it["key"].asString() } }
            .toSet()
    }

    private fun applicableStyles(component: JsonNode): Set<String>? {
        val value = component["applicableStyles"]
        if (value?.isString == true) return null
        if (value?.isArray == true) {
            return value.mapTo(linkedSetOf<String>()) { it.asString() }
        }
        return emptySet()
    }

    private fun propertyRules(component: JsonNode): List<PropertyRule> {
        val defaults = component["defaultProps"]?.propertyNames()?.asSequence()?.map { name ->
            val value = component["defaultProps"][name]
            PropertyRule(name, inferredType(value) ?: inferredType(exampleProperty(component, name)), emptySet())
        }?.toList().orEmpty()
        val inspector = component["inspector"].orEmpty().map { field ->
            PropertyRule(
                path = field["key"].asString(),
                type = field["type"]?.asString(),
                options = field["options"].orEmpty().map { it["value"].asString() }.toSet(),
            )
        }
        return (defaults + inspector).associateBy(PropertyRule::path).values.toList()
    }

    private fun exampleProperty(
        component: JsonNode,
        property: String,
    ): JsonNode? {
        val componentType = component["type"].asString()
        component["examples"].orEmpty().forEach { example ->
            example["fragment"]?.get("nodes")?.properties()?.forEach { (_, node) ->
                if (node["type"]?.asString() == componentType) {
                    node["props"]?.get(property)?.takeUnless(JsonNode::isNull)?.let { return it }
                }
            }
        }
        return null
    }

    private fun inferredType(node: JsonNode?): String? = when {
        node == null || node.isNull -> null
        node.isString -> "text"
        node.isNumber -> "number"
        node.isBoolean -> "boolean"
        node.isArray -> "array"
        node.isObject -> "object"
        else -> null
    }

    private fun read(name: String): JsonNode {
        val stream = CatalogRegistries::class.java.getResourceAsStream("/META-INF/epistola-catalog/$name")
            ?: error("Missing catalog registry: $name")
        return stream.use(mapper::readTree)
    }

    private fun JsonNode?.orEmpty(): List<JsonNode> = if (this != null && isArray) toList() else emptyList()
}
