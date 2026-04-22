package app.epistola.client.tracker

import app.epistola.client.api.TrackersApi
import app.epistola.client.model.EventEnvelope
import app.epistola.client.model.PollTrackerRequest
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages the poll loop for an event tracker, handling acknowledgment, backpressure,
 * and catch-up mode.
 *
 * Example:
 * ```
 * val poller = TrackerPoller.builder()
 *     .trackersApi(trackersApi)
 *     .tenantId("acme-corp")
 *     .trackerId("invoice-processor")
 *     .pollInterval(Duration.ofSeconds(10))
 *     .batchSize(100)
 *     .handler { events ->
 *         events.forEach { println("${it.type}: ${it.resourceRef.resourceId}") }
 *     }
 *     .build()
 *
 * // Run the poll loop (blocks current thread)
 * poller.start()
 * ```
 */
class TrackerPoller private constructor(
    private val trackersApi: TrackersApi,
    private val tenantId: String,
    private val trackerId: String,
    private val pollInterval: Duration,
    private val batchSize: Int,
    private val handler: (List<EventEnvelope>) -> Unit,
    private val errorHandler: ((Exception) -> Unit)?,
) {
    private val running = AtomicBoolean(false)
    private var lastAcknowledged: Long? = null

    companion object {
        fun builder(): Builder = Builder()
    }

    /**
     * Result of a single poll operation.
     */
    data class PollResult(
        val events: List<EventEnvelope>,
        val sequenceNumber: Long,
        val hasMore: Boolean,
    )

    /**
     * Starts the poll loop. Blocks the current thread until [stop] is called.
     *
     * The loop:
     * 1. Polls for events (acknowledging the previous batch)
     * 2. Calls the handler with the received events
     * 3. If `hasMore` is true, polls again immediately
     * 4. If `hasMore` is false, waits for [pollInterval] before the next poll
     *
     * If the handler throws an exception, the events are NOT acknowledged and
     * will be re-delivered on the next poll.
     */
    fun start() {
        running.set(true)
        while (running.get()) {
            try {
                val result = pollOnce()
                if (!running.get()) break

                if (result.events.isEmpty() || !result.hasMore) {
                    Thread.sleep(pollInterval.toMillis())
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                if (errorHandler != null) {
                    errorHandler.invoke(e)
                }
                // Wait before retrying after an error
                try {
                    Thread.sleep(pollInterval.toMillis())
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
    }

    /**
     * Signals the poll loop to stop. The loop exits after the current poll completes.
     */
    fun stop() {
        running.set(false)
    }

    /**
     * Performs a single poll: acknowledges the previous batch and fetches the next one.
     * Call this directly if you want to manage the polling schedule yourself.
     *
     * If the handler processes the events successfully, they will be acknowledged
     * on the next call to [pollOnce]. If the handler throws, they will NOT be
     * acknowledged and will be re-delivered.
     */
    fun pollOnce(): PollResult {
        val request = PollTrackerRequest(
            acknowledgeUpTo = lastAcknowledged,
            limit = batchSize,
        )

        val response = trackersApi.pollTrackerUpdates(
            tenantId = tenantId,
            trackerId = trackerId,
            pollTrackerRequest = request,
        )

        if (response.events.isNotEmpty()) {
            // Call handler — if it throws, we don't update lastAcknowledged
            handler(response.events)
        }

        // Handler succeeded, mark for acknowledgment on next poll
        lastAcknowledged = response.cursor.sequenceNumber

        return PollResult(
            events = response.events,
            sequenceNumber = response.cursor.sequenceNumber,
            hasMore = response.cursor.hasMore,
        )
    }

    class Builder {
        private var trackersApi: TrackersApi? = null
        private var tenantId: String? = null
        private var trackerId: String? = null
        private var pollInterval: Duration = Duration.ofSeconds(10)
        private var batchSize: Int = 100
        private var handler: ((List<EventEnvelope>) -> Unit)? = null
        private var errorHandler: ((Exception) -> Unit)? = null

        fun trackersApi(api: TrackersApi) = apply { this.trackersApi = api }
        fun tenantId(tenantId: String) = apply { this.tenantId = tenantId }
        fun trackerId(trackerId: String) = apply { this.trackerId = trackerId }

        /** Poll interval when caught up (default: 10 seconds). */
        fun pollInterval(interval: Duration) = apply {
            require(!interval.isNegative && !interval.isZero) { "pollInterval must be positive" }
            this.pollInterval = interval
        }

        /** Maximum events per poll (default: 100, max: 1000). */
        fun batchSize(size: Int) = apply {
            require(size in 1..1000) { "batchSize must be between 1 and 1000" }
            this.batchSize = size
        }

        /** Handler called with each batch of events. */
        fun handler(handler: (List<EventEnvelope>) -> Unit) = apply { this.handler = handler }

        /** Optional error handler for poll failures. */
        fun errorHandler(handler: (Exception) -> Unit) = apply { this.errorHandler = handler }

        @Suppress("ktlint:standard:function-signature")
        fun build(): TrackerPoller =
            TrackerPoller(
                trackersApi = requireNotNull(trackersApi) { "trackersApi is required" },
                tenantId = requireNotNull(tenantId) { "tenantId is required" },
                trackerId = requireNotNull(trackerId) { "trackerId is required" },
                pollInterval = pollInterval,
                batchSize = batchSize,
                handler = requireNotNull(handler) { "handler is required" },
                errorHandler = errorHandler,
            )
    }
}
