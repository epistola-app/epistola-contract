// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The partition assignment a node holds, and the routing decisions that follow from it.
 *
 * <p>Generation results are routed to nodes by consistent hashing on the request's
 * {@code routingKey}: {@code murmur3(routingKey) % totalPartitions}, with partitions assigned to
 * nodes by the server. This is the client half of that scheme — deciding which partition a key
 * lands on, and constructing a key that lands here.
 *
 * <p>Shared rather than reimplemented per client. The prefix search below is the reason: every
 * client had a version that returned keys landing on <em>another</em> node's partition, and the
 * same wrong assumption had to be fixed in each of them separately.
 *
 * <p>Immutable; a new instance is built from each poll's partition metadata.
 */
public final class PartitionRouting {

    /**
     * How many prefixes {@link #routingKeyToMe(String)} tries before giving up. Reaching this means
     * every one of a thousand hashes missed every partition this node owns.
     */
    private static final int MAX_ROUTING_KEY_ATTEMPTS = 1000;

    private final int total;
    private final List<Integer> mine;

    private PartitionRouting(int total, List<Integer> mine) {
        this.total = total;
        this.mine = mine;
    }

    /**
     * The assignment for a node owning {@code mine} of {@code total} partitions, or {@code null}
     * when the server has not reported a usable assignment yet — no partition count, or none owned.
     * Returning {@code null} rather than an empty object keeps "not known yet" a single check at
     * the call site.
     */
    public static @Nullable PartitionRouting of(@Nullable Integer total, @Nullable List<Integer> mine) {
        if (total == null || total <= 0 || mine == null || mine.isEmpty()) {
            return null;
        }
        return new PartitionRouting(total, Collections.unmodifiableList(List.copyOf(mine)));
    }

    /** Total number of partitions the server is using. */
    public int total() {
        return total;
    }

    /** The partitions assigned to this node. */
    public List<Integer> mine() {
        return mine;
    }

    /** The partition a routing key lands on, using the server's hash (murmur3 x86 32-bit, seed 0). */
    public int partitionFor(String routingKey) {
        int hash = Murmur3.hash32(routingKey.getBytes(StandardCharsets.UTF_8), 0);
        return (hash & 0x7FFFFFFF) % total;
    }

    /** True when {@code routingKey} would land on one of this node's partitions. */
    public boolean isMine(String routingKey) {
        return mine.contains(partitionFor(routingKey));
    }

    /**
     * A routing key that targets one of this node's partitions — so a submission's result comes
     * back to the node that made it.
     *
     * <p>Returns {@code key} unchanged when it already routes here; otherwise searches numbered
     * prefixes ({@code "0:key"}, {@code "1:key"}, …) for one that does. The search is
     * deterministic, so the same key always yields the same routed key. Returns {@code null} in
     * the vanishingly unlikely event that no prefix within {@value #MAX_ROUTING_KEY_ATTEMPTS}
     * attempts lands here.
     *
     * <p>Trying only the partition numbers this node owns is <em>not</em> enough, which is the
     * mistake every client made independently: {@code "3:key"} hashes to wherever it hashes, not
     * to partition 3. Only checking each candidate's actual partition can tell us. With p of n
     * partitions owned each attempt succeeds with probability p/n, so this converges in a handful
     * of iterations.
     *
     * <p>The prefix is what the server hashes, so a rewritten key is a different key: pass the
     * value returned here as the request's {@code routingKey}, and expect it back on the result.
     */
    public @Nullable String routingKeyToMe(String key) {
        if (isMine(key)) {
            return key;
        }
        for (int attempt = 0; attempt < MAX_ROUTING_KEY_ATTEMPTS; attempt++) {
            String candidate = attempt + ":" + key;
            if (isMine(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
