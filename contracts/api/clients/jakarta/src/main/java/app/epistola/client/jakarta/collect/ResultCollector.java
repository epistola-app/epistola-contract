// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.collect;

import app.epistola.client.jakarta.EpistolaJson;
import app.epistola.client.jakarta.model.CollectMeta;
import app.epistola.client.jakarta.model.CollectRequest;
import app.epistola.client.jakarta.model.GenerationResult;
import app.epistola.client.jakarta.model.PartitionAssignment;
import app.epistola.protocol.Compression;
import app.epistola.protocol.PartitionRouting;
import app.epistola.protocol.PollBackoff;
import jakarta.ws.rs.core.Response;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Collects generation results from {@code /generation/collect} with NDJSON streaming,
 * compression and adaptive polling.
 *
 * <p>Results are processed one at a time — the response is never held in memory. Results from this
 * node come first; orphaned results from nodes that stopped polling follow.
 *
 * <p>What it handles:
 * <ul>
 *   <li>NDJSON streaming (constant memory)</li>
 *   <li>Compression: gzip built in, lz4/zstd used when those libraries are on the classpath</li>
 *   <li>Adaptive polling — immediate while {@code hasMore}, exponential backoff when idle</li>
 *   <li>Sequence-based acknowledgement: safe across restarts, and a batch whose handler threw
 *       is redelivered rather than lost</li>
 *   <li>Partition-aware routing-key helpers</li>
 *   <li>Observability through {@link MetricsListener}</li>
 *   <li>A thread-safe {@link #collectOnce()} for scheduling the polls yourself</li>
 * </ul>
 *
 * <pre>{@code
 * ResultCollector collector = ResultCollector.builder()
 *     .collectApi(collectApi)
 *     .tenantId("acme-corp")
 *     .handler(result -> {
 *         switch (result.getStatus()) {
 *             case COMPLETED -> downloadAndProcess(result.getDocumentId(), result.getCorrelationId());
 *             case FAILED -> logFailure(result.getCorrelationId(), result.getError());
 *         }
 *     })
 *     .build();
 *
 * collector.start();  // blocks, runs the adaptive poll loop
 * }</pre>
 *
 * <p>In a Jakarta EE application, run {@link #start()} on a managed thread
 * ({@code ManagedExecutorService}) or drive {@link #collectOnce()} from a
 * {@code @Schedule} timer — never on an unmanaged {@code new Thread(...)}.
 */
public final class ResultCollector {

    private final GenerationCollectApi collectApi;
    private final String tenantId;
    private final int batchSize;
    private final Duration minInterval;
    private final Duration maxInterval;
    private final Duration kickInterval;
    private final Consumer<GenerationResult> handler;
    private final Consumer<Exception> errorHandler;
    private final MetricsListener metricsListener;
    private final boolean registerShutdownHook;

    /**
     * How many prefixes {@link #routingKeyToMe(String)} tries before giving up. Reaching this
     * means every one of a thousand hashes missed every partition this node owns.
     */
    private static final int MAX_ROUTING_KEY_ATTEMPTS = 1000;

    // The polling policy — shared with the Kotlin client, because the collapse-to-zero bug lived
    // in this arithmetic in every client.
    private final PollBackoff backoff;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ReentrantLock pollLock = new ReentrantLock();

    // Wakeable sleep — replaces Thread.sleep so kick() and stop() can cut an in-progress wait
    // short. Capacity 1 is intentional: rapid repeat calls collapse into a single wake-up.
    private final LinkedBlockingQueue<Boolean> wakeUp = new LinkedBlockingQueue<>(1);

    private volatile long currentInterval;
    private volatile PartitionAssignment partitionAssignment;
    private Long lastAcknowledgedSequence;
    private Thread shutdownHook;

    private ResultCollector(Builder builder) {
        this.collectApi = builder.collectApi;
        this.tenantId = builder.tenantId;
        this.batchSize = builder.batchSize;
        this.minInterval = builder.minInterval;
        this.maxInterval = builder.maxInterval;
        this.kickInterval = builder.kickInterval;
        this.handler = builder.handler;
        this.errorHandler = builder.errorHandler;
        this.metricsListener = builder.metricsListener;
        this.registerShutdownHook = builder.registerShutdownHook;
        this.backoff = PollBackoff.of(
                builder.minInterval.toMillis(), builder.maxInterval.toMillis(), builder.backoffMultiplier);
        this.currentInterval = builder.minInterval.toMillis();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The outcome of a single collection. */
    public static final class CollectResult {

        private final int count;
        private final boolean hasMore;

        CollectResult(int count, boolean hasMore) {
            this.count = count;
            this.hasMore = hasMore;
        }

        /** How many results the handler was called for. */
        public int getCount() {
            return count;
        }

        /** True when the server still has results queued for this node. */
        public boolean isHasMore() {
            return hasMore;
        }
    }

    /** Observability hooks. Implementations must not block the poll loop. */
    public interface MetricsListener {

        /** Called after each poll completes, successfully or not. */
        void onPoll(int count, boolean hasMore, long durationMs, Exception error);

        /** Called when the server hands this node a different set of partitions. */
        void onPartitionChange(PartitionAssignment oldAssignment, PartitionAssignment newAssignment);
    }

    /** The current partition assignment, refreshed from the {@code _meta} line of every poll. */
    public PartitionAssignment getPartitionAssignment() {
        return partitionAssignment;
    }

    // --- Partition routing helpers ---

    /** The current assignment as the shared router sees it, or null while it is unknown. */
    private PartitionRouting routing() {
        PartitionAssignment assignment = partitionAssignment;
        return assignment == null ? null : PartitionRouting.of(assignment.getTotal(), assignment.getMine());
    }

    /**
     * The partition a routing key lands on, using the server's hash (murmur3 x86 32-bit, seed 0).
     * Returns {@code null} until the first poll has reported an assignment.
     */
    public Integer partitionFor(String routingKey) {
        PartitionRouting routing = routing();
        return routing == null ? null : routing.partitionFor(routingKey);
    }

    /** True when {@code routingKey} would land on one of this node's partitions. */
    public boolean isMyPartition(String routingKey) {
        PartitionRouting routing = routing();
        return routing != null && routing.isMine(routingKey);
    }

    /**
     * A routing key that targets one of this node's partitions — useful to make a submission's
     * result come back to the node that made it. Returns {@code null} until the first poll has
     * reported an assignment.
     *
     * <p>The prefix is what the server hashes, so a rewritten key is a different key: pass the
     * value returned here as the request's {@code routingKey}, and expect it back on the result.
     */
    public String routingKeyToMe(String key) {
        PartitionRouting routing = routing();
        return routing == null ? null : routing.routingKeyToMe(key);
    }

    // --- Poll loop ---

    /** Runs the adaptive poll loop, blocking the calling thread until {@link #stop()}. */
    public void start() {
        running.set(true);
        currentInterval = minInterval.toMillis();

        if (registerShutdownHook) {
            shutdownHook = new Thread(this::stop, "ResultCollector-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }

        try {
            while (running.get()) {
                try {
                    CollectResult result = collectOnce();
                    if (!running.get()) {
                        break;
                    }
                    if (result.isHasMore()) {
                        currentInterval = backoff.afterHasMore();
                    } else if (result.getCount() > 0) {
                        currentInterval = backoff.afterResults();
                    } else {
                        currentInterval = backoff.afterIdlePoll(currentInterval);
                    }
                    sleepInterruptibly(currentInterval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    if (errorHandler != null) {
                        errorHandler.accept(e);
                    }
                    long jitter = ThreadLocalRandom.current().nextLong(currentInterval / 2 + 1);
                    currentInterval = backoff.afterIdlePoll(currentInterval);
                    try {
                        sleepInterruptibly(currentInterval + jitter);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } finally {
            removeShutdownHook();
        }
    }

    /**
     * Hints that a result is expected soon — typically called straight after a submission, so the
     * next poll happens within the kick interval instead of waiting out the full backoff.
     *
     * <p>Threshold-guarded: while the collector is already polling at or below the kick interval
     * the next poll is imminent anyway, and waking early would only burn a poll. So this does
     * nothing during active periods and matters only once the collector has backed off into idle.
     *
     * <p>Safe to call from any thread, any number of times.
     */
    public void kick() {
        long shortened = backoff.afterKick(currentInterval, kickInterval.toMillis());
        if (shortened != currentInterval) {
            currentInterval = shortened;
            wakeUp.offer(Boolean.TRUE);
        }
    }

    /** Signals the poll loop to stop once the current collection completes. */
    public void stop() {
        running.set(false);
        // Wake the loop so shutdown is not delayed by an in-progress backoff.
        wakeUp.offer(Boolean.TRUE);
    }

    /**
     * Performs a single collection. Thread-safe: concurrent calls are serialized so a result is
     * never delivered twice.
     *
     * <p>Streams the NDJSON response line by line, calling the handler for each result. If the
     * handler throws, the acknowledgement cursor is not advanced past the failing result and the
     * rest of the batch is redelivered on the next call.
     */
    public CollectResult collectOnce() {
        pollLock.lock();
        try {
            long startTime = System.currentTimeMillis();
            try {
                CollectResult result = poll();
                if (metricsListener != null) {
                    metricsListener.onPoll(
                            result.getCount(), result.isHasMore(), System.currentTimeMillis() - startTime, null);
                }
                return result;
            } catch (RuntimeException e) {
                if (metricsListener != null) {
                    metricsListener.onPoll(0, false, System.currentTimeMillis() - startTime, e);
                }
                throw e;
            }
        } finally {
            pollLock.unlock();
        }
    }

    private CollectResult poll() {
        CollectRequest request = new CollectRequest().limit(batchSize);
        if (lastAcknowledgedSequence != null) {
            request.setAcknowledgeUpTo(lastAcknowledgedSequence);
        }

        try (Response response =
                collectApi.collectGenerationResults(tenantId, Compression.acceptEncoding(), request)) {
            InputStream body = response.readEntity(InputStream.class);
            if (body == null) {
                return new CollectResult(0, false);
            }

            int count = 0;
            boolean hasMore = false;
            Long lastSequenceInBatch = null;

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(Compression.decompress(body), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    GenerationResult result = EpistolaJson.jsonb().fromJson(line, GenerationResult.class);
                    if (result.getSequence() == null) {
                        // Only the trailing _meta line lacks a sequence; re-read it as such.
                        hasMore = applyMeta(line);
                        break;
                    }
                    handler.accept(result);
                    lastSequenceInBatch = result.getSequence();
                    count++;
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read the collect response stream", e);
            }

            // Only reached when every line was handled: a throwing handler leaves the cursor
            // where it was, so the whole batch is redelivered rather than silently dropped.
            if (lastSequenceInBatch != null) {
                lastAcknowledgedSequence = lastSequenceInBatch;
            }

            return new CollectResult(count, hasMore);
        }
    }

    private boolean applyMeta(String line) {
        CollectMeta meta = EpistolaJson.jsonb().fromJson(line, CollectMeta.class);
        PartitionAssignment assignment = meta.getPartitions();
        if (assignment != null && !Objects.equals(partitionAssignment, assignment)) {
            PartitionAssignment previous = partitionAssignment;
            partitionAssignment = assignment;
            if (metricsListener != null) {
                metricsListener.onPartitionChange(previous, assignment);
            }
        }
        return Boolean.TRUE.equals(meta.getHasMore());
    }

    /**
     * Wakeable replacement for {@code Thread.sleep}: returns early when {@link #kick()} or
     * {@link #stop()} offers a token, and drains any extra tokens afterwards so a burst of calls
     * cannot shorten the next wait too.
     */
    private void sleepInterruptibly(long durationMs) throws InterruptedException {
        if (durationMs <= 0) {
            wakeUp.clear();
            return;
        }
        wakeUp.poll(durationMs, TimeUnit.MILLISECONDS);
        wakeUp.clear();
    }

    private void removeShutdownHook() {
        if (shutdownHook == null) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // The JVM is already shutting down; the hook is running or has run.
        }
        shutdownHook = null;
    }

    /** Builds a {@link ResultCollector}. {@code collectApi}, {@code tenantId} and {@code handler} are required. */
    public static final class Builder {

        private GenerationCollectApi collectApi;
        private String tenantId;
        private int batchSize = 100;
        private Duration minInterval = Duration.ofSeconds(1);
        private Duration maxInterval = Duration.ofSeconds(30);
        private Duration kickInterval = Duration.ofSeconds(3);
        private double backoffMultiplier = 3.0;
        private Consumer<GenerationResult> handler;
        private Consumer<Exception> errorHandler;
        private MetricsListener metricsListener;
        private boolean registerShutdownHook = true;

        /** The collect endpoint — inject {@code @RestClient GenerationCollectApi} or build one. */
        public Builder collectApi(GenerationCollectApi collectApi) {
            this.collectApi = collectApi;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        /** Maximum results per collection (default: 100). */
        public Builder batchSize(int batchSize) {
            if (batchSize < 1 || batchSize > 10000) {
                throw new IllegalArgumentException("batchSize must be between 1 and 10000");
            }
            this.batchSize = batchSize;
            return this;
        }

        /** Minimum poll interval while results are flowing (default: 1s). */
        public Builder minInterval(Duration minInterval) {
            this.minInterval = requirePositive(minInterval, "minInterval");
            return this;
        }

        /** Maximum poll interval when idle (default: 30s). */
        public Builder maxInterval(Duration maxInterval) {
            this.maxInterval = requirePositive(maxInterval, "maxInterval");
            return this;
        }

        /**
         * Wait used by {@link #kick()} to override the current backoff (default: 3s). Short enough
         * to feel responsive, long enough that the server has had a chance to produce the expected
         * result before the poll asks for it.
         */
        public Builder kickInterval(Duration kickInterval) {
            this.kickInterval = requirePositive(kickInterval, "kickInterval");
            return this;
        }

        /**
         * Exponential backoff multiplier applied on each empty poll (default: 3.0). With the
         * default 1s minimum and 30s maximum that is 1s → 3s → 9s → 27s → 30s. Higher multipliers
         * reach the maximum sooner and cut idle poll volume; {@link #kick()} is the safety net that
         * gets back to fast polling when a result is expected.
         */
        public Builder backoffMultiplier(double backoffMultiplier) {
            if (backoffMultiplier <= 1.0) {
                throw new IllegalArgumentException("backoffMultiplier must be > 1.0");
            }
            this.backoffMultiplier = backoffMultiplier;
            return this;
        }

        /** Called for each result as it streams in. Throwing leaves the batch unacknowledged from that point. */
        public Builder handler(Consumer<GenerationResult> handler) {
            this.handler = handler;
            return this;
        }

        /** Optional handler for collection failures; the loop backs off and retries either way. */
        public Builder errorHandler(Consumer<Exception> errorHandler) {
            this.errorHandler = errorHandler;
            return this;
        }

        /** Optional metrics listener. */
        public Builder metricsListener(MetricsListener metricsListener) {
            this.metricsListener = metricsListener;
            return this;
        }

        /**
         * Registers a JVM shutdown hook that stops polling gracefully (default: true). Turn it off
         * inside an application server, where the container's lifecycle callbacks own shutdown.
         */
        public Builder registerShutdownHook(boolean registerShutdownHook) {
            this.registerShutdownHook = registerShutdownHook;
            return this;
        }

        public ResultCollector build() {
            if (collectApi == null) {
                throw new IllegalStateException("collectApi is required");
            }
            if (tenantId == null) {
                throw new IllegalStateException("tenantId is required");
            }
            if (handler == null) {
                throw new IllegalStateException("handler is required");
            }
            return new ResultCollector(this);
        }

        private static Duration requirePositive(Duration value, String name) {
            if (value == null || value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }
    }
}
