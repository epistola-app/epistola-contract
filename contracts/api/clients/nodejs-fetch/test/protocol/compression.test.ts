// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import assert from 'node:assert/strict'
import { test } from 'node:test'
import * as zlib from 'node:zlib'
import { acceptEncoding, decompress, detectCodec, ndjsonLines, supportsZstd } from '../../src/index.js'

const PAYLOAD = Buffer.from('{"sequence":1}\n{"_meta":true}\n', 'utf8')

async function collect(chunks: AsyncIterable<Uint8Array>): Promise<Buffer> {
  const parts: Buffer[] = []
  for await (const chunk of decompress(chunks)) parts.push(chunk)
  return Buffer.concat(parts)
}

async function* inChunks(buffer: Buffer, size: number): AsyncGenerator<Uint8Array> {
  for (let offset = 0; offset < buffer.length; offset += size) {
    yield buffer.subarray(offset, offset + size)
  }
}

test('plain content passes through unchanged, whatever the chunking', async () => {
  for (const size of [1, 3, 7, 1024]) {
    assert.deepEqual(await collect(inChunks(PAYLOAD, size)), PAYLOAD)
  }
})

test('a gzip stream is recognised by its magic bytes and inflated', async () => {
  const gzipped = zlib.gzipSync(PAYLOAD)
  for (const size of [1, 2, 5, 1024]) {
    assert.deepEqual(await collect(inChunks(gzipped, size)), PAYLOAD)
  }
})

test('a zstd stream is inflated when this runtime can decode it', { skip: !supportsZstd() }, async () => {
  const compressed = zlib.zstdCompressSync(PAYLOAD)
  assert.equal(detectCodec(compressed), 'zstd')
  assert.deepEqual(await collect(inChunks(compressed, 3)), PAYLOAD)
})

test('the Accept-Encoding offer names gzip and only what can be decoded', () => {
  const offer = acceptEncoding()
  assert.match(offer, /(^|[ ,])gzip($|[ ,;])/)
  assert.equal(offer.includes('zstd'), supportsZstd())
  assert.ok(!offer.includes('lz4'))
})

test('an lz4 frame is refused rather than parsed as text', async () => {
  const lz4 = Buffer.from([0x04, 0x22, 0x4d, 0x18, 0x60, 0x40, 0x82])
  assert.equal(detectCodec(lz4), 'lz4')
  await assert.rejects(collect(inChunks(lz4, 100)), /lz4/)
})

test('an empty stream yields nothing', async () => {
  assert.equal((await collect(inChunks(Buffer.alloc(0), 1))).length, 0)
})

test('returning early closes the source', async () => {
  let closed = false
  const source = {
    [Symbol.asyncIterator]() {
      let sent = false
      return {
        async next() {
          if (sent) return { done: true as const, value: undefined }
          sent = true
          return { done: false as const, value: PAYLOAD }
        },
        async return() {
          closed = true
          return { done: true as const, value: undefined }
        },
      }
    },
  }
  for await (const line of ndjsonLines(decompress(source))) {
    assert.equal(line, '{"sequence":1}')
    break
  }
  assert.ok(closed, 'the source iterator was not closed')
})
