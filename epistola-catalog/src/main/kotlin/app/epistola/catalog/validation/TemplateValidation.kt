package app.epistola.catalog.validation

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument

/** Severity shared by portable template and whole-catalog findings. */
enum class ValidationSeverity {
    /** Invalid portable content that must be corrected before acceptance. */
    ERROR,

    /** Suspicious but portable content that consumers may still accept. */
    WARNING,
}

/**
 * Stable, product-neutral diagnostic returned by [TemplateValidator].
 *
 * Findings are values rather than exceptions so validation can continue after
 * ordinary content errors and report every independently detectable problem.
 */
data class TemplateValidationFinding(
    /** Machine-readable identifier from [TemplateValidationCodes]. */
    val code: String,
    /** Whether the finding prevents the document from being valid. */
    val severity: ValidationSeverity,
    /** Deterministic property path within the template document. */
    val path: String,
    /** Human-readable explanation for logs and user interfaces. */
    val message: String,
)

/** Deterministically ordered result of portable template validation. */
data class TemplateValidationReport(
    val findings: List<TemplateValidationFinding>,
) {
    /** True when [findings] contains no errors. */
    val valid: Boolean get() = findings.none { it.severity == ValidationSeverity.ERROR }
}

/** Semantic role of the document currently being validated. */
enum class TemplateDocumentKind {
    /** A complete template or variant template. */
    TEMPLATE,

    /** Reusable stencil content with stencil-only placeholder rules. */
    STENCIL,
}

/**
 * Result of resolving a catalog-scoped reference through a consumer adapter.
 */
enum class ResourceResolution {
    /** The referenced resource exists and is usable. */
    PRESENT,

    /** The consumer can authoritatively say the resource does not exist. */
    MISSING,

    /** The consumer did not have enough catalog context to decide. */
    UNKNOWN,
}

/**
 * Product-neutral identity of a resource referenced by template content.
 *
 * @property type portable resource discriminator such as `stencil` or `theme`.
 * @property slug resource slug within its catalog.
 * @property catalogKey owning catalog; null means the current catalog.
 * @property version exact stencil version, when the reference is versioned.
 * @property isDraft whether authoring explicitly targets a mutable draft.
 */
data class CatalogResourceReference(
    val type: String,
    val slug: String,
    val catalogKey: String? = null,
    val version: Int? = null,
    val isDraft: Boolean = false,
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
    /** Controls template-only versus stencil-only validation rules. */
    val documentKind: TemplateDocumentKind get() = TemplateDocumentKind.TEMPLATE

    /**
     * Catalog that owns the document being validated. A null stencil-node
     * catalogKey resolves to this catalog when comparing reference identity.
     */
    val currentCatalogKey: String? get() = null

    /**
     * Stencil resource whose content is being validated, when applicable.
     *
     * Seeding validation with the containing stencil makes direct and
     * transitive self-reference detectable even though stencil resource
     * content uses a synthetic root node rather than an outer stencil node.
     */
    val containingStencil: CatalogResourceReference? get() = null

    /**
     * Whether authoring references may target draft stencil versions.
     * Portable catalog validation overrides this to false.
     */
    val allowDraftStencilReferences: Boolean get() = true

    /**
     * Resolves a theme, stencil, or other catalog-scoped reference.
     *
     * Return [ResourceResolution.UNKNOWN] when existence cannot be checked.
     * Validators only emit a not-found finding for [ResourceResolution.MISSING].
     */
    fun resolveResource(reference: CatalogResourceReference): ResourceResolution = ResourceResolution.UNKNOWN

    /**
     * Resolves the parameter schema used to validate a stencil node's bindings.
     *
     * The default reads the immutable `parameterSchemaSnapshot` embedded in
     * node properties. Authoring consumers may override this to refresh a
     * draft schema before validation.
     */
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
        /** Context for standalone structural validation without lookups. */
        val EMPTY: TemplateValidationContext = object : TemplateValidationContext {}

        /**
         * Creates a standalone stencil context.
         *
         * Supplying [stencilId] seeds self-reference detection. Catalog and
         * version are optional when validating content that has not yet been
         * assigned a portable identity.
         */
        fun forStencil(
            stencilId: String? = null,
            catalogKey: String? = null,
            version: Int? = null,
        ): TemplateValidationContext = object : TemplateValidationContext {
            override val documentKind: TemplateDocumentKind = TemplateDocumentKind.STENCIL
            override val currentCatalogKey: String? = catalogKey
            override val containingStencil: CatalogResourceReference? = stencilId?.let {
                CatalogResourceReference("stencil", it, catalogKey, version)
            }
        }
    }
}

/** Portable limits that consumers must enforce consistently. */
object TemplateValidationLimits {
    /** Maximum complete composition chain, counting the outer stencil. */
    const val MAX_STENCIL_NESTING_DEPTH = 5
}

/**
 * Stable finding-code registry for [TemplateValidator] and
 * [ParameterSchemaValidator].
 *
 * Codes are compatibility-sensitive API. Additions require a matching golden
 * fixture; existing values must not be repurposed.
 */
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

    /** Complete published code set, used to enforce fixture coverage. */
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
