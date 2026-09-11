// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { murmur3x86_32String } from './murmur3.js'

/**
 * How many prefixes {@link PartitionRouting.routingKeyToMe} tries before giving up. Reaching this
 * means every one of a thousand hashes missed every partition this node owns.
 */
export const MAX_ROUTING_KEY_ATTEMPTS = 1000

/**
 * The partition assignment a node holds, and the routing decisions that follow from it.
 *
 * Generation results are routed to nodes by consistent hashing on the request's `routingKey`:
 * `murmur3(routingKey) % totalPartitions`, with partitions assigned to nodes by the server. This is
 * the client half of that scheme — deciding which partition a key lands on, and constructing a key
 * that lands here.
 *
 * Immutable; a new instance is built from each poll's partition metadata.
 */
export class PartitionRouting {
  private constructor(
    /** Total number of partitions the server is using. */
    readonly total: number,
    /** The partitions assigned to this node. */
    readonly mine: readonly number[],
  ) {}

  /**
   * The assignment for a node owning `mine` of `total` partitions, or undefined when the server
   * has not reported a usable assignment yet — no partition count, or none owned. Returning
   * undefined rather than an empty object keeps "not known yet" a single check at the call site.
   */
  static of(total: number | null | undefined, mine: readonly number[] | null | undefined): PartitionRouting | undefined {
    if (total === null || total === undefined || total <= 0 || !mine || mine.length === 0) {
      return undefined
    }
    return new PartitionRouting(total, Object.freeze([...mine]))
  }

  /** The partition a routing key lands on, using the server's hash (murmur3 x86 32-bit, seed 0). */
  partitionFor(routingKey: string): number {
    return (murmur3x86_32String(routingKey, 0) & 0x7fffffff) % this.total
  }

  /** True when `routingKey` would land on one of this node's partitions. */
  isMine(routingKey: string): boolean {
    return this.mine.includes(this.partitionFor(routingKey))
  }

  /**
   * A routing key that targets one of this node's partitions — so a submission's result comes back
   * to the node that made it.
   *
   * Returns `key` unchanged when it already routes here; otherwise searches numbered prefixes
   * (`"0:key"`, `"1:key"`, …) for one that does. The search is deterministic, so the same key
   * always yields the same routed key. Returns undefined in the vanishingly unlikely event that no
   * prefix within {@link MAX_ROUTING_KEY_ATTEMPTS} attempts lands here.
   *
   * Trying only the partition numbers this node owns is *not* enough, which is the mistake every
   * client made independently: `"3:key"` hashes to wherever it hashes, not to partition 3. Only
   * checking each candidate's actual partition can tell us. With p of n partitions owned each
   * attempt succeeds with probability p/n, so this converges in a handful of iterations.
   *
   * The prefix is what the server hashes, so a rewritten key is a different key: pass the value
   * returned here as the request's `routingKey`, and expect it back on the result.
   */
  routingKeyToMe(key: string): string | undefined {
    if (this.isMine(key)) {
      return key
    }
    for (let attempt = 0; attempt < MAX_ROUTING_KEY_ATTEMPTS; attempt++) {
      const candidate = `${attempt}:${key}`
      if (this.isMine(candidate)) {
        return candidate
      }
    }
    return undefined
  }
}
