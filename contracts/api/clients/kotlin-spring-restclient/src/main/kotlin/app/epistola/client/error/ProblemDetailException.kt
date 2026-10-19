// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.error

import app.epistola.client.infrastructure.Serializer
import app.epistola.client.model.DataModelValidationError
import app.epistola.client.model.InvalidDataField
import app.epistola.client.model.MissingDataField
import app.epistola.client.model.ProblemDetail
import app.epistola.client.model.ValidationError
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.web.client.RestClientResponseException
import java.net.URI
import java.nio.charset.Charset

/**
 * A [RestClientResponseException] carrying a parsed [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)
 * [ProblemDetail] (`application/problem+json`) body.
 *
 * Thrown by the opt-in error handler installed via [installProblemDetailHandler]. It extends
 * [RestClientResponseException] on purpose: the generated APIs declare
 * `@Throws(RestClientResponseException::class)`, so existing `catch (e: RestClientResponseException)`
 * sites keep working and consumers retain the inherited `statusCode` /
 * [getResponseBodyAsString] accessors. (Note: because it is a sibling of `HttpClientErrorException`
 * / `HttpServerErrorException`, it is *not* caught by `catch (e: HttpClientErrorException)` — catch
 * [ProblemDetailException] or [RestClientResponseException].)
 *
 * The machine-readable discriminator is the problem [type] URI; switch on [typeSlug]. Field-level
 * validation errors (the `ValidationProblemDetail` shape from the contract) are surfaced via
 * [errors]; per-example data-model validation failures (the `DataModelValidationProblemDetail`
 * shape, `data-model-validation-error`) are surfaced via [validationErrors] — `ProblemDetail`,
 * `ValidationProblemDetail`, and `DataModelValidationProblemDetail` are independent classes, so the
 * base fields and each extension are carried separately. Any *other* member a problem body carries —
 * one the contract does not name, such as `catalog-schema-too-old`'s `version` / `baselineVersion` —
 * is in [extensions], since [ProblemDetail] is hand-written with a catch-all rather than generated
 * as a closed set of fields.
 */
