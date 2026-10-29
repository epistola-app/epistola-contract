// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.api.error

import app.epistola.api.model.CatalogReadOnlyReason
import app.epistola.api.model.CatalogReleaseRefDto
import app.epistola.api.model.DataModelValidationError
import app.epistola.api.model.MissingReleaseDependency
import app.epistola.api.model.UnreleasedCatalogDependency
import app.epistola.api.model.ValidationError
import app.epistola.protocol.ProblemTypeUris
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import java.net.URI

/**
 * Builds [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem responses using Spring's
 * native [ProblemDetail], keeping the `type` discriminator and the `errors` extension
 * consistent with the API contract.
 *
 * This is an **opt-in** helper — it registers no beans and installs no error handling. The
 * `@RestControllerAdvice` that turns exceptions into responses lives in the consuming
 * application (see the module README).
 *
 * The contract's machine-readable discriminator is the problem [type] URI (there is no separate
 * `code` member). Application-level errors use a `https://epistola.app/errors/{slug}` type;
 * framework-level errors with no specific type keep Spring's default `about:blank`.
 *
 * Example usage:
 * ```
 * // application-level error
 * throw ErrorResponseException(
 *     HttpStatus.NOT_FOUND,
 *     ProblemDetails.of(
 *         status = HttpStatus.NOT_FOUND,
 *         type = ProblemDetails.typeFor("not-found"),
 *         detail = "Theme 'classic' was not found",
 *     ),
 *     null,
 * )
 * ```
 */
object ProblemDetails {

    /**
     * Base URI for Epistola problem `type` values, e.g. `https://epistola.app/errors/not-found`.
     * Taken from [GENERATED_PROBLEM_TYPE_BASE], which the build writes from the spec's
     * `x-problem-types` registry.
     */
    const val TYPE_BASE: String = GENERATED_PROBLEM_TYPE_BASE

    /** The RFC 9457 default problem type, used when no specific [type] is supplied. */
    val BLANK_TYPE: URI = ProblemTypeUris.BLANK_TYPE

    /**
     * Extension member carrying field-level validation errors. Generated from the
     * `ValidationProblemDetail` schema, so it stays the name the clients read.
     */
    const val ERRORS_PROPERTY: String = ProblemExtensionMembers.ERRORS

    /**
     * Extension member carrying per-example data-model validation failures. Generated from the
     * `DataModelValidationProblemDetail` schema, so it stays the name the clients read.
     */
    const val VALIDATION_ERRORS_PROPERTY: String = ProblemExtensionMembers.VALIDATION_ERRORS

    /**
     * Extension member listing the dependency releases a deployment is missing. Generated from the
     * `ReleaseDependenciesMissingProblemDetail` schema, so it stays the name the clients read.
     */
    const val MISSING_DEPENDENCIES_PROPERTY: String = ProblemExtensionMembers.MISSING_DEPENDENCIES

    /**
     * Extension member listing the drafts of other catalogs a release would need released first.
     * Generated from the `ReleaseDependenciesUnreleasedProblemDetail` schema, so it stays the name
     * the clients read.
     */
    const val UNRELEASED_DEPENDENCIES_PROPERTY: String = ProblemExtensionMembers.UNRELEASED_DEPENDENCIES

    /**
     * Extension member saying why a catalog cannot be changed. Generated from the
     * `CatalogReadOnlyProblemDetail` schema, so it stays the name the clients read.
     */
    const val READ_ONLY_REASON_PROPERTY: String = ProblemExtensionMembers.READ_ONLY_REASON

    /**
     * Extension member listing the conflicts of a merge that have no resolution. Generated from the
     * `MergeConflictsUnresolvedProblemDetail` schema, so it stays the name the clients read.
     */
    const val UNRESOLVED_CONFLICTS_PROPERTY: String = ProblemExtensionMembers.UNRESOLVED_CONFLICTS

    /**
     * Extension member naming the version of the release a `merge-base-missing` problem asks for.
     * Generated from the `MergeBaseMissingProblemDetail` schema, so it stays the name the clients read.
     */
    const val VERSION_PROPERTY: String = ProblemExtensionMembers.VERSION

