// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import assert from 'node:assert/strict'
import { test } from 'node:test'
import { PollBackoff } from '../../src/index.js'

test('recovers from a hasMore burst instead of returning zero', () => {
  // hasMore sets the interval to 0 so the next poll is immediate, and 0 * multiplier is still 0 —
  // without a floor, a burst that drained left the loop polling flat out forever.
  const backoff = PollBackoff.of(1_000, 30_000, 3)
  assert.equal(backoff.afterHasMore(), 0)
  assert.equal(backoff.afterIdlePoll(0), 1_000)
  assert.equal(backoff.afterIdlePoll(1_000), 3_000)
  assert.equal(backoff.afterIdlePoll(20_000), 30_000)
  assert.equal(backoff.afterResults(), 1_000)
  assert.equal(backoff.initial(), 1_000)
})

test('a kick only ever shortens the interval', () => {
  const backoff = PollBackoff.of(1_000, 30_000, 3)
  assert.equal(backoff.afterKick(9_000, 3_000), 3_000)
  assert.equal(backoff.afterKick(1_000, 3_000), 1_000)
})

test('rejects a non-positive minimum, a maximum below it, and a multiplier of one', () => {
  assert.throws(() => PollBackoff.of(0, 30_000, 3), RangeError)
  assert.throws(() => PollBackoff.of(1_000, 500, 3), RangeError)
  assert.throws(() => PollBackoff.of(1_000, 30_000, 1), RangeError)
})
