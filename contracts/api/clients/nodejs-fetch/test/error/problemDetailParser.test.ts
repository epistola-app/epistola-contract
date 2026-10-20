// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import assert from 'node:assert/strict'
import { test } from 'node:test'
import { KnownProblemSlugs, MissingReleaseDependencyReason, ProblemDetailException, ResponseError, isProblemJson, parseProblem } from '../../src/index.js'

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

test('parses catalog-source-unavailable as a 503 and as a 502', () => {
  for (const status of [503, 502]) {
    const problem = parse({
      type: 'https://epistola.app/errors/catalog-source-unavailable',
      title: 'Catalog Source Unavailable',
      status,
      detail: 'registry.gemeente-x.nl could not be used',
    }, status)!
    assert.equal(problem.typeSlug, KnownProblemSlugs.CATALOG_SOURCE_UNAVAILABLE)
    assert.equal(problem.problemStatus, status)
    assert.equal(problem.statusCode, status)
  }
})

test('parses a validation problem with its errors array', () => {
  const problem = parse({
    type: 'https://epistola.app/errors/validation-error',
    title: 'Validation Failed',
    status: 400,
    errors: [
      { field: 'name', message: 'must not be blank' },
      { field: 'key', message: 'invalid', rejectedValue: 'BAD' },
    ],
  }, 400)!
  assert.equal(problem.typeSlug, KnownProblemSlugs.VALIDATION_ERROR)
  assert.ok(problem.isValidationProblem)
  assert.deepEqual(problem.errors.map((e) => e.field), ['name', 'key'])
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

test('parses a release-dependencies-missing problem with its missingDependencies list', () => {
  const problem = parse({
    type: 'https://epistola.app/errors/release-dependencies-missing',
    title: 'Release dependencies missing',
    status: 409,
    missingDependencies: [
      { registry: 'registry.epistola.app', namespace: 'acme', catalogKey: 'brand', version: '1.2.0', reason: 'unavailable' },
      { registry: 'registry.epistola.app', namespace: 'acme', catalogKey: 'legal', version: '2.0.0', reason: 'unavailable' },
    ],
  }, 409)!
  assert.equal(problem.typeSlug, KnownProblemSlugs.RELEASE_DEPENDENCIES_MISSING)
  assert.ok(problem.isReleaseDependenciesMissingProblem)
  assert.equal(problem.isValidationProblem, false)
  assert.deepEqual(problem.missingDependencies, [
    { registry: 'registry.epistola.app', namespace: 'acme', catalogKey: 'brand', version: '1.2.0', reason: MissingReleaseDependencyReason.Unavailable },
    { registry: 'registry.epistola.app', namespace: 'acme', catalogKey: 'legal', version: '2.0.0', reason: MissingReleaseDependencyReason.Unavailable },
  ])
})

test('a problem without missingDependencies reports none', () => {
  const problem = parse({ type: 'https://epistola.app/errors/release-dependencies-missing', title: 'Conflict', status: 409 }, 409)!
  assert.deepEqual(problem.missingDependencies, [])
  assert.equal(problem.isReleaseDependenciesMissingProblem, false)
})

test('malformed missingDependencies entries are dropped and an unknown reason is kept', () => {
  const problem = parse({
    type: 'https://epistola.app/errors/release-dependencies-missing',
    title: 'Conflict',
    status: 409,
    missingDependencies: [
      'not-an-object',
      null,
      ['registry.epistola.app'],
      { registry: 'registry.epistola.app', namespace: 'acme', catalogKey: 'brand', version: '1.2.0' },
      { registry: 'registry.epistola.app', namespace: 'acme', catalogKey: 'brand', version: 3, reason: 'unavailable' },
      { registry: 'registry.epistola.app', namespace: 'acme', catalogKey: 'brand', version: '1.2.0', reason: 'quarantined' },
    ],
  }, 409)!
  assert.deepEqual(problem.missingDependencies, [
    { registry: 'registry.epistola.app', namespace: 'acme', catalogKey: 'brand', version: '1.2.0', reason: 'quarantined' },
  ])
  assert.ok(problem.isReleaseDependenciesMissingProblem)

  const notAnArray = parse({ type: 'https://epistola.app/errors/release-dependencies-missing', title: 'Conflict', status: 409, missingDependencies: { registry: 'x' } }, 409)!
  assert.deepEqual(notAnArray.missingDependencies, [])
})

test('parses a release-dependencies-unreleased problem with its unreleasedDependencies list', () => {
  const problem = parse({
    type: 'https://epistola.app/errors/release-dependencies-unreleased',
    title: 'Release dependencies unreleased',
    status: 409,
    unreleasedDependencies: [
      { registry: 'exchange.epistola.app', namespace: 'acme-corp', catalogKey: 'branding', releaseId: 'r4t8w2nc', releaseName: 'House style 2027' },
      { registry: 'registry.gemeente-x.nl', namespace: 'huisstijl', catalogKey: 'fonts', releaseId: 'h3v9p6db', releaseName: 'New typefaces, round 2' },
    ],
  }, 409)!
  assert.equal(problem.typeSlug, KnownProblemSlugs.RELEASE_DEPENDENCIES_UNRELEASED)
  assert.ok(problem.isReleaseDependenciesUnreleasedProblem)
  assert.equal(problem.isReleaseDependenciesMissingProblem, false)
  assert.deepEqual(problem.unreleasedDependencies.map((d) => `${d.catalogKey}#${d.releaseId}=${d.releaseName}`), [
    'branding#r4t8w2nc=House style 2027',
    'fonts#h3v9p6db=New typefaces, round 2',
  ])
})

test('malformed unreleasedDependencies entries are dropped, and other problems report none', () => {
  const problem = parse({
    type: 'https://epistola.app/errors/release-dependencies-unreleased',
    title: 'Conflict',
    status: 409,
    unreleasedDependencies: [
      'not-an-object',
      { registry: 'exchange.epistola.app', namespace: 'acme-corp', catalogKey: 'branding', releaseId: 'r4t8w2nc' },
      { registry: 'exchange.epistola.app', namespace: 'acme-corp', catalogKey: 'fonts', releaseId: 'h3v9p6db', releaseName: 'Typefaces' },
    ],
  }, 409)!
  assert.deepEqual(problem.unreleasedDependencies.map((d) => d.catalogKey), ['fonts'])
  const other = parse({ type: 'https://epistola.app/errors/conflict', title: 'Conflict', status: 409 }, 409)!
  assert.deepEqual(other.unreleasedDependencies, [])
  assert.equal(other.isReleaseDependenciesUnreleasedProblem, false)
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
