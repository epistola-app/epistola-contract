// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.epistola.client.jakarta.EpistolaRestClients;
import app.epistola.client.jakarta.StubServer;
import app.epistola.client.jakarta.model.GenerationResult;
import app.epistola.client.jakarta.model.PartitionAssignment;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Drives the collector against a stub server through a real MicroProfile Rest Client, because the
 * things that go wrong here — the acknowledgement cursor, redelivery after a handler failure,
 * decompression, the trailing meta line — are all properties of what actually crosses the wire.
 */
class ResultCollectorTest {

    private static final String NDJSON = "application/vnd.epistola.v1+ndjson";

    private static String result(long sequence, String status, String correlationId) {
        return "{\"sequence\":" + sequence
                + ",\"requestId\":\"11111111-1111-1111-1111-11111111111" + (sequence % 10) + "\""
                + ",\"status\":\"" + status + "\""
                + ",\"correlationId\":\"" + correlationId + "\"}";
    }

    private static String meta(boolean hasMore) {
        return "{\"_meta\":true,\"hasMore\":" + hasMore + ",\"count\":0}";
    }

    private static String metaWithPartitions(boolean hasMore, String mine, int total) {
        return "{\"_meta\":true,\"hasMore\":" + hasMore
                + ",\"partitions\":{\"total\":" + total + ",\"mine\":" + mine + ",\"hash\":\"murmur3\"}}";
    }

    // --- Streaming and handler dispatch ---

    @Test
    void every_result_line_reaches_the_handler_and_the_meta_line_does_not() {
        List<GenerationResult> handled = new ArrayList<>();
        String body = String.join(
                "\n",
                result(501, "COMPLETED", "order-1"),
                result(502, "FAILED", "order-2"),
                meta(false));

        try (StubServer stub = StubServer.start(request -> StubServer.StubResponse.of(200, NDJSON, body))) {
            ResultCollector.CollectResult outcome = collector(stub, handled::add).collectOnce();

            assertEquals(2, outcome.getCount());
            assertFalse(outcome.isHasMore());
            assertEquals(2, handled.size());
            assertEquals(501L, handled.get(0).getSequence());
            assertEquals(GenerationResult.StatusEnum.COMPLETED, handled.get(0).getStatus());
            assertEquals("order-2", handled.get(1).getCorrelationId());
            assertEquals(GenerationResult.StatusEnum.FAILED, handled.get(1).getStatus());
        }
    }

    @Test
    void an_empty_batch_reports_no_results() {
        try (StubServer stub =
                StubServer.start(request -> StubServer.StubResponse.of(200, NDJSON, meta(false)))) {
            ResultCollector.CollectResult outcome = collector(stub, r -> {}).collectOnce();

            assertEquals(0, outcome.getCount());
            assertFalse(outcome.isHasMore());
        }
    }

    @Test
    void has_more_is_carried_out_of_the_meta_line() {
        try (StubServer stub = StubServer.start(request ->
                StubServer.StubResponse.of(200, NDJSON, result(1, "COMPLETED", "a") + "\n" + meta(true)))) {
            assertTrue(collector(stub, r -> {}).collectOnce().isHasMore());
        }
    }

    @Test
    void blank_lines_are_skipped() {
        List<GenerationResult> handled = new ArrayList<>();
        String body = result(1, "COMPLETED", "a") + "\n\n\n" + result(2, "COMPLETED", "b") + "\n" + meta(false);

        try (StubServer stub = StubServer.start(request -> StubServer.StubResponse.of(200, NDJSON, body))) {
            assertEquals(2, collector(stub, handled::add).collectOnce().getCount());
        }
    }

    // --- Acknowledgement cursor ---

    @Test
    void the_first_poll_acknowledges_nothing_and_the_next_acknowledges_the_last_sequence() {
        Deque<String> bodies = new ArrayDeque<>(List.of(
                result(501, "COMPLETED", "a") + "\n" + result(502, "COMPLETED", "b") + "\n" + meta(false),
                result(503, "COMPLETED", "c") + "\n" + meta(false)));

        try (StubServer stub = StubServer.start(request ->
                StubServer.StubResponse.of(200, NDJSON, bodies.poll()))) {
            ResultCollector collector = collector(stub, r -> {});
            collector.collectOnce();
            collector.collectOnce();

            assertFalse(
                    stub.requests().get(0).body().contains("acknowledgeUpTo"),
                    "there is nothing to acknowledge on the first poll");
            assertTrue(
                    stub.requests().get(1).body().contains("\"acknowledgeUpTo\":502"),
                    "the second poll should acknowledge the last handled sequence, was: "
                            + stub.requests().get(1).body());
        }
    }

