// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import type { GenerateDocumentBatchRequest, GenerateDocumentOperationRequest } from '../generated/api/apis/index.js'
import type { GenerationJobResponse } from '../generated/api/models/index.js'
import type { InitOverrideFunction } from '../generated/api/runtime.js'
import { KnownProblemSlugs } from '../generated/knownProblemSlugs.js'
import { ProblemDetailException } from '../error/problemDetailException.js'
import { ServerTemplateDataValidator, problemValidationFailures, type TemplateDataValidationSource } from './serverTemplateDataValidator.js'
import { TemplateDataValidationException, type TemplateDataValidator, type ValidationFailure } from './templateDataValidator.js'

export { TemplateDataValidationException } from './templateDataValidator.js'
export type { TemplateDataValidator, ValidationFailure } from './templateDataValidator.js'

/** Loads a template's JSON Schema; resolves to undefined when the template has none. */
export type SchemaLoader = () => Promise<object | undefined>

/**
 * Cache for JSON Schemas keyed by (tenantId, catalogId, templateId).
 *
 * The catalog is part of the key, not decoration: the same template id in two catalogs of one
 * tenant is two different templates with two different schemas.
 *
 * Nothing this package ships uses this any more — the default validator asks the server, which has
 * no schema to cache. It is kept, and exported, because a {@link TemplateDataValidator} that
 * validates in-process does need it: such a validator is only "local" after a round trip for the
 * template, and without a cache it is slower than asking the server outright.
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

/**
 * Checks template data against a template's data contract, rejecting when it does not fit.
 *
 * The check itself is delegated to a {@link TemplateDataValidator}; this class is the thin,
 * rejecting façade over it. By default that is {@link ServerTemplateDataValidator}, so no JSON
 * Schema compiler is involved and the verdict is the server's:
 *
 * ```ts
 * const validator = new TemplateSchemaValidator(templatesApi)
 * await validator.validate('my-tenant', 'my-catalog', 'my-template', data)
 * ```
 *
 * To have the check run in-process instead, pass an implementation built on the compiler of your
 * choice:
 *
 * ```ts
 * const validator = new TemplateSchemaValidator(new MyAjvValidator(templatesApi))
 * ```
 *
 * @see TemplateDataValidator for the failure shape every implementation owes its callers.
 */
export class TemplateSchemaValidator {
  private readonly validator: TemplateDataValidator

  constructor(validatorOrApi: TemplateDataValidator | TemplateDataValidationSource) {
    this.validator = isValidator(validatorOrApi) ? validatorOrApi : new ServerTemplateDataValidator(validatorOrApi)
  }

  /**
   * Validates `data` against the template's data contract. Resolves when it fits; rejects with
   * {@link TemplateDataValidationException} when it does not.
   *
   * Against a server older than contract 1.4.0 the default validator rejects with the API's own
   * error, because `validateTemplateData` does not exist there.
   */
  async validate(tenantId: string, catalogId: string, templateId: string, data: unknown): Promise<void> {
    const failures = await this.validator.validate(tenantId, catalogId, templateId, data)
    if (failures.length > 0) {
      throw new TemplateDataValidationException(failures)
    }
  }
}

/** The two generation calls {@link ValidatingGenerationApi} wraps; the generated `GenerationApi` satisfies it. */
export interface GenerationApiLike {
  generateDocument(requestParameters: GenerateDocumentOperationRequest, initOverrides?: RequestInit | InitOverrideFunction): Promise<GenerationJobResponse>
  generateDocumentBatch(requestParameters: GenerateDocumentBatchRequest, initOverrides?: RequestInit | InitOverrideFunction): Promise<GenerationJobResponse>
}

/**
 * Wraps a `GenerationApi` and reports unacceptable template data as a
 * {@link TemplateDataValidationException} rather than a generic problem response.
 *
 * It gets there two ways, and which one applies is the {@link TemplateDataValidator}'s call:
 *
 * - **Before the request**, when the validator answers in-process
 *   (`preflightsGeneration`). Nothing is sent, and every item of a batch is reported at once with
 *   its `items[<index>]` prefix — otherwise fixing a hundred-item batch takes a hundred round trips.
 * - **From the response**, always. The server validates the data it is given, so a rejected request
 *   comes back as a `template-data-invalid` problem, which is translated into the same exception
 *   with the same field pointers.
 *
 * The default validator asks the server, and therefore declines the pre-flight: checking first
 * would spend an extra round trip — one per item, for a batch — to learn what submitting already
 * tells us. Either way the caller catches one error type and reads one failure shape.
 */
export class ValidatingGenerationApi {
  private readonly validator: TemplateDataValidator

  constructor(
    private readonly delegate: GenerationApiLike,
    validatorOrApi: TemplateDataValidator | TemplateDataValidationSource,
  ) {
    this.validator = isValidator(validatorOrApi) ? validatorOrApi : new ServerTemplateDataValidator(validatorOrApi)
  }

  async generateDocument(requestParameters: GenerateDocumentOperationRequest, initOverrides?: RequestInit | InitOverrideFunction): Promise<GenerationJobResponse> {
    if (this.validator.preflightsGeneration !== false) {
      const request = requestParameters.generateDocumentRequest
      const failures = await this.validator.validate(requestParameters.tenantId, request.catalogId, request.templateId, request.data)
      if (failures.length > 0) {
        throw new TemplateDataValidationException(failures)
      }
    }
    return this.translatingProblem(() => this.delegate.generateDocument(requestParameters, initOverrides))
  }

  async generateDocumentBatch(requestParameters: GenerateDocumentBatchRequest, initOverrides?: RequestInit | InitOverrideFunction): Promise<GenerationJobResponse> {
    if (this.validator.preflightsGeneration !== false) {
      const failures: ValidationFailure[] = []
      for (const [index, item] of requestParameters.generateBatchRequest.items.entries()) {
        for (const failure of await this.validator.validate(requestParameters.tenantId, item.catalogId, item.templateId, item.data)) {
          failures.push({ ...failure, path: `items[${index}]${failure.path}` })
        }
      }
      if (failures.length > 0) {
        throw new TemplateDataValidationException(failures)
      }
    }
    return this.translatingProblem(() => this.delegate.generateDocumentBatch(requestParameters, initOverrides))
  }

  /**
   * Rewrites the server's `template-data-invalid` problem into the error a caller of this class is
   * already catching. Every other problem propagates untouched — this class narrows one failure
   * mode, it does not swallow failures.
   */
  private async translatingProblem(call: () => Promise<GenerationJobResponse>): Promise<GenerationJobResponse> {
    try {
      return await call()
    } catch (error) {
      if (error instanceof ProblemDetailException && error.typeSlug === KnownProblemSlugs.TEMPLATE_DATA_INVALID) {
        throw new TemplateDataValidationException(problemValidationFailures(error.extensions))
      }
      throw error
    }
  }
}

function isValidator(candidate: TemplateDataValidator | TemplateDataValidationSource): candidate is TemplateDataValidator {
  return typeof (candidate as TemplateDataValidator).validate === 'function'
}

function cacheKey(tenantId: string, catalogId: string, templateId: string): string {
  return JSON.stringify([tenantId, catalogId, templateId])
}
