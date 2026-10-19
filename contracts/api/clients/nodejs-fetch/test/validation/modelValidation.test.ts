// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import assert from 'node:assert/strict'
import { test } from 'node:test'
import { CONSTRAINED_MODELS, MODEL_CONSTRAINTS, ModelValidationException, validateCreateTenantRequest, validateModel } from '../../src/index.js'

test('the contract declares constraints on the models the generator found', () => {
  assert.ok(CONSTRAINED_MODELS.length > 10)
  assert.ok(CONSTRAINED_MODELS.includes('CreateTenantRequest'))
  assert.ok(MODEL_CONSTRAINTS.CreateTenantRequest.some((f) => f.property === 'key' && f.constraints.some((c) => c.kind === 'pattern')))
})

test('a valid model is returned unchanged', () => {
  const request = { key: 'acme-corp', name: 'Acme' }
  assert.equal(validateCreateTenantRequest(request), request)
})

test('violations are collected per property', () => {
  assert.throws(
    () => validateCreateTenantRequest({ key: 'Acme Corp', name: '' }),
    (error: unknown) => {
      assert.ok(error instanceof ModelValidationException)
      assert.equal(error.model, 'CreateTenantRequest')
      assert.deepEqual(
        error.violations.map((v) => v.property),
        ['key', 'name'],
      )
      assert.match(error.violations[0].message, /must match pattern/)
      assert.match(error.violations[1].message, /length must be at least 1/)
      return true
    },
  )
})

test('a required constrained property that is missing is a violation; an optional one is not', () => {
  assert.throws(() => validateModel('CreateTenantRequest', { name: 'Acme' }), /key: is required/)
  // description is optional on UpdateTenantRequest; leaving it out is fine.
  validateModel('UpdateTenantRequest', {})
})

test('a dependency declaration pins an exact release, checked through the ReleaseVersion reference', () => {
  validateModel('CatalogDependencyRequirement', { namespace: 'epistola', catalogKey: 'system', releaseVersion: '1.3.0' })
  for (const release of ['^1.3.0', '1.x', '*', '01.3.0', '1.3.0-rc.1']) {
    assert.throws(
      () => validateModel('CatalogDependencyRequirement', { namespace: 'epistola', catalogKey: 'system', releaseVersion: release }),
      /releaseVersion: must match pattern/,
      release,
    )
  }
  // In a draft a declaration may name a draft instead; exactly one of the two is a server-side rule.
  validateModel('CatalogDependencyRequirement', { namespace: 'acme-corp', catalogKey: 'branding', versionId: 'r4t8w2nc' })
})

test('an environment override takes an exact release or a draft', () => {
  validateModel('SetEnvironmentOverrideRequest', { releaseVersion: '1.3.0' })
  validateModel('SetEnvironmentOverrideRequest', { versionId: 'r4t8w2nc' })
  for (const release of ['^1.3.0', '1.3', '2.0.0-beta.1', 'next']) {
    assert.throws(() => validateModel('SetEnvironmentOverrideRequest', { releaseVersion: release }), /releaseVersion: /, release)
  }
  for (const draft of ['1.3.0', 'Draft-1', 'abc', 'k7m2--x9']) {
    assert.throws(() => validateModel('SetEnvironmentOverrideRequest', { versionId: draft }), /versionId: /, draft)
  }
})