    @Test
    void a_batch_whose_handler_threw_is_redelivered_rather_than_acknowledged() {
        List<Long> handled = new ArrayList<>();
        Deque<String> bodies = new ArrayDeque<>(List.of(
                result(501, "COMPLETED", "a") + "\n" + result(502, "COMPLETED", "b") + "\n" + meta(false),
                result(501, "COMPLETED", "a") + "\n" + meta(false)));

        try (StubServer stub = StubServer.start(request ->
                StubServer.StubResponse.of(200, NDJSON, bodies.poll()))) {
            ResultCollector collector = collector(stub, r -> {
                handled.add(r.getSequence());
                if (r.getSequence() == 502L) {
                    throw new IllegalStateException("downstream write failed");
                }
            });

            assertThrows(IllegalStateException.class, collector::collectOnce);
            collector.collectOnce();

            assertEquals(List.of(501L, 502L, 501L), handled);
            assertFalse(
                    stub.requests().get(1).body().contains("acknowledgeUpTo"),
                    "a failed batch must not advance the cursor, or those results are lost");
        }
    }

    @Test
    void the_batch_size_is_sent_as_the_limit() {
        try (StubServer stub =
                StubServer.start(request -> StubServer.StubResponse.of(200, NDJSON, meta(false)))) {
            ResultCollector.builder()
                    .collectApi(collectApi(stub))
                    .tenantId("acme-corp")
                    .batchSize(250)
                    .handler(r -> {})
                    .registerShutdownHook(false)
                    .build()
                    .collectOnce();

            assertTrue(stub.onlyRequest().body().contains("\"limit\":250"), stub.onlyRequest().body());
        }
    }

    @Test
    void the_request_targets_the_tenant_scoped_collect_path_with_the_vendor_media_type() {
        try (StubServer stub =
                StubServer.start(request -> StubServer.StubResponse.of(200, NDJSON, meta(false)))) {
            collector(stub, r -> {}).collectOnce();

            StubServer.RecordedRequest request = stub.onlyRequest();
            assertEquals("POST", request.method());
            assertEquals("/api/tenants/acme-corp/generation/collect", request.path());
            assertEquals("application/vnd.epistola.v1+json", request.header("Content-Type"));
            assertTrue(request.header("Accept").contains(NDJSON), request.header("Accept"));
            assertTrue(
                    request.header("Accept-Encoding").contains("gzip"),
                    "gzip is always offered: " + request.header("Accept-Encoding"));
        }
    }

    // --- Compression ---

    @Test
    void a_gzip_encoded_body_is_decompressed() {
        List<GenerationResult> handled = new ArrayList<>();
        byte[] body = gzip(result(1, "COMPLETED", "a") + "\n" + result(2, "COMPLETED", "b") + "\n" + meta(false));

        try (StubServer stub = StubServer.start(request ->
                StubServer.StubResponse.of(200, NDJSON, body).contentEncoding("gzip"))) {
            assertEquals(2, collector(stub, handled::add).collectOnce().getCount());
            assertEquals("a", handled.get(0).getCorrelationId());
        }
    }

    @Test
    void a_gzip_body_sent_without_the_header_is_still_decompressed() {
        // The magic bytes are trusted over the header on purpose — an application server may have
        // decoded the body already, or forwarded it without re-declaring the encoding.
        List<GenerationResult> handled = new ArrayList<>();
        byte[] body = gzip(result(1, "COMPLETED", "a") + "\n" + meta(false));

        try (StubServer stub = StubServer.start(request -> StubServer.StubResponse.of(200, NDJSON, body))) {
            assertEquals(1, collector(stub, handled::add).collectOnce().getCount());
        }
    }

    // --- Partition routing ---

    @Test
    void the_partition_assignment_is_taken_from_the_meta_line() {
        try (StubServer stub = StubServer.start(request ->
                StubServer.StubResponse.of(200, NDJSON, metaWithPartitions(false, "[0,3]", 8)))) {
            ResultCollector collector = collector(stub, r -> {});
            assertNull(collector.getPartitionAssignment(), "unknown until the first poll");

            collector.collectOnce();

            PartitionAssignment assignment = collector.getPartitionAssignment();
            assertNotNull(assignment);
            assertEquals(8, assignment.getTotal());
            assertEquals(List.of(0, 3), assignment.getMine());
        }
    }

