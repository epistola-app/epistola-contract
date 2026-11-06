// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.error

import app.epistola.client.infrastructure.Serializer
import app.epistola.client.model.CatalogReleaseRefDto
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
 * one the contract does not name, such as `catalog-schema-too-old`'s `baselineVersion` —
 * is in [extensions], since [ProblemDetail] is hand-written with a catch-all rather than generated
 * as a closed set of fields.
 */
class ProblemDetailException(
    /** The parsed base problem (`type`, `title`, `status`, `detail`, `instance`, `help`). */
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
     * The problem type's page with this request's context in its query, such as
     * `https://epistola.app/errors/not-found?operation=getTemplate`, or `null` when the server sent
     * none. Switch on the type ([typeSlug]), not on this; open it for hints on what to do. It names
     * contract terms only, never a value, so it can be logged or shared.
     */
    val help: String? get() = problem.help

    /**
     * Every problem member outside `type`/`title`/`status`/`detail`/`instance`/`help`, keyed by its JSON
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
     * `deployCatalogRelease`) reports cannot be had, in the order the server listed them; empty for any
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
     * Why the catalog cannot be changed, from a `catalog-read-only` problem (409, from any write to
     * a catalog whose `CatalogDto.editable` is false): `editing-disabled` (the installation only
     * receives and runs releases) or `supplied-by-epistola` (the server supplies the catalog). Kept
     * as the string the server sent, so a reason a later contract adds is surfaced rather than
     * dropped; null for any other problem, and when the member is not a string.
     */
    val readOnlyReason: String? get() = extensions[ProblemExtensionMembers.READ_ONLY_REASON] as? String

    /** True when this problem said the catalog cannot be changed, and why. */
    val isCatalogReadOnlyProblem: Boolean get() = readOnlyReason != null

    /**
     * The conflicts a `merge-conflicts-unresolved` problem (409, from `mergeIntoCatalogDraft`,
     * alpha) reports have no resolution, each as `resourceType/resourceKey`, in the order the server
     * listed them; empty for any other problem. An entry that is not a string is skipped.
     */
    val unresolvedConflicts: List<String>
        get() = (extensions[ProblemExtensionMembers.UNRESOLVED_CONFLICTS] as? List<*>).orEmpty().filterIsInstance<String>()

    /** True when this problem listed merge conflicts that need a resolution. */
    val isMergeConflictsUnresolvedProblem: Boolean get() = unresolvedConflicts.isNotEmpty()

    /**
     * The version of the release a `merge-base-missing` problem (409, from `mergeIntoCatalogDraft`,
     * alpha) asks to get first: the merge base, which this installation does not have. Read from
     * the `version` member of that problem only, since other problems use the name for other
     * things; null for any other problem, and when the member is not a string.
     */
    val missingBaseVersion: String?
        get() = if (typeSlug == KnownProblemSlugs.MERGE_BASE_MISSING) extensions[ProblemExtensionMembers.VERSION] as? String else null

    /**
     * That release's catalog fingerprint (`v5:<hex>`), from the `fingerprint` member of a
     * `merge-base-missing` problem; null for any other problem, and when the member is not a string.
     */
    val missingBaseFingerprint: String?
        get() = if (typeSlug == KnownProblemSlugs.MERGE_BASE_MISSING) extensions[ProblemExtensionMembers.FINGERPRINT] as? String else null

    /** True when this problem named a merge base this installation does not have. */
    val isMergeBaseMissingProblem: Boolean get() = missingBaseVersion != null

    /**
     * The versions of the releases a `catalog-version-behind` problem (409, from
     * `releaseCatalogDraft`) says the draft is behind and the request did not acknowledge: name each
     * in `acknowledgedBehind` to release it anyway. Empty for any other problem; an entry that is
     * not a string is skipped.
     */
    val behind: List<String>
        get() = (extensions[ProblemExtensionMembers.BEHIND] as? List<*>).orEmpty().filterIsInstance<String>()

    /** True when this problem said the draft is behind releases the request did not acknowledge. */
    val isCatalogVersionBehindProblem: Boolean get() = typeSlug == KnownProblemSlugs.CATALOG_VERSION_BEHIND

    /**
     * The environments a `release-in-use` problem (409) says deploy the release; empty for any other
     * problem, and when none does. No operation deletes a released release yet; discarding a draft
     * is refused with `catalog-draft-in-use` ([dependents], [heldResources]).
     */
    val environments: List<String>
        get() = (extensions[ProblemExtensionMembers.ENVIRONMENTS] as? List<*>).orEmpty().filterIsInstance<String>()

    /**
     * The releases of other catalogs a `release-in-use` problem says pin the release, and render with
     * it; empty for any other problem. An entry of an unexpected shape is skipped.
     */
    val pinnedBy: List<CatalogReleaseRefDto> get() = membersAt(ProblemExtensionMembers.PINNED_BY)

    /** True when this problem said the release cannot be deleted while something uses it. */
    val isReleaseInUseProblem: Boolean get() = typeSlug == KnownProblemSlugs.RELEASE_IN_USE

    /**
     * The drafts of other catalogs a `catalog-draft-in-use` problem (409, from
     * `deleteCatalogRelease`) says name the draft in their dependency declarations, in the order the
     * server listed them; empty for any other problem, and when none does. Change their
     * declarations, or discard them, first.
     *
     * An entry missing one of its five members, or carrying one that is not a string, is skipped.
     */
    val dependents: List<CatalogDraftRef>
        get() {
            val raw = extensions[ProblemExtensionMembers.DEPENDENTS] as? List<*> ?: return emptyList()
            return raw.mapNotNull { CatalogDraftRef.fromMember(it) }
        }

    /**
     * The resources a `catalog-draft-in-use` problem says only the draft holds while something
     * outside it still uses them, each as `resourceType/resourceKey` (`theme/house-style`), in the
     * order the server listed them; empty for any other problem, and when none. An entry that is
     * not a string is skipped.
     */
    val heldResources: List<String>
        get() = (extensions[ProblemExtensionMembers.HELD_RESOURCES] as? List<*>).orEmpty().filterIsInstance<String>()

    /** True when this problem said the draft cannot be discarded while something relies on it. */
    val isCatalogDraftInUseProblem: Boolean get() = typeSlug == KnownProblemSlugs.CATALOG_DRAFT_IN_USE

    /**
     * The keys of the variants an `ambiguous-variant` problem (409, from generating or previewing a
     * document) says match the request's attributes equally well: name one by `variantKey`, or send
     * attributes that tell them apart. Empty for any other problem.
     */
    val tiedVariants: List<String>
        get() = (extensions[ProblemExtensionMembers.TIED_VARIANTS] as? List<*>).orEmpty().filterIsInstance<String>()

    /** True when this problem said several variants match equally well. */
    val isAmbiguousVariantProblem: Boolean get() = typeSlug == KnownProblemSlugs.AMBIGUOUS_VARIANT

    /** True when this problem is a refused authorization (`forbidden`, 403). */
    val isForbiddenProblem: Boolean get() = typeSlug == KnownProblemSlugs.FORBIDDEN

    /**
     * The permission a `forbidden` problem says the caller lacks, as the operation's
     * `x-required-permissions` names it (`TEMPLATE_EDIT`): grant the caller, or the API key, a role
     * that carries it. Null for any other problem, and when the member is absent or not a string.
     */
    val requiredPermission: String? get() = forbiddenMember(ProblemExtensionMembers.REQUIRED_PERMISSION)

    /**
     * The platform role a `forbidden` problem says the caller lacks (`TENANT_MANAGER`); null for any
     * other problem, and when the member is absent or not a string.
     */
    val requiredPlatformRole: String? get() = forbiddenMember(ProblemExtensionMembers.REQUIRED_PLATFORM_ROLE)

    /**
     * The tenant a `forbidden` problem is about, from its `tenantKey` member. Without a
     * [requiredPermission], the caller has no access to that tenant at all. Null for any other
     * problem, which may use the name for other things.
     */
    val refusedTenantKey: String? get() = forbiddenMember(ProblemExtensionMembers.TENANT_KEY)

    /**
     * Why a policy refused the request, from the `reason` member of a `forbidden` problem: text for a
     * person, sent when there is no single permission to grant. Null for any other problem.
     */
    val refusalReason: String? get() = forbiddenMember(ProblemExtensionMembers.REASON)

    private fun forbiddenMember(name: String): String? = if (isForbiddenProblem) extensions[name] as? String else null

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