    /** Extension member naming that release's catalog fingerprint (`MergeBaseMissingProblemDetail`). */
    const val FINGERPRINT_PROPERTY: String = ProblemExtensionMembers.FINGERPRINT

    /**
     * Extension member naming the rendering defaults version a `catalog-rendering-defaults-too-new`
     * problem's release names (`CatalogRenderingDefaultsTooNewProblemDetail`).
     */
    const val RENDERING_DEFAULTS_VERSION_PROPERTY: String = ProblemExtensionMembers.RENDERING_DEFAULTS_VERSION

    /** Extension member naming the newest rendering defaults version this installation has. */
    const val SUPPORTED_VERSION_PROPERTY: String = ProblemExtensionMembers.SUPPORTED_VERSION

    /**
     * Extension member listing the versions of the releases a draft is behind that a release did not
     * acknowledge. Generated from the `CatalogVersionBehindProblemDetail` schema.
     */
    const val BEHIND_PROPERTY: String = ProblemExtensionMembers.BEHIND

    /** Extension member listing the environments that deploy a release (`ReleaseInUseProblemDetail`). */
    const val ENVIRONMENTS_PROPERTY: String = ProblemExtensionMembers.ENVIRONMENTS

    /** Extension member listing the releases of other catalogs that pin a release (`ReleaseInUseProblemDetail`). */
    const val PINNED_BY_PROPERTY: String = ProblemExtensionMembers.PINNED_BY

    /** Extension member listing the variants that match equally well (`AmbiguousVariantProblemDetail`). */
    const val TIED_VARIANTS_PROPERTY: String = ProblemExtensionMembers.TIED_VARIANTS

    // The mapping is shared with the clients, which use it in the other direction.
    private val uris = ProblemTypeUris.of(TYPE_BASE)

    /** Builds a problem `type` URI from a kebab-case slug, e.g. `typeFor("not-found")`. */
    fun typeFor(slug: String): URI = uris.typeFor(slug)

    /**
     * The kebab-case slug of a problem `type` URI, or `null` for `about:blank` and non-Epistola
     * types — the inverse of [typeFor], and what the clients switch on.
     */
    fun slugFor(type: URI?): String? = uris.slugFor(type)

    /**
     * The canonical problem `type` slugs from the contract's error-type registry, for use with
     * [typeFor].
     *
     * Every value here comes from [KnownProblemSlugs], which the build generates from the spec's
     * `x-problem-types` extension — the same registry the published clients generate their
     * constants from, so a `when (e.typeSlug)` on the client lines up with what this server emits.
     * Prefer referring to [KnownProblemSlugs] directly in new code; this object is the shape the
     * server module has always exposed, and `ProblemRegistryTest` fails if it stops covering the
     * registry. The slug list is open: implementations may also emit slugs not listed here.
     */
    object KnownSlugs {
        /** 400 — the request body or parameters failed validation (use [validation]). */
        const val VALIDATION_ERROR: String = KnownProblemSlugs.VALIDATION_ERROR

        /** 400 — template data breaks the template's data contract (`errors` plus the missing and invalid fields). */
        const val TEMPLATE_DATA_INVALID: String = KnownProblemSlugs.TEMPLATE_DATA_INVALID

        /** 400 — malformed or not applicable to the resource state (no `errors`). */
        const val BAD_REQUEST: String = KnownProblemSlugs.BAD_REQUEST

        /**
         * 400 — a received release renders with rendering defaults this installation does not
         * have; upgrade to receive it (use [catalogRenderingDefaultsTooNew]).
         */
        const val CATALOG_RENDERING_DEFAULTS_TOO_NEW: String = KnownProblemSlugs.CATALOG_RENDERING_DEFAULTS_TOO_NEW

        /** 400 — a catalog archive's wire version is newer than this installation reads. */
        const val CATALOG_SCHEMA_TOO_NEW: String = KnownProblemSlugs.CATALOG_SCHEMA_TOO_NEW

        /** 400 — a catalog archive's wire version is older than the oldest this installation migrates. */
        const val CATALOG_SCHEMA_TOO_OLD: String = KnownProblemSlugs.CATALOG_SCHEMA_TOO_OLD

