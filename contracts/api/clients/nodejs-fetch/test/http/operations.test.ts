// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import assert from 'node:assert/strict'
import { test } from 'node:test'
import { CONTRACT_OPERATIONS, findOperation, operationPath } from '../../src/index.js'

test('a listing operation asks for the success type and the problem document, in that order', () => {
  const operation = findOperation('GET', '/tenants/acme-corp/catalogs/main/templates')!
  assert.equal(operation.operationId, 'listTemplates')
  assert.equal(operation.accept, 'application/vnd.epistola.v1+json, application/problem+json')
  assert.equal(operation.contentType, undefined)
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