    @Test
    void partition_helpers_return_null_until_an_assignment_is_known() {
        try (StubServer stub =
                StubServer.start(request -> StubServer.StubResponse.of(200, NDJSON, meta(false)))) {
            ResultCollector collector = collector(stub, r -> {});

            assertNull(collector.partitionFor("order-1"));
            assertFalse(collector.isMyPartition("order-1"));
            assertNull(collector.routingKeyToMe("order-1"));
        }
    }

    @Test
    void a_routing_key_hashes_into_range_and_agrees_with_is_my_partition() {
        try (StubServer stub = StubServer.start(request ->
                StubServer.StubResponse.of(200, NDJSON, metaWithPartitions(false, "[0,3]", 8)))) {
            ResultCollector collector = collector(stub, r -> {});
            collector.collectOnce();

            for (int i = 0; i < 200; i++) {
                String key = "order-" + i;
                Integer partition = collector.partitionFor(key);
                assertNotNull(partition);
                assertTrue(partition >= 0 && partition < 8, "partition out of range: " + partition);
                assertEquals(List.of(0, 3).contains(partition), collector.isMyPartition(key));
            }
        }
    }

    @Test
    void routing_key_to_me_always_produces_a_key_that_lands_here() {
        try (StubServer stub = StubServer.start(request ->
                StubServer.StubResponse.of(200, NDJSON, metaWithPartitions(false, "[0,3]", 8)))) {
            ResultCollector collector = collector(stub, r -> {});
            collector.collectOnce();

            int rewritten = 0;
            for (int i = 0; i < 100; i++) {
                String key = "order-" + i;
                String routed = collector.routingKeyToMe(key);
                assertNotNull(routed);
                assertTrue(collector.isMyPartition(routed), "routingKeyToMe produced a foreign key: " + routed);
                if (!routed.equals(key)) {
                    rewritten++;
                }
            }
            assertTrue(rewritten > 0, "with 2 of 8 partitions, most keys should need rewriting");
        }
    }

    // --- Metrics, loop control and validation ---

    @Test
    void the_metrics_listener_sees_polls_and_partition_changes() {
        List<String> events = new CopyOnWriteArrayList<>();
        Deque<String> bodies = new ArrayDeque<>(List.of(
                result(1, "COMPLETED", "a") + "\n" + metaWithPartitions(true, "[0]", 4),
                metaWithPartitions(false, "[0,1]", 4)));

        try (StubServer stub = StubServer.start(request ->
                StubServer.StubResponse.of(200, NDJSON, bodies.poll()))) {
            ResultCollector collector = ResultCollector.builder()
                    .collectApi(collectApi(stub))
                    .tenantId("acme-corp")
                    .handler(r -> {})
                    .registerShutdownHook(false)
                    .metricsListener(new ResultCollector.MetricsListener() {
                        @Override
                        public void onPoll(int count, boolean hasMore, long durationMs, Exception error) {
                            events.add("poll:" + count + ":" + hasMore + ":" + (error != null));
                        }

                        @Override
                        public void onPartitionChange(PartitionAssignment before, PartitionAssignment after) {
                            events.add("partitions:" + (before == null ? "-" : before.getMine()) + "->" + after.getMine());
                        }
                    })
                    .build();

            collector.collectOnce();
            collector.collectOnce();

            assertEquals(
                    List.of("partitions:-->[0]", "poll:1:true:false", "partitions:[0]->[0, 1]", "poll:0:false:false"),
                    events);
        }
    }

    @Test
    void a_failing_poll_is_reported_to_the_metrics_listener_and_rethrown() {
        List<String> events = new ArrayList<>();

        try (StubServer stub = StubServer.start(request ->
                StubServer.StubResponse.of(500, "text/plain", "boom"))) {
            ResultCollector collector = ResultCollector.builder()
                    .collectApi(collectApi(stub))
                    .tenantId("acme-corp")
                    .handler(r -> {})
                    .registerShutdownHook(false)
                    .metricsListener(new ResultCollector.MetricsListener() {
                        @Override
                        public void onPoll(int count, boolean hasMore, long durationMs, Exception error) {
                            events.add("poll:error=" + (error != null));
                        }

                        @Override
                        public void onPartitionChange(PartitionAssignment before, PartitionAssignment after) {
                            events.add("partitions");
                        }
                    })
                    .build();

            assertThrows(RuntimeException.class, collector::collectOnce);
            assertEquals(List.of("poll:error=true"), events);
        }
    }

