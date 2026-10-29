// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import type {
  CatalogReadOnlyReason,
  CatalogReleaseRefDto,
  DataModelValidationError,
  InvalidDataField,
  MissingDataField,
  MissingReleaseDependency,
  MissingReleaseDependencyReason,
  ProblemDetail,
  UnreleasedCatalogDependency,
  ValidationError,
} from '../generated/api/models/index.js'
import { ResponseError } from '../generated/api/runtime.js'
import { KnownProblemSlugs, ProblemExtensionMembers } from '../generated/knownProblemSlugs.js'
import { BLANK_TYPE, slugFor } from './problemTypes.js'

/**
 * A {@link ResponseError} carrying a parsed [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)
 * {@link ProblemDetail} (`application/problem+json`) body.
 *
 * Thrown by the problem-detail middleware {@link EpistolaClient} installs. It extends the generated
 * `ResponseError` on purpose: existing `instanceof ResponseError` sites keep working and consumers
 * retain the inherited `response`. (Its body has been read, so use {@link rawBody} rather than
 * reading it again.)
 *
 * The machine-readable discriminator is the problem {@link type} URI; switch on {@link typeSlug}.
 * Field-level validation errors (the `ValidationProblemDetail` shape) are surfaced via
 * {@link errors}; per-example data-model validation failures (the `DataModelValidationProblemDetail`
 * shape, `data-model-validation-error`) via {@link validationErrors}; the releases a deployment lacks
 * (`release-dependencies-missing`) via {@link missingDependencies}; the drafts of other catalogs a
 * release would need released first (`release-dependencies-unreleased`) via
 * {@link unreleasedDependencies}; why a catalog cannot be changed (`catalog-read-only`) via
 * {@link readOnlyReason}. Any *other* member a problem
 * body carries — one the contract does not name, such as `catalog-schema-too-old`'s `version` /
 * `baselineVersion` — is in {@link extensions}.
 */
export class ProblemDetailException extends ResponseError {
  constructor(
    /** The parsed base problem (`type`, `title`, `status`, `detail`, `instance`). */
    readonly problem: ProblemDetail,
    /** Field-level validation errors when the body was a `ValidationProblemDetail`, else empty. */
    readonly errors: readonly ValidationError[],
    /**
     * Per-example data-model validation failures (example name → failures) when the body was a
     * `DataModelValidationProblemDetail` (`data-model-validation-error`, 422), else empty.
     */
    readonly validationErrors: Readonly<Record<string, readonly DataModelValidationError[]>>,
    /**
     * Every problem member outside `type`/`title`/`status`/`detail`/`instance`, keyed by its JSON
     * name — so an extension member on any problem type the contract adds later can be read
     * without a client release.
     */
    readonly extensions: Readonly<Record<string, unknown>>,
    /** The HTTP status of the error response. */
    readonly statusCode: number,
    /** The response body as received. */
    readonly rawBody: string,
    response: Response,
  ) {
    super(response, buildMessage(statusCode, problem))
  }

  /** The problem `type` URI (`about:blank` when unspecified). */
  get type(): string {
    return this.problem.type || BLANK_TYPE
  }

  /**
   * Kebab-case slug derived from {@link type} by stripping the Epistola type base, or undefined
   * for `about:blank` and non-Epistola types. Compare against `KnownProblemSlugs`.
   */
  get typeSlug(): string | undefined {
    return slugFor(this.problem.type)
  }

  /** Short human-readable summary of the problem type (RFC 9457 `title`). */
  get title(): string {
    return this.problem.title
  }

  /**
   * The HTTP status carried in the problem body. Usually equal to {@link statusCode}, but named
   * distinctly because the two come from different places.
   */
  get problemStatus(): number {
    return this.problem.status
  }

  /** Occurrence-specific explanation (RFC 9457 `detail`), if the server provided one. */
  get detail(): string | undefined {
    return this.problem.detail
  }

  /** URI reference identifying this occurrence (RFC 9457 `instance`), if the server provided one. */
  get instance(): string | undefined {
    return this.problem.instance
  }

  /** True when this problem carried field-level validation errors. */
  get isValidationProblem(): boolean {
    return this.errors.length > 0
  }

  /** True when this problem carried per-example data-model validation failures. */
  get isDataModelValidationProblem(): boolean {
    return Object.keys(this.validationErrors).length > 0
  }

  /**
   * Absent fields reported by a `template-data-invalid` problem; empty for any other.
   *
   * Each `path` is a JSON Pointer into the request's `data`, and `schema` is the contract's schema
   * for that field, so a caller can ask for exactly what is missing. An entry whose `required` is
   * false is informational: the resolved version's template reads the field, but leaving it out
   * does not make the data invalid.
   *
   * Read off {@link extensions}, which already carries every member the base problem does not
   * model, so this is a typed view rather than extra parsing.
   */
  get missingFields(): readonly MissingDataField[] {
    return objectsAt(this.extensions, ProblemExtensionMembers.MISSING_FIELDS) as readonly MissingDataField[]
  }

