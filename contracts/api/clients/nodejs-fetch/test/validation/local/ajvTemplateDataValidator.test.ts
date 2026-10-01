// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import assert from 'node:assert/strict'
import { test } from 'node:test'
import { AjvTemplateDataValidator, type TemplateSchemaSource } from './ajvTemplateDataValidator.js'

/**
 * The reference local adapter, held to the contract `TemplateDataValidator` states — above all that
 * `path` is a JSON Pointer.
 *
 * This is what makes the interface's promise testable rather than aspirational: a real compiler,
 * with its own idea of how to name a location, converted to the one shape callers read.
 */

const INVOICE_SCHEMA = {
  $schema: 'https://json-schema.org/draft/2020-12/schema',
  type: 'object',
  required: ['customer', 'invoiceNumber'],
  properties: {
    customer: {
      type: 'object',
      required: ['name', 'email'],
      properties: {
        name: { type: 'string', minLength: 1 },
        email: { type: 'string', format: 'email' },
      },
    },
    invoiceNumber: { type: 'string' },
    lineItems: {
      type: 'array',
      items: {
        type: 'object',
        required: ['quantity'],
        properties: { quantity: { type: 'integer', minimum: 1 } },
      },
    },
  },
}

const VALID_DATA = {
  customer: { name: 'Jane Smith', email: 'jane@example.com' },
  invoiceNumber: 'INV-2026-001',
  lineItems: [{ quantity: 3 }],
}

class StubTemplatesApi implements TemplateSchemaSource {
  fetches = 0
  constructor(private readonly schema: object | undefined) {}
  async getTemplate(): Promise<{ schema?: object }> {
    this.fetches += 1
    return this.schema === undefined ? {} : { schema: this.schema }
  }
}

test('valid data yields no findings', async () => {
  const validator = new AjvTemplateDataValidator(new StubTemplatesApi(INVOICE_SCHEMA))

  assert.deepEqual(await validator.validate('acme-corp', 'default', 'invoice', VALID_DATA), [])
})

test('paths are JSON Pointers', async () => {
  const validator = new AjvTemplateDataValidator(new StubTemplatesApi(INVOICE_SCHEMA))
  const data = { ...VALID_DATA, customer: { name: '', email: 'jane@example.com' } }

  const findings = await validator.validate('acme-corp', 'default', 'invoice', data)

  // The old implementation reported a dotted key here, `customer.name`.
  assert.equal(findings.length, 1)
  assert.equal(findings[0]?.path, '/customer/name')
  assert.equal(findings[0]?.keyword, 'minLength')
})

test('a pointer into an array uses the index as a segment', async () => {
  const validator = new AjvTemplateDataValidator(new StubTemplatesApi(INVOICE_SCHEMA))
  const data = { ...VALID_DATA, lineItems: [{ quantity: 0 }] }

  const findings = await validator.validate('acme-corp', 'default', 'invoice', data)

  assert.equal(findings[0]?.path, '/lineItems/0/quantity')
  assert.equal(findings[0]?.keyword, 'minimum')
})

test('a missing required field is reported at its own pointer, as the server reports it', async () => {
  const validator = new AjvTemplateDataValidator(new StubTemplatesApi(INVOICE_SCHEMA))

  const findings = await validator.validate('acme-corp', 'default', 'invoice', { invoiceNumber: 'INV-2026-001' })

  // Ajv points at the parent and names the absent property separately; joining them is what gives
  // the pointer the interface asks for, and matches `missingFields[].path` from the server.
  assert.deepEqual(
    findings.map((failure) => failure.path),
    ['/customer'],
  )
  assert.equal(findings[0]?.keyword, 'required')
})

test('format is asserted here, which is exactly why a local verdict is its own', async () => {
  const validator = new AjvTemplateDataValidator(new StubTemplatesApi(INVOICE_SCHEMA))
  const data = { ...VALID_DATA, customer: { name: 'Jane Smith', email: 'not-an-email' } }

  const findings = await validator.validate('acme-corp', 'default', 'invoice', data)

  // Recorded as a divergence, not a guarantee: this adapter registers ajv-formats, so `format`
  // asserts — while the JVM reference adapter, on networknt under 2020-12, treats it as an
  // annotation and accepts the same data. Two local engines, two answers, same contract. The
  // server's is the one that decides what renders.
  assert.equal(findings.length, 1)
  assert.equal(findings[0]?.path, '/customer/email')
  assert.equal(findings[0]?.keyword, 'format')
})

test('a template without a schema claims nothing about the data', async () => {
  const validator = new AjvTemplateDataValidator(new StubTemplatesApi(undefined))

  assert.deepEqual(await validator.validate('acme-corp', 'default', 'invoice', { anything: 1 }), [])
})

test('the schema is fetched once and reused', async () => {
  const templates = new StubTemplatesApi(INVOICE_SCHEMA)
  const validator = new AjvTemplateDataValidator(templates)

  await validator.validate('acme-corp', 'default', 'invoice', VALID_DATA)
  await validator.validate('acme-corp', 'default', 'invoice', VALID_DATA)

  // Without the cache a "local" validator costs a round trip per call, which is worse than asking
  // the server to validate outright.
  assert.equal(templates.fetches, 1)
})

test('it takes the generation pre-flight, unlike the server-backed default', async () => {
  assert.equal(new AjvTemplateDataValidator(new StubTemplatesApi(INVOICE_SCHEMA)).preflightsGeneration, true)
})
