// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import AjvModule, { type ErrorObject, type Options, type ValidateFunction } from 'ajv'
import Ajv2019Module from 'ajv/dist/2019.js'
import Ajv2020Module from 'ajv/dist/2020.js'
import addFormatsModule from 'ajv-formats'
import type { GenerateDocumentBatchRequest, GenerateDocumentOperationRequest } from '../generated/api/apis/index.js'
import type { GenerationJobResponse } from '../generated/api/models/index.js'
import type { InitOverrideFunction } from '../generated/api/runtime.js'

/** A single field-level validation failure. */
export interface ValidationFailure {
  /** Path to the invalid field, e.g. `customer.name`; empty for the root. */
  readonly path: string
  /** Human-readable error description. */
  readonly message: string
  /** JSON Schema keyword that failed, e.g. `required`, `type`. */
  readonly keyword: string | undefined
}

/**
 * Thrown when template data fails JSON Schema validation on the client side. Mirrors the server's
 * validation error structure. (Named an exception rather than an error because the contract's own
 * `TemplateDataValidationError` model — the server's validation result item — is exported alongside.)
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

/** Loads a template's JSON Schema; resolves to undefined when the template has none. */
export type SchemaLoader = () => Promise<object | undefined>

/**
 * Cache for JSON Schemas keyed by (tenantId, catalogId, templateId).
 *
 * The catalog is part of the key, not decoration: the same template id in two catalogs of one
 * tenant is two different templates with two different schemas.
 */
export interface SchemaCache {
  /**
   * Returns a cached schema, or invokes `loader` on a miss and stores the result. An undefined
   * result means the template has no schema defined, and is cached as such.
   */
  getOrLoad(tenantId: string, catalogId: string, templateId: string, loader: SchemaLoader): Promise<object | undefined>
}

/** Default TTL-based cache. Entries expire `ttlMs` after they were stored (default: 5 minutes). */
export class TtlSchemaCache implements SchemaCache {
  private readonly entries = new Map<string, { schema: object | undefined; storedAt: number }>()

  constructor(private readonly ttlMs = 300_000) {
    if (!(ttlMs > 0)) {
      throw new RangeError('ttlMs must be positive')
    }
  }

  async getOrLoad(tenantId: string, catalogId: string, templateId: string, loader: SchemaLoader): Promise<object | undefined> {
    const key = cacheKey(tenantId, catalogId, templateId)
    const entry = this.entries.get(key)
    if (entry !== undefined && performance.now() < entry.storedAt + this.ttlMs) {
      return entry.schema
    }
    const schema = await loader()
    this.entries.set(key, { schema, storedAt: performance.now() })
    return schema
  }

  /** Evicts a specific entry (useful after template updates). */
  evict(tenantId: string, catalogId: string, templateId: string): void {
    this.entries.delete(cacheKey(tenantId, catalogId, templateId))
  }

  /** Evicts all entries. */
  evictAll(): void {
    this.entries.clear()
  }
}

/** The one call on `TemplatesApi` the validator needs — a stub satisfies it in tests. */
export interface TemplateSchemaSource {
  getTemplate(requestParameters: { tenantId: string; catalogId: string; templateId: string }): Promise<{ schema?: object }>
}

/**
 * Validates template data against the JSON Schema defined on the template.
 *
 * Fetches the template from the server on first use and caches the schema. No-op when the template
 * has no schema.
 *
 * ```ts
 * const validator = new TemplateSchemaValidator(templatesApi)
 * await validator.validate('my-tenant', 'my-catalog', 'my-template', data)
 * ```
 */
export class TemplateSchemaValidator {
  private readonly compiled = new WeakMap<object, ValidateFunction>()

  constructor(
    private readonly templatesApi: TemplateSchemaSource,
    private readonly cache: SchemaCache = new TtlSchemaCache(),
  ) {}

  /**
   * Validates `data` against the schema of the specified template. Resolves when the data is valid
   * or the template has no schema; rejects with {@link TemplateDataValidationException} otherwise.
   */
  async validate(tenantId: string, catalogId: string, templateId: string, data: unknown): Promise<void> {
    const schema = await this.cache.getOrLoad(tenantId, catalogId, templateId, () => this.loadSchema(tenantId, catalogId, templateId))
    if (schema === undefined) {
      return
    }
    const validate = this.compile(schema)
    if (validate(data)) {
      return
    }
    const failures = (validate.errors ?? []).map(toFailure).sort((a, b) => a.path.localeCompare(b.path))
    throw new TemplateDataValidationException(failures)
  }