        /** 400 — the payload is not a catalog archive this installation recognizes. */
        const val CATALOG_SCHEMA_UNKNOWN: String = KnownProblemSlugs.CATALOG_SCHEMA_UNKNOWN

        /** 401 — missing or invalid credentials. */
        const val UNAUTHORIZED: String = KnownProblemSlugs.UNAUTHORIZED

        /** 401 — API-key authentication is disabled for this deployment. */
        const val API_KEY_AUTH_DISABLED: String = KnownProblemSlugs.API_KEY_AUTH_DISABLED

        /** 403 — authenticated but not allowed to perform the operation. */
        const val FORBIDDEN: String = KnownProblemSlugs.FORBIDDEN

        /** 404 — the addressed resource does not exist. */
        const val NOT_FOUND: String = KnownProblemSlugs.NOT_FOUND

        /** 404 — the template exists, but none of its variants matches the attributes required. */
        const val NO_MATCHING_VARIANT: String = KnownProblemSlugs.NO_MATCHING_VARIANT

        /**
         * 404 — the release registry the request names (alpha) is not one this installation has
         * configured or is connected to.
         */
        const val RELEASE_REGISTRY_NOT_FOUND: String = KnownProblemSlugs.RELEASE_REGISTRY_NOT_FOUND

        /** 409 — the request conflicts with the current state of the resource. */
        const val CONFLICT: String = KnownProblemSlugs.CONFLICT

        /** 409 — the draft is behind releases the request did not acknowledge (use [catalogVersionBehind]). */
        const val CATALOG_VERSION_BEHIND: String = KnownProblemSlugs.CATALOG_VERSION_BEHIND

        /** 409 — the release is deployed or pinned, so it cannot be deleted (use [releaseInUse]). */
        const val RELEASE_IN_USE: String = KnownProblemSlugs.RELEASE_IN_USE

        /** 409 — another draft of the catalog has this name. */
        const val CATALOG_DRAFT_NAME_IN_USE: String = KnownProblemSlugs.CATALOG_DRAFT_NAME_IN_USE

        /** 409 — several variants match equally well (use [ambiguousVariant]). */
        const val AMBIGUOUS_VARIANT: String = KnownProblemSlugs.AMBIGUOUS_VARIANT

        /** 409 — the generation job has finished, so it cannot be cancelled. */
        const val GENERATION_JOB_NOT_CANCELLABLE: String = KnownProblemSlugs.GENERATION_JOB_NOT_CANCELLABLE

        /**
         * 409 — publishing a release (alpha) was refused: the release registry holds this version
         * with other content.
         */
        const val RELEASE_NUMBER_TAKEN: String = KnownProblemSlugs.RELEASE_NUMBER_TAKEN

        /**
         * 409 — publishing a release (alpha) was refused: the release registry (the Exchange) does
         * not grant this installation the catalog's namespace.
         */
        const val NAMESPACE_NOT_GRANTED: String = KnownProblemSlugs.NAMESPACE_NOT_GRANTED

        /**
         * 409 — a release cannot be deployed because dependency releases it pins cannot be had
         * (use [releaseDependenciesMissing]).
         */
        const val RELEASE_DEPENDENCIES_MISSING: String = KnownProblemSlugs.RELEASE_DEPENDENCIES_MISSING

        /**
         * 409 — a draft cannot be released because its dependency declarations name drafts of
         * other catalogs, and the request does not release them along
         * (use [releaseDependenciesUnreleased]).
         */
        const val RELEASE_DEPENDENCIES_UNRELEASED: String = KnownProblemSlugs.RELEASE_DEPENDENCIES_UNRELEASED

        /**
         * 409 — the catalog cannot be changed in this installation, so a write to it was refused
         * (use [catalogReadOnly]).
         */
        const val CATALOG_READ_ONLY: String = KnownProblemSlugs.CATALOG_READ_ONLY

