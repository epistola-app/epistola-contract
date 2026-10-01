// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import type { InitOverrideFunction } from '../generated/api/runtime.js'
import type { TemplateDataValidationResult } from '../generated/api/models/index.js'
import type { TemplateDataValidator, ValidationFailure } from './templateDataValidator.js'

/** The one call on `TemplatesApi` the server-backed validator needs — a stub satisfies it in tests. */
export interface TemplateDataValidationSource {
  validateTemplateData(
    requestParameters: {
      tenantId: string
      catalogId: string
      templateId: string
      validateTemplateDataRequest: { data: object; variantId?: string | null; versionId?: number | null; environmentId?: string | null }
    },
    initOverrides?: RequestInit | InitOverrideFunction,
  ): Promise<TemplateDataValidationResult>
}

/** Which version's contract to check against; omit all three to use the template's latest. */
export interface ServerValidationTarget {
  /** Variant to check against. Used as the default variant when only a version or environment is set. */
  readonly variantId?: string
  /** Explicit version number (mutually exclusive with `environmentId`). */
  readonly versionId?: number
  /** Environment whose active version to check against (mutually exclusive with `versionId`). */
  readonly environmentId?: string
}

/**
 * The {@link TemplateDataValidator} this package ships: it asks Epistola.
 *
 * This is the default, and it carries no JSON Schema compiler. The server already owns the verdict
 * — it validates every generation request whatever the client did first — so asking it is the only
 * answer that cannot disagree with what generation will do. It also knows things a schema alone
 * does not: which optional fields the resolved version's template actually reads.
 *
 * Validation is a far cheaper call than rendering, so checking as data is entered is reasonable. It
 * is still a network call, which is why {@link preflightsGeneration} is false.
 *
 * **Server floor.** `validateTemplateData` arrived with contract **1.4.0**. Against an older server
 * the call fails like any other unknown operation; it is not degraded into "valid", because
 * silently reporting unvalidated data as acceptable is worse than failing.
 */
export class ServerTemplateDataValidator implements TemplateDataValidator {
  /**
   * False — the server checks the same data when the generation request is submitted, so
   * pre-flighting here would only spend a second round trip to learn the same thing.
   */
  readonly preflightsGeneration = false

  constructor(
    private readonly templatesApi: TemplateDataValidationSource,
    private readonly target: ServerValidationTarget = {},
  ) {}

  async validate(tenantId: string, catalogId: string, templateId: string, data: unknown): Promise<readonly ValidationFailure[]> {
    const result = await this.templatesApi.validateTemplateData({
      tenantId,
      catalogId,
      templateId,
      validateTemplateDataRequest: {
        data: (data ?? {}) as object,
        variantId: this.target.variantId,
        versionId: this.target.versionId,
        environmentId: this.target.environmentId,
      },
    })
    if (result.valid) {
      return []
    }
    return toValidationFailures(result.errors, result.missingFields, result.invalidFields)
  }
}

/** The finding for a rejection that names nothing. */
const UNSPECIFIED: ValidationFailure = {
  path: '',
  message: 'does not fit this template’s data contract, which gave no field-level detail',
  keyword: undefined,
}

const MISSING_REQUIRED_MESSAGE = 'is required but was not supplied'

/**
 * Turns what the server reports about template data into the one shape
 * {@link TemplateDataValidator} pins.
 *
 * `invalidFields` and `missingFields` are preferred over `errors` where the server sends them, and
 * not only because they carry more. Their `path` is specified as a JSON Pointer into the data,
 * which is what the interface promises callers; `errors[].path` describes itself as a pointer but is
 * documented with a JSONPath example (`$.customer.email`), so passing it through unexamined would
 * make the promise depend on which member the server happened to fill. The fallback to `errors`
 * exists so a server that sends only that is still reported rather than silently accepted, and the
 * last resort exists because an empty array means "acceptable" to a caller — a rejection that names
 * no field must not become a pass.
 *
 * An absent **optional** field is not a finding: the contract says so explicitly, and the server
 * lists those in `missingFields` too so a client can offer them.
 */
export function toValidationFailures(
  errors: readonly { path?: string; message?: string; keyword?: string }[] | undefined,
  missingFields: readonly { path?: string; required?: boolean }[] | undefined,
  invalidFields: readonly { path?: string; keyword?: string; message?: string }[] | undefined,
): readonly ValidationFailure[] {
  const fromFields: ValidationFailure[] = []
  for (const field of invalidFields ?? []) {
    if (typeof field.path !== 'string') continue
    fromFields.push({ path: field.path, message: field.message ?? 'is not acceptable for this template', keyword: field.keyword })
  }
  for (const field of missingFields ?? []) {
    if (typeof field.path !== 'string' || field.required === false) continue
    fromFields.push({ path: field.path, message: MISSING_REQUIRED_MESSAGE, keyword: 'required' })
  }
  if (fromFields.length > 0) {
    return fromFields
  }

  const fromErrors: ValidationFailure[] = []
  for (const error of errors ?? []) {
    if (typeof error.path !== 'string') continue
    fromErrors.push({ path: error.path, message: error.message ?? '', keyword: error.keyword })
  }
  return fromErrors.length > 0 ? fromErrors : [UNSPECIFIED]
}

/** The same, read off the extension members of a `template-data-invalid` problem body. */
export function problemValidationFailures(extensions: Readonly<Record<string, unknown>>): readonly ValidationFailure[] {
  const members = (key: string): Record<string, unknown>[] => {
    const value = extensions[key]
    return Array.isArray(value) ? value.filter((entry): entry is Record<string, unknown> => entry !== null && typeof entry === 'object' && !Array.isArray(entry)) : []
  }
  const read = <T>(entries: Record<string, unknown>[]): T[] => entries as unknown as T[]
  return toValidationFailures(
    read(members('errors')),
    read(members('missingFields')),
    read(members('invalidFields')),
  )
}
