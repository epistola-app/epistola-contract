// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import assert from 'node:assert/strict'
import { test } from 'node:test'
import { murmur3x86_32, murmur3x86_32String } from '../../src/index.js'

test('matches the Guava reference vector', () => {
  // Guava Hashing.murmur3_32_fixed(0).hashString("hello", UTF_8) == 0x248bfa47
  assert.equal(murmur3x86_32String('hello', 0), 0x248bfa47)
})

test('empty input hashes to zero with seed zero', () => {
  assert.equal(murmur3x86_32(new Uint8Array(0), 0), 0)
})

test('handles every tail length and returns an unsigned value', () => {
  // Reference values from the same x86_32 implementation the server uses.
  assert.equal(murmur3x86_32String('a'), 0x3c2569b2)
  assert.equal(murmur3x86_32String('ab'), 0x9bbfd75f)
  assert.equal(murmur3x86_32String('abc'), 0xb3dd93fa)
  assert.equal(murmur3x86_32String('abcd'), 0x43ed676a)
  for (const value of ['', 'a', 'ab', 'abc', 'abcd', 'The quick brown fox']) {
    assert.ok(murmur3x86_32String(value) >= 0)
  }
})

test('is seeded', () => {
  assert.notEqual(murmur3x86_32String('hello', 0), murmur3x86_32String('hello', 1))
})
