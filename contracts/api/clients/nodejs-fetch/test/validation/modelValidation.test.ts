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
  validateModel('CatalogDependencyRequirement', { namespace: 'epistola', catalogKey: 'system', version: '1.3.0' })
  for (const release of ['^1.3.0', '1.x', '*', '01.3.0', '1.3.0-rc.1']) {
    assert.throws(
      () => validateModel('CatalogDependencyRequirement', { namespace: 'epistola', catalogKey: 'system', version: release }),
      /version: must match pattern/,
      release,
    )
  }
  // In a draft a declaration may name a draft instead; exactly one of the two is a server-side rule.
  validateModel('CatalogDependencyRequirement', { namespace: 'acme-corp', catalogKey: 'branding', releaseId: 'r4t8w2nc' })
})

test('a dependency declaration may give the catalog an alias, under the catalog key rule', () => {
  validateModel('CatalogDependencyRequirement', { alias: 'shared-branding', namespace: 'acme-corp', catalogKey: 'branding', version: '1.3.0' })
  assert.throws(
    () => validateModel('CatalogDependencyRequirement', { alias: 'x', namespace: 'acme-corp', catalogKey: 'branding', version: '1.3.0' }),
    /alias: /,
  )
})

test('a patch release number is a release number, labelled with a namespace and a sequence number from 1', () => {
  validateModel('CatalogDependencyRequirement', { namespace: 'epistola', catalogKey: 'system', version: '1.3.1-gemeente-x.1' })
  validateModel('ReleaseCatalogDraftRequest', { version: '2.0.0-redesign-2027.12' })
  for (const release of ['1.3.1-gemeente-x.0', '1.3.1-gemeente-x.01', '1.3.1-Gemeente-x.1', '1.3.1-gemeente-x', '1.3.1+build.5']) {
    assert.throws(() => validateModel('ReleaseCatalogDraftRequest', { version: release }), /version: must match pattern/, release)
  }
})

test('a content hash is a V6 fingerprint, whatever algorithm the recorded fingerprint names', () => {
  const hex = '0123456789abcdef'.repeat(4)
  const release = { version: '1.0.0', releaseId: 'v1x9q2', status: 'released', behind: [], dependencies: [], deployedTo: [], fingerprint: `v5:${hex}` }
  validateModel('CatalogReleaseDto', { ...release, contentHash: `v6:${hex}` })
  validateModel('CatalogReleaseDto', { ...release, contentHash: null })
  for (const contentHash of [hex, `v5:${hex}`, `v7:${hex}`, `v6:${hex.toUpperCase()}`, `v6:${hex.slice(1)}`]) {
    assert.throws(() => validateModel('CatalogReleaseDto', { ...release, contentHash }), /contentHash: must match pattern/, contentHash)
  }
})

test('an attribute criterion names its attribute by resource key, never dotted', () => {
  validateModel('VariantSelectionAttribute', { key: 'locale', value: 'nl-NL', catalogKey: 'system' })
  // catalogKey is an alias, under the catalog key rule.
  assert.throws(() => validateModel('VariantSelectionAttribute', { key: 'locale', value: 'nl-NL', catalogKey: 'System' }), /catalogKey: must match pattern/)
  validateModel('VariantSelectionAttribute', { key: '2026-campaign', value: 'spring' })
  assert.throws(() => validateModel('VariantSelectionAttribute', { key: 'system.locale', value: 'nl-NL' }), /key: must match pattern/)
})
