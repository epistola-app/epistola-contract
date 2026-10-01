// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import type { TemplateDataValidator, ValidationFailure } from '../../../src/validation/templateDataValidator.js'
import { TtlSchemaCache, type SchemaCache } from '../../../src/validation/templateSchemaValidator.js'
import { loadAjv, type AjvErrorLike, type AjvInstanceLike, type AjvRuntime, type AjvValidateFunctionLike } from './ajvRuntime.js'

/** The one call on `TemplatesApi` this adapter needs. */
export interface TemplateSchemaSource {
  getTemplate(requestParameters: { tenantId: string; catalogId: string; templateId: string }): Promise<{ schema?: object }>
}

/**
 * A {@link TemplateDataValidator} that validates in-process, on Ajv.
 *
 * **This lives in test sources on purpose.** The published package ships no JSON Schema compiler
 * and declares no `ajv` dependency, optional or otherwise, so no consumer carries one for a feature
 * they may never call. This exists to prove the interface is implementable against a real compiler
 * rather than a stub, to drive the conformance scenario, and to be copied — it is short enough to
 * paste into an application.
 *
 * Two details are the reason a copyable example is worth keeping:
 *
 * - **Ajv's `instancePath` is already a JSON Pointer**, which is why this adapter is so short. The
 *   one exception is `required`: Ajv points at the *parent* object and names the absent property in
 *   `params.missingProperty`, so the two are joined to get the pointer the interface asks for —
 *   which is also where the server reports it (`missingFields[].path`).
 * - **The schema is fetched, so it is cached.** A validator is only "local" after a round trip for
 *   the template; without a cache this is slower than asking the server.
 *
 * And one thing a copy cannot fix, which is the argument for the default being the server: **a
 * local compiler's verdict is its own.** Ajv asserts `format` only with `ajv-formats` installed and
 * registered, treats unknown keywords per its `strict` setting, and supports the dialects its
 * version supports. Epistola decides what it will render, and only asking it cannot disagree.
 */
export class AjvTemplateDataValidator implements TemplateDataValidator {
  readonly preflightsGeneration = true

  private readonly compiled = new WeakMap<object, AjvValidateFunctionLike>()

  constructor(
    private readonly templatesApi: TemplateSchemaSource,
    private readonly cache: SchemaCache = new TtlSchemaCache(),
  ) {}

  async validate(tenantId: string, catalogId: string, templateId: string, data: unknown): Promise<readonly ValidationFailure[]> {
    const schema = await this.cache.getOrLoad(tenantId, catalogId, templateId, async () => {
      const template = await this.templatesApi.getTemplate({ tenantId, catalogId, templateId })
      return template.schema ?? undefined
    })
    // No schema on the template means nothing is claimed about the data, so nothing is wrong with
    // it. Same answer the server gives.
    if (schema === undefined) {
      return []
    }

    const validate = await this.compile(schema)
    if (validate(data)) {
      return []
    }
    return (validate.errors ?? []).map(toFailure)
  }

  private async compile(schema: object): Promise<AjvValidateFunctionLike> {
    let validate = this.compiled.get(schema)
    if (validate === undefined) {
      validate = ajvFor(await loadAjv(), schema).compile(schema)
      this.compiled.set(schema, validate)
    }
    return validate
  }
}

const OPTIONS = { allErrors: true, strict: false } as const

/** Picks the Ajv dialect the schema declares, defaulting to draft-07 as Ajv itself does. */
function ajvFor(runtime: AjvRuntime, schema: object): AjvInstanceLike {
  const declared = (schema as { $schema?: unknown }).$schema
  const dialect = typeof declared === 'string' ? declared : ''
  const ajv = dialect.includes('2020-12') ? new runtime.Ajv2020(OPTIONS) : dialect.includes('2019-09') ? new runtime.Ajv2019(OPTIONS) : new runtime.Ajv(OPTIONS)
  runtime.addFormats(ajv)
  return ajv
}

function toFailure(error: AjvErrorLike): ValidationFailure {
  // `required` is reported against the parent, with the absent property named separately. Joining
  // them gives the field's own pointer, which is where the server reports it too.
  const path =
    error.keyword === 'required' && typeof error.params.missingProperty === 'string'
      ? `${error.instancePath}/${escapePointerSegment(error.params.missingProperty)}`
      : error.instancePath
  return { path, message: error.message ?? error.keyword, keyword: error.keyword }
}

function escapePointerSegment(segment: string): string {
  return segment.replace(/~/g, '~0').replace(/\//g, '~1')
}
