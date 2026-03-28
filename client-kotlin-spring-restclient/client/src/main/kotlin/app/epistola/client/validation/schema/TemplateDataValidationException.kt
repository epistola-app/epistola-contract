package app.epistola.client.validation.schema

/**
 * Thrown when template data fails JSON Schema validation on the client side.
 * Mirrors the server's validation error structure.
 */
class TemplateDataValidationException(
    val errors: List<ValidationError>,
    message: String = "Template data validation failed with ${errors.size} error(s)",
) : RuntimeException(message) {

    data class ValidationError(
        /** JSON Pointer path to the invalid field, e.g. "/customer/name". */
        val path: String,
        /** Human-readable error description. */
        val message: String,
        /** JSON Schema keyword that failed, e.g. "required", "type", "minLength". */
        val keyword: String?,
    )

    /** Format all errors as a multi-line string. */
    fun formatErrors(): String = errors.joinToString("\n") { "  ${it.path}: ${it.message}" }
}
