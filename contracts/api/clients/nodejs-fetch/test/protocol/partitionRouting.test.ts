// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import assert from 'node:assert/strict'
import { test } from 'node:test'
import { PartitionRouting } from '../../src/index.js'

test('no assignment without a partition count or without owned partitions', () => {
  assert.equal(PartitionRouting.of(undefined, [0]), undefined)
  assert.equal(PartitionRouting.of(0, [0]), undefined)
  assert.equal(PartitionRouting.of(8, []), undefined)
  assert.equal(PartitionRouting.of(8, null), undefined)
})

test('partitionFor is stable and in range', () => {
  const routing = PartitionRouting.of(8, [0, 1])!
  const partition = routing.partitionFor('customer-123')
  assert.ok(partition >= 0 && partition < 8)
  assert.equal(routing.partitionFor('customer-123'), partition)
})

test('agrees with the answers the conformance suite holds every client to', () => {
  // scenarios/110-partition-routing.yaml, total 8, mine [0, 3]
  const routing = PartitionRouting.of(8, [0, 3])!
  assert.deepEqual(
    ['invoice-2026-001', 'order-42', 'customer-acme', 'batch-7', 'shipment-99'].map((k) => routing.partitionFor(k)),
    [0, 6, 5, 5, 5],
  )
  assert.deepEqual(
    ['invoice-2026-001', 'order-42', 'customer-acme', 'batch-7', 'shipment-99'].map((k) => routing.routingKeyToMe(k)),
    ['invoice-2026-001', '0:order-42', '5:customer-acme', '4:batch-7', '0:shipment-99'],
  )
})

test('routingKeyToMe returns the key unchanged when all partitions are mine', () => {
  const routing = PartitionRouting.of(4, [0, 1, 2, 3])!
  assert.ok(routing.isMine('some-key'))
  assert.equal(routing.routingKeyToMe('some-key'), 'some-key')
})

test('routingKeyToMe always produces a key that lands here', () => {
  // Trying only the partition numbers this node owns is not enough: "3:key" hashes to wherever it
  // hashes, not to partition 3. With 2 of 8 partitions the old fallback returned a foreign key
  // more often than not.
  const routing = PartitionRouting.of(8, [0, 1])!
  let rewritten = 0
  for (let i = 0; i < 100; i++) {
    const key = `order-${i}`
    const routed = routing.routingKeyToMe(key)
    assert.ok(routed !== undefined, `routingKeyToMe returned undefined for ${key}`)
    assert.ok(routing.isMine(routed), `produced a foreign key: ${routed}`)
    if (routed !== key) rewritten++
  }
  assert.ok(rewritten > 0, 'with 2 of 8 partitions, most keys should need rewriting')
})

test('routingKeyToMe is deterministic', () => {
  const routing = PartitionRouting.of(8, [0, 1])!
  assert.equal(routing.routingKeyToMe('order-7'), routing.routingKeyToMe('order-7'))
})