        /**
         * 409 — merging a release into a draft (alpha) was refused because conflicts have no
         * resolution (use [mergeConflictsUnresolved]).
         */
        const val MERGE_CONFLICTS_UNRESOLVED: String = KnownProblemSlugs.MERGE_CONFLICTS_UNRESOLVED

        /** 409 — a merge (alpha) cannot be applied: the release removes a resource the draft still uses. */
        const val MERGE_REFUSED: String = KnownProblemSlugs.MERGE_REFUSED

        /**
         * 409 — a merge (alpha) was refused: the draft or the release changed since the preview
         * whose `token` the request sent as `expectedToken`.
         */
        const val MERGE_PREVIEW_OUTDATED: String = KnownProblemSlugs.MERGE_PREVIEW_OUTDATED

        /**
         * 409 — a merge (alpha) was refused: this installation does not have the merge base (use
         * [mergeBaseMissing]).
         */
        const val MERGE_BASE_MISSING: String = KnownProblemSlugs.MERGE_BASE_MISSING

        /**
         * 422 — a merge (alpha) does not apply: the draft already contains the release, or a
         * resolution names a resource that is not a conflict.
         */
        const val MERGE_NOT_APPLICABLE: String = KnownProblemSlugs.MERGE_NOT_APPLICABLE

        /**
         * 409 — a production environment serves only releases: a draft deployed to it, or an
         * environment that serves a draft marked production.
         */
        const val ENVIRONMENT_DEPLOYMENT_REFUSED: String = KnownProblemSlugs.ENVIRONMENT_DEPLOYMENT_REFUSED

        /**
         * 412 — an update's `If-Match` names a revision that is no longer current: the resource
         * changed since it was read, and nothing changed.
         */
        const val RESOURCE_MODIFIED: String = KnownProblemSlugs.RESOURCE_MODIFIED

        /** 422 — data examples do not validate against the data model (use [dataModelValidation]). */
        const val DATA_MODEL_VALIDATION_ERROR: String = KnownProblemSlugs.DATA_MODEL_VALIDATION_ERROR

        /** 429 — too many requests; the client is being rate limited. */
        const val RATE_LIMITED: String = KnownProblemSlugs.RATE_LIMITED

        /** 501 — the operation is part of the contract, but this server does not implement it yet. */
        const val OPERATION_NOT_IMPLEMENTED: String = KnownProblemSlugs.OPERATION_NOT_IMPLEMENTED

        /**
         * 503 or 502 — a catalog's source could not be used, so its releases could not
         * be listed or installed (use [catalogSourceUnavailable]).
         */
        const val CATALOG_SOURCE_UNAVAILABLE: String = KnownProblemSlugs.CATALOG_SOURCE_UNAVAILABLE
    }

    /**
     * Builds a [ProblemDetail] for an application-level error. `title` defaults to the status
     * reason phrase; [type] is the machine-readable discriminator and defaults to [BLANK_TYPE]
     * (`about:blank`) when not supplied.
     */
    fun of(
        status: HttpStatusCode,
        type: URI = BLANK_TYPE,
        detail: String? = null,
        instance: URI? = null,
    ): ProblemDetail {
        val problem = ProblemDetail.forStatus(status)
        problem.type = type
        if (detail != null) problem.detail = detail
        if (instance != null) problem.instance = instance
        return problem
    }

    /**
     * Builds a validation [ProblemDetail] — like [of] plus the field-level [errors] extension
     * member (the `ValidationProblemDetail` shape from the contract).
     */
    fun validation(
        status: HttpStatusCode,
        type: URI = BLANK_TYPE,
        errors: List<ValidationError>,
        detail: String? = null,
        instance: URI? = null,
    ): ProblemDetail {
        val problem = of(status, type, detail, instance)
        problem.setProperty(ERRORS_PROPERTY, errors)
        return problem
    }

    /**
     * Builds a data-model validation [ProblemDetail] — like [of] plus the [validationErrors]
     * extension member mapping each data-example name to the failures it produced (the
     * `DataModelValidationProblemDetail` shape from the contract, `data-model-validation-error`).
     */
    fun dataModelValidation(
        status: HttpStatusCode,
        type: URI = BLANK_TYPE,
        validationErrors: Map<String, List<DataModelValidationError>>,
        detail: String? = null,
        instance: URI? = null,
    ): ProblemDetail {
        val problem = of(status, type, detail, instance)
        problem.setProperty(VALIDATION_ERRORS_PROPERTY, validationErrors)
        return problem
    }

