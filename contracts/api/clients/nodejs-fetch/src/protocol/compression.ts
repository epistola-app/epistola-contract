// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { Readable, pipeline, type Transform } from 'node:stream'
import * as zlib from 'node:zlib'

/**
 * Decompresses the result-collection response.
 *
 * Chooses the decompressor by *sniffing the stream's magic bytes* rather than trusting
 * `Content-Encoding`. Node's own `fetch` decodes `gzip` (and, from 22.15, `zstd`) before the body
 * reaches this code, and leaves the `Content-Encoding` header on the response as if it had not —
 * decoding a second time on the header's say-so would corrupt the stream, and a consumer-supplied
 * `fetchApi` may or may not decode at all. The magic bytes describe what is actually there.
 *
 * `gzip` is always available. `zstd` is offered only when this Node runtime's zlib can decode it,
 * because the server sends whatever the request's `Accept-Encoding` offered. `lz4` is never offered:
 * Node has no decoder for it.
 */
export type Codec = 'gzip' | 'zstd' | 'lz4'

const GZIP_MAGIC = [0x1f, 0x8b]
const LZ4_FRAME_MAGIC = [0x04, 0x22, 0x4d, 0x18]
const ZSTD_MAGIC = [0x28, 0xb5, 0x2f, 0xfd]

type ZstdCapableZlib = typeof zlib & { createZstdDecompress?: () => Transform }

/** Whether this runtime can decode zstd (`zlib.createZstdDecompress`, Node 22.15 / 23.8 and later). */
export function supportsZstd(): boolean {
  return typeof (zlib as ZstdCapableZlib).createZstdDecompress === 'function'
}

/** The `Accept-Encoding` value naming every codec this runtime can decode. */
export function acceptEncoding(): string {
  return supportsZstd() ? 'zstd, gzip' : 'gzip'
}

/** The codec the leading bytes of a stream call for, or undefined for plain content. */
export function detectCodec(leading: Uint8Array): Codec | undefined {
  if (startsWith(leading, GZIP_MAGIC)) return 'gzip'
  if (startsWith(leading, LZ4_FRAME_MAGIC)) return 'lz4'
  if (startsWith(leading, ZSTD_MAGIC)) return 'zstd'
  return undefined
}

/**
 * Yields the decompressed bytes of `chunks`, or the bytes unchanged when they are already plain.
 *
 * Returning early from the consumer (breaking out of a `for await`) closes the source iterator, so a
 * fetch body is cancelled rather than left to drain.
 */
export async function* decompress(chunks: AsyncIterable<Uint8Array>): AsyncGenerator<Buffer, void, undefined> {
  const iterator = chunks[Symbol.asyncIterator]()
  let sourceClosed = false
  const closeSource = async () => {
    if (!sourceClosed) {
      sourceClosed = true
      await iterator.return?.()
    }
  }

  try {
    // Peek at the first four bytes without losing them.
    const buffered: Buffer[] = []
    let bufferedLength = 0
    while (bufferedLength < 4) {
      const next = await iterator.next()
      if (next.done) {
        sourceClosed = true
        break
      }
      const chunk = toBuffer(next.value)
      if (chunk.length === 0) continue
      buffered.push(chunk)
      bufferedLength += chunk.length
    }
    const codec = detectCodec(Buffer.concat(buffered).subarray(0, 4))

    const remainder = async function* (): AsyncGenerator<Buffer, void, undefined> {
      yield* buffered
      while (!sourceClosed) {
        const next = await iterator.next()
        if (next.done) {
          sourceClosed = true
          return
        }
        yield toBuffer(next.value)
      }
    }

    if (codec === undefined) {
      yield* remainder()
      return
    }

    const inflater = openDecoder(codec)
    // Errors from either side reach the consumer through the inflater's async iterator: a source
    // failure destroys the inflater with that error, and a corrupt stream is the inflater's own.
    pipeline(Readable.from(remainder()), inflater, () => undefined)
    for await (const chunk of inflater) {
      yield chunk as Buffer
    }
  } finally {
    await closeSource()
  }
}

function openDecoder(codec: Codec): Transform {
  switch (codec) {
    case 'gzip':
      return zlib.createGunzip()
    case 'zstd': {
      const factory = (zlib as ZstdCapableZlib).createZstdDecompress
      if (factory === undefined) {
        throw new Error('Server sent zstd-compressed results but this Node.js runtime cannot decode zstd (needs zlib.createZstdDecompress, Node 22.15+)')
      }
      return factory()
    }
    case 'lz4':
      throw new Error('Server sent lz4-compressed results, which this client never offers and Node.js cannot decode')
  }
}

function startsWith(buffer: Uint8Array, prefix: readonly number[]): boolean {
  if (buffer.length < prefix.length) return false
  for (let i = 0; i < prefix.length; i++) {
    if (buffer[i] !== prefix[i]) return false
  }
  return true
}

function toBuffer(chunk: Uint8Array): Buffer {
  return Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk.buffer, chunk.byteOffset, chunk.byteLength)
}
