// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PollBackoffTest {

    private final PollBackoff backoff = PollBackoff.of(1_000, 30_000, 3.0);

    @Test
    void an_idle_poll_after_a_has_more_burst_returns_to_the_floor_not_to_zero() {
        // The bug every client shipped: afterHasMore() is 0 so the next poll is immediate, and
        // 0 * multiplier is still 0 — without the floor the caller issues its next request with no
        // delay, forever, as soon as a burst drains.
        assertEquals(0L, backoff.afterHasMore());
        assertEquals(1_000L, backoff.afterIdlePoll(backoff.afterHasMore()));
    }

    @Test
    void idle_polls_grow_the_interval_and_stop_at_the_maximum() {
        assertEquals(3_000L, backoff.afterIdlePoll(1_000));
        assertEquals(9_000L, backoff.afterIdlePoll(3_000));
        assertEquals(27_000L, backoff.afterIdlePoll(9_000));
        assertEquals(30_000L, backoff.afterIdlePoll(27_000));
        assertEquals(30_000L, backoff.afterIdlePoll(30_000), "capped, not growing forever");
    }

    @Test
    void results_reset_the_interval_to_the_floor() {
        assertEquals(1_000L, backoff.afterResults());
        assertEquals(1_000L, backoff.initial());
    }

    @Test
    void a_kick_only_shortens_an_interval_that_is_already_longer() {
        assertEquals(3_000L, backoff.afterKick(30_000, 3_000), "idle: shorten to the kick interval");
        assertEquals(1_000L, backoff.afterKick(1_000, 3_000), "already faster: leave it alone");
        assertEquals(3_000L, backoff.afterKick(3_000, 3_000), "exactly at it: leave it alone");
    }

    @Test
    void the_configuration_is_validated_where_it_is_supplied() {
        assertThrows(IllegalArgumentException.class, () -> PollBackoff.of(0, 30_000, 3.0));
        assertThrows(IllegalArgumentException.class, () -> PollBackoff.of(-1, 30_000, 3.0));
        assertThrows(IllegalArgumentException.class, () -> PollBackoff.of(30_000, 1_000, 3.0));
        assertThrows(IllegalArgumentException.class, () -> PollBackoff.of(1_000, 30_000, 1.0));
    }
}
