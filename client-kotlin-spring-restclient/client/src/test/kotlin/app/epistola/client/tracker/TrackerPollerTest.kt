package app.epistola.client.tracker

import app.epistola.client.api.TrackersApi
import app.epistola.client.model.EventEnvelope
import app.epistola.client.model.PollTrackerRequest
import app.epistola.client.model.PollTrackerResponse
import app.epistola.client.model.PollTrackerResponseCursor
import app.epistola.client.model.ResourceRef
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackerPollerTest {

    private val trackersApi = mockk<TrackersApi>()

    private fun event(seq: Long): EventEnvelope = EventEnvelope(
        id = java.util.UUID.randomUUID(),
        type = EventEnvelope.Type.GENERATION_PERIOD_COMPLETED,
        tenantId = "acme-corp",
        timestamp = java.time.OffsetDateTime.now(),
        sequenceNumber = seq,
        resourceRef = ResourceRef(
            resourceType = ResourceRef.ResourceType.GENERATION_JOB,
            resourceId = "job-$seq",
        ),
    )

    private fun response(
        events: List<EventEnvelope>,
        seq: Long,
        hasMore: Boolean,
    ) = PollTrackerResponse(
        events = events,
        cursor = PollTrackerResponseCursor(sequenceNumber = seq, hasMore = hasMore),
    )

    @Test
    fun `first poll does not acknowledge`() {
        val requestSlot = slot<PollTrackerRequest>()
        every {
            trackersApi.pollTrackerUpdates("acme-corp", "my-tracker", capture(requestSlot))
        } returns response(listOf(event(100)), 100, false)

        val handled = mutableListOf<EventEnvelope>()
        val poller = TrackerPoller.builder()
            .trackersApi(trackersApi)
            .tenantId("acme-corp")
            .trackerId("my-tracker")
            .handler { handled.addAll(it) }
            .build()

        poller.pollOnce()

        assertNull(requestSlot.captured.acknowledgeUpTo)
        assertEquals(1, handled.size)
    }

    @Test
    fun `second poll acknowledges previous batch`() {
        val requests = mutableListOf<PollTrackerRequest>()
        every {
            trackersApi.pollTrackerUpdates("acme-corp", "my-tracker", capture(requests))
        } returns response(listOf(event(100)), 100, false) andThen
            response(listOf(event(101)), 101, false)

        val poller = TrackerPoller.builder()
            .trackersApi(trackersApi)
            .tenantId("acme-corp")
            .trackerId("my-tracker")
            .handler { }
            .build()

        poller.pollOnce()
        poller.pollOnce()

        assertNull(requests[0].acknowledgeUpTo)
        assertEquals(100, requests[1].acknowledgeUpTo)
    }

    @Test
    fun `does not acknowledge when handler throws`() {
        val requests = mutableListOf<PollTrackerRequest>()
        var callCount = 0
        every {
            trackersApi.pollTrackerUpdates("acme-corp", "my-tracker", capture(requests))
        } returns response(listOf(event(100)), 100, false)

        val poller = TrackerPoller.builder()
            .trackersApi(trackersApi)
            .tenantId("acme-corp")
            .trackerId("my-tracker")
            .handler {
                callCount++
                if (callCount == 1) throw RuntimeException("processing failed")
            }
            .build()

        // First poll: handler throws
        assertFailsWith<RuntimeException> { poller.pollOnce() }

        // Second poll: should NOT acknowledge (handler failed)
        poller.pollOnce()

        assertNull(requests[0].acknowledgeUpTo)
        assertNull(requests[1].acknowledgeUpTo)
    }

    @Test
    fun `pollOnce returns correct result`() {
        val events = listOf(event(100), event(101))
        every {
            trackersApi.pollTrackerUpdates("acme-corp", "my-tracker", any())
        } returns response(events, 101, true)

        val poller = TrackerPoller.builder()
            .trackersApi(trackersApi)
            .tenantId("acme-corp")
            .trackerId("my-tracker")
            .handler { }
            .build()

        val result = poller.pollOnce()

        assertEquals(2, result.events.size)
        assertEquals(101, result.sequenceNumber)
        assertTrue(result.hasMore)
    }

    @Test
    fun `uses configured batch size`() {
        val requestSlot = slot<PollTrackerRequest>()
        every {
            trackersApi.pollTrackerUpdates("acme-corp", "my-tracker", capture(requestSlot))
        } returns response(emptyList(), 0, false)

        val poller = TrackerPoller.builder()
            .trackersApi(trackersApi)
            .tenantId("acme-corp")
            .trackerId("my-tracker")
            .batchSize(50)
            .handler { }
            .build()

        poller.pollOnce()

        assertEquals(50, requestSlot.captured.limit)
    }

    @Test
    fun `builder rejects invalid batch size`() {
        assertFailsWith<IllegalArgumentException> {
            TrackerPoller.builder().batchSize(0)
        }
        assertFailsWith<IllegalArgumentException> {
            TrackerPoller.builder().batchSize(1001)
        }
    }

    @Test
    fun `builder rejects zero poll interval`() {
        assertFailsWith<IllegalArgumentException> {
            TrackerPoller.builder().pollInterval(Duration.ZERO)
        }
    }

    @Test
    fun `builder requires trackersApi`() {
        assertFailsWith<IllegalArgumentException> {
            TrackerPoller.builder()
                .tenantId("acme-corp")
                .trackerId("my-tracker")
                .handler { }
                .build()
        }
    }

    @Test
    fun `builder requires handler`() {
        assertFailsWith<IllegalArgumentException> {
            TrackerPoller.builder()
                .trackersApi(trackersApi)
                .tenantId("acme-corp")
                .trackerId("my-tracker")
                .build()
        }
    }

    @Test
    fun `stop terminates the poll loop`() {
        var pollCount = 0
        every {
            trackersApi.pollTrackerUpdates("acme-corp", "my-tracker", any())
        } answers {
            pollCount++
            response(emptyList(), 0, false)
        }

        val poller = TrackerPoller.builder()
            .trackersApi(trackersApi)
            .tenantId("acme-corp")
            .trackerId("my-tracker")
            .pollInterval(Duration.ofMillis(50))
            .handler { }
            .build()

        // Stop after a short delay
        val thread = Thread { poller.start() }
        thread.start()
        Thread.sleep(200)
        poller.stop()
        thread.join(1000)

        assertTrue(pollCount >= 1)
        assertTrue(!thread.isAlive)
    }
}
