// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import assert from 'node:assert/strict'
import { test } from 'node:test'
import { CONSTRAINED_MODELS, MODEL_CONSTRAINTS, ModelValidationException, validateCreateTenantRequest, validateModel } from '../../src/index.js'

test('the contract declares constraints on the models the generator found', () => {
  assert.ok(CONSTRAINED_MODELS.length > 10)
  assert.ok(CONSTRAINED_MODELS.includes('CreateTenantRequest'))
  assert.ok(MODEL_CONSTRAINTS.CreateTenantRequest.some((f) => f.property === 'id' && f.constraints.some((c) => c.kind === 'pattern')))
})

test('a valid model is returned unchanged', () => {
  const request = { id: 'acme-corp', name: 'Acme' }
  assert.equal(validateCreateTenantRequest(request), request)
})

test('violations are collected per property', () => {
  assert.throws(
    () => validateCreateTenantRequest({ id: 'Acme Corp', name: '' }),
    (error: unknown) => {
      assert.ok(error instanceof ModelValidationException)
      assert.equal(error.model, 'CreateTenantRequest')
      assert.deepEqual(
        error.violations.map((v) => v.property),
        ['id', 'name'],
      )
      assert.match(error.violations[0].message, /must match pattern/)
      assert.match(error.violations[1].message, /length must be at least 1/)
      return true
    },
  )
})

test('a required constrained property that is missing is a violation; an optional one is not', () => {
  assert.throws(() => validateModel('CreateTenantRequest', { name: 'Acme' }), /id: is required/)
  // description is optional on UpdateTenantRequest; leaving it out is fine.
  validateModel('UpdateTenantRequest', {})
})
