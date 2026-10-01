// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  ProblemDetailException,
  ServerTemplateDataValidator,
  TemplateDataValidationException,
  TemplateSchemaValidator,
  ValidatingGenerationApi,
  type GenerationApiLike,
  type GenerationJobResponse,
  type TemplateDataValidationSource,
  type TemplateDataValidator,
  type ValidationFailure,
} from '../../src/index.js'

/**
 * The validation façade over the shipped default, `ServerTemplateDataValidator`.
 *
 * The behaviour worth pinning is the mapping, not the HTTP: the server answers in three members of
 * one result and the client owes its callers a single shape, documented on `TemplateDataValidator`.
 */

interface Result {
  valid: boolean
  errors?: { path?: string; message?: string; keyword?: string }[]
  missingFields?: { path?: string; required?: boolean }[]
  invalidFields?: { path?: string; keyword?: string; message?: string }[]
}

class StubTemplatesApi implements TemplateDataValidationSource {
  calls = 0
  sent: unknown[] = []
  constructor(private readonly result: Result) {}
  async validateTemplateData(requestParameters: { validateTemplateDataRequest: unknown }): Promise<never> {
    this.calls += 1
    this.sent.push(requestParameters.validateTemplateDataRequest)
    return this.result as never
  }
}

const JOB = { requestId: 'job-1' } as unknown as GenerationJobResponse

class StubGenerationApi implements GenerationApiLike {
  submissions = 0
  constructor(private readonly failure?: unknown) {}
  async generateDocument(): Promise<GenerationJobResponse> {
    this.submissions += 1
    if (this.failure !== undefined) throw this.failure
    return JOB
  }
  async generateDocumentBatch(): Promise<GenerationJobResponse> {
    this.submissions += 1
    if (this.failure !== undefined) throw this.failure
    return JOB
  }
}

const singleRequest = {
  tenantId: 'acme-corp',
  generateDocumentRequest: { catalogId: 'default', templateId: 'invoice', data: {} },
} as never

const batchRequest = {
  tenantId: 'acme-corp',
  generateBatchRequest: {
    items: [
      { catalogId: 'default', templateId: 'invoice', data: {} },
      { catalogId: 'default', templateId: 'reminder', data: {} },
    ],
  },
} as never

/** A validator that answers in-process, so it takes the pre-flight. */
function local(...paths: string[]): TemplateDataValidator {
  return {
    preflightsGeneration: true,
    async validate(): Promise<readonly ValidationFailure[]> {
      return paths.map((path) => ({ path, message: 'is required but was not supplied', keyword: 'required' }))
    },
  }
}

function templateDataInvalid(extensions: Record<string, unknown>): ProblemDetailException {
  return new ProblemDetailException(
    {
      type: 'https://epistola.app/errors/template-data-invalid',
      title: 'Template data invalid',
      status: 400,
      detail: 'The supplied data does not fit the template’s data contract',
    },
    [],
    {},
    extensions,
    400,
    '{}',
    new Response('{}', { status: 400 }),
  )
}

test('valid data passes without throwing', async () => {
  const api = new StubTemplatesApi({ valid: true })

  await new TemplateSchemaValidator(api).validate('acme-corp', 'default', 'invoice', { name: 'Jane' })

  assert.equal(api.calls, 1)
  assert.deepEqual((api.sent[0] as { data: unknown }).data, { name: 'Jane' })
})

test('invalidFields become failures keyed by their JSON Pointer', async () => {
  const api = new StubTemplatesApi({
    valid: false,
    invalidFields: [
      { path: '/customer/email', keyword: 'format', message: 'must be a valid email address' },
      { path: '/lineItems/0/quantity', keyword: 'minimum', message: 'must be at least 1' },
    ],
  })

  const thrown = await capture(() => new TemplateSchemaValidator(api).validate('acme-corp', 'default', 'invoice', {}))

  assert.deepEqual(
    thrown.errors.map((failure) => failure.path),
    ['/customer/email', '/lineItems/0/quantity'],
  )
  assert.deepEqual(
    thrown.errors.map((failure) => failure.keyword),
    ['format', 'minimum'],
  )
})

test('a missing required field is a failure and a missing optional field is not', async () => {
  const api = new StubTemplatesApi({
    valid: false,
    missingFields: [
      { path: '/customer/address', required: true },
      { path: '/customer/phone', required: false },
    ],
  })

  const thrown = await capture(() => new TemplateSchemaValidator(api).validate('acme-corp', 'default', 'invoice', {}))

  assert.deepEqual(
    thrown.errors.map((failure) => failure.path),
    ['/customer/address'],
  )
  assert.equal(thrown.errors[0]?.keyword, 'required')
})

test('errors is used when the server sends no field members', async () => {
  const api = new StubTemplatesApi({
    valid: false,
    errors: [{ path: '/invoiceNumber', message: 'does not match the required format', keyword: 'pattern' }],
  })

  const thrown = await capture(() => new TemplateSchemaValidator(api).validate('acme-corp', 'default', 'invoice', {}))

  assert.equal(thrown.errors[0]?.path, '/invoiceNumber')
  assert.equal(thrown.errors[0]?.keyword, 'pattern')
})

