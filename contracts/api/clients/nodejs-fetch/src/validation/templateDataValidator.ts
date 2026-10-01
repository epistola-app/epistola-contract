// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

/** A single field-level validation failure. */
export interface ValidationFailure {
  /**
   * JSON Pointer (RFC 6901) into the data, e.g. `/customer/email`; empty for the document root.
   *
   * Not a dotted key and not a JSONPath — see {@link TemplateDataValidator} for why this is pinned.
   */
  readonly path: string
  /** Human-readable error description. */
  readonly message: string
  /** JSON Schema keyword that failed, e.g. `required`, `type`. */
  readonly keyword: string | undefined
}

/**
 * Thrown when template data does not fit a template's data contract. (Named an exception rather
 * than an error because the contract's own `TemplateDataValidationError` model — the server's
 * validation result item — is exported alongside.)
 */
export class TemplateDataValidationException extends Error {
  override readonly name = 'TemplateDataValidationException'

  constructor(
    /** Every failure found. */
    readonly errors: readonly ValidationFailure[],
    message?: string,
  ) {
    super(message ?? `Template data validation failed with ${errors.length} error(s)`)
  }

  /** Formats all failures as a multi-line string. */
  formatErrors(): string {
    return this.errors.map((failure) => `  ${failure.path}: ${failure.message}`).join('\n')
  }
}

/**
 * Decides whether template data satisfies a template's data contract.
 *
 * This package ships exactly one implementation, `ServerTemplateDataValidator`, and uses it by
 * default. That is deliberate: nothing here pins a JSON Schema compiler, so no consumer carries one
 * for a feature they may never call. A consumer who wants the check to run in-process —
 * pre-flighting a large batch without a request per item, say — implements this interface over the
 * compiler of their choice (Ajv is one line of adapter; see the README) and keeps the parts worth
 * sharing: the typed exception, and the batch aggregation in `ValidatingGenerationApi`.
 *
 * ## The failures an implementation returns
 *
 * An empty array means the data is acceptable. Anything else is a finding, and the three members
 * are a **contract rather than a convention** — `TemplateSchemaValidator` puts them straight into a
 * {@link TemplateDataValidationException}, and callers read them to point at the field that is
 * wrong:
 *
 * - **`path` is a JSON Pointer (RFC 6901) into the data**: `/customer/email`,
 *   `/lineItems/0/quantity`, and `''` for the root. Compilers genuinely disagree here — Ajv reports
 *   an `instancePath` that is already a pointer but names a missing property in
 *   `params.missingProperty` instead of the path, networknt an `instanceLocation` whose string form
 *   is a JSONPath — so converting is the adapter's job, not the caller's.
 * - **`keyword`** is the JSON Schema keyword that failed, or undefined.
 * - **`message`** is human-readable text. It is shown to people, so it should not contain the raw
 *   schema or the failing pattern.
 *
 * Holding every implementation to one shape is the reason this interface exists. Without it a
 * `TemplateDataValidationException` means something different per compiler, which is the drift
 * that shipping a validator in each of five clients had already produced: this client reported
 * `customer.name`, the JVM ones `$.customer.name`, under docs promising a pointer.
 *
 * One deviation, and it is not part of the pointer: `ValidatingGenerationApi` prefixes a batch
 * item's failures with `items[<index>]` to say which item they came from.
 */
export interface TemplateDataValidator {
  /**
   * Resolves to the findings for `data` against the given template's data contract, empty when
   * there are none.
   *
   * @param tenantId Tenant identifier.
   * @param catalogId Catalog identifier. The same template id in two catalogs of one tenant is two
   *   different templates with two different contracts.
   * @param templateId Template identifier.
   * @param data The data to check.
   */
  validate(tenantId: string, catalogId: string, templateId: string, data: unknown): Promise<readonly ValidationFailure[]>

  /**
   * Whether `ValidatingGenerationApi` should check data with this validator *before* submitting a
   * generation request. Defaults to true when omitted.
   *
   * True for a validator that answers in-process, where checking first is nearly free and reports
   * every item of a batch at once. False for one that asks the server, because the server validates
   * the very same data when the job is submitted: pre-flighting would double the requests for a
   * single document and add one per item for a batch, to reach the same verdict. A validator that
   * declines pre-flight still produces a {@link TemplateDataValidationException} out of the
   * generation call itself — `ValidatingGenerationApi` translates the server's
   * `template-data-invalid` problem into one either way.
   */
  readonly preflightsGeneration?: boolean
}
