# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""The Python client's conformance driver.

Asks the conformance server what to do, does it with the published client, and reports back. It
asserts nothing — the server judges the requests, so the four clients are held to one set of
expectations rather than four that drift. See ../../README.md for the driver contract.
"""

from __future__ import annotations

import base64
import datetime
import hashlib
import json
import sys
import threading
import time
import traceback
import urllib.request

from epistola_client import (
    CatalogDependencyRequirement,
    CatalogsApi,
    ClientIdentity,
    ConsumersApi,
    CreateCatalogVersionRequest,
    EnvironmentsApi,
    EpistolaClientBuilder,
    GenerateDocumentRequest,
    GenerationApi,
    ImagesApi,
    JwtSigner,
    PingRequest,
    ProblemDetailException,
    ReleaseCatalogVersionRequest,
    RenumberCatalogVersionRequest,
    ResolveIncomingChangeRequest,
    ResultCollector,
    SystemApi,
    TemplateDataValidationError,
    TemplateSchemaValidator,
    TemplatesApi,
    UpdateCatalogDependenciesRequest,
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
        "validate-template-data": _validate_template_data,
        "routing": _routing,
        "generate-document": _generate_document,
        "update-consumer": _update_consumer,
        "download-document": _download_document,
        "list-images": _list_images,
        "upload-image": _upload_image,
        "download-image": _download_image,
        "delete-image": _delete_image,
        "list-catalog-dependencies": _list_catalog_dependencies,
        "update-catalog-dependencies": _update_catalog_dependencies,
        "create-catalog-version": _create_catalog_version,
        "list-catalog-versions": _list_catalog_versions,
        "get-catalog-version": _get_catalog_version,
        "renumber-catalog-version": _renumber_catalog_version,
        "preview-catalog-version-renumber": _preview_catalog_version_renumber,
        "delete-catalog-version": _delete_catalog_version,
        "release-catalog-version": _release_catalog_version,
        "list-incoming-changes": _list_incoming_changes,
        "resolve-incoming-change": _resolve_incoming_change,
        "deploy-release": _deploy_release,
        "undeploy-release": _undeploy_release,
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
    """Reports what the client made of the last listing, `id` included while it is still served."""
    api = TemplatesApi(_client(base_url, config))
    for _ in range(config.get("repeat", 1)):
        response = api.list_templates(
            config["tenantKey"], config["registry"], config["namespace"], config["catalogKey"], config["catalogVersion"]
        )
    _report(
        base_url,
        {
            "templateKeys": ",".join(_show(template.key) for template in response.items),
        },
    )


def _problem(base_url: str, config: dict) -> None:
    try:
        TemplatesApi(_client(base_url, config)).list_templates(
            config["tenantKey"], config["registry"], config["namespace"], config["catalogKey"], config["catalogVersion"]
        )
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
                "problemMissingFields": ",".join(
                    f"{f.path}:{str(f.required).lower()}" for f in exc.missing_fields
                ),
                "problemInvalidFields": ",".join(
                    f"{f.path}:{f.keyword}" for f in exc.invalid_fields
                ),
            },
        )


def _validate_template_data(base_url: str, config: dict) -> None:
    """Reports the failures the client surfaced, which is the shape every client owes its callers."""
    validator = TemplateSchemaValidator(TemplatesApi(_client(base_url, config)))
    try:
        validator.validate(
            config["tenantKey"],
            config["registry"],
            config["namespace"],
            config["catalogKey"],
            config["templateKey"],
            config.get("data", {}),
        )
        _report(
            base_url,
            {"validationFailurePaths": "<accepted>", "validationFailureKeywords": "<accepted>"},
        )
    except TemplateDataValidationError as exc:
        _report(
            base_url,
            {
                "validationFailurePaths": ",".join(f.path for f in exc.errors),
                "validationFailureKeywords": ",".join(_show(f.keyword) for f in exc.errors),
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
        .tenant_key(config["tenantKey"])
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
            "releases": ",".join(
                f"{_show(r.registry)}/{_show(r.namespace)}/{_show(r.catalog_key)}@{_show(r.release_version)}"
                for r in handled
            ),
        },
    )


def _generate_document(base_url: str, config: dict) -> None:
    """A request body with something in it: required fields, two of the optional ones set, the rest
    left alone, and a free-form ``data`` object carrying every JSON type. What the server receives
    is the generator's serialization, which is the part no client hand-writes and no client's own
    tests inspect.

    Optional members are passed only when the scenario sets them: an unset optional must stay off
    the wire, not arrive as null.
    """
    optional = {
        name: config[name]
        for name in ("registry", "version", "environmentKey", "correlationId", "routingKey")
        if name in config
    }
    GenerationApi(_client(base_url, config)).generate_document(
        config["tenantKey"],
        GenerateDocumentRequest(
            namespace=config["namespace"],
            catalogKey=config["catalogKey"],
            templateKey=config["templateKey"],
            data=config["data"],
            **optional,
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
        config["tenantKey"], config["documentId"]
    )
    _report_bytes(base_url, bytes(content))


def _list_images(base_url: str, config: dict) -> None:
    """Lists images and reports what the client made of them. Keys may be UUIDs or readable names,
    and an SVG's dimensions are null, which must stay null rather than become 0.
    """
    images = (
        ImagesApi(_client(base_url, config))
        .list_images(
            config["tenantKey"],
            config["registry"],
            config["namespace"],
            config["catalogKey"],
            config["catalogVersion"],
            search=config["search"],
        )
        .items
    )
    _report(
        base_url,
        {
            "imageKeys": ",".join(image.key for image in images),
            "widths": ",".join(_show(image.width) for image in images),
            "heights": ",".join(_show(image.height) for image in images),
            "mediaTypes": ",".join(image.media_type for image in images),
        },
    )


def _upload_image(base_url: str, config: dict) -> None:
    """Uploads an image from bytes, as the ``(filename, bytes)`` pair the generated method takes. The
    client derives the part's content type from that filename.
    """
    image = ImagesApi(_client(base_url, config)).upload_image(
        config["tenantKey"],
        config["registry"],
        config["namespace"],
        config["catalogKey"],
        config["catalogVersion"],
        (config["filename"], base64.b64decode(config["fileBase64"])),
        name=config["name"],
    )
    _report(base_url, {"imageKey": image.key, "imageName": image.name})


def _download_image(base_url: str, config: dict) -> None:
    content = ImagesApi(_client(base_url, config)).download_image_content(
        config["tenantKey"],
        config["registry"],
        config["namespace"],
        config["catalogKey"],
        config["catalogVersion"],
        config["imageKey"],
    )
    _report_bytes(base_url, bytes(content))


def _delete_image(base_url: str, config: dict) -> None:
    ImagesApi(_client(base_url, config)).delete_image(
        config["tenantKey"],
        config["registry"],
        config["namespace"],
        config["catalogKey"],
        config["catalogVersion"],
        config["imageKey"],
        force=config["force"],
    )


def _list_catalog_dependencies(base_url: str, config: dict) -> None:
    response = CatalogsApi(_client(base_url, config)).list_catalog_dependencies(
        config["tenantKey"], config["registry"], config["namespace"], config["catalogKey"], config["catalogVersion"]
    )
    _report_dependencies(base_url, response.items)


def _update_catalog_dependencies(base_url: str, config: dict) -> None:
    response = CatalogsApi(_client(base_url, config)).update_catalog_dependencies(
        config["tenantKey"],
        config["registry"],
        config["namespace"],
        config["catalogKey"],
        config["catalogVersion"],
        UpdateCatalogDependenciesRequest(
            dependencies=[
                CatalogDependencyRequirement(
                    # Only when the entry names one: omitted means the server's default registry.
                    **({"registry": dependency["registry"]} if "registry" in dependency else {}),
                    namespace=dependency["namespace"],
                    catalogKey=dependency["catalogKey"],
                    version=dependency["version"],
                )
                for dependency in config["dependencies"]
            ]
        ),
    )
    _report_dependencies(base_url, response.items)


def _report_dependencies(base_url: str, dependencies) -> None:
    _report(
        base_url,
        {
            "registries": ",".join(_show(d.registry) for d in dependencies),
            "namespaces": ",".join(_show(d.namespace) for d in dependencies),
            "catalogKeys": ",".join(_show(d.catalog_key) for d in dependencies),
            "versions": ",".join(_show(d.version) for d in dependencies),
            "resolvedVersions": ",".join(_show(d.resolved_version) for d in dependencies),
        },
    )


def _catalog_version_address(config: dict) -> tuple:
    return (config["tenantKey"], config["registry"], config["namespace"], config["catalogKey"], config["catalogVersion"])


def _create_catalog_version(base_url: str, config: dict) -> None:
    """Opens a draft. ``from`` is a Python keyword, so the generated model names the member
    ``var_from``; it is passed by its alias, and only when the scenario sets it."""
    optional = {"from": config["from"]} if "from" in config else {}
    created = CatalogsApi(_client(base_url, config)).create_catalog_version(
        config["tenantKey"],
        config["registry"],
        config["namespace"],
        config["catalogKey"],
        CreateCatalogVersionRequest(version=config["version"], **optional),
    )
    _report(
        base_url,
        {
            "version": _show(created.version),
            "status": _show(_enum(created.status)),
            "base": _show(created.base),
            "createdFrom": _show(created.created_from),
            "releasedAt": _show_instant(created.released_at),
        },
    )


def _list_catalog_versions(base_url: str, config: dict) -> None:
    optional = {"status": config["status"]} if "status" in config else {}
    versions = (
        CatalogsApi(_client(base_url, config))
        .list_catalog_versions(
            config["tenantKey"], config["registry"], config["namespace"], config["catalogKey"], **optional
        )
        .items
    )
    _report(
        base_url,
        {
            "versions": ",".join(_show(v.version) for v in versions),
            "statuses": ",".join(_show(_enum(v.status)) for v in versions),
            "bases": ",".join(_show(v.base) for v in versions),
            "openConflicts": ",".join(_show(v.open_conflicts) for v in versions),
        },
    )


def _get_catalog_version(base_url: str, config: dict) -> None:
    version = CatalogsApi(_client(base_url, config)).get_catalog_version(
        *_catalog_version_address(config)
    )
    _report(
        base_url,
        {
            "version": _show(version.version),
            "status": _show(_enum(version.status)),
            "releasedAt": _show_instant(version.released_at),
            "fingerprint": _show(version.fingerprint),
            "dependencies": ",".join(
                f"{_show(d.registry)}/{_show(d.namespace)}/{_show(d.catalog_key)}@{_show(d.release_version)}"
                for d in version.dependencies
            ),
            "deployedTo": ",".join(version.deployed_to),
        },
    )


def _renumber_catalog_version(base_url: str, config: dict) -> None:
    optional = (
        {"acknowledgeWarnings": config["acknowledgeWarnings"]}
        if "acknowledgeWarnings" in config
        else {}
    )
    version = CatalogsApi(_client(base_url, config)).renumber_catalog_version(
        *_catalog_version_address(config),
        RenumberCatalogVersionRequest(version=config["version"], **optional),
    )
    _report(base_url, {"version": _show(version.version), "status": _show(_enum(version.status))})


def _preview_catalog_version_renumber(base_url: str, config: dict) -> None:
    """Warning kinds are an open set: a kind this client has never heard of is reported as is."""
    preview = CatalogsApi(_client(base_url, config)).preview_catalog_version_renumber(
        config["to"], *_catalog_version_address(config)
    )
    _report(
        base_url,
        {
            "newBase": _show(preview.new_base),
            "warningKinds": ",".join(w.kind for w in preview.warnings),
        },
    )


def _delete_catalog_version(base_url: str, config: dict) -> None:
    CatalogsApi(_client(base_url, config)).delete_catalog_version(*_catalog_version_address(config))


def _release_catalog_version(base_url: str, config: dict) -> None:
    optional = {"notes": config["notes"]} if "notes" in config else {}
    response = CatalogsApi(_client(base_url, config)).release_catalog_version(
        *_catalog_version_address(config),
        ReleaseCatalogVersionRequest(**optional),
    )
    _report(
        base_url,
        {
            "version": _show(response.catalog_version.version),
            "status": _show(_enum(response.catalog_version.status)),
            "flowedInto": ",".join(
                f"{f.version}:{f.merged}:{f.conflicts}" for f in response.flowed_into
            ),
            "openedDraft": _show(response.opened_draft),
        },
    )


def _list_incoming_changes(base_url: str, config: dict) -> None:
    optional = {"state": config["state"]} if "state" in config else {}
    changes = (
        CatalogsApi(_client(base_url, config))
        .list_incoming_changes(*_catalog_version_address(config), **optional)
        .items
    )
    _report(
        base_url,
        {
            "ids": ",".join(c.id for c in changes),
            "nodes": ",".join(_show(c.node) for c in changes),
            "states": ",".join(_show(_enum(c.state)) for c in changes),
            "resolutions": ",".join(_show(_enum(c.resolution)) for c in changes),
        },
    )


def _resolve_incoming_change(base_url: str, config: dict) -> None:
    change = CatalogsApi(_client(base_url, config)).resolve_incoming_change(
        *_catalog_version_address(config),
        config["changeId"],
        ResolveIncomingChangeRequest(resolution=config["resolution"]),
    )
    _report(
        base_url,
        {
            "id": change.id,
            "state": _show(_enum(change.state)),
            "resolution": _show(_enum(change.resolution)),
        },
    )


def _deploy_release(base_url: str, config: dict) -> None:
    """Deploys one release; the request has no body, the release is the last path segment.

    ``includeDependencies`` is passed only when the scenario sets it, so an unset one sends no
    query parameter. A refusal is reported by the dependency releases it names.
    """
    optional = (
        {"include_dependencies": config["includeDependencies"]}
        if "includeDependencies" in config
        else {}
    )
    try:
        deployment = EnvironmentsApi(_client(base_url, config)).deploy_release(
            config["tenantKey"],
            config["environmentKey"],
            config["registry"],
            config["namespace"],
            config["catalogKey"],
            config["releaseVersion"],
            **optional,
        )
    except ProblemDetailException as exc:
        _report(
            base_url,
            {
                "problemTypeSlug": exc.type_slug or "<null>",
                "problemStatus": exc.status_code,
                "problemMissingDependencies": ",".join(
                    f"{d.registry}/{d.namespace}/{d.catalog_key}@{d.release_version}:{d.reason}"
                    for d in exc.missing_dependencies
                ),
            },
        )
        return
    _report(
        base_url,
        {
            "registry": _show(deployment.registry),
            "namespace": _show(deployment.namespace),
            "catalogKey": _show(deployment.catalog_key),
            "releaseVersion": _show(deployment.release_version),
        },
    )


def _undeploy_release(base_url: str, config: dict) -> None:
    EnvironmentsApi(_client(base_url, config)).undeploy_release(
        config["tenantKey"],
        config["environmentKey"],
        config["registry"],
        config["namespace"],
        config["catalogKey"],
        config["releaseVersion"],
    )


def _report_bytes(base_url: str, data: bytes) -> None:
    _report(base_url, {"byteLength": len(data), "sha256": hashlib.sha256(data).hexdigest()})


def _update_consumer(base_url: str, config: dict) -> None:
    """A partial update that sets exactly one field.

    Everything the caller did not name must stay off the wire: the contract reads a null on these as
    "clear this", so a serializer that writes nulls for unset properties turns "rename this
    consumer" into "rename it and erase its description, contact and expiry".
    """
    ConsumersApi(_client(base_url, config)).update_consumer(
        config["tenantKey"],
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
        .tenant_key(config["tenantKey"])
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
            "mineFlags": ",".join(
                "true" if collector.is_my_partition(k) else "false" for k in keys
            ),
        },
    )


def _show(value) -> str:
    """Renders a missing value the way the other drivers' languages print theirs, so the harness
    compares one spelling rather than four."""
    return "null" if value is None else str(value)


def _enum(value):
    """A generated enum's wire value; ``str()`` of one is its class-qualified name."""
    return getattr(value, "value", value)


def _show_instant(value) -> str:
    """An instant as RFC 3339 with ``Z`` for UTC, the spelling the other drivers print."""
    if value is None:
        return "null"
    text = value.isoformat()
    return text[: -len("+00:00")] + "Z" if text.endswith("+00:00") else text


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
