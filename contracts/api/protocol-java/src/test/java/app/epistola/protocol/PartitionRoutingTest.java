// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class PartitionRoutingTest {

    @Test
    void an_unusable_assignment_is_null_rather_than_an_empty_object() {
        assertNull(PartitionRouting.of(null, List.of(0)));
        assertNull(PartitionRouting.of(0, List.of(0)), "a zero partition count would divide by zero");
        assertNull(PartitionRouting.of(8, null));
        assertNull(PartitionRouting.of(8, List.of()), "owning no partitions means nothing routes here");
    }

    @Test
    void a_partition_is_stable_and_in_range() {
        PartitionRouting routing = assignment();

        for (int i = 0; i < 200; i++) {
            String key = "order-" + i;
            int partition = routing.partitionFor(key);
            assertTrue(partition >= 0 && partition < 8, "partition out of range: " + partition);
            assertEquals(partition, routing.partitionFor(key), "the same key must always hash the same");
            assertEquals(List.of(0, 3).contains(partition), routing.isMine(key));
        }
    }

    @Test
    void routing_key_to_me_always_produces_a_key_that_lands_here() {
        // The mistake every client made on its own: "3:key" hashes to wherever it hashes, not to
        // partition 3. With 2 of 8 partitions the old fallback returned a foreign key more often
        // than not, sending the result to another node.
        PartitionRouting routing = assignment();

        int rewritten = 0;
        for (int i = 0; i < 200; i++) {
            String key = "order-" + i;
            String routed = routing.routingKeyToMe(key);
            assertNotNull(routed, "routingKeyToMe returned null for " + key);
            assertTrue(routing.isMine(routed), "produced a foreign key: " + routed);
            if (!routed.equals(key)) {
                rewritten++;
            }
        }
        assertTrue(rewritten > 0, "with 2 of 8 partitions, most keys should need rewriting");
    }

    @Test
    void a_key_that_already_lands_here_is_returned_unchanged() {
        PartitionRouting routing = assignment();
        String own = null;
        for (int i = 0; own == null && i < 200; i++) {
            if (routing.isMine("order-" + i)) {
                own = "order-" + i;
            }
        }
        assertNotNull(own, "expected at least one key to land on our partitions");
        assertEquals(own, routing.routingKeyToMe(own));
    }

    @Test
    void routing_key_to_me_is_deterministic() {
        PartitionRouting routing = assignment();
        assertEquals(routing.routingKeyToMe("order-7"), routing.routingKeyToMe("order-7"));
    }

    @Test
    void owning_every_partition_means_every_key_already_lands_here() {
        PartitionRouting routing = PartitionRouting.of(4, List.of(0, 1, 2, 3));
        assertNotNull(routing);
        for (int i = 0; i < 50; i++) {
            assertTrue(routing.isMine("order-" + i));
            assertEquals("order-" + i, routing.routingKeyToMe("order-" + i));
        }
    }

    @Test
    void the_hash_matches_the_servers_murmur3_vectors() {
        // Guava's Hashing.murmur3_32_fixed(0) over the same UTF-8 bytes — the server's partition
        // assignment. Determinism alone would not catch an implementation that is consistently
        // wrong; if these drift, every node hands out routing keys that land somewhere else.
        assertEquals(0x00000000, Murmur3.hash32(new byte[0], 0));
        assertEquals(0x3c2569b2, Murmur3.hash32("a".getBytes(StandardCharsets.UTF_8), 0));
        assertEquals(0xb3dd93fa, Murmur3.hash32("abc".getBytes(StandardCharsets.UTF_8), 0));
        assertEquals(0x43ed676a, Murmur3.hash32("abcd".getBytes(StandardCharsets.UTF_8), 0));
        assertEquals(0x248bfa47, Murmur3.hash32("hello".getBytes(StandardCharsets.UTF_8), 0));
        assertEquals(0x149bbb7f, Murmur3.hash32("hello, world".getBytes(StandardCharsets.UTF_8), 0));
        assertEquals(
                0x2e4ff723,
                Murmur3.hash32("The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8), 0));
    }

    @Test
    void the_assignment_it_reports_is_the_one_it_was_given() {
        PartitionRouting routing = assignment();
        assertEquals(8, routing.total());
        assertEquals(List.of(0, 3), routing.mine());
        assertFalse(routing.mine().isEmpty());
    }

    private static PartitionRouting assignment() {
        PartitionRouting routing = PartitionRouting.of(8, List.of(0, 3));
        assertNotNull(routing);
        return routing;
    }
}