    /**
     * Builds a `release-dependencies-missing` [ProblemDetail] (409): like [of] plus the
     * [missingDependencies] extension member, each dependency release that cannot be had, and
     * why (the `ReleaseDependenciesMissingProblemDetail` shape from the contract).
     */
    fun releaseDependenciesMissing(
        missingDependencies: List<MissingReleaseDependency>,
        detail: String? = null,
        instance: URI? = null,
    ): ProblemDetail {
        val problem = of(HttpStatusCode.valueOf(409), typeFor(KnownProblemSlugs.RELEASE_DEPENDENCIES_MISSING), detail, instance)
        problem.setProperty(MISSING_DEPENDENCIES_PROPERTY, missingDependencies)
        return problem
    }

    /**
     * Builds a `release-dependencies-unreleased` [ProblemDetail] (409): like [of] plus the
     * [unreleasedDependencies] extension member, each draft of another catalog the released
     * draft's dependency declarations name (the `ReleaseDependenciesUnreleasedProblemDetail` shape
     * from the contract).
     */
    fun releaseDependenciesUnreleased(
        unreleasedDependencies: List<UnreleasedCatalogDependency>,
        detail: String? = null,
        instance: URI? = null,
    ): ProblemDetail {
        val problem = of(HttpStatusCode.valueOf(409), typeFor(KnownProblemSlugs.RELEASE_DEPENDENCIES_UNRELEASED), detail, instance)
        problem.setProperty(UNRELEASED_DEPENDENCIES_PROPERTY, unreleasedDependencies)
        return problem
    }

    /**
     * Builds a `catalog-version-behind` [ProblemDetail] (409): like [of] plus the [behind] extension
     * member, the versions of the releases the draft is behind that the request did not acknowledge
     * (the `CatalogVersionBehindProblemDetail` shape from the contract).
     */
    fun catalogVersionBehind(
        behind: List<String>,
        detail: String? = null,
        instance: URI? = null,
    ): ProblemDetail {
        val problem = of(HttpStatusCode.valueOf(409), typeFor(KnownProblemSlugs.CATALOG_VERSION_BEHIND), detail, instance)
        problem.setProperty(BEHIND_PROPERTY, behind)
        return problem
    }

    /**
     * Builds a `release-in-use` [ProblemDetail] (409): like [of] plus the [environments] that deploy
     * the release and the releases of other catalogs it is [pinnedBy] (the `ReleaseInUseProblemDetail`
     * shape from the contract).
     */
    fun releaseInUse(
        environments: List<String>,
        pinnedBy: List<CatalogReleaseRefDto>,
        detail: String? = null,
        instance: URI? = null,
    ): ProblemDetail {
        val problem = of(HttpStatusCode.valueOf(409), typeFor(KnownProblemSlugs.RELEASE_IN_USE), detail, instance)
        problem.setProperty(ENVIRONMENTS_PROPERTY, environments)
        problem.setProperty(PINNED_BY_PROPERTY, pinnedBy)
        return problem
    }

    /**
     * Builds an `ambiguous-variant` [ProblemDetail] (409): like [of] plus the [tiedVariants]
     * extension member, the keys of the variants that match equally well (the
     * `AmbiguousVariantProblemDetail` shape from the contract).
     */
    fun ambiguousVariant(
        tiedVariants: List<String>,
        detail: String? = null,
        instance: URI? = null,
    ): ProblemDetail {
        val problem = of(HttpStatusCode.valueOf(409), typeFor(KnownProblemSlugs.AMBIGUOUS_VARIANT), detail, instance)
        problem.setProperty(TIED_VARIANTS_PROPERTY, tiedVariants)
        return problem
    }

