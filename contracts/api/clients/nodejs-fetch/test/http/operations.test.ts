// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import assert from 'node:assert/strict'
import { test } from 'node:test'
import { CONTRACT_OPERATIONS, findOperation, operationPath } from '../../src/index.js'

test('a listing operation asks for the success type and the problem document, in that order', () => {
  const operation = findOperation('GET', '/tenants/acme-corp/catalogs/~/acme-corp/main/versions/1.0.0/templates')!
  assert.equal(operation.operationId, 'listTemplates')
  assert.equal(operation.accept, 'application/vnd.epistola.v1+json, application/problem+json')
  assert.equal(operation.contentType, undefined)
})

test('a catalog is addressed by registry, namespace and key, the default registry as ~ or %7E', () => {
  assert.equal(findOperation('GET', '/tenants/acme/catalogs/exchange.example.org/team-a/letters/versions/1.4.1/dependencies')!.operationId, 'listCatalogDependencies')
  assert.equal(findOperation('PUT', '/tenants/acme/catalogs/~/acme/letters/versions/2.0.0-rc.1/dependencies')!.operationId, 'updateCatalogDependencies')
  assert.equal(findOperation('PUT', '/tenants/acme/catalogs/%7E/acme/letters/versions/2.0.0-rc.1/dependencies')!.operationId, 'updateCatalogDependencies')
  assert.equal(findOperation('PUT', '/tenants/acme/environments/production/deployments/~/acme-corp/letters/1.4.0')!.operationId, 'deployRelease')
  assert.equal(findOperation('DELETE', '/tenants/acme/environments/production/deployments/acme.com/acme/letters/1.4.0')!.operationId, 'undeployRelease')
  // Without the registry segment a catalog path addresses nothing.
  assert.equal(findOperation('GET', '/tenants/acme/catalogs/team-a/letters/versions/1.4.1/dependencies'), undefined)
})

test('a catalog version is addressed by its number, and its sub-resources by literal segments', () => {
  const base = '/tenants/acme/catalogs/~/acme-corp/letters/versions'
  assert.equal(findOperation('GET', base)!.operationId, 'listCatalogVersions')
  assert.equal(findOperation('POST', base)!.operationId, 'createCatalogVersion')
  assert.equal(findOperation('GET', `${base}/1.4.0`)!.operationId, 'getCatalogVersion')
  assert.equal(findOperation('PATCH', `${base}/1.4.1`)!.operationId, 'renumberCatalogVersion')
  assert.equal(findOperation('DELETE', `${base}/3.0.0`)!.operationId, 'deleteCatalogVersion')
  assert.equal(findOperation('GET', `${base}/1.5.0/renumber-preview`)!.operationId, 'previewCatalogVersionRenumber')
  assert.equal(findOperation('POST', `${base}/1.5.0/release`)!.operationId, 'releaseCatalogVersion')
  // Drafts are independent copies: nothing flows into them, so there are no incoming changes.
  assert.equal(findOperation('GET', `${base}/2.0.0/incoming`), undefined)
  assert.equal(findOperation('POST', `${base}/2.0.0/incoming/chg-1/resolve`), undefined)
  // Data validation stayed on the catalog: it checks against a release, not a version's content.
  assert.equal(findOperation('POST', '/tenants/acme/catalogs/~/acme-corp/letters/templates/invoice/validate')!.operationId, 'validateTemplateData')
  // The release operations that versions replaced are gone.
  assert.equal(findOperation('POST', '/tenants/acme/catalogs/~/acme-corp/letters/release'), undefined)
  assert.equal(findOperation('GET', '/tenants/acme/catalogs/~/acme-corp/letters/releases'), undefined)
})

test('ping declares no error responses, so only the success type is asked for', () => {
  assert.equal(findOperation('POST', '/ping')!.accept, 'application/vnd.epistola.v1+json')
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
