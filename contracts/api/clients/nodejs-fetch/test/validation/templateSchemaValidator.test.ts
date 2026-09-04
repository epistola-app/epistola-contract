// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  TemplateDataValidationException,
  TemplateSchemaValidator,
  TtlSchemaCache,
  ValidatingGenerationApi,
  type GenerationApiLike,
  type GenerationJobResponse,
  type TemplateSchemaSource,
} from '../../src/index.js'

const SCHEMA = {
  type: 'object',
  required: ['name'],
  properties: {
    name: { type: 'string' },
    age: { type: 'integer', minimum: 0 },
    email: { type: 'string', format: 'email' },
  },
}

class StubTemplatesApi implements TemplateSchemaSource {
  calls = 0
  constructor(private readonly schema: object | undefined) {}
  async getTemplate(): Promise<{ schema?: object }> {
    this.calls++
    return { schema: this.schema }
  }
}

class StubGenerationApi implements GenerationApiLike {
  generated: unknown[] = []
  private readonly job: GenerationJobResponse = { requestId: '88888888-8888-4888-8888-000000000001', status: 'PENDING', jobType: 'SINGLE', totalCount: 1, createdAt: new Date() }
  async generateDocument(params: unknown): Promise<GenerationJobResponse> {
    this.generated.push(params)
    return this.job
  }
  async generateDocumentBatch(params: unknown): Promise<GenerationJobResponse> {
    this.generated.push(params)
    return this.job
  }
}

test('valid data passes', async () => {
  await new TemplateSchemaValidator(new StubTemplatesApi(SCHEMA)).validate('t', 'c', 'tpl', { name: 'Ada', age: 30 })
})

test('invalid data fails with field-level failures', async () => {
  await assert.rejects(new TemplateSchemaValidator(new StubTemplatesApi(SCHEMA)).validate('t', 'c', 'tpl', { age: -1, email: 'nope' }), (error: unknown) => {
    assert.ok(error instanceof TemplateDataValidationException)
    assert.deepEqual(error.errors.map((f) => [f.path, f.keyword]), [
      ['age', 'minimum'],
      ['email', 'format'],
      ['name', 'required'],
    ])
    assert.match(error.formatErrors(), /name: must have required property/)
    return true
  })
})

test('a template without a schema is a no-op', async () => {
  await new TemplateSchemaValidator(new StubTemplatesApi(undefined)).validate('t', 'c', 'tpl', { whatever: true })
})

test('the schema is cached between calls', async () => {
  const api = new StubTemplatesApi(SCHEMA)
  const validator = new TemplateSchemaValidator(api)
  await validator.validate('t', 'c', 'tpl', { name: 'a' })
  await validator.validate('t', 'c', 'tpl', { name: 'b' })
  assert.equal(api.calls, 1)
})

test('the same template id in two catalogs is two cache entries', async () => {
  // Two catalogs of one tenant can both hold a "tpl" template, with different schemas. Keying on
  // (tenant, template) alone would validate one against the other's contract.
  const api = new StubTemplatesApi(SCHEMA)
  const validator = new TemplateSchemaValidator(api)
  await validator.validate('t', 'catalog-a', 'tpl', { name: 'a' })
  await validator.validate('t', 'catalog-b', 'tpl', { name: 'b' })
  assert.equal(api.calls, 2)
})

test('a TTL cache expires and can be evicted', async () => {
  const api = new StubTemplatesApi(SCHEMA)
  const cache = new TtlSchemaCache(20)
  const validator = new TemplateSchemaValidator(api, cache)
  await validator.validate('t', 'c', 'tpl', { name: 'a' })
  await new Promise((resolve) => setTimeout(resolve, 30))
  await validator.validate('t', 'c', 'tpl', { name: 'a' })
  assert.equal(api.calls, 2)
  cache.evict('t', 'c', 'tpl')
  await validator.validate('t', 'c', 'tpl', { name: 'a' })
  assert.equal(api.calls, 3)
  assert.throws(() => new TtlSchemaCache(0), RangeError)
})

test('a 2020-12 schema is validated with that dialect', async () => {
  const schema = {
    $schema: 'https://json-schema.org/draft/2020-12/schema',
    type: 'object',
    properties: { lines: { type: 'array', prefixItems: [{ type: 'string' }], items: false } },
  }
  const validator = new TemplateSchemaValidator(new StubTemplatesApi(schema))
  await validator.validate('t', 'c', 'tpl', { lines: ['ok'] })
  await assert.rejects(validator.validate('t', 'c', 'tpl', { lines: ['ok', 'extra'] }), TemplateDataValidationException)
})

test('ValidatingGenerationApi validates before delegating', async () => {
  const generation = new StubGenerationApi()
  const api = new ValidatingGenerationApi(generation, new StubTemplatesApi(SCHEMA))
  await assert.rejects(
    api.generateDocument({ tenantId: 't', generateDocumentRequest: { catalogId: 'c', templateId: 'tpl', data: { age: -5 } } }),
    TemplateDataValidationException,
  )
  assert.deepEqual(generation.generated, [])
  await api.generateDocument({ tenantId: 't', generateDocumentRequest: { catalogId: 'c', templateId: 'tpl', data: { name: 'ok' } } })
  assert.equal(generation.generated.length, 1)
})

test('batch validation aggregates errors with the item index', async () => {
  const generation = new StubGenerationApi()
  const api = new ValidatingGenerationApi(generation, new StubTemplatesApi(SCHEMA))
  await assert.rejects(
    api.generateDocumentBatch({
      tenantId: 't',
      generateBatchRequest: {
        items: [
          { catalogId: 'c', templateId: 'tpl', data: { name: 'ok' } },
          { catalogId: 'c', templateId: 'tpl', data: {} },
        ],
      },
    }),
    (error: unknown) => {
      assert.ok(error instanceof TemplateDataValidationException)
      assert.ok(error.errors.some((f) => f.path.startsWith('items[1]')))
      return true
    },
  )
  assert.deepEqual(generation.generated, [])
})
