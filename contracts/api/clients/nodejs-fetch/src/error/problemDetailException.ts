// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import type { DataModelValidationError, InvalidDataField, MissingDataField, ProblemDetail, ValidationError } from '../generated/api/models/index.js'
import { ResponseError } from '../generated/api/runtime.js'
import { ProblemExtensionMembers } from '../generated/knownProblemSlugs.js'
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
 * shape, `data-model-validation-error`) via {@link validationErrors}. Any *other* member a problem
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
