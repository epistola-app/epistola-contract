# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""The Python client's conformance driver.

Asks the conformance server what to do, does it with the published client, and reports back. It
asserts nothing — the server judges the requests, so the four clients are held to one set of
expectations rather than four that drift. See ../../README.md for the driver contract.
"""

from __future__ import annotations

import datetime
import hashlib
import json
import sys
import threading
import time
import traceback
import urllib.request

from epistola_client import (
    ClientIdentity,
    ConsumersApi,
    EpistolaClientBuilder,
    GenerateDocumentRequest,
    GenerationApi,
    JwtSigner,
    PingRequest,
    ProblemDetailException,
    ResultCollector,
    SystemApi,
    TemplatesApi,
    UpdateConsumerRequest,
)


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: driver.py <conformance server base url>", file=sys.stderr)
        return 2

    base_url = sys.argv[1]
    instruction = _get(f"{base_url}/__conformance/action")
    config = instruction["config"]

    actions = {
        "ping": _ping,
        "list-templates": _list_templates,
        "collect": _collect,
        "problem": _problem,
        "routing": _routing,
        "generate-document": _generate_document,
        "update-consumer": _update_consumer,
        "download-document": _download_document,
    }

    try:
        action = actions[instruction["action"]]
    except KeyError:
        print(f"unknown action {instruction['action']}", file=sys.stderr)
        return 2

    try:
        action(base_url, config)
        _done(base_url, None)
        return 0
    except Exception as exc:  # noqa: BLE001 — the harness wants the cause, whatever it is
        _done(base_url, f"{type(exc).__name__}: {exc}")
        traceback.print_exc()
        return 1


# --- Actions ---


def _ping(base_url: str, config: dict) -> None:
    SystemApi(_client(base_url, config)).ping(
        PingRequest(
            name="Conformance Driver",
            description="Drives the Python client through one conformance scenario",
            contact="conformance@epistola.app",
        )
    )


def _list_templates(base_url: str, config: dict) -> None:
    api = TemplatesApi(_client(base_url, config))
    for _ in range(config.get("repeat", 1)):
        api.list_templates(config["tenantId"], config["catalogId"])


def _problem(base_url: str, config: dict) -> None:
    try:
        TemplatesApi(_client(base_url, config)).list_templates(config["tenantId"], config["catalogId"])
        _report(base_url, {"problemTypeSlug": "<no exception was thrown>"})
    except ProblemDetailException as exc:
        _report(
            base_url,
            {
                "problemTypeSlug": exc.type_slug or "<null>",
                "problemStatus": exc.status_code,
                "problemTitle": exc.title or "<null>",
                # The generated pydantic model exposes the JSON "field" member as var_field; the
                # wire name is the same, so this is a naming difference, not a divergence.
                "problemFieldErrors": ",".join(f"{e.var_field}:{e.message}" for e in exc.errors),
            },
        )


def _collect(base_url: str, config: dict) -> None:
    handled = []
    fail_on_sequence = config.get("failHandlerOnSequence")

    def handle(result):
        handled.append(result)
        if result.sequence == fail_on_sequence:
            raise RuntimeError("conformance: deliberate handler failure")

    collector = (
        ResultCollector.builder()
        .api_client(_client(base_url, config))
        .tenant_id(config["tenantId"])
        .batch_size(config["batchSize"])
        # The Python builder takes seconds, not milliseconds — the scenario speaks one unit and
        # each driver converts to whatever its client's API uses.
        .min_interval(config["minIntervalMs"] / 1000)
        .max_interval(config["maxIntervalMs"] / 1000)
        .backoff_multiplier(config["multiplier"])
        .register_shutdown_hook(False)
        .handler(handle)
        # Without this the loop swallows collection failures and simply backs off, which reaches
        # the harness as "the client chose not to poll" rather than as the cause.
        .error_handler(lambda exc: traceback.print_exception(type(exc), exc, exc.__traceback__))
        .build()
    )

    thread = threading.Thread(target=collector.start, name="conformance-collector", daemon=True)
    thread.start()
    time.sleep(config["runForMs"] / 1000)
    collector.stop()
    thread.join(5)

    assignment = collector.current_partition_assignment
    _report(
        base_url,
        {
            "resultsHandled": len(handled),
            "statuses": ",".join(result.status for result in handled),
            "correlationIds": ",".join(result.correlation_id or "" for result in handled),
            "handledSequences": ",".join(str(result.sequence) for result in handled),
            "partitionTotal": assignment.total if assignment is not None else -1,
        },
    )


def _generate_document(base_url: str, config: dict) -> None:
    """A request body with something in it: required fields, two of the optional ones set, the rest
    left alone, and a free-form ``data`` object carrying every JSON type. What the server receives
    is the generator's serialization, which is the part no client hand-writes and no client's own
    tests inspect.
    """
    GenerationApi(_client(base_url, config)).generate_document(
        config["tenantId"],
        GenerateDocumentRequest(
            catalogId=config["catalogId"],
            templateId=config["templateId"],
            data=config["data"],
            correlationId=config["correlationId"],
            routingKey=config["routingKey"],
        ),
    )


def _download_document(base_url: str, config: dict) -> None:
    """Downloads a document and reports what arrived, byte for byte.

    The four clients return four different things here — a File, a FileParameter, a bytearray — and
    the only thing that has to be identical is the content. A stack that decodes a PDF as text
    corrupts every document it fetches, silently and irreversibly, so the fixture is deliberately
    not valid UTF-8.
    """
    content = GenerationApi(_client(base_url, config)).download_document(
        config["tenantId"], config["documentId"]
    )
    data = bytes(content)

    _report(
        base_url,
        {"byteLength": len(data), "sha256": hashlib.sha256(data).hexdigest()},
    )


def _update_consumer(base_url: str, config: dict) -> None:
    """A partial update that sets exactly one field.

    Everything the caller did not name must stay off the wire: the contract reads a null on these as
    "clear this", so a serializer that writes nulls for unset properties turns "rename this
    consumer" into "rename it and erase its description, contact and expiry".
    """
    ConsumersApi(_client(base_url, config)).update_consumer(
        config["tenantId"],
        config["consumerId"],
        UpdateConsumerRequest(name=config["name"]),
    )


def _routing(base_url: str, config: dict) -> None:
    """One poll to learn the partition assignment from the ``_meta`` line, then the routing helpers.

    The values are reported rather than asserted here: the harness holds all four clients to the
    same answers, which is the only way four independent murmur3 implementations stay in step.
    """
    collector = (
        ResultCollector.builder()
        .api_client(_client(base_url, config))
        .tenant_id(config["tenantId"])
        .register_shutdown_hook(False)
        .handler(lambda result: None)
        .build()
    )

    collector.collect_once()

    keys = config["keys"]
    assignment = collector.current_partition_assignment
    _report(
        base_url,
        {
            "partitionTotal": assignment.total if assignment is not None else -1,
            "partitions": ",".join(f"{k}:{_show(collector.partition_for(k))}" for k in keys),
            "routed": ",".join(f"{k}={_show(collector.routing_key_to_me(k))}" for k in keys),
            "routedPartitions": ",".join(
                _show(collector.partition_for(collector.routing_key_to_me(k))) for k in keys
            ),
            "mineFlags": ",".join("true" if collector.is_my_partition(k) else "false" for k in keys),
        },
    )


def _show(value) -> str:
    """Renders a missing value the way the other drivers' languages print theirs, so the harness
    compares one spelling rather than four."""
    return "null" if value is None else str(value)


# --- Client assembly ---


def _client(base_url: str, config: dict):
    """Builds the client the way the README tells consumers to. The API base path is part of the
    contract's ``servers`` entry, so the driver appends it rather than the harness serving the API
    at the root.
    """
    identity = ClientIdentity.builder().node_id(config["nodeId"])
    for product in config.get("products", []):
        identity.product(product["name"], product["version"])

    builder = (
        EpistolaClientBuilder()
        .base_url(f"{base_url}/api")
        .identity(identity.build())
        .install_problem_detail_handler()
    )

    auth = config.get("auth", "none")
    if auth == "api-key":
        builder.api_key(config["apiKey"])
    elif auth == "jwt":
        builder.jwt_signer(
            JwtSigner.builder()
            .consumer_id(config["consumerId"])
            .private_key(JwtSigner.parse_private_key_pem(config["privateKeyPem"]))
            .token_lifetime(datetime.timedelta(seconds=config["tokenLifetimeSeconds"]))
            .build()
        )

    return builder.build()


# --- Control plane ---


def _get(url: str) -> dict:
    with urllib.request.urlopen(url) as response:  # noqa: S310 — a loopback URL from the harness
        return json.loads(response.read().decode("utf-8"))


def _post(url: str, payload: dict) -> None:
    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request):  # noqa: S310 — a loopback URL from the harness
        pass


def _report(base_url: str, values: dict) -> None:
    _post(f"{base_url}/__conformance/report", values)


def _done(base_url: str, error: str | None) -> None:
    _post(f"{base_url}/__conformance/done", {} if error is None else {"error": error})


if __name__ == "__main__":
    sys.exit(main())