  /** Supplied values the template's data contract rejected; empty for any other problem. */
  get invalidFields(): readonly InvalidDataField[] {
    return objectsAt(this.extensions, ProblemExtensionMembers.INVALID_FIELDS) as readonly InvalidDataField[]
  }

  /** True when this problem described template data field by field. */
  get isTemplateDataProblem(): boolean {
    return this.missingFields.length > 0 || this.invalidFields.length > 0
  }

  /**
   * The releases a `release-dependencies-missing` problem (409, from `deployCatalogRelease`) reports cannot
   * be had, in the order the server listed them; empty for any other problem.
   *
   * These are the releases the deployed release pins that the tenant does not have and that could
   * not be fetched or verified. Each
   * {@link MissingDependency.reason} is kept as the string the server sent, so a reason the
   * contract adds later is not lost. An entry that does not carry all five members as strings is
   * dropped rather than guessed at.
   */
  get missingDependencies(): readonly MissingDependency[] {
    return objectsAt(this.extensions, ProblemExtensionMembers.MISSING_DEPENDENCIES).filter(isMissingDependency)
  }

  /** True when this problem listed releases a deployment is missing. */
  get isReleaseDependenciesMissingProblem(): boolean {
    return this.missingDependencies.length > 0
  }

  /**
   * The drafts of other catalogs a `release-dependencies-unreleased` problem (409, from
   * `releaseCatalogDraft`) reports the released draft's dependency declarations name, directly or
   * through another such draft, in the order the server listed them; empty for any other problem.
   *
   * Release them first, or along with the draft (`releaseDependencies`). An entry that does not
   * carry all five members as strings is dropped rather than guessed at.
   */
  get unreleasedDependencies(): readonly UnreleasedDependency[] {
    return objectsAt(this.extensions, ProblemExtensionMembers.UNRELEASED_DEPENDENCIES).filter(isUnreleasedDependency)
  }

  /** True when this problem listed dependency drafts a release would need released first. */
  get isReleaseDependenciesUnreleasedProblem(): boolean {
    return this.unreleasedDependencies.length > 0
  }

  /**
   * Why the catalog cannot be changed, from a `catalog-read-only` problem (409, from any write to
   * a catalog whose `CatalogDto.editable` is false): `editing-disabled` (the installation only
   * receives and runs releases) or `supplied-by-epistola` (the server supplies the catalog). Kept
   * as the string the server sent, so a reason a later contract adds is surfaced; `undefined` for
   * any other problem, and when the member is not a string.
   */
  get readOnlyReason(): CatalogReadOnlyReason | (string & {}) | undefined {
    const value = this.extensions[ProblemExtensionMembers.READ_ONLY_REASON]
    return typeof value === 'string' ? value : undefined
  }

  /** True when this problem said the catalog cannot be changed, and why. */
  get isCatalogReadOnlyProblem(): boolean {
    return this.readOnlyReason !== undefined
  }

  /**
   * The conflicts a `merge-conflicts-unresolved` problem (409, from `mergeIntoCatalogDraft`,
   * alpha) lists without a resolution, each as `resourceType/resourceKey`, in the order the server
   * listed them; empty for any other problem. An entry that is not a string is dropped.
   */
  get unresolvedConflicts(): readonly string[] {
    const value = this.extensions[ProblemExtensionMembers.UNRESOLVED_CONFLICTS]
    return Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string') : []
  }

  /** True when this problem listed merge conflicts that need a resolution. */
  get isMergeConflictsUnresolvedProblem(): boolean {
    return this.unresolvedConflicts.length > 0
  }

  /**
   * The version of the release a `merge-base-missing` problem (409, from `mergeIntoCatalogDraft`,
   * alpha) asks to get first: the merge base, which this installation does not have. Read from the
   * `version` member of that problem only, since other problems use the name for other things;
   * `undefined` for any other problem, and when the member is not a string.
   */
  get missingBaseVersion(): string | undefined {
    return this.mergeBaseMissingMember(ProblemExtensionMembers.VERSION)
  }

  /**
   * That release's catalog fingerprint (`v5:<hex>`), from the `fingerprint` member of a
   * `merge-base-missing` problem; `undefined` for any other problem, and when it is not a string.
   */
  get missingBaseFingerprint(): string | undefined {
    return this.mergeBaseMissingMember(ProblemExtensionMembers.FINGERPRINT)
  }

  /** True when this problem named a merge base this installation does not have. */
  get isMergeBaseMissingProblem(): boolean {
    return this.missingBaseVersion !== undefined
  }

  /**
   * The versions of the releases a `catalog-version-behind` problem (409, from
   * `releaseCatalogDraft`) says the draft is behind and the request did not acknowledge: name each
   * in `acknowledgedBehind` to release it anyway. Empty for any other problem.
   */
  get behind(): readonly string[] {
    return stringsAt(this.extensions, ProblemExtensionMembers.BEHIND)
  }

