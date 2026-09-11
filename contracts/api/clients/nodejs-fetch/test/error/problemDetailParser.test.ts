// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import assert from 'node:assert/strict'
import { test } from 'node:test'
import { KnownProblemSlugs, ProblemDetailException, ResponseError, isProblemJson, parseProblem } from '../../src/index.js'

function parse(body: unknown, status: number): ProblemDetailException | undefined {
  const text = typeof body === 'string' ? body : JSON.stringify(body)
  return parseProblem(text, status, new Response(text, { status, headers: { 'Content-Type': 'application/problem+json' } }))
}

test('parses a base problem and exposes the type slug', () => {
  const problem = parse({ type: 'https://epistola.app/errors/not-found', title: 'Not Found', status: 404, detail: 'tenant acme not found', instance: '/api/tenants/acme' }, 404)!
  assert.ok(problem instanceof ProblemDetailException)
  assert.ok(problem instanceof ResponseError)
  assert.equal(problem.typeSlug, KnownProblemSlugs.NOT_FOUND)
  assert.equal(problem.type, 'https://epistola.app/errors/not-found')
  assert.equal(problem.title, 'Not Found')
  assert.equal(problem.problemStatus, 404)
  assert.equal(problem.statusCode, 404)
  assert.equal(problem.detail, 'tenant acme not found')
  assert.equal(problem.instance, '/api/tenants/acme')
  assert.equal(problem.message, '404 Not Found: tenant acme not found')
  assert.equal(problem.isValidationProblem, false)
  assert.equal(problem.isDataModelValidationProblem, false)
  assert.deepEqual(problem.extensions, {})
})

test('parses a validation problem with its errors array', () => {
  const problem = parse({
    type: 'https://epistola.app/errors/validation-error',
    title: 'Validation Failed',
    status: 400,
    errors: [
      { field: 'name', message: 'must not be blank' },
      { field: 'slug', message: 'invalid', rejectedValue: 'BAD' },
    ],
  }, 400)!
  assert.equal(problem.typeSlug, KnownProblemSlugs.VALIDATION_ERROR)
  assert.ok(problem.isValidationProblem)
  assert.deepEqual(problem.errors.map((e) => e.field), ['name', 'slug'])
  assert.equal(problem.errors[1].rejectedValue, 'BAD')
  // The raw extension member is also reachable by name.
  assert.ok(Array.isArray(problem.extensions.errors))
})

test('parses a data-model validation problem with its validationErrors map', () => {
  const problem = parse({
    type: 'https://epistola.app/errors/data-model-validation-error',
    title: 'Unprocessable',
    status: 422,
    validationErrors: { 'example-a': [{ path: '#/customer/name', message: 'required' }] },
  }, 422)!
  assert.equal(problem.typeSlug, KnownProblemSlugs.DATA_MODEL_VALIDATION_ERROR)
  assert.ok(problem.isDataModelValidationProblem)
  assert.equal(problem.validationErrors['example-a'][0].path, '#/customer/name')
})

test('keeps extension members the contract does not name', () => {
  const problem = parse({ type: 'https://epistola.app/errors/catalog-schema-too-old', title: 'Too old', status: 409, version: 4, baselineVersion: 6 }, 409)!
  assert.equal(problem.typeSlug, 'catalog-schema-too-old')
  assert.deepEqual(problem.extensions, { version: 4, baselineVersion: 6 })
})

test('about:blank and a missing type have no slug', () => {
  const blank = parse({ type: 'about:blank', title: 'Server Error', status: 500 }, 500)!
  assert.equal(blank.typeSlug, undefined)
  assert.equal(blank.type, 'about:blank')
  const missing = parse({ title: 'Server Error', status: 500 }, 500)!
  assert.equal(missing.type, 'about:blank')
  assert.equal(missing.typeSlug, undefined)
})

test('a body missing title or status falls back to the response status', () => {
  const problem = parse({ type: 'https://epistola.app/errors/rate-limited' }, 429)!
  assert.equal(problem.problemStatus, 429)
  assert.equal(problem.message, '429 429')
})

test('malformed JSON and non-object bodies yield nothing', () => {
  assert.equal(parse('{ not json', 400), undefined)
  assert.equal(parse('[1, 2, 3]', 400), undefined)
  assert.equal(parse('null', 400), undefined)
})

test('isProblemJson ignores parameters and case', () => {
  assert.ok(isProblemJson('application/problem+json'))
  assert.ok(isProblemJson('Application/Problem+JSON; charset=utf-8'))
  assert.ok(!isProblemJson('application/json'))
  assert.ok(!isProblemJson(undefined))
})
