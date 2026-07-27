package app.epistola.catalog.validation

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument

enum class ValidationSeverity {
    ERROR,
    WARNING,
}

data class TemplateValidationFinding(
    val code: String,
    val severity: ValidationSeverity,
    val path: String,
    val message: String,
)

data class TemplateValidationReport(
    val findings: List<TemplateValidationFinding>,
) {
    val valid: Boolean get() = findings.none { it.severity == ValidationSeverity.ERROR }
}

enum class TemplateDocumentKind {
    TEMPLATE,
    STENCIL,
}

enum class ResourceResolution {
    PRESENT,
    MISSING,
    UNKNOWN,
}

data class CatalogResourceReference(
    val type: String,
    val slug: String,
    val catalogKey: String? = null,
    val version: Int? = null,
)

/**
 * Product-neutral catalog lookup boundary used by portable template validation.
 *
 * Implementations may resolve from an in-memory catalog, a Suite adapter, or a
 * future Exchange client. Returning [ResourceResolution.UNKNOWN] suppresses
 * existence findings when a consumer intentionally validates without a full
 * catalog graph.
 */
interface TemplateValidationContext {
    val documentKind: TemplateDocumentKind get() = TemplateDocumentKind.TEMPLATE

    fun resolveResource(reference: CatalogResourceReference): ResourceResolution = ResourceResolution.UNKNOWN

    fun resolveParameterSchema(
        node: Node,
        document: TemplateDocument,
    ): Map<String, Any?>? = node.props?.get("parameterSchemaSnapshot") as? Map<String, Any?>

    /**
     * Returns the available preset names for the effective theme, or null when
     * the consumer cannot resolve them.
     */
    fun resolveStylePresets(document: TemplateDocument): Set<String>? = null

    companion object {
        val EMPTY: TemplateValidationContext = object : TemplateValidationContext {}

        fun forStencil(): TemplateValidationContext = object : TemplateValidationContext {
            override val documentKind: TemplateDocumentKind = TemplateDocumentKind.STENCIL
        }
    }
}

object TemplateValidationLimits {
    const val MAX_STENCIL_NESTING_DEPTH = 5
}

object TemplateValidationCodes {
    const val TEMPLATE_GRAPH_INVALID = "TEMPLATE_GRAPH_INVALID"
    const val TEMPLATE_NODE_TYPE_UNSUPPORTED = "TEMPLATE_NODE_TYPE_UNSUPPORTED"
    const val TEMPLATE_SLOT_INVALID = "TEMPLATE_SLOT_INVALID"
    const val TEMPLATE_CHILD_TYPE_NOT_ALLOWED = "TEMPLATE_CHILD_TYPE_NOT_ALLOWED"
    const val TEMPLATE_NODE_PROPERTY_INVALID = "TEMPLATE_NODE_PROPERTY_INVALID"
    const val TEMPLATE_STYLE_UNKNOWN = "TEMPLATE_STYLE_UNKNOWN"
    const val TEMPLATE_STYLE_NOT_APPLICABLE = "TEMPLATE_STYLE_NOT_APPLICABLE"
    const val TEMPLATE_STYLE_PRESET_UNKNOWN = "TEMPLATE_STYLE_PRESET_UNKNOWN"
    const val TEMPLATE_THEME_NOT_FOUND = "TEMPLATE_THEME_NOT_FOUND"
    const val TEMPLATE_EXPRESSION_INVALID = "TEMPLATE_EXPRESSION_INVALID"

    const val PLACEHOLDER_NAME_DUPLICATE = "PLACEHOLDER_NAME_DUPLICATE"
    const val PLACEHOLDER_NAME_INVALID = "PLACEHOLDER_NAME_INVALID"
    const val PLACEHOLDER_NESTED_DEFINITION = "PLACEHOLDER_NESTED_DEFINITION"
    const val PLACEHOLDER_OUTSIDE_STENCIL = "PLACEHOLDER_OUTSIDE_STENCIL"
    const val STENCIL_NESTING_DEPTH_EXCEEDED = "STENCIL_NESTING_DEPTH_EXCEEDED"
    const val STENCIL_RECURSION = "STENCIL_RECURSION"
    const val STENCIL_REFERENCE_INVALID = "STENCIL_REFERENCE_INVALID"
    const val STENCIL_REFERENCE_NOT_FOUND = "STENCIL_REFERENCE_NOT_FOUND"

