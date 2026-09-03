// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.protocol;

/**
 * The adaptive polling policy for result collection: how long to wait before the next
 * {@code /generation/collect} call, given what the last one returned.
 *
 * <p>Immediate while the server says it has more; back to the floor while results are flowing;
 * exponential backoff once idle, capped. The point of collecting this in one place is
 * {@link #afterIdlePoll(long)} — every client independently computed the idle interval as
 * {@code current * multiplier} with no floor, which collapses to a busy loop as soon as a
 * {@code hasMore} burst sets the interval to zero.
 *
 * <p>Pure and immutable: the numbers only, no sleeping, no clock, no I/O. Each client keeps its own
 * loop, because how it waits (and how it is woken early) is bound to its runtime.
 */
public final class PollBackoff {

    private final long minIntervalMillis;
    private final long maxIntervalMillis;
    private final double multiplier;

    private PollBackoff(long minIntervalMillis, long maxIntervalMillis, double multiplier) {
        this.minIntervalMillis = minIntervalMillis;
        this.maxIntervalMillis = maxIntervalMillis;
        this.multiplier = multiplier;
    }

    /**
     * @param minIntervalMillis the wait while results are flowing, and the floor for backoff
     * @param maxIntervalMillis the longest wait when idle
     * @param multiplier        applied on each empty poll; must be greater than 1
     */
    public static PollBackoff of(long minIntervalMillis, long maxIntervalMillis, double multiplier) {
        if (minIntervalMillis <= 0) {
            throw new IllegalArgumentException("minInterval must be positive");
        }
        if (maxIntervalMillis < minIntervalMillis) {
            throw new IllegalArgumentException("maxInterval must be at least minInterval");
        }
        if (multiplier <= 1.0) {
            throw new IllegalArgumentException("backoffMultiplier must be > 1.0");
        }
        return new PollBackoff(minIntervalMillis, maxIntervalMillis, multiplier);
    }

    /** The interval a collector starts at. */
    public long initial() {
        return minIntervalMillis;
    }

    /**
     * After a poll that reported more results waiting: go again immediately.
     */
    public long afterHasMore() {
        return 0L;
    }

    /**
     * After a poll that returned results and caught up: wait the floor.
     */
    public long afterResults() {
        return minIntervalMillis;
    }

    /**
     * After an empty poll (or a failed one): grow the interval, floored at the minimum and capped
     * at the maximum.
     *
     * <p>The floor is the whole point. {@link #afterHasMore()} returns 0 so the next poll is
     * immediate, and {@code 0 * multiplier} is still 0 — without the floor, a burst that drained
     * (or a server that went down mid-burst) leaves the caller issuing its next request with no
     * delay at all, indefinitely, bounded only by round-trip time.
     */
    public long afterIdlePoll(long currentIntervalMillis) {
        long grown = (long) (currentIntervalMillis * multiplier);
        return Math.min(Math.max(grown, minIntervalMillis), maxIntervalMillis);
    }

    /**
     * The interval a "a result is expected shortly" hint should shorten to, or the current interval
     * when the caller is already polling at least that often — waking early then would burn a poll
     * for nothing.
     */
    public long afterKick(long currentIntervalMillis, long kickIntervalMillis) {
        return currentIntervalMillis > kickIntervalMillis ? kickIntervalMillis : currentIntervalMillis;
    }
}