    /**
     * Builds a `catalog-read-only` [ProblemDetail] (409): like [of] plus the [readOnlyReason]
     * extension member, why the catalog cannot be changed in this installation (the
     * `CatalogReadOnlyProblemDetail` shape from the contract), written as its wire value.
     */
    fun catalogReadOnly(
        readOnlyReason: CatalogReadOnlyReason,
        detail: String? = null,
        instance: URI? = null,
    ): ProblemDetail {
        val problem = of(HttpStatusCode.valueOf(409), typeFor(KnownProblemSlugs.CATALOG_READ_ONLY), detail, instance)
        problem.setProperty(READ_ONLY_REASON_PROPERTY, readOnlyReason.value)
        return problem
    }

    /**
     * Builds a `merge-conflicts-unresolved` [ProblemDetail] (409): like [of] plus the
     * [unresolvedConflicts] extension member, each conflict of the merge without a resolution, as
     * `resourceType/resourceKey` (the `MergeConflictsUnresolvedProblemDetail` shape from the
     * contract).
     */
    fun mergeConflictsUnresolved(
        unresolvedConflicts: List<String>,
        detail: String? = null,
        instance: URI? = null,
    ): ProblemDetail {
        val problem = of(HttpStatusCode.valueOf(409), typeFor(KnownProblemSlugs.MERGE_CONFLICTS_UNRESOLVED), detail, instance)
        problem.setProperty(UNRESOLVED_CONFLICTS_PROPERTY, unresolvedConflicts)
        return problem
    }

    /**
     * Builds a `merge-base-missing` [ProblemDetail] (409): like [of] plus the [version] and
     * [fingerprint] extension members, naming the merge base this installation does not have, the
     * release to get first (the `MergeBaseMissingProblemDetail` shape from the contract).
     */
    fun mergeBaseMissing(
        version: String,
        fingerprint: String,
        detail: String? = null,
        instance: URI? = null,
    ): ProblemDetail {
        val problem = of(HttpStatusCode.valueOf(409), typeFor(KnownProblemSlugs.MERGE_BASE_MISSING), detail, instance)
        problem.setProperty(VERSION_PROPERTY, version)
        problem.setProperty(FINGERPRINT_PROPERTY, fingerprint)
        return problem
    }

    /**
     * Builds a `catalog-rendering-defaults-too-new` [ProblemDetail] (400): like [of] plus the
     * [renderingDefaultsVersion] the received release names, as it names it, and the
     * [supportedVersion], the newest this installation has (the
     * `CatalogRenderingDefaultsTooNewProblemDetail` shape from the contract). For `importCatalog`
     * and `installCatalogRelease`, refused before anything is written.
     */
    fun catalogRenderingDefaultsTooNew(
        renderingDefaultsVersion: String,
        supportedVersion: Int,
        detail: String? = null,
        instance: URI? = null,
    ): ProblemDetail {
        val problem = of(HttpStatusCode.valueOf(400), typeFor(KnownProblemSlugs.CATALOG_RENDERING_DEFAULTS_TOO_NEW), detail, instance)
        problem.setProperty(RENDERING_DEFAULTS_VERSION_PROPERTY, renderingDefaultsVersion)
        problem.setProperty(SUPPORTED_VERSION_PROPERTY, supportedVersion)
        return problem
    }

    /**
     * Builds a `catalog-source-unavailable` [ProblemDetail]: a catalog's source (its
     * registry, through its mirrors) could not be used by `listAvailableCatalogReleases` or
     * `installCatalogRelease`, and nothing was installed. The one problem type is sent with either
     * of two statuses: 503 when the source could not be reached or did not answer in time, 502
     * when it answered with an error or with something that is not a valid answer. Any other
     * status is refused.
     *
     * A 503 may tell the caller when to retry with a `Retry-After` response header, which the
     * application sets on the response; it is not part of the problem body.
     */
    fun catalogSourceUnavailable(
        status: HttpStatusCode,
        detail: String? = null,
        instance: URI? = null,
    ): ProblemDetail {
        require(status.value() == 502 || status.value() == 503) {
            "catalog-source-unavailable is sent as 502 or 503, not ${status.value()}"
        }
        return of(status, typeFor(KnownProblemSlugs.CATALOG_SOURCE_UNAVAILABLE), detail, instance)
    }
}