test('the field members win over errors, which may describe the same failure differently', async () => {
  // `errors[].path` documents itself as a JSON Pointer but is specified with a JSONPath example,
  // so preferring invalidFields keeps the promise from depending on which member the server filled.
  const api = new StubTemplatesApi({
    valid: false,
    errors: [{ path: '$.customer.email', message: 'must be a valid email address', keyword: 'format' }],
    invalidFields: [{ path: '/customer/email', keyword: 'format', message: 'must be a valid email address' }],
  })

  const thrown = await capture(() => new TemplateSchemaValidator(api).validate('acme-corp', 'default', 'invoice', {}))

  assert.equal(thrown.errors.length, 1)
  assert.equal(thrown.errors[0]?.path, '/customer/email')
})

test('an invalid result with nothing to report still throws', async () => {
  // `valid: false` is the verdict, and reporting no reason must not become "fine".
  const thrown = await capture(() =>
    new TemplateSchemaValidator(new StubTemplatesApi({ valid: false })).validate('acme-corp', 'default', 'invoice', {}),
  )

  assert.equal(thrown.errors.length, 1)
  assert.equal(thrown.errors[0]?.path, '')
})

test('the version selectors are passed through when configured', async () => {
  const api = new StubTemplatesApi({ valid: true })

  await new TemplateSchemaValidator(
    new ServerTemplateDataValidator(api, { variantId: 'nl-nl', environmentId: 'production' }),
  ).validate('acme-corp', 'default', 'invoice', {})

  const sent = api.sent[0] as { variantId?: string; environmentId?: string }
  assert.equal(sent.variantId, 'nl-nl')
  assert.equal(sent.environmentId, 'production')
})

test('a plugged-in validator replaces the server entirely', async () => {
  const api = new StubTemplatesApi({ valid: true })

  const thrown = await capture(() =>
    new TemplateSchemaValidator(local('/name')).validate('acme-corp', 'default', 'invoice', {}),
  )

  assert.equal(thrown.errors[0]?.path, '/name')
  assert.equal(api.calls, 0)
})

test('the default validator submits without a pre-flight request', async () => {
  const templates = new StubTemplatesApi({ valid: true })
  const generation = new StubGenerationApi()

  await new ValidatingGenerationApi(generation, templates).generateDocument(singleRequest)

  // The server validates what it is given, so asking it first would be a second round trip for
  // the same verdict.
  assert.equal(templates.calls, 0)
  assert.equal(generation.submissions, 1)
})

test('a rejected submission becomes a TemplateDataValidationException', async () => {
  const rejected = templateDataInvalid({
    invalidFields: [{ path: '/customer/email', keyword: 'format', message: 'must be a valid email address' }],
    missingFields: [
      { path: '/invoiceNumber', required: true },
      { path: '/customer/phone', required: false },
    ],
  })
  const api = new ValidatingGenerationApi(new StubGenerationApi(rejected), new StubTemplatesApi({ valid: true }))

  const thrown = await capture(() => api.generateDocument(singleRequest))

  assert.deepEqual(
    thrown.errors.map((failure) => failure.path),
    ['/customer/email', '/invoiceNumber'],
  )
})

test('any other problem propagates untouched', async () => {
  const notFound = new ProblemDetailException(
    { type: 'https://epistola.app/errors/not-found', title: 'Not Found', status: 404 },
    [],
    {},
    {},
    404,
    '{}',
    new Response('{}', { status: 404 }),
  )
  const api = new ValidatingGenerationApi(new StubGenerationApi(notFound), new StubTemplatesApi({ valid: true }))

  await assert.rejects(() => api.generateDocument(singleRequest), (error: unknown) => error === notFound)
})

test('a local validator throws before the request is sent', async () => {
  const generation = new StubGenerationApi()

  await capture(() => new ValidatingGenerationApi(generation, local('/name')).generateDocument(singleRequest))

  assert.equal(generation.submissions, 0)
})

test('a local validator reports every item of a batch at once, prefixed by its index', async () => {
  const generation = new StubGenerationApi()

  const thrown = await capture(() =>
    new ValidatingGenerationApi(generation, local('/name')).generateDocumentBatch(batchRequest),
  )

  assert.deepEqual(
    thrown.errors.map((failure) => failure.path),
    ['items[0]/name', 'items[1]/name'],
  )
  assert.equal(generation.submissions, 0)
})

test('the default validator pre-flights nothing per batch item either', async () => {
  const templates = new StubTemplatesApi({ valid: true })
  const generation = new StubGenerationApi()

  await new ValidatingGenerationApi(generation, templates).generateDocumentBatch(batchRequest)

  assert.equal(templates.calls, 0)
  assert.equal(generation.submissions, 1)
})

/** Runs `call`, expecting it to reject with a TemplateDataValidationException, and returns it. */
async function capture(call: () => Promise<unknown>): Promise<TemplateDataValidationException> {
  try {
    await call()
  } catch (error) {
    assert.ok(error instanceof TemplateDataValidationException, `expected a validation exception, got ${String(error)}`)
    return error
  }
  throw new assert.AssertionError({ message: 'expected a TemplateDataValidationException, but the call resolved' })
}
