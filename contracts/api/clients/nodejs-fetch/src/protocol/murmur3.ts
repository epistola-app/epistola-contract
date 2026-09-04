// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

/**
 * MurmurHash3 x86 32-bit.
 *
 * Must match the server's partition assignment exactly (Guava's `Hashing.murmur3_32_fixed(seed)`),
 * otherwise {@link PartitionRouting} would hand out routing keys that land on another node's
 * partition. The tests pin it against known vectors, and the conformance suite holds it to the same
 * answers the other four clients give.
 *
 * Public because it is the hash the protocol is defined in terms of; a consumer computing routing
 * keys ahead of time needs the same function.
 *
 * @returns the hash as an unsigned 32-bit integer
 */
export function murmur3x86_32(data: Uint8Array, seed = 0): number {
  const C1 = 0xcc9e2d51
  const C2 = 0x1b873593
  let h1 = seed | 0
  const length = data.length
  const blocks = length >>> 2

  for (let i = 0; i < blocks; i++) {
    const index = i * 4
    let k1 = (data[index] | (data[index + 1] << 8) | (data[index + 2] << 16) | (data[index + 3] << 24)) | 0
    k1 = Math.imul(k1, C1)
    k1 = (k1 << 15) | (k1 >>> 17)
    k1 = Math.imul(k1, C2)
    h1 ^= k1
    h1 = (h1 << 13) | (h1 >>> 19)
    h1 = (Math.imul(h1, 5) + 0xe6546b64) | 0
  }

  const tail = blocks * 4
  const remainder = length & 3
  let k1 = 0
  if (remainder === 3) {
    k1 ^= data[tail + 2] << 16
  }
  if (remainder >= 2) {
    k1 ^= data[tail + 1] << 8
  }
  if (remainder >= 1) {
    k1 ^= data[tail]
    k1 = Math.imul(k1, C1)
    k1 = (k1 << 15) | (k1 >>> 17)
    k1 = Math.imul(k1, C2)
    h1 ^= k1
  }

  h1 ^= length
  h1 ^= h1 >>> 16
  h1 = Math.imul(h1, 0x85ebca6b)
  h1 ^= h1 >>> 13
  h1 = Math.imul(h1, 0xc2b2ae35)
  h1 ^= h1 >>> 16
  return h1 >>> 0
}

/** {@link murmur3x86_32} over the UTF-8 encoding of `value`, which is how the server hashes a routing key. */
export function murmur3x86_32String(value: string, seed = 0): number {
  return murmur3x86_32(new TextEncoder().encode(value), seed)
}
