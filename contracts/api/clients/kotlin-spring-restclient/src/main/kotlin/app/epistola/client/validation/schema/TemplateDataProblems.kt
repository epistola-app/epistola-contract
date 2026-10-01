// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.validation.schema

import app.epistola.client.error.ProblemDetailException
import app.epistola.client.error.ProblemExtensionMembers
import app.epistola.client.model.InvalidDataField
import app.epistola.client.model.MissingDataField
import app.epistola.client.model.TemplateDataValidationError
import app.epistola.client.validation.schema.TemplateDataValidationException.ValidationError

/**
 * Turns what the server reports about template data into the one error shape
 * [TemplateDataValidator] pins.
 *
 * `invalidFields` and `missingFields` are preferred over `errors` where the server sends them, and
 * not only because they carry more. Their `path` is specified as a JSON Pointer into the data,
 * which is what the interface promises callers; `errors[].path` describes itself as a pointer but
 * is documented with a JSONPath example (`$.customer.email`), so passing it through unexamined
 * would make the promise depend on which member the server happened to fill. The fallback to
 * `errors` exists so a server that sends only that is still reported rather than silently accepted.
 *
 * An absent **optional** field is not a finding — the contract says so explicitly, and the server
 * lists those in `missingFields` too so a client can offer them. Only `required` ones become errors.
 */
internal fun templateDataValidationErrors(
    errors: List<TemplateDataValidationError>?,
    missingFields: List<MissingDataField>?,
    invalidFields: List<InvalidDataField>?,
): List<ValidationError> {
    val fromFields = buildList {
        invalidFields?.forEach { field ->
            add(ValidationError(path = field.path, message = field.message, keyword = field.keyword))
        }
        missingFields?.filter { it.required }?.forEach { field ->
            add(ValidationError(path = field.path, message = MISSING_REQUIRED_MESSAGE, keyword = "required"))
        }
    }
    if (fromFields.isNotEmpty()) {
        return fromFields
    }
    val fromErrors = errors.orEmpty().map { error ->
        ValidationError(path = error.path, message = error.message, keyword = error.keyword)
    }
    return fromErrors.ifEmpty { listOf(unspecifiedFinding()) }
}

/**
 * The same, read off a `template-data-invalid` problem response.
 *
 * The generated client does not type a problem body's extension members, so these arrive as plain
 * maps on [ProblemDetailException.extensions] and are read by the names the contract generates into
 * [ProblemExtensionMembers]. Anything of an unexpected shape is skipped rather than guessed at: a
 * malformed problem body should not become a confident claim about a particular field.
 */
internal fun ProblemDetailException.templateDataValidationErrors(): List<ValidationError> {
    val fromFields = buildList {
        extensions[ProblemExtensionMembers.INVALID_FIELDS].asMembers().forEach { field ->
            val path = field["path"] as? String ?: return@forEach
            add(
                ValidationError(
                    path = path,
                    message = field["message"] as? String ?: INVALID_VALUE_MESSAGE,
                    keyword = field["keyword"] as? String,
                ),
            )
        }
        extensions[ProblemExtensionMembers.MISSING_FIELDS].asMembers().forEach { field ->
            val path = field["path"] as? String ?: return@forEach
            if (field["required"] == false) return@forEach
            add(ValidationError(path = path, message = MISSING_REQUIRED_MESSAGE, keyword = "required"))
        }
    }
    if (fromFields.isNotEmpty()) {
        return fromFields
    }
    // The base validation members, whose `field` is a name rather than a pointer. Reported as-is
    // with no path: inventing `/$field` would claim a location the server did not give.
    val fromErrors = errors.map { error ->
        ValidationError(path = "", message = listOfNotNull(error.field, error.message).joinToString(": "), keyword = null)
    }
    return fromErrors.ifEmpty { listOf(unspecifiedFinding()) }
}

/**
 * The finding for a rejection that names nothing.
 *
 * Both callers only get here once the verdict is already "not acceptable", and an empty list means
 * the opposite to a [TemplateDataValidator] caller. Returning nothing would turn a rejection into
 * a pass, so a server that refuses the data without saying which field is wrong is still reported —
 * at the document root, which is the only location that is certainly true.
 */
private fun unspecifiedFinding() = ValidationError(
    path = "",
    message = "does not fit this template's data contract, which gave no field-level detail",
    keyword = null,
)

/**
 * An extension member's array of objects, as Jackson left it. The cast is unchecked by erasure and
 * deliberately shallow — only "it is a map" is actually verified, and every value is read back with
 * a safe cast, so a member of the wrong shape yields no finding instead of a wrong one.
 */
@Suppress("UNCHECKED_CAST")
private fun Any?.asMembers(): List<Map<String, Any?>> = (this as? List<*>)?.mapNotNull { it as? Map<String, Any?> }.orEmpty()

private const val MISSING_REQUIRED_MESSAGE = "is required but was not supplied"
private const val INVALID_VALUE_MESSAGE = "is not acceptable for this template"