class ProblemDetailException(
    /** The parsed base problem (`type`, `title`, `status`, `detail`, `instance`). */
    val problem: ProblemDetail,
    /** Field-level validation errors when the body was a `ValidationProblemDetail`, else empty. */
    val errors: List<ValidationError>,
    /**
     * Per-example data-model validation failures (example name → failures) when the body was a
     * `DataModelValidationProblemDetail` (`data-model-validation-error`, 422), else empty.
     */
    val validationErrors: Map<String, List<DataModelValidationError>> = emptyMap(),
    statusCode: HttpStatusCode,
    statusText: String,
    headers: HttpHeaders?,
    responseBody: ByteArray?,
    responseCharset: Charset?,
) : RestClientResponseException(
    buildMessage(statusCode, problem),
    statusCode,
    statusText,
    headers,
    responseBody,
    responseCharset,
) {
    /** The problem `type` URI (`about:blank` when unspecified). */
    val type: URI get() = problem.type

    /**
     * Kebab-case slug derived from [type] by stripping [ProblemTypes.TYPE_BASE], or `null` for
     * `about:blank` and non-Epistola types. Compare against [KnownProblemSlugs].
     */
    val typeSlug: String? get() = ProblemTypes.slugFor(problem.type)

    /** Short human-readable summary of the problem type (RFC 9457 `title`). */
    val title: String get() = problem.title

    /**
     * The HTTP status carried in the problem body. Usually equal to the response
     * [getStatusCode], but named distinctly to avoid clashing with the inherited member.
     */
    val problemStatus: Int get() = problem.status

    /** Occurrence-specific explanation (RFC 9457 `detail`), if the server provided one. */
    val detail: String? get() = problem.detail

    /**
     * Every problem member outside `type`/`title`/`status`/`detail`/`instance`, keyed by its JSON
     * name — `catalog-schema-too-old`'s `version` and `baselineVersion`, or any extension member on
     * any problem type the contract adds later, without needing a client release to read it.
     */
    val extensions: Map<String, Any?> get() = problem.extensions

    /** True when this problem carried field-level validation errors. */
    val isValidationProblem: Boolean get() = errors.isNotEmpty()

    /** True when this problem carried per-example data-model validation failures. */
    val isDataModelValidationProblem: Boolean get() = validationErrors.isNotEmpty()

    /**
     * Absent fields reported by a `template-data-invalid` problem; empty for any other.
     *
     * Each `path` is a JSON Pointer into the request's `data` and `schema` is the contract's schema
     * for that field, so a caller can ask for exactly what is missing. An entry whose `required` is
     * false is informational: the resolved version's template reads the field, but leaving it out
     * does not make the data invalid.
     *
     * A typed view over [extensions], which already carries every member the base problem does not
     * model, so this only saves the conversion.
     */
    val missingFields: List<MissingDataField> get() = membersAt(ProblemExtensionMembers.MISSING_FIELDS)

    /** Supplied values the template's data contract rejected; empty for any other problem. */
    val invalidFields: List<InvalidDataField> get() = membersAt(ProblemExtensionMembers.INVALID_FIELDS)

    /** True when this problem described template data field by field. */
    val isTemplateDataProblem: Boolean get() = missingFields.isNotEmpty() || invalidFields.isNotEmpty()

    /**
     * The releases of other catalogs a `release-dependencies-missing` problem (409, from
     * `deployRelease`) reports cannot be had, in the order the server listed them; empty for any
     * other problem.
     *
     * Each entry's [MissingReleaseDependency.reason] is kept as the string the server sent, so a
     * reason a later contract adds is surfaced rather than dropped. An entry missing one of its
     * five members, or carrying one that is not a string, is skipped.
     */
    val missingDependencies: List<MissingReleaseDependency>
        get() {
            val raw = extensions[ProblemExtensionMembers.MISSING_DEPENDENCIES] as? List<*> ?: return emptyList()
            return raw.mapNotNull { MissingReleaseDependency.fromMember(it) }
        }

    /** True when this problem listed dependency releases a deployment is missing. */
    val isReleaseDependenciesMissingProblem: Boolean get() = missingDependencies.isNotEmpty()

    /**
     * The drafts of other catalogs a `release-dependencies-unreleased` problem (409, from
     * `releaseCatalogDraft`) reports the released draft's dependency declarations name, in the
     * order the server listed them; empty for any other problem. Release them first, or along with
     * the draft (`releaseDependencies`).
     *
     * An entry missing one of its five members, or carrying one that is not a string, is skipped.
     */
    val unreleasedDependencies: List<UnreleasedCatalogDependency>
        get() {
            val raw = extensions[ProblemExtensionMembers.UNRELEASED_DEPENDENCIES] as? List<*> ?: return emptyList()
            return raw.mapNotNull { UnreleasedCatalogDependency.fromMember(it) }
        }

    /** True when this problem listed dependency drafts a release would have to release first. */
    val isReleaseDependenciesUnreleasedProblem: Boolean get() = unreleasedDependencies.isNotEmpty()

    /**
     * One extension member's array of objects, converted to [T]. An entry of an unexpected shape
     * yields nothing rather than a guess: a malformed problem body should not become a confident
     * claim about a particular field, nor hide the problem it decorates.
     */
    private inline fun <reified T> membersAt(member: String): List<T> {
        val raw = extensions[member] as? List<*> ?: return emptyList()
        return raw.mapNotNull { entry ->
            runCatching { Serializer.jacksonObjectMapper.convertValue(entry, T::class.java) }.getOrNull()
        }
    }

    private companion object {
        fun buildMessage(status: HttpStatusCode, problem: ProblemDetail): String = "$status ${problem.title}" + (problem.detail?.let { ": $it" } ?: "")
    }
}
