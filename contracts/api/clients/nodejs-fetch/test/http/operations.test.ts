// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import assert from 'node:assert/strict'
import { test } from 'node:test'
import { CONTRACT_OPERATIONS, findOperation, operationPath } from '../../src/index.js'

test('a listing operation asks for the success type and the problem document, in that order', () => {
  const operation = findOperation('GET', '/tenants/acme-corp/catalogs/~/acme-corp/main/releases/1.0.0/templates')!
  assert.equal(operation.operationId, 'listTemplates')
  assert.equal(operation.accept, 'application/vnd.epistola.v2+json, application/problem+json')
  assert.equal(operation.contentType, undefined)
})

test('a catalog is addressed by registry, namespace and key, the default registry as ~ or %7E', () => {
  assert.equal(findOperation('GET', '/tenants/acme/catalogs/exchange.example.org/team-a/letters/releases/1.4.1/dependencies')!.operationId, 'listCatalogDependencies')
  assert.equal(findOperation('PUT', '/tenants/acme/catalogs/~/acme/letters/releases/k7m2x9qa/dependencies')!.operationId, 'updateCatalogDependencies')
  assert.equal(findOperation('PUT', '/tenants/acme/catalogs/%7E/acme/letters/releases/k7m2x9qa/dependencies')!.operationId, 'updateCatalogDependencies')
  // A deployment is addressed by the release: a released one by its version or id, a draft by its id.
  assert.equal(findOperation('PUT', '/tenants/acme/environments/production/deployments/~/acme-corp/letters/1.4.0')!.operationId, 'deployCatalogRelease')
  assert.equal(findOperation('PUT', '/tenants/acme/environments/acceptance/deployments/~/acme-corp/letters/k7m2x9qa')!.operationId, 'deployCatalogRelease')
  assert.equal(findOperation('DELETE', '/tenants/acme/environments/production/deployments/acme.com/acme/letters/1.4.0')!.operationId, 'undeployCatalogRelease')
  assert.equal(findOperation('DELETE', '/tenants/acme/environments/acceptance/deployments/acme.com/acme/letters/k7m2x9qa')!.operationId, 'undeployCatalogRelease')
  // Without the registry segment a catalog path addresses nothing.
  assert.equal(findOperation('GET', '/tenants/acme/catalogs/team-a/letters/releases/1.4.1/dependencies'), undefined)
})

test('a release is addressed by its id or by its version, and its sub-resources by literal segments', () => {
  const base = '/tenants/acme/catalogs/~/acme-corp/letters/releases'
  const releaseId = 'r4t8w2nc'
  assert.equal(findOperation('GET', base)!.operationId, 'listCatalogReleases')
  assert.equal(findOperation('POST', base)!.operationId, 'createCatalogRelease')
  assert.equal(findOperation('GET', `${base}/1.4.0`)!.operationId, 'getCatalogRelease')
  assert.equal(findOperation('GET', `${base}/${releaseId}`)!.operationId, 'getCatalogRelease')
  assert.equal(findOperation('PATCH', `${base}/${releaseId}`)!.operationId, 'updateCatalogRelease')
  assert.equal(findOperation('DELETE', `${base}/${releaseId}`)!.operationId, 'deleteCatalogRelease')
  assert.equal(findOperation('GET', `${base}/${releaseId}/release-preview`)!.operationId, 'previewCatalogDraftRelease')
  // Any draft is released at its own address (ADR 0002); there is no promotion.
  assert.equal(findOperation('POST', `${base}/${releaseId}/release`)!.operationId, 'releaseCatalogDraft')
  assert.equal(findOperation('POST', `${base}/${releaseId}/promote`), undefined)
  // Merging a release into a draft (alpha, ADR 0005).
  assert.equal(findOperation('GET', `${base}/${releaseId}/merge-preview`)!.operationId, 'previewCatalogDraftMerge')
  assert.equal(findOperation('POST', `${base}/${releaseId}/merge`)!.operationId, 'mergeIntoCatalogDraft')
  assert.equal(findOperation('GET', `${base}/${releaseId}/merge-sides`)!.operationId, 'getCatalogDraftMergeSides')
  assert.equal(findOperation('GET', `${base}/${releaseId}/templates`)!.operationId, 'listTemplates')
  // A draft gets its version when it is released, so there is nothing to renumber.
  assert.equal(findOperation('GET', `${base}/${releaseId}/renumber-preview`), undefined)
  // Drafts are independent copies: nothing flows into them, so there are no incoming changes.
  assert.equal(findOperation('GET', `${base}/2.0.0/incoming`), undefined)
  assert.equal(findOperation('POST', `${base}/2.0.0/incoming/chg-1/resolve`), undefined)
  // Data validation stayed on the catalog: it checks against a version range, not one release's content.
  assert.equal(findOperation('POST', '/tenants/acme/catalogs/~/acme-corp/letters/templates/invoice/validate')!.operationId, 'validateTemplateData')
  // A draft is released at its own address; there is no catalog-wide release operation.
  assert.equal(findOperation('POST', '/tenants/acme/catalogs/~/acme-corp/letters/release'), undefined)
})

test('ping declares no error responses, so only the success type is asked for', () => {
  assert.equal(findOperation('POST', '/ping')!.accept, 'application/vnd.epistola.v2+json')
})

test('a literal segment beats a parameter where both templates match', () => {
  assert.equal(findOperation('GET', '/tenants/acme/documents/jobs')!.operationId, 'listGenerationJobs')
  assert.equal(findOperation('GET', '/tenants/acme/documents/99999999-9999-4999-8999-000000000001')!.operationId, 'downloadDocument')
  assert.equal(findOperation('GET', '/tenants/acme/documents/99999999-9999-4999-8999-000000000001')!.accept, 'application/pdf, application/problem+json')
  assert.equal(findOperation('POST', '/tenants/acme/consumers/register')!.operationId, 'registerConsumer')
})

test('method and unknown paths matter', () => {
  assert.equal(findOperation('DELETE', '/ping'), undefined)
  assert.equal(findOperation('GET', '/nowhere'), undefined)
  assert.equal(findOperation('get', '/tenants/acme')!.operationId, 'getTenant')
})

test('every operation in the table has a method, a path template and an operationId', () => {
  assert.ok(CONTRACT_OPERATIONS.length > 50)
  for (const operation of CONTRACT_OPERATIONS) {
    assert.match(operation.path, /^\//)
    assert.ok(operation.operationId.length > 0)
  }
})

test('operationPath strips the configured base path', () => {
  assert.equal(operationPath('https://epistola.example.com/api/tenants/acme?page=0', 'https://epistola.example.com/api'), '/tenants/acme')
  assert.equal(operationPath('https://epistola.example.com/api', 'https://epistola.example.com/api/'), '/')
  assert.equal(operationPath('https://epistola.example.com/other/tenants/acme', 'https://epistola.example.com/api'), '/other/tenants/acme')
  assert.equal(operationPath('http://127.0.0.1:1234/tenants/acme', 'http://127.0.0.1:1234'), '/tenants/acme')
})
