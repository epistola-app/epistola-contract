// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import assert from 'node:assert/strict'
import { test } from 'node:test'
import { ndjsonLines } from '../../src/index.js'

async function* chunks(...parts: Buffer[]): AsyncGenerator<Uint8Array> {
  yield* parts
}

async function all(source: AsyncIterable<Uint8Array>): Promise<string[]> {
  const result: string[] = []
  for await (const line of ndjsonLines(source)) result.push(line)
  return result
}

test('splits on newlines across chunk boundaries', async () => {
  const text = Buffer.from('{"a":1}\n{"b":2}\n\n{"c":3}\n', 'utf8')
  assert.deepEqual(await all(chunks(text.subarray(0, 5), text.subarray(5, 12), text.subarray(12))), ['{"a":1}', '{"b":2}', '', '{"c":3}'])
})

test('a multi-byte character split between two reads is decoded whole', async () => {
  const text = Buffer.from('{"city":"Sittard – Geleen"}\n', 'utf8')
  const dash = text.indexOf(Buffer.from('–', 'utf8')) + 1 // one byte into the three-byte dash
  assert.deepEqual(await all(chunks(text.subarray(0, dash), text.subarray(dash))), ['{"city":"Sittard – Geleen"}'])
})

test('a final line without a newline is still yielded, and CRLF reads as LF', async () => {
  assert.deepEqual(await all(chunks(Buffer.from('{"a":1}\r\n{"b":2}', 'utf8'))), ['{"a":1}', '{"b":2}'])
})
