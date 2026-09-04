// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import assert from 'node:assert/strict'
import { test } from 'node:test'
import { CONTRACT_VERSION, ClientIdentity, ContractIdentity } from '../../src/index.js'

test('User-Agent starts with the contract token and appends products in order', () => {
  const identity = ClientIdentity.builder()
    .nodeId('my-pod')
    .product('valtimo-epistola-plugin', '1.2.0')
    .product('gzac', '5.0.0')
    .build()
  assert.equal(identity.userAgent, `epistola-contract/${CONTRACT_VERSION} valtimo-epistola-plugin/1.2.0 gzac/5.0.0`)
  assert.equal(identity.nodeId, 'my-pod')
})

test('the contract version is a release or snapshot version', () => {
  assert.match(ClientIdentity.CONTRACT_VERSION, /^\d+\.\d+(\.\d+)?(-.+)?$/)
  assert.equal(ClientIdentity.CONTRACT_VERSION, CONTRACT_VERSION)
})

test('the node id defaults to the hostname', () => {
  assert.ok(ClientIdentity.builder().build().nodeId.length > 0)
})

test('headers() carries both headers under the generated names', () => {
  const headers = ClientIdentity.builder().nodeId('n1').build().headers()
  assert.equal(headers[ContractIdentity.NODE_ID_HEADER], 'n1')
  assert.equal(headers['X-EP-Node-Id'], 'n1')
  assert.ok(headers['User-Agent'].startsWith(`${ContractIdentity.CONTRACT_PRODUCT}${ContractIdentity.VERSION_SEPARATOR}`))
})

test('invalid product names and blank versions are rejected', () => {
  for (const name of ['bad/name', 'bad name', '', '   ']) {
    assert.throws(() => ClientIdentity.builder().product(name, '1.0.0'), RangeError)
  }
  assert.throws(() => ClientIdentity.builder().product('ok', ''), RangeError)
  assert.throws(() => ClientIdentity.builder().nodeId(' '), RangeError)
})
