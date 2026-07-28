package app.epistola.catalog.validation

import app.epistola.catalog.validation.TemplateValidationCodes.PARAMETER_DEFAULT_TYPE_MISMATCH
import app.epistola.catalog.validation.TemplateValidationCodes.PARAMETER_NAME_INVALID
import app.epistola.catalog.validation.TemplateValidationCodes.PARAMETER_NAME_RESERVED
import app.epistola.catalog.validation.TemplateValidationCodes.PARAMETER_REQUIRED_UNKNOWN
import app.epistola.catalog.validation.TemplateValidationCodes.PARAMETER_SCHEMA_INVALID_TYPE
import app.epistola.catalog.validation.TemplateValidationCodes.PARAMETER_TYPE_UNSUPPORTED

/**
 * Portable validator for the parameter-schema subset supported by stencil and
 * parametrised component bindings.
 *
 * Consumers validating a standalone schema should use this entry point instead
 * of manufacturing a synthetic [app.epistola.template.model.TemplateDocument].
 */
object ParameterSchemaValidator {
    private val parameterNameRegex = Regex("^[a-z][a-zA-Z0-9_]{0,63}$")
    private val reservedNames = setOf("params", "item", "sys", "index")
    private val reservedSuffixes = listOf("_index", "_first", "_last")
    private val primitiveTypes = setOf("string", "number", "integer", "boolean")

    /**
     * Validates the supported JSON Schema subset.
     *
     * Supported properties are primitive values or arrays of primitives;
     * string formats are limited to `date` and `date-time`. The validator also
     * checks reserved names, required-property closure, and default values.
     *
     * @param schema schema object to inspect without mutating it.
     * @param path finding-path prefix used by the embedding resource.
     */
    fun validate(
        schema: Map<String, Any?>,
        path: String = "parameterSchema",
    ): TemplateValidationReport {
        val findings = mutableListOf<TemplateValidationFinding>()
        appendFindings(schema, path, findings)
        return TemplateValidationReport(
            findings.distinct().sortedWith(compareBy({ it.path }, { it.code }, { it.message })),
        )
    }

    internal fun appendFindings(
        schema: Map<String, Any?>,
        path: String,
        findings: MutableList<TemplateValidationFinding>,
    ) {
        if (schema["type"] != "object") {
            findings.error(PARAMETER_SCHEMA_INVALID_TYPE, "$path.type", "parameter schema type must be 'object'")
            return
        }
        val properties = schema["properties"]
        if (properties != null && properties !is Map<*, *>) {
            findings.error(PARAMETER_SCHEMA_INVALID_TYPE, "$path.properties", "parameter schema properties must be an object")
            return
        }
        val declared = (properties as? Map<*, *>)?.keys?.filterIsInstance<String>().orEmpty().toSet()
        (properties as? Map<*, *>)?.entries?.sortedBy { it.key.toString() }?.forEach { (rawName, rawDefinition) ->
            val name = rawName as? String ?: return@forEach
            if (!parameterNameRegex.matches(name)) {
                findings.error(PARAMETER_NAME_INVALID, "$path.properties", "parameter name '$name' is invalid")
            }
            if (name in reservedNames || reservedSuffixes.any(name::endsWith)) {
                findings.error(PARAMETER_NAME_RESERVED, "$path.properties", "parameter name '$name' collides with a reserved scope name")
            }
            val definition = rawDefinition as? Map<*, *>
            val type = definition?.get("type") as? String
            if (type !in primitiveTypes && type != "array") {
                findings.error(PARAMETER_TYPE_UNSUPPORTED, "$path.properties.$name.type", "parameter '$name' has unsupported type '${type ?: "<missing>"}'")
                return@forEach
            }
            val effectiveType = if (type == "array") (definition?.get("items") as? Map<*, *>)?.get("type") as? String else type
            if (type == "array" && effectiveType !in primitiveTypes) {
                findings.error(PARAMETER_TYPE_UNSUPPORTED, "$path.properties.$name.items.type", "array parameter '$name' must contain primitives")
            }
            val format = if (type == "array") {
                ((definition?.get("items") as? Map<*, *>)?.get("format") as? String)
            } else {
                definition?.get("format") as? String
            }
            if (effectiveType == "string" && format != null && format !in setOf("date", "date-time")) {
                findings.error(PARAMETER_TYPE_UNSUPPORTED, "$path.properties.$name.format", "parameter '$name' has unsupported string format '$format'")
            }
            if (definition?.containsKey("default") == true && !defaultMatches(definition["default"], type, effectiveType)) {
                findings.error(PARAMETER_DEFAULT_TYPE_MISMATCH, "$path.properties.$name.default", "parameter '$name' default does not match its declared type")
            }
        }
        val required = schema["required"]
        if (required != null && required !is List<*>) {
            findings.error(PARAMETER_SCHEMA_INVALID_TYPE, "$path.required", "parameter schema required must be an array")
        } else {
            (required as? List<*>)?.filterIsInstance<String>().orEmpty().sorted().filterNot(declared::contains).forEach { name ->
                findings.error(PARAMETER_REQUIRED_UNKNOWN, "$path.required", "required parameter '$name' is not declared in properties")
            }
        }
    }

    private fun defaultMatches(
        value: Any?,
        type: String?,
        itemType: String?,
    ): Boolean = when (type) {
        "string" -> value is String
        "number" -> value is Number
        "integer" -> value is Byte || value is Short || value is Int || value is Long
        "boolean" -> value is Boolean
        "array" -> value is List<*> && value.all { defaultMatches(it, itemType, null) }
        else -> false
    }

    private fun MutableList<TemplateValidationFinding>.error(
        code: String,
        path: String,
        message: String,
    ) {
        add(TemplateValidationFinding(code, ValidationSeverity.ERROR, path, message))
    }
}