  private async loadSchema(tenantId: string, catalogId: string, templateId: string): Promise<object | undefined> {
    const template = await this.templatesApi.getTemplate({ tenantId, catalogId, templateId })
    return template.schema ?? undefined
  }

  private compile(schema: object): ValidateFunction {
    let validate = this.compiled.get(schema)
    if (validate === undefined) {
      validate = ajvFor(schema).compile(schema)
      this.compiled.set(schema, validate)
    }
    return validate
  }
}

/** The two generation calls {@link ValidatingGenerationApi} wraps; the generated `GenerationApi` satisfies it. */
export interface GenerationApiLike {
  generateDocument(requestParameters: GenerateDocumentOperationRequest, initOverrides?: RequestInit | InitOverrideFunction): Promise<GenerationJobResponse>
  generateDocumentBatch(requestParameters: GenerateDocumentBatchRequest, initOverrides?: RequestInit | InitOverrideFunction): Promise<GenerationJobResponse>
}

/**
 * Wraps a `GenerationApi` and validates request data against the template's JSON Schema before
 * sending it to the server.
 *
 * For single-document requests, validation errors are thrown immediately. For batch requests, all
 * items are validated and errors are collected into one {@link TemplateDataValidationException},
 * with each failure's path prefixed `items[<index>].`.
 */
export class ValidatingGenerationApi {
  private readonly validator: TemplateSchemaValidator

  constructor(
    private readonly delegate: GenerationApiLike,
    templatesApi: TemplateSchemaSource,
    cache?: SchemaCache,
  ) {
    this.validator = new TemplateSchemaValidator(templatesApi, cache)
  }

  async generateDocument(requestParameters: GenerateDocumentOperationRequest, initOverrides?: RequestInit | InitOverrideFunction): Promise<GenerationJobResponse> {
    const request = requestParameters.generateDocumentRequest
    await this.validator.validate(requestParameters.tenantId, request.catalogId, request.templateId, request.data)
    return this.delegate.generateDocument(requestParameters, initOverrides)
  }

  async generateDocumentBatch(requestParameters: GenerateDocumentBatchRequest, initOverrides?: RequestInit | InitOverrideFunction): Promise<GenerationJobResponse> {
    const failures: ValidationFailure[] = []
    for (const [index, item] of requestParameters.generateBatchRequest.items.entries()) {
      try {
        await this.validator.validate(requestParameters.tenantId, item.catalogId, item.templateId, item.data)
      } catch (error) {
        if (!(error instanceof TemplateDataValidationException)) throw error
        for (const failure of error.errors) {
          failures.push({ ...failure, path: failure.path ? `items[${index}].${failure.path}` : `items[${index}]` })
        }
      }
    }
    if (failures.length > 0) {
      throw new TemplateDataValidationException(failures)
    }
    return this.delegate.generateDocumentBatch(requestParameters, initOverrides)
  }
}

// Ajv ships CommonJS. Node's ESM interop hands `import X from` the whole `module.exports`, and Ajv
// assigns its class to both `module.exports` and `module.exports.default`, so `.default` is the
// class under Node, under TypeScript's NodeNext typing, and under bundlers alike.
const Ajv = AjvModule.default
const Ajv2019 = Ajv2019Module.default
const Ajv2020 = Ajv2020Module.default
const addFormats = addFormatsModule.default
type AjvInstance = InstanceType<typeof Ajv>

const AJV_OPTIONS: Options = { allErrors: true, strict: false }

/** Picks the Ajv dialect the schema declares, defaulting to draft-07 as Ajv itself does. */
function ajvFor(schema: object): AjvInstance {
  const declared = (schema as { $schema?: unknown }).$schema
  const dialect = typeof declared === 'string' ? declared : ''
  const ajv = dialect.includes('2020-12') ? new Ajv2020(AJV_OPTIONS) : dialect.includes('2019-09') ? new Ajv2019(AJV_OPTIONS) : new Ajv(AJV_OPTIONS)
  addFormats(ajv)
  return ajv
}

function toFailure(error: ErrorObject): ValidationFailure {
  const segments = error.instancePath.split('/').filter((segment) => segment !== '').map(unescapePointer)
  if (error.keyword === 'required' && typeof error.params.missingProperty === 'string') {
    segments.push(error.params.missingProperty)
  }
  return { path: segments.join('.'), message: error.message ?? error.keyword, keyword: error.keyword }
}

function unescapePointer(segment: string): string {
  return segment.replace(/~1/g, '/').replace(/~0/g, '~')
}

function cacheKey(tenantId: string, catalogId: string, templateId: string): string {
  return JSON.stringify([tenantId, catalogId, templateId])
}