    const val NODE_PARAMETER_BINDING_UNKNOWN = "NODE_PARAMETER_BINDING_UNKNOWN"
    const val NODE_PARAMETER_BINDING_SYNTAX_INVALID = "NODE_PARAMETER_BINDING_SYNTAX_INVALID"
    const val NODE_PARAMETER_BINDING_MISSING_REQUIRED = "NODE_PARAMETER_BINDING_MISSING_REQUIRED"
    const val NODE_PARAMETER_BINDINGS_INVALID_SHAPE = "NODE_PARAMETER_BINDINGS_INVALID_SHAPE"
    const val NODE_PARAMETER_BINDING_NAME_INVALID = "NODE_PARAMETER_BINDING_NAME_INVALID"
    const val NODE_PARAMETER_BINDING_EMPTY = "NODE_PARAMETER_BINDING_EMPTY"
    const val NODE_PARAMS_ALIAS_RESERVED = "NODE_PARAMS_ALIAS_RESERVED"

    const val PARAMETER_SCHEMA_INVALID_TYPE = "PARAMETER_SCHEMA_INVALID_TYPE"
    const val PARAMETER_REQUIRED_UNKNOWN = "PARAMETER_REQUIRED_UNKNOWN"
    const val PARAMETER_NAME_INVALID = "PARAMETER_NAME_INVALID"
    const val PARAMETER_NAME_RESERVED = "PARAMETER_NAME_RESERVED"
    const val PARAMETER_TYPE_UNSUPPORTED = "PARAMETER_TYPE_UNSUPPORTED"
    const val PARAMETER_DEFAULT_TYPE_MISMATCH = "PARAMETER_DEFAULT_TYPE_MISMATCH"

    const val PAGEHEADER_TOO_MANY = "PAGEHEADER_TOO_MANY"
    const val PAGEHEADER_ROOT_MISSING = "PAGEHEADER_ROOT_MISSING"
    const val PAGEHEADER_NOT_AT_ROOT = "PAGEHEADER_NOT_AT_ROOT"

    val ALL: Set<String> = setOf(
        TEMPLATE_GRAPH_INVALID,
        TEMPLATE_NODE_TYPE_UNSUPPORTED,
        TEMPLATE_SLOT_INVALID,
        TEMPLATE_CHILD_TYPE_NOT_ALLOWED,
        TEMPLATE_NODE_PROPERTY_INVALID,
        TEMPLATE_STYLE_UNKNOWN,
        TEMPLATE_STYLE_NOT_APPLICABLE,
        TEMPLATE_STYLE_PRESET_UNKNOWN,
        TEMPLATE_THEME_NOT_FOUND,
        TEMPLATE_EXPRESSION_INVALID,
        PLACEHOLDER_NAME_DUPLICATE,
        PLACEHOLDER_NAME_INVALID,
        PLACEHOLDER_NESTED_DEFINITION,
        PLACEHOLDER_OUTSIDE_STENCIL,
        STENCIL_NESTING_DEPTH_EXCEEDED,
        STENCIL_RECURSION,
        STENCIL_REFERENCE_INVALID,
        STENCIL_REFERENCE_NOT_FOUND,
        NODE_PARAMETER_BINDING_UNKNOWN,
        NODE_PARAMETER_BINDING_SYNTAX_INVALID,
        NODE_PARAMETER_BINDING_MISSING_REQUIRED,
        NODE_PARAMETER_BINDINGS_INVALID_SHAPE,
        NODE_PARAMETER_BINDING_NAME_INVALID,
        NODE_PARAMETER_BINDING_EMPTY,
        NODE_PARAMS_ALIAS_RESERVED,
        PARAMETER_SCHEMA_INVALID_TYPE,
        PARAMETER_REQUIRED_UNKNOWN,
        PARAMETER_NAME_INVALID,
        PARAMETER_NAME_RESERVED,
        PARAMETER_TYPE_UNSUPPORTED,
        PARAMETER_DEFAULT_TYPE_MISMATCH,
        PAGEHEADER_TOO_MANY,
        PAGEHEADER_ROOT_MISSING,
        PAGEHEADER_NOT_AT_ROOT,
    )
}
