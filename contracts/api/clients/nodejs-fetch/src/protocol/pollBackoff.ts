// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

/**
 * The adaptive polling policy for result collection: how long to wait before the next
 * `/generation/collect` call, given what the last one returned.
 *
 * Immediate while the server says it has more; back to the floor while results are flowing;
 * exponential backoff once idle, capped. The point of collecting this in one place is
 * {@link afterIdlePoll} — every client independently computed the idle interval as
 * `current * multiplier` with no floor, which collapses to a busy loop as soon as a `hasMore` burst
 * sets the interval to zero.
 *
 * Pure and immutable: the numbers only, no sleeping, no clock, no I/O. The collector keeps its own
 * loop, because how it waits (and how it is woken early) is bound to the runtime.
 */
export class PollBackoff {
  private constructor(
    private readonly minIntervalMs: number,
    private readonly maxIntervalMs: number,
    private readonly multiplier: number,
  ) {}

  /**
   * @param minIntervalMs the wait while results are flowing, and the floor for backoff
   * @param maxIntervalMs the longest wait when idle
   * @param multiplier applied on each empty poll; must be greater than 1
   */
  static of(minIntervalMs: number, maxIntervalMs: number, multiplier: number): PollBackoff {
    if (!(minIntervalMs > 0)) {
      throw new RangeError('minInterval must be positive')
    }
    if (!(maxIntervalMs >= minIntervalMs)) {
      throw new RangeError('maxInterval must be at least minInterval')
    }
    if (!(multiplier > 1.0)) {
      throw new RangeError('backoffMultiplier must be > 1.0')
    }
    return new PollBackoff(minIntervalMs, maxIntervalMs, multiplier)
  }

  /** The interval a collector starts at. */
  initial(): number {
    return this.minIntervalMs
  }

  /** After a poll that reported more results waiting: go again immediately. */
  afterHasMore(): number {
    return 0
  }

  /** After a poll that returned results and caught up: wait the floor. */
  afterResults(): number {
    return this.minIntervalMs
  }

  /**
   * After an empty poll (or a failed one): grow the interval, floored at the minimum and capped at
   * the maximum.
   *
   * The floor is the whole point. {@link afterHasMore} returns 0 so the next poll is immediate, and
   * `0 * multiplier` is still 0 — without the floor, a burst that drained (or a server that went
   * down mid-burst) leaves the caller issuing its next request with no delay at all, indefinitely,
   * bounded only by round-trip time.
   */
  afterIdlePoll(currentIntervalMs: number): number {
    const grown = Math.floor(currentIntervalMs * this.multiplier)
    return Math.min(Math.max(grown, this.minIntervalMs), this.maxIntervalMs)
  }

  /**
   * The interval a "a result is expected shortly" hint should shorten to, or the current interval
   * when the caller is already polling at least that often — waking early then would burn a poll
   * for nothing.
   */
  afterKick(currentIntervalMs: number, kickIntervalMs: number): number {
    return currentIntervalMs > kickIntervalMs ? kickIntervalMs : currentIntervalMs
  }
}