  /** True when this problem said the draft is behind releases the request did not acknowledge. */
  get isCatalogVersionBehindProblem(): boolean {
    return this.typeSlug === KnownProblemSlugs.CATALOG_VERSION_BEHIND
  }

  /**
   * The environments a `release-in-use` problem (409, from `deleteCatalogRelease`) says deploy the
   * release; empty for any other problem, and when none does.
   */
  get environments(): readonly string[] {
    return stringsAt(this.extensions, ProblemExtensionMembers.ENVIRONMENTS)
  }

  /**
   * The releases of other catalogs a `release-in-use` problem says pin the release, and render with
   * it; empty for any other problem. An entry of an unexpected shape is dropped.
   */
  get pinnedBy(): readonly CatalogReleaseRef[] {
    return objectsAt(this.extensions, ProblemExtensionMembers.PINNED_BY).filter(isCatalogReleaseRef)
  }

  /** True when this problem said the release cannot be deleted while something uses it. */
  get isReleaseInUseProblem(): boolean {
    return this.typeSlug === KnownProblemSlugs.RELEASE_IN_USE
  }

  /**
   * The keys of the variants an `ambiguous-variant` problem (409, from generating or previewing a
   * document) says match the request's attributes equally well: name one by `variantKey`, or send
   * attributes that tell them apart. Empty for any other problem.
   */
  get tiedVariants(): readonly string[] {
    return stringsAt(this.extensions, ProblemExtensionMembers.TIED_VARIANTS)
  }

  /** True when this problem said several variants match equally well. */
  get isAmbiguousVariantProblem(): boolean {
    return this.typeSlug === KnownProblemSlugs.AMBIGUOUS_VARIANT
  }

  private mergeBaseMissingMember(member: string): string | undefined {
    if (this.typeSlug !== KnownProblemSlugs.MERGE_BASE_MISSING) return undefined
    const value = this.extensions[member]
    return typeof value === 'string' ? value : undefined
  }
}

/**
 * One release a `release-dependencies-missing` problem reports, as listed in its
 * `missingDependencies` member. The generated `MissingReleaseDependency` with `reason` widened to
 * a string: compare it against `MissingReleaseDependencyReason`, and expect values beyond it.
 */
export interface MissingDependency extends Omit<MissingReleaseDependency, 'reason'> {
  /** Why the release is missing: one of `MissingReleaseDependencyReason`, or one added later. */
  readonly reason: MissingReleaseDependencyReason | (string & {})
}

const MISSING_DEPENDENCY_MEMBERS = ['registry', 'namespace', 'catalogKey', 'version', 'reason'] as const

function isMissingDependency(entry: object): entry is MissingDependency {
  const record = entry as Record<string, unknown>
  return MISSING_DEPENDENCY_MEMBERS.every((member) => typeof record[member] === 'string')
}

/**
 * One draft a `release-dependencies-unreleased` problem reports, as listed in its
 * `unreleasedDependencies` member: the generated `UnreleasedCatalogDependency`.
 */
export type UnreleasedDependency = Readonly<UnreleasedCatalogDependency>

const UNRELEASED_DEPENDENCY_MEMBERS = ['registry', 'namespace', 'catalogKey', 'releaseId', 'releaseName'] as const

function isUnreleasedDependency(entry: object): entry is UnreleasedDependency {
  const record = entry as Record<string, unknown>
  return UNRELEASED_DEPENDENCY_MEMBERS.every((member) => typeof record[member] === 'string')
}

/**
 * One release a problem names in full, such as each entry of a `release-in-use` problem's
 * `pinnedBy` member: the generated `CatalogReleaseRefDto`.
 */
export type CatalogReleaseRef = Readonly<CatalogReleaseRefDto>

const CATALOG_RELEASE_REF_MEMBERS = ['registry', 'namespace', 'catalogKey', 'version', 'releaseId'] as const

function isCatalogReleaseRef(entry: object): entry is CatalogReleaseRef {
  const record = entry as Record<string, unknown>
  return CATALOG_RELEASE_REF_MEMBERS.every((member) => typeof record[member] === 'string')
}

/** One extension member's array of strings, or empty when absent; entries that are not strings are dropped. */
function stringsAt(extensions: Readonly<Record<string, unknown>>, member: string): readonly string[] {
  const value = extensions[member]
  return Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string') : []
}

/**
 * One extension member's array of objects, or empty when absent or malformed. Entries of an
 * unexpected shape are dropped rather than guessed at: a malformed problem body should not become a
 * confident claim about a particular field.
 */
function objectsAt(extensions: Readonly<Record<string, unknown>>, member: string): readonly object[] {
  const value = extensions[member]
  if (!Array.isArray(value)) {
    return []
  }
  return value.filter((entry): entry is object => entry !== null && typeof entry === 'object' && !Array.isArray(entry))
}

function buildMessage(status: number, problem: ProblemDetail): string {
  const title = problem.title || String(status)
  return problem.detail ? `${status} ${title}: ${problem.detail}` : `${status} ${title}`
}
