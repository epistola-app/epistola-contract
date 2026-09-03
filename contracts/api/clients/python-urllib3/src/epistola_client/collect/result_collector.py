# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""Collects generation results via the ``/generation/collect`` endpoint with NDJSON
streaming, compression, and adaptive polling.

Results are processed one at a time — the response is never loaded into memory. Results
from your node are returned first; orphaned results from dead nodes follow.

Features: NDJSON streaming (constant memory); compression (gzip built-in, lz4/zstd
auto-detected when the libraries are present); adaptive polling (immediate on hasMore,
exponential backoff when idle); sequence-based acknowledgment; partition-aware
routing-key helpers; metrics via :class:`MetricsListener`; thread-safe
:meth:`ResultCollector.collect_once`; and a process-exit hook for graceful stop.

The collector is driven by an :class:`~epistola_client.http.client.EpistolaApiClient` so
base URL, identity, and JWT auth are reused from the same client passed to the API classes.
"""

from __future__ import annotations

import atexit
import gzip
import io
import json
import random
import threading
import time
from dataclasses import dataclass
from typing import Callable, List, Optional, Protocol

_VENDOR_JSON = "application/vnd.epistola.v1+json"
_NDJSON = "application/vnd.epistola.v1+ndjson"

#: How many prefixes :meth:`ResultCollector.routing_key_to_me` tries before giving up. Reaching
#: this means every one of a thousand hashes missed every partition this node owns.
MAX_ROUTING_KEY_ATTEMPTS = 1000


@dataclass(frozen=True)
class GenerationResult:
    """A completed or failed generation result."""

    sequence: int
    request_id: str
    batch_id: Optional[str]
    status: str
    document_id: Optional[str]
    correlation_id: Optional[str]
    routing_key: Optional[str]
    template_id: Optional[str]
    variant_id: Optional[str]
    version_id: Optional[int]
    filename: Optional[str]
    content_type: Optional[str]
    size_bytes: Optional[int]
    error: Optional[str]
    completed_at: Optional[str]


@dataclass(frozen=True)
class CollectResult:
    """The outcome of one collection call."""

    count: int
    has_more: bool


@dataclass(frozen=True)
class PartitionAssignment:
    """Partition assignment info from the server."""

    total: int
    mine: List[int]
    hash: str = "murmur3"


class MetricsListener(Protocol):
    """Callback interface for observability."""

    def on_poll(self, count: int, has_more: bool, duration_ms: float, error: Optional[BaseException]) -> None:
        """Called after each poll completes."""
        ...

    def on_partition_change(
        self, old_assignment: Optional[PartitionAssignment], new_assignment: PartitionAssignment
    ) -> None:
        """Called when the partition assignment changes."""
        ...


def _murmur3_x86_32(data: bytes, seed: int = 0) -> int:
    """MurmurHash3 x86 32-bit with configurable seed (matches the server's Guava impl)."""
    c1 = 0xCC9E2D51
    c2 = 0x1B873593
    length = len(data)
    h1 = seed & 0xFFFFFFFF
    rounded_end = length & ~0x3

    for i in range(0, rounded_end, 4):
        k1 = (data[i] | (data[i + 1] << 8) | (data[i + 2] << 16) | (data[i + 3] << 24)) & 0xFFFFFFFF
        k1 = (k1 * c1) & 0xFFFFFFFF
        k1 = ((k1 << 15) | (k1 >> 17)) & 0xFFFFFFFF
        k1 = (k1 * c2) & 0xFFFFFFFF
        h1 ^= k1
        h1 = ((h1 << 13) | (h1 >> 19)) & 0xFFFFFFFF
        h1 = (h1 * 5 + 0xE6546B64) & 0xFFFFFFFF

    k1 = 0
    tail = rounded_end
    rem = length & 0x3
    if rem == 3:
        k1 ^= data[tail + 2] << 16
    if rem >= 2:
        k1 ^= data[tail + 1] << 8
    if rem >= 1:
        k1 ^= data[tail]
        k1 = (k1 * c1) & 0xFFFFFFFF
        k1 = ((k1 << 15) | (k1 >> 17)) & 0xFFFFFFFF
        k1 = (k1 * c2) & 0xFFFFFFFF
        h1 ^= k1

    h1 ^= length
    h1 ^= h1 >> 16
    h1 = (h1 * 0x85EBCA6B) & 0xFFFFFFFF
    h1 ^= h1 >> 13
    h1 = (h1 * 0xC2B2AE35) & 0xFFFFFFFF
    h1 ^= h1 >> 16
    return h1  # unsigned 32-bit


def _try_load_lz4() -> Optional[Callable]:
    try:
        import lz4.frame  # type: ignore

        return lambda fileobj: lz4.frame.LZ4FrameFile(fileobj, mode="rb")
    except Exception:
        return None


def _try_load_zstd() -> Optional[Callable]:
    try:
        import zstandard  # type: ignore

        return lambda fileobj: zstandard.ZstdDecompressor().stream_reader(fileobj)
    except Exception:
        return None


_LZ4_DECOMPRESSOR = _try_load_lz4()
_ZSTD_DECOMPRESSOR = _try_load_zstd()


class ResultCollector:
    """Adaptive-polling collector for the ``/generation/collect`` NDJSON endpoint."""

    def __init__(
        self,
        api_client,
        tenant_id: str,
        batch_size: int,
        min_interval: float,
        max_interval: float,
        kick_interval: float,
        backoff_multiplier: float,
        handler: Callable[[GenerationResult], None],
        error_handler: Optional[Callable[[BaseException], None]],
        metrics_listener: Optional[MetricsListener],
        register_shutdown_hook: bool,
    ) -> None:
        self._api_client = api_client
        self._tenant_id = tenant_id
        self._batch_size = batch_size
        self._min_interval = min_interval
        self._max_interval = max_interval
        self._kick_interval = kick_interval
        self._backoff_multiplier = backoff_multiplier
        self._handler = handler
        self._error_handler = error_handler
        self._metrics_listener = metrics_listener
        self._register_shutdown_hook = register_shutdown_hook

        self._running = False
        self._current_interval = min_interval
        self._last_acknowledged_sequence: Optional[int] = None
        self._poll_lock = threading.Lock()
        self._wake = threading.Event()
        self._shutdown_hook_registered = False

        #: Current partition assignment, updated on each poll from the ``_meta`` line.
        self.current_partition_assignment: Optional[PartitionAssignment] = None

    @staticmethod
    def builder() -> "ResultCollectorBuilder":
        """Create a new :class:`ResultCollectorBuilder`."""
        return ResultCollectorBuilder()

    # --- Partition routing helpers ---

    def partition_for(self, routing_key: str) -> Optional[int]:
        """Compute the partition number for a routing key using the server's hash
        (murmur3 x86 32-bit, seed 0). Returns ``None`` if the assignment is not yet known.
        """
        assignment = self.current_partition_assignment
        # A zero partition count would be a ZeroDivisionError, not a "no partition" answer.
        if assignment is None or not assignment.total:
            return None
        h = _murmur3_x86_32(routing_key.encode("utf-8"), 0)
        return (h & 0x7FFFFFFF) % assignment.total

    def is_my_partition(self, routing_key: str) -> bool:
        """Check whether a routing key would land on one of this node's partitions."""
        partition = self.partition_for(routing_key)
        if partition is None:
            return False
        assignment = self.current_partition_assignment
        return assignment is not None and partition in assignment.mine

    def routing_key_to_me(self, key: str) -> Optional[str]:
        """Return a routing key that targets one of this node's partitions.

        ``key`` unchanged when it already routes here; otherwise numbered prefixes
        (``"0:key"``, ``"1:key"``, ...) are searched for one that does. The search is
        deterministic, so the same key always yields the same routed key. ``None`` when the
        assignment is not yet known, or in the vanishingly unlikely event that no prefix within
        ``MAX_ROUTING_KEY_ATTEMPTS`` lands here.

        The prefix is what the server hashes, so a rewritten key is a different key: pass the
        value returned here as the request's ``routingKey``, and expect it back on the result.
        """
        assignment = self.current_partition_assignment
        if assignment is None or not assignment.total or not assignment.mine:
            return None
        if self.is_my_partition(key):
            return key
        # Trying only the partition numbers this node owns is not enough: "3:key" hashes to
        # wherever it hashes, not to partition 3. Only checking the hash of each candidate can
        # tell us, so keep trying prefixes until one lands. With p of n partitions owned, each
        # attempt succeeds with probability p/n, so this converges in a handful of iterations.
        for attempt in range(MAX_ROUTING_KEY_ATTEMPTS):
            candidate = f"{attempt}:{key}"
            if self.is_my_partition(candidate):
                return candidate
        return None

    def _back_off(self, interval: float) -> float:
        """The next idle interval, floored at ``min_interval`` and capped at ``max_interval``.

        The floor is not cosmetic. A poll reporting ``hasMore`` sets the interval to 0 so the next
        one is immediate, and ``0 * multiplier`` is still 0 — without the floor, a burst that
        drained (or a server that went down mid-burst) would leave the loop polling
        ``/generation/collect`` flat out, with no path back to a sane interval.
        """
        grown = interval * self._backoff_multiplier
        return min(max(grown, self._min_interval), self._max_interval)

    # --- Poll loop ---

    def start(self) -> None:
        """Start the adaptive poll loop, blocking the current thread until :meth:`stop`."""
        self._running = True
        self._current_interval = self._min_interval

        if self._register_shutdown_hook and not self._shutdown_hook_registered:
            atexit.register(self.stop)
            self._shutdown_hook_registered = True

        try:
            while self._running:
                try:
                    result = self.collect_once()
                    if not self._running:
                        break

                    if result.has_more:
                        self._current_interval = 0.0
                    elif result.count > 0:
                        self._current_interval = self._min_interval
                    else:
                        self._current_interval = self._back_off(self._current_interval)

                    self._sleep_interruptibly(self._current_interval)
                except Exception as exc:  # noqa: BLE001 - poll loop must survive transient errors
                    if self._error_handler is not None:
                        self._error_handler(exc)
                    jitter = random.uniform(0, self._current_interval / 2 + 0.001)
                    self._current_interval = self._back_off(self._current_interval)
                    self._sleep_interruptibly(self._current_interval + jitter)
        finally:
            self._remove_shutdown_hook()

    def _sleep_interruptibly(self, duration_seconds: float) -> None:
        if duration_seconds <= 0:
            self._wake.clear()
            return
        self._wake.wait(timeout=duration_seconds)
        self._wake.clear()

    def kick(self) -> None:
        """Hint that a result is expected soon — shortens the current backoff to
        ``kick_interval`` and wakes the poll loop. Safe to call from any thread.
        """
        if self._current_interval > self._kick_interval:
            self._current_interval = self._kick_interval
            self._wake.set()

    def stop(self) -> None:
        """Signal the poll loop to stop after the current collection completes."""
        self._running = False
        self._wake.set()

    def collect_once(self) -> CollectResult:
        """Perform a single collection call. Thread-safe — concurrent calls are serialized
        to prevent duplicate delivery. Streams the NDJSON response line by line, invoking
        the handler per result. If the handler raises, the sequence is not advanced and the
        batch is redelivered next call.
        """
        with self._poll_lock:
            start_time = time.monotonic()
            try:
                count, has_more = self._do_collect()
                if self._metrics_listener is not None:
                    self._metrics_listener.on_poll(
                        count, has_more, (time.monotonic() - start_time) * 1000, None
                    )
                return CollectResult(count, has_more)
            except BaseException as exc:
                if self._metrics_listener is not None:
                    self._metrics_listener.on_poll(
                        0, False, (time.monotonic() - start_time) * 1000, exc
                    )
                raise

    def _do_collect(self) -> tuple:
        if self._last_acknowledged_sequence is not None:
            body = json.dumps(
                {"acknowledgeUpTo": self._last_acknowledged_sequence, "limit": self._batch_size}
            )
        else:
            body = json.dumps({"limit": self._batch_size})

        headers = self._api_client.epistola_request_headers()
        headers["Content-Type"] = _VENDOR_JSON
        headers["Accept"] = _NDJSON
        headers["Accept-Encoding"] = _supported_encodings()

        url = self._api_client.configuration.host.rstrip("/") + (
            f"/tenants/{self._tenant_id}/generation/collect"
        )

        response = self._api_client.rest_client.pool_manager.request(
            "POST",
            url,
            body=body.encode("utf-8"),
            headers=headers,
            preload_content=False,
            decode_content=False,
        )
        try:
            if response.status < 200 or response.status > 299:
                raise RuntimeError(f"collect failed: HTTP {response.status}")

            encoding = response.headers.get("content-encoding")
            stream = _decompress(response, encoding)
            reader = io.TextIOWrapper(_EofTolerantStream(stream), encoding="utf-8")

            count = 0
            has_more = False
            last_sequence_in_batch: Optional[int] = None

            for raw_line in reader:
                line = raw_line.strip()
                if not line:
                    continue
                node = json.loads(line)
                if node.get("_meta") is True:
                    has_more = bool(node.get("hasMore", False))
                    self._update_partition_assignment(node)
                    break
                parsed = _parse_result(node)
                self._handler(parsed)
                last_sequence_in_batch = parsed.sequence
                count += 1

            if last_sequence_in_batch is not None:
                self._last_acknowledged_sequence = last_sequence_in_batch

            return count, has_more
        finally:
            response.release_conn()

    def _update_partition_assignment(self, meta_node: dict) -> None:
        partitions = meta_node.get("partitions")
        if not isinstance(partitions, dict):
            return
        total = partitions.get("total")
        mine = partitions.get("mine")
        if total is None or mine is None:
            return
        new_assignment = PartitionAssignment(
            total=int(total), mine=[int(x) for x in mine], hash=partitions.get("hash", "murmur3")
        )
        old = self.current_partition_assignment
        if new_assignment != old:
            self.current_partition_assignment = new_assignment
            if self._metrics_listener is not None:
                self._metrics_listener.on_partition_change(old, new_assignment)

    def _remove_shutdown_hook(self) -> None:
        if self._shutdown_hook_registered:
            try:
                atexit.unregister(self.stop)
            except Exception:
                pass
            self._shutdown_hook_registered = False


class _EofTolerantStream(io.RawIOBase):
    """Adapts a response body so that reading past its end yields EOF rather than raising.

    urllib3 releases and closes its ``HTTPResponse`` the moment the body is exhausted.
    ``TextIOWrapper`` then calls ``read()`` once more looking for EOF and gets
    ``ValueError: I/O operation on closed file`` instead of ``b""``. Iterating an uncompressed
    NDJSON batch therefore yielded the first line and then raised — the handler saw one result, the
    ``_meta`` line was never reached, and the batch went unacknowledged and was redelivered
    forever. The gzip path happened to escape it because ``GzipFile`` stops reading on its own
    trailer, which is why this only ever bit an uncompressed stream.
    """

    def __init__(self, stream) -> None:
        self._stream = stream

    def readable(self) -> bool:
        return True

    def readinto(self, buffer) -> int:
        if getattr(self._stream, "closed", False):
            return 0
        data = self._stream.read(len(buffer))
        if not data:
            return 0
        buffer[: len(data)] = data
        return len(data)


def _supported_encodings() -> str:
    encodings = []
    if _LZ4_DECOMPRESSOR is not None:
        encodings.append("lz4")
    if _ZSTD_DECOMPRESSOR is not None:
        encodings.append("zstd")
    encodings.append("gzip")
    return ", ".join(encodings)


def _decompress(response, encoding: Optional[str]):
    if encoding == "gzip":
        return gzip.GzipFile(fileobj=response)
    if encoding == "lz4":
        if _LZ4_DECOMPRESSOR is None:
            raise RuntimeError("Server sent lz4 but the lz4 library is not available")
        return _LZ4_DECOMPRESSOR(response)
    if encoding == "zstd":
        if _ZSTD_DECOMPRESSOR is None:
            raise RuntimeError("Server sent zstd but the zstandard library is not available")
        return _ZSTD_DECOMPRESSOR(response)
    return response


def _parse_result(node: dict) -> GenerationResult:
    return GenerationResult(
        sequence=int(node["sequence"]),
        request_id=node.get("requestId") or "",
        batch_id=node.get("batchId"),
        status=node.get("status") or "",
        document_id=node.get("documentId"),
        correlation_id=node.get("correlationId"),
        routing_key=node.get("routingKey"),
        template_id=node.get("templateId"),
        variant_id=node.get("variantId"),
        version_id=node.get("versionId"),
        filename=node.get("filename"),
        content_type=node.get("contentType"),
        size_bytes=node.get("sizeBytes"),
        error=node.get("error"),
        completed_at=node.get("completedAt"),
    )


class ResultCollectorBuilder:
    """Fluent builder for :class:`ResultCollector`."""

    def __init__(self) -> None:
        self._api_client = None
        self._tenant_id: Optional[str] = None
        self._batch_size = 100
        self._min_interval = 1.0
        self._max_interval = 30.0
        self._kick_interval = 3.0
        self._backoff_multiplier = 3.0
        self._handler: Optional[Callable[[GenerationResult], None]] = None
        self._error_handler: Optional[Callable[[BaseException], None]] = None
        self._metrics_listener: Optional[MetricsListener] = None
        self._register_shutdown_hook = True

    def api_client(self, api_client) -> "ResultCollectorBuilder":
        """The :class:`EpistolaApiClient` to poll with (reuses its base URL, identity, JWT)."""
        self._api_client = api_client
        return self

    def tenant_id(self, tenant_id: str) -> "ResultCollectorBuilder":
        """The tenant whose results to collect."""
        self._tenant_id = tenant_id
        return self

    def batch_size(self, size: int) -> "ResultCollectorBuilder":
        """Maximum results per collection (default: 100)."""
        if size < 1 or size > 10000:
            raise ValueError("batch_size must be between 1 and 10000")
        self._batch_size = size
        return self

    def min_interval(self, seconds: float) -> "ResultCollectorBuilder":
        """Minimum poll interval when results are flowing (default: 1s)."""
        _require_positive(seconds, "min_interval")
        self._min_interval = seconds
        return self

    def max_interval(self, seconds: float) -> "ResultCollectorBuilder":
        """Maximum poll interval when idle (default: 30s)."""
        _require_positive(seconds, "max_interval")
        self._max_interval = seconds
        return self

    def kick_interval(self, seconds: float) -> "ResultCollectorBuilder":
        """Wait time used by :meth:`ResultCollector.kick` to override the backoff (default: 3s)."""
        _require_positive(seconds, "kick_interval")
        self._kick_interval = seconds
        return self

    def backoff_multiplier(self, multiplier: float) -> "ResultCollectorBuilder":
        """Exponential backoff multiplier applied on each empty poll (default: 3.0)."""
        if multiplier <= 1.0:
            raise ValueError("backoff_multiplier must be > 1.0")
        self._backoff_multiplier = multiplier
        return self

    def handler(self, handler: Callable[[GenerationResult], None]) -> "ResultCollectorBuilder":
        """Handler called for each result as it streams in."""
        self._handler = handler
        return self

    def error_handler(self, handler: Callable[[BaseException], None]) -> "ResultCollectorBuilder":
        """Optional error handler for collection failures."""
        self._error_handler = handler
        return self

    def metrics_listener(self, listener: MetricsListener) -> "ResultCollectorBuilder":
        """Optional metrics listener for observability."""
        self._metrics_listener = listener
        return self

    def register_shutdown_hook(self, register: bool) -> "ResultCollectorBuilder":
        """Register a process-exit hook to stop polling gracefully (default: True)."""
        self._register_shutdown_hook = register
        return self

    def build(self) -> ResultCollector:
        """Build the :class:`ResultCollector`."""
        if self._api_client is None:
            raise ValueError("api_client is required")
        if self._tenant_id is None:
            raise ValueError("tenant_id is required")
        if self._handler is None:
            raise ValueError("handler is required")
        return ResultCollector(
            self._api_client,
            self._tenant_id,
            self._batch_size,
            self._min_interval,
            self._max_interval,
            self._kick_interval,
            self._backoff_multiplier,
            self._handler,
            self._error_handler,
            self._metrics_listener,
            self._register_shutdown_hook,
        )


def _require_positive(value: float, name: str) -> None:
    if value <= 0:
        raise ValueError(f"{name} must be positive")
