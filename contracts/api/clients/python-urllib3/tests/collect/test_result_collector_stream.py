# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""Tests for the collector's NDJSON read loop, against a real HTTP server.

The rest of the collector's tests exercise pure functions — murmur3, partition routing, the backoff
arithmetic, the builder's guards — and never make a request. That left ``_do_collect`` untested,
and it is where the batch is actually read: a stream that ended early handed the handler its first
result and then raised, and the ``_meta`` line that carries ``hasMore`` and the partition
assignment was never reached. A stub that returns bytes cannot show that, because the defect is in
how a live response behaves once its body is exhausted.
"""

import gzip
import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from epistola_client import EpistolaClientBuilder, ResultCollector

RESULTS = [
    {"sequence": 501, "requestId": "11111111-1111-4111-8111-000000000501", "status": "COMPLETED"},
    {"sequence": 502, "requestId": "11111111-1111-4111-8111-000000000502", "status": "FAILED"},
    {"sequence": 503, "requestId": "11111111-1111-4111-8111-000000000503", "status": "COMPLETED"},
]
META = {"_meta": True, "hasMore": True, "count": 3, "partitions": {"total": 8, "mine": [0, 3], "hash": "murmur3"}}


def _ndjson() -> bytes:
    return ("\n".join(json.dumps(line) for line in [*RESULTS, META]) + "\n").encode("utf-8")


@pytest.fixture(params=["identity", "gzip"])
def server(request):
    """Serves one NDJSON batch, plain or gzipped — the two paths read the body differently."""
    compress = request.param == "gzip"
    body = gzip.compress(_ndjson()) if compress else _ndjson()

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.epistola.v1+ndjson")
            if compress:
                self.send_header("Content-Encoding", "gzip")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):
            pass

    httpd = HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{httpd.server_address[1]}"
    httpd.shutdown()


def _collector(base_url, handled):
    return (
        ResultCollector.builder()
        .api_client(EpistolaClientBuilder().base_url(f"{base_url}/api").build())
        .tenant_id("acme-corp")
        .handler(handled.append)
        .register_shutdown_hook(False)
        .build()
    )


def test_every_line_of_the_batch_is_read(server):
    handled = []

    result = _collector(server, handled).collect_once()

    assert [r.sequence for r in handled] == [501, 502, 503]
    assert result.count == 3
    # The meta line comes last, so reaching it at all is the proof the stream was read to the end.
    assert result.has_more is True


def test_the_meta_line_updates_the_partition_assignment(server):
    handled = []
    collector = _collector(server, handled)

    collector.collect_once()

    assert collector.current_partition_assignment is not None
    assert collector.current_partition_assignment.total == 8
    assert collector.current_partition_assignment.mine == [0, 3]


def test_the_batch_is_acknowledged_up_to_the_last_result_handled(server):
    handled = []
    collector = _collector(server, handled)

    collector.collect_once()

    assert collector._last_acknowledged_sequence == 503