    @Test
    void the_poll_loop_stops_promptly_when_asked_to() throws Exception {
        CountDownLatch polled = new CountDownLatch(1);

        try (StubServer stub = StubServer.start(request -> {
            polled.countDown();
            return StubServer.StubResponse.of(200, NDJSON, meta(false));
        })) {
            ResultCollector collector = ResultCollector.builder()
                    .collectApi(collectApi(stub))
                    .tenantId("acme-corp")
                    .handler(r -> {})
                    .registerShutdownHook(false)
                    // A 30s backoff after the first empty poll: stop() has to cut it short, or
                    // the loop would outlive this test by half a minute.
                    .minInterval(Duration.ofSeconds(30))
                    .maxInterval(Duration.ofSeconds(30))
                    .build();

            Thread loop = new Thread(collector::start, "collector-loop");
            loop.start();

            assertTrue(polled.await(5, TimeUnit.SECONDS), "the loop should have polled at least once");
            collector.stop();
            loop.join(5_000);
            assertFalse(loop.isAlive(), "stop() should end the loop without waiting out the backoff");
        }
    }

    @Test
    void the_interval_recovers_from_a_has_more_burst_instead_of_polling_flat_out() throws Exception {
        // hasMore sets the interval to 0 so the next poll is immediate. Once the queue drains, the
        // backoff has to climb again — and `0 * multiplier` is still 0, so without a floor the next
        // request goes out with zero delay, forever, bounded only by round-trip time. Counting
        // polls over a window is what catches it.
        AtomicInteger polls = new AtomicInteger();
        AtomicBoolean drained = new AtomicBoolean(false);

        try (StubServer stub = StubServer.start(request -> {
            polls.incrementAndGet();
            // One burst, then nothing: hasMore first, empty from then on.
            if (drained.compareAndSet(false, true)) {
                return StubServer.StubResponse.of(200, NDJSON, result(1, "COMPLETED", "a") + "\n" + meta(true));
            }
            return StubServer.StubResponse.of(200, NDJSON, meta(false));
        })) {
            ResultCollector collector = ResultCollector.builder()
                    .collectApi(collectApi(stub))
                    .tenantId("acme-corp")
                    .handler(r -> {})
                    .registerShutdownHook(false)
                    .minInterval(Duration.ofMillis(200))
                    .maxInterval(Duration.ofSeconds(5))
                    .build();

            Thread loop = new Thread(collector::start, "collector-loop");
            loop.start();
            try {
                Thread.sleep(600);
            } finally {
                collector.stop();
                loop.join(5_000);
            }

            // With a 200ms floor and a 3x multiplier: burst, then ~200ms, ~600ms — a handful of
            // polls. Without the floor this runs into the thousands.
            assertTrue(polls.get() < 20, "expected the loop to back off, but it polled " + polls.get() + " times");
        }
    }

    @Test
    void the_builder_requires_what_it_cannot_default() {
        assertThrows(IllegalStateException.class, () -> ResultCollector.builder().build());
        assertThrows(
                IllegalStateException.class,
                () -> ResultCollector.builder().tenantId("acme-corp").handler(r -> {}).build());
        assertThrows(IllegalArgumentException.class, () -> ResultCollector.builder().batchSize(0));
        assertThrows(IllegalArgumentException.class, () -> ResultCollector.builder().batchSize(10001));
        assertThrows(IllegalArgumentException.class, () -> ResultCollector.builder().backoffMultiplier(1.0));
        assertThrows(IllegalArgumentException.class, () -> ResultCollector.builder().minInterval(Duration.ZERO));
    }

    private static ResultCollector collector(StubServer stub, java.util.function.Consumer<GenerationResult> handler) {
        return ResultCollector.builder()
                .collectApi(collectApi(stub))
                .tenantId("acme-corp")
                .handler(handler)
                .registerShutdownHook(false)
                .build();
    }

    private static GenerationCollectApi collectApi(StubServer stub) {
        return EpistolaRestClients.builder().baseUri(stub.baseUri()).build().api(GenerationCollectApi.class);
    }

    private static byte[] gzip(String content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
