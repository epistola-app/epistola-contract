// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

/** One constraint a model value violates. */
export interface ConstraintViolation {
  /** The property that failed, e.g. `id`. */
  readonly property: string
  /** What the contract required, e.g. `must match pattern ^[a-z][a-z0-9]*(-[a-z0-9]+)*$`. */
  readonly message: string
}

/**
 * Thrown by the generated `validateModel` / `validate<Model>` helpers when a value violates the
 * constraints the contract declares on its model — a slug pattern, a length bound, a range — so
 * a request that a validating server would reject fails before it leaves the process.
 */
export class ModelValidationException extends Error {
  override readonly name = 'ModelValidationException'

  constructor(
    /** The contract model that was validated, e.g. `CreateTenantRequest`. */
    readonly model: string,
    /** Every violation found, in property order. */
    readonly violations: readonly ConstraintViolation[],
  ) {
    super(`${model} failed validation with ${violations.length} violation(s):\n${violations.map((v) => `  ${v.property}: ${v.message}`).join('\n')}`)
  }
}
