// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import {
  DataModelValidationErrorFromJSON,
  ValidationErrorFromJSON,
  type DataModelValidationError,
  type ProblemDetail,
  type ValidationError,
} from '../generated/api/models/index.js'
import { ProblemExtensionMembers } from '../generated/knownProblemSlugs.js'
import { ProblemDetailException } from './problemDetailException.js'
import { BLANK_TYPE, PROBLEM_JSON } from './problemTypes.js'

const BASE_MEMBERS = new Set(['type', 'title', 'status', 'detail', 'instance'])

/** True when a `Content-Type` header value names the RFC 9457 problem media type, parameters aside. */
export function isProblemJson(contentType: string | null | undefined): boolean {
  if (!contentType) return false
  return contentType.split(';')[0].trim().toLowerCase() === PROBLEM_JSON
}

/**
 * Parses a problem+json `body` into a {@link ProblemDetailException}, or returns undefined on any
 * parse failure so the caller can fall back to the generic `ResponseError`.
 *
 * Parses the base {@link ProblemDetail} plus the field-level `errors` array
 * (`ValidationProblemDetail`) and the per-example `validationErrors` map
 * (`DataModelValidationProblemDetail`); the member names come from the contract. Every other
 * top-level member lands in `extensions`.
 */
export function parseProblem(body: string, statusCode: number, response: Response): ProblemDetailException | undefined {
  let tree: unknown
  try {
    tree = JSON.parse(body)
  } catch {
    return undefined
  }
  if (tree === null || typeof tree !== 'object' || Array.isArray(tree)) {
    return undefined
  }
  const node = tree as Record<string, unknown>

  const problem: ProblemDetail = {
    type: typeof node.type === 'string' && node.type !== '' ? node.type : BLANK_TYPE,
    title: typeof node.title === 'string' ? node.title : '',
    status: typeof node.status === 'number' ? node.status : statusCode,
    detail: typeof node.detail === 'string' ? node.detail : undefined,
    instance: typeof node.instance === 'string' ? node.instance : undefined,
  }

  let errors: ValidationError[] = []
  const rawErrors = node[ProblemExtensionMembers.ERRORS]
  if (Array.isArray(rawErrors)) {
    try {
      errors = rawErrors.filter(isRecord).map((entry) => ValidationErrorFromJSON(entry))
    } catch {
      errors = []
    }
  }

  const validationErrors: Record<string, DataModelValidationError[]> = {}
  const rawValidation = node[ProblemExtensionMembers.VALIDATION_ERRORS]
  if (isRecord(rawValidation)) {
    for (const [example, failures] of Object.entries(rawValidation)) {
      if (Array.isArray(failures)) {
        try {
          validationErrors[example] = failures.filter(isRecord).map((entry) => DataModelValidationErrorFromJSON(entry))
        } catch {
          // A malformed extension should not hide the problem it decorates.
        }
      }
    }
  }

  const extensions: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(node)) {
    if (!BASE_MEMBERS.has(key)) {
      extensions[key] = value
    }
  }

  return new ProblemDetailException(problem, errors, validationErrors, extensions, statusCode, body, response)
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}
