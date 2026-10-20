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
    CatalogPresetDto,
    CatalogThemeReference,
    CatalogsApi,
    ClientIdentity,
    ConsumersApi,
    CreateCatalogDraftRequest,
    CreateEnvironmentRequest,
    EnvironmentsApi,
    EpistolaClientBuilder,
    GenerateDocumentRequest,
    GenerationApi,
    ImagesApi,
    JwtSigner,
    PingRequest,
    ProblemDetailException,
    ReleaseCatalogDraftRequest,
    RenameCatalogDraftRequest,
    ResultCollector,
    StencilsApi,
    SystemApi,
    TemplateDataValidationError,
    TemplateSchemaValidator,
    TemplatesApi,
    UpdateCatalogDependenciesRequest,
    UpdateConsumerRequest,
    UpdateEnvironmentRequest,
    UpdateStencilContentRequest,
    UpdateVariantContentRequest,
    VariantsApi,
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
        "list-documents": _list_documents,
        "list-images": _list_images,
        "upload-image": _upload_image,
        "import-catalog": _import_catalog,
        "install-catalog-release": _install_catalog_release,
        "list-available-catalog-releases": _list_available_catalog_releases,
        "download-image": _download_image,
        "delete-image": _delete_image,
        "list-catalog-dependencies": _list_catalog_dependencies,
        "update-catalog-dependencies": _update_catalog_dependencies,
        "create-catalog-draft": _create_catalog_draft,
        "list-catalog-versions": _list_catalog_versions,
        "get-catalog-version": _get_catalog_version,
        "rename-catalog-draft": _rename_catalog_draft,
        "set-catalog-draft-theme-settings": _set_catalog_draft_theme_settings,
        "preview-catalog-draft-release": _preview_catalog_draft_release,
        "delete-catalog-draft": _delete_catalog_draft,
        "release-catalog-draft": _release_catalog_draft,
        "deploy-catalog-version": _deploy_catalog_version,
        "undeploy-catalog-version": _undeploy_catalog_version,
        "create-environment": _create_environment,
        "update-environment": _update_environment,
        "update-variant-content": _update_variant_content,
        "get-variant-content": _get_variant_content,
        "mark-variant-ready": _mark_variant_ready,
        "mark-template-ready": _mark_template_ready,
        "get-stencil-content": _get_stencil_content,
        "update-stencil-content": _update_stencil_content,
        "mark-stencil-ready": _mark_stencil_ready,
        "list-stencil-usage": _list_stencil_usage,
        "get-catalog-changes": _get_catalog_changes,
        "mark-catalog-ready": _mark_catalog_ready,
        "list-environment-deployments": _list_environment_deployments,
        "list-deployment-history": _list_deployment_history,
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
            "templateDescriptions": "|".join(_show(template.description) for template in response.items),
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
        for name in (
            "registry",
            "variantKey",
            "versionRange",
            "environmentKey",
            "correlationId",
            "routingKey",
        )
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


def _list_documents(base_url: str, config: dict) -> None:
    """Lists documents with the catalog and template filters the scenario sets, each sent only
    when set, and reports what the client made of the page.
    """
    filters = {
        python_name: config[name]
        for name, python_name in (
            ("templateKey", "template_key"),
            ("registry", "registry"),
            ("namespace", "namespace"),
            ("catalogKey", "catalog_key"),
        )
        if name in config
    }
    documents = (
        GenerationApi(_client(base_url, config)).list_documents(config["tenantKey"], **filters).items
    )
    _report(
        base_url,
        {
            "catalogs": ",".join(
                f"{_show(d.registry)}/{_show(d.namespace)}/{_show(d.catalog_key)}" for d in documents
            ),
            "templateKeys": ",".join(d.template_key for d in documents),
            "variantKeys": ",".join(d.variant_key for d in documents),
        },
    )


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


def _import_catalog(base_url: str, config: dict) -> None:
    """Imports a catalog archive, the file as a ``(filename, bytes)`` pair like an image upload.
    ``registry``, ``namespace`` and ``catalogType`` go out only when the scenario sets them. A
    refusal is reported by its problem."""
    optional = {
        name: config[key]
        for key, name in (("registry", "registry"), ("namespace", "namespace"), ("catalogType", "catalog_type"))
        if key in config
    }
    try:
        imported = CatalogsApi(_client(base_url, config)).import_catalog(
            config["tenantKey"],
            (config["filename"], base64.b64decode(config["fileBase64"])),
            **optional,
        )
    except ProblemDetailException as exc:
        _report(base_url, {"problemTypeSlug": exc.type_slug or "<null>", "problemStatus": exc.status_code})
        return
    _report(
        base_url,
        {
            "registry": _show(imported.registry),
            "namespace": _show(imported.namespace),
            "catalogKey": _show(imported.catalog_key),
            "releaseVersion": _show(imported.release_version),
            "newlyInstalled": _show_bool(imported.newly_installed),
            "total": _show(imported.total),
        },
    )


def _install_catalog_release(base_url: str, config: dict) -> None:
    """Installs a release a subscribed catalog's source offers, named in the path. A refusal or a
    failing source is reported by its problem, missing dependencies included."""
    try:
        installed = CatalogsApi(_client(base_url, config)).install_catalog_release(
            config["tenantKey"],
            config["registry"],
            config["namespace"],
            config["catalogKey"],
            config["releaseVersion"],
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
            "registry": _show(installed.registry),
            "namespace": _show(installed.namespace),
            "catalogKey": _show(installed.catalog_key),
            "releaseVersion": _show(installed.release_version),
            "newlyInstalled": _show_bool(installed.newly_installed),
            "total": _show(installed.total),
            "installedDependencies": ",".join(installed.installed_dependencies or []),
            "presentDependencies": ",".join(installed.present_dependencies or []),
        },
    )


def _list_available_catalog_releases(base_url: str, config: dict) -> None:
    """Lists the releases a subscribed catalog's source offers that the tenant lacks, reporting
    every one in order. A failing source is reported by its problem."""
    try:
        releases = CatalogsApi(_client(base_url, config)).list_available_catalog_releases(
            config["tenantKey"],
            config["registry"],
            config["namespace"],
            config["catalogKey"],
        )
    except ProblemDetailException as exc:
        _report(base_url, {"problemTypeSlug": exc.type_slug or "<null>", "problemStatus": exc.status_code})
        return
    _report(
        base_url,
        {
            "registry": _show(releases.registry),
            "namespace": _show(releases.namespace),
            "catalogKey": _show(releases.catalog_key),
            "availableComparedTo": ";".join(f"{_show(r.release_version)}={_show(r.compared_to)}" for r in releases.available),
            "availableChanges": ";".join(
                f"{_show(r.release_version)}="
                + "+".join(f"{_show(c.type)}/{_show(c.key)}:{_show(_enum(c.change))}" for c in r.changes)
                for r in releases.available
            ),
            "availableDependencies": ";".join(
                f"{_show(r.release_version)}="
                + "+".join(
                    f"{_show(d.registry)}/{_show(d.namespace)}/{_show(d.catalog_key)}"
                    f"@{_show(d.release_version)}:{_show_bool(d.present)}"
                    for d in r.dependencies
                )
                for r in releases.available
            ),
        },
    )


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
                    # Exactly one of these names what the declaration uses: only the one given.
                    **{name: dependency[name] for name in ("releaseVersion", "versionId") if name in dependency},
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
            # A declaration that pins no release (migrated from wire v7) reports ``null``.
            "releaseVersions": ",".join(_show(d.release_version) for d in dependencies),
            "versionIds": ",".join(_show(d.version_id) for d in dependencies),
        },
    )


def _catalog_version_address(config: dict) -> tuple:
    return (config["tenantKey"], config["registry"], config["namespace"], config["catalogKey"], config["catalogVersion"])


def _create_catalog_draft(base_url: str, config: dict) -> None:
    """Starts a draft. ``from`` is a Python keyword, so the generated model names the member
    ``var_from``; it is passed by its alias, and only when the scenario sets it."""
    optional = {"from": config["from"]} if "from" in config else {}
    created = CatalogsApi(_client(base_url, config)).create_catalog_draft(
        config["tenantKey"],
        config["registry"],
        config["namespace"],
        config["catalogKey"],
        CreateCatalogDraftRequest(name=config["name"], **optional),
    )
    _report(
        base_url,
        {
            "releaseVersion": _show(created.release_version),
            "versionId": _show(created.version_id),
            "name": _show(created.name),
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
            "releaseVersions": ",".join(_show(v.release_version) for v in versions),
            "versionIds": ",".join(_show(v.version_id) for v in versions),
            "names": "|".join(_show(v.name) for v in versions),
            "statuses": ",".join(_show(_enum(v.status)) for v in versions),
            "bases": ",".join(_show(v.base) for v in versions),
            "createdFrom": ",".join(_show(v.created_from) for v in versions),
            "behindCounts": ",".join(str(len(v.behind)) for v in versions),
        },
    )


def _get_catalog_version(base_url: str, config: dict) -> None:
    version = CatalogsApi(_client(base_url, config)).get_catalog_version(
        *_catalog_version_address(config)
    )
    _report(
        base_url,
        {
            "releaseVersion": _show(version.release_version),
            "versionId": _show(version.version_id),
            "name": _show(version.name),
            "status": _show(_enum(version.status)),
            "releasedAt": _show_instant(version.released_at),
            "fingerprint": _show(version.fingerprint),
            "dependencies": ",".join(
                f"{_show(d.registry)}/{_show(d.namespace)}/{_show(d.catalog_key)}@{_show(d.release_version)}"
                for d in version.dependencies
            ),
            "deployedTo": ",".join(version.deployed_to),
            "behind": ";".join(
                f"{_show(b.release_version)}="
                + "+".join(f"{_show(c.type)}/{_show(c.key)}:{_show(_enum(c.change))}" for c in b.changes)
                for b in version.behind
            ),
        },
    )


def _rename_catalog_draft(base_url: str, config: dict) -> None:
    version = CatalogsApi(_client(base_url, config)).rename_catalog_draft(
        *_catalog_version_address(config),
        RenameCatalogDraftRequest(name=config["name"]),
    )
    _report(
        base_url,
        {
            "releaseVersion": _show(version.release_version),
            "versionId": _show(version.version_id),
            "name": _show(version.name),
            "status": _show(_enum(version.status)),
            "base": _show(version.base),
        },
    )


def _set_catalog_draft_theme_settings(base_url: str, config: dict) -> None:
    """Sets a draft's default theme and presets; no name goes out, and an unset qualifier stays off the wire."""
    theme_qualifiers = {"catalog_key": config["defaultThemeCatalogKey"]} if config.get("defaultThemeCatalogKey") is not None else {}
    default_theme = CatalogThemeReference(key=config["defaultThemeKey"], **theme_qualifiers)
    presets = [
        CatalogPresetDto(
            key=entry["key"],
            name=entry["name"],
            **({"description": entry["description"]} if entry.get("description") is not None else {}),
        )
        for entry in config["presets"]
    ]
    version = CatalogsApi(_client(base_url, config)).rename_catalog_draft(
        *_catalog_version_address(config),
        RenameCatalogDraftRequest(default_theme=default_theme, presets=presets),
    )
    parsed = version.presets or []
    theme = version.default_theme
    _report(
        base_url,
        {
            "versionId": _show(version.version_id),
            "name": _show(version.name),
            "defaultThemeKey": _show(theme.key if theme else None),
            "defaultThemeCatalogKey": _show(theme.catalog_key if theme else None),
            "presetKeys": ",".join(p.key for p in parsed),
            "presetNames": "|".join(p.name for p in parsed),
            "presetDescriptions": "|".join(_show(p.description) for p in parsed),
        },
    )


def _preview_catalog_draft_release(base_url: str, config: dict) -> None:
    preview = CatalogsApi(_client(base_url, config)).preview_catalog_draft_release(
        *_catalog_version_address(config)
    )
    _report(
        base_url,
        {
            "base": _show(preview.base),
            "changeKind": _show(_enum(preview.change_kind)),
            "reasons": "|".join(preview.reasons),
            "needed": _show(preview.needed),
            "suggested": _show(preview.suggested),
        },
    )


def _delete_catalog_draft(base_url: str, config: dict) -> None:
    CatalogsApi(_client(base_url, config)).delete_catalog_draft(*_catalog_version_address(config))


def _release_catalog_draft(base_url: str, config: dict) -> None:
    """Releases a draft; each optional member goes out only when the scenario sets it. A refusal is
    reported by its problem, with the dependency drafts it names."""
    optional = {
        name: config[name]
        for name in ("releaseVersion", "notes", "acknowledgedBehind", "releaseDependencies")
        if name in config
    }
    try:
        response = CatalogsApi(_client(base_url, config)).release_catalog_draft(
            *_catalog_version_address(config),
            ReleaseCatalogDraftRequest(**optional),
        )
    except ProblemDetailException as exc:
        _report(
            base_url,
            {
                "problemTypeSlug": exc.type_slug or "<null>",
                "problemStatus": exc.status_code,
                "problemUnreleasedDependencies": "|".join(
                    f"{d.registry}/{d.namespace}/{d.catalog_key}#{d.version_id}={d.draft_name}"
                    for d in exc.unreleased_dependencies
                ),
            },
        )
        return
    _report(
        base_url,
        {
            "releaseVersion": _show(response.catalog_version.release_version),
            "status": _show(_enum(response.catalog_version.status)),
            "versionId": _show(response.catalog_version.version_id),
            "draftsBehind": "|".join(response.drafts_behind),
            "releasedDependencies": ",".join(
                f"{_show(d.registry)}/{_show(d.namespace)}/{_show(d.catalog_key)}@{_show(d.release_version)}"
                for d in response.released_dependencies
            ),
        },
    )


def _deploy_catalog_version(base_url: str, config: dict) -> None:
    """Deploys one catalog version; the request has no body, the version (a release number or a
    draft's ``versionId``) is the last path segment.

    ``includeDependencies`` and ``replace`` are passed only when the scenario sets them, so an unset
    one sends no query parameter. A refusal is reported by the dependency releases it names.
    """
    optional = {}
    if "includeDependencies" in config:
        optional["include_dependencies"] = config["includeDependencies"]
    if "replace" in config:
        optional["replace"] = config["replace"]
    try:
        deployment = EnvironmentsApi(_client(base_url, config)).deploy_catalog_version(
            config["tenantKey"],
            config["environmentKey"],
            config["registry"],
            config["namespace"],
            config["catalogKey"],
            config["catalogVersion"],
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
            "versionId": _show(deployment.version_id),
            "kind": _show(_enum(deployment.kind)),
            "releaseVersion": _show(deployment.release_version),
            "draftName": _show(deployment.draft_name),
            "lastUsedAt": _show_instant(deployment.last_used_at),
            "documentsRequested": _show(deployment.documents_requested),
        },
    )


def _undeploy_catalog_version(base_url: str, config: dict) -> None:
    EnvironmentsApi(_client(base_url, config)).undeploy_catalog_version(
        config["tenantKey"],
        config["environmentKey"],
        config["registry"],
        config["namespace"],
        config["catalogKey"],
        config["catalogVersion"],
    )


def _update_variant_content(base_url: str, config: dict) -> None:
    """Replaces a variant's content with a one-node template model, sending ``ifMatch`` as
    ``If-Match`` only when the scenario sets it. A refusal is reported by its problem."""
    request = UpdateVariantContentRequest.from_dict(
        {
            "templateModel": {
                "modelVersion": 1,
                "root": "root",
                "nodes": {"root": {"id": "root", "type": "root", "slots": []}},
                "slots": {},
                "themeRef": {"type": "inherit"},
            }
        }
    )
    try:
        content = VariantsApi(_client(base_url, config)).update_variant_content(
            config["tenantKey"],
            config["registry"],
            config["namespace"],
            config["catalogKey"],
            config["catalogVersion"],
            config["templateKey"],
            config["variantKey"],
            request,
            if_match=config.get("ifMatch"),
        )
    except ProblemDetailException as exc:
        _report(base_url, {"problemTypeSlug": exc.type_slug or "<null>", "problemStatus": exc.status_code})
        return
    _report(base_url, {"variantKey": content.variant_key})


# --- Content, ready marks and changes ---

_ONE_NODE_MODEL = {
    "modelVersion": 1,
    "root": "root",
    "nodes": {"root": {"id": "root", "type": "root", "slots": []}},
    "slots": {},
    "themeRef": {"type": "inherit"},
}


def _get_variant_content(base_url: str, config: dict) -> None:
    """Reads a variant's content through ``*_with_http_info``, which is how a caller reaches the
    response headers: the ``ETag`` is reported exactly as received, quotes included."""
    response = VariantsApi(_client(base_url, config)).get_variant_content_with_http_info(
        *_catalog_version_address(config), config["templateKey"], config["variantKey"]
    )
    content = response.data
    _report(
        base_url,
        {
            "variantKey": _show(content.variant_key),
            "templateModelRoot": _show(content.template_model.root),
            "status": _show(_enum(content.status)),
            "lastModified": _show_instant(content.last_modified),
            "readyAt": _show_instant(content.ready_at),
            "etag": _show((response.headers or {}).get("ETag")),
        },
    )


def _mark_variant_ready(base_url: str, config: dict) -> None:
    content = VariantsApi(_client(base_url, config)).mark_variant_ready(
        *_catalog_version_address(config), config["templateKey"], config["variantKey"]
    )
    _report(
        base_url,
        {
            "variantKey": _show(content.variant_key),
            "status": _show(_enum(content.status)),
            "lastModified": _show_instant(content.last_modified),
            "readyAt": _show_instant(content.ready_at),
        },
    )


def _mark_template_ready(base_url: str, config: dict) -> None:
    template = TemplatesApi(_client(base_url, config)).mark_template_ready(
        *_catalog_version_address(config), config["templateKey"]
    )
    _report(
        base_url,
        {
            "key": _show(template.key),
            "name": _show(template.name),
            "variantKeys": ",".join(_show(v.key) for v in template.variants),
            "variantStatuses": ",".join(_show(_enum(v.status)) for v in template.variants),
        },
    )


def _schema_type(schema) -> str:
    return _show(schema.get("type") if schema is not None else None)


def _get_stencil_content(base_url: str, config: dict) -> None:
    """Reads a stencil's content through ``*_with_http_info``, reporting the ``ETag`` as received."""
    response = StencilsApi(_client(base_url, config)).get_stencil_content_with_http_info(
        *_catalog_version_address(config), config["stencilKey"]
    )
    content = response.data
    _report(
        base_url,
        {
            "stencilKey": _show(content.stencil_key),
            "contentRoot": _show(content.content.root),
            "parameterSchemaType": _schema_type(content.parameter_schema),
            "status": _show(_enum(content.status)),
            "lastModified": _show_instant(content.last_modified),
            "readyAt": _show_instant(content.ready_at),
            "etag": _show((response.headers or {}).get("ETag")),
        },
    )


def _update_stencil_content(base_url: str, config: dict) -> None:
    """Replaces a stencil's content with a one-node template model and ``parameterSchema``, sending
    ``ifMatch`` as ``If-Match``, and reports the new ``ETag`` the update answered with."""
    request = UpdateStencilContentRequest.from_dict(
        {"content": _ONE_NODE_MODEL, "parameterSchema": config["parameterSchema"]}
    )
    response = StencilsApi(_client(base_url, config)).update_stencil_content_with_http_info(
        *_catalog_version_address(config),
        config["stencilKey"],
        request,
        if_match=config.get("ifMatch"),
    )
    content = response.data
    _report(
        base_url,
        {
            "stencilKey": _show(content.stencil_key),
            "status": _show(_enum(content.status)),
            "readyAt": _show_instant(content.ready_at),
            "etag": _show((response.headers or {}).get("ETag")),
        },
    )


def _mark_stencil_ready(base_url: str, config: dict) -> None:
    response = StencilsApi(_client(base_url, config)).mark_stencil_ready(
        *_catalog_version_address(config), config["stencilKey"]
    )
    _report(
        base_url,
        {
            "stencilKey": _show(response.stencil.stencil_key),
            "status": _show(_enum(response.stencil.status)),
            "readyAt": _show_instant(response.stencil.ready_at),
            "parameterSchemaType": _schema_type(response.stencil.parameter_schema),
            "notFitting": ",".join(
                f"{_show(u.template_key)}/{_show(u.variant_key)}:{_show_bool(u.fits)}" for u in response.not_fitting
            ),
            "notFittingProblems": ";".join("|".join(u.problems) for u in response.not_fitting),
        },
    )


def _list_stencil_usage(base_url: str, config: dict) -> None:
    response = StencilsApi(_client(base_url, config)).list_stencil_usage(
        *_catalog_version_address(config), config["stencilKey"], page=config["page"], size=config["size"]
    )
    _report(
        base_url,
        {
            "templateKeys": ",".join(_show(u.template_key) for u in response.items),
            "templateNames": "|".join(_show(u.template_name) for u in response.items),
            "variantKeys": ",".join(_show(u.variant_key) for u in response.items),
            "fits": ",".join(_show_bool(u.fits) for u in response.items),
            "problems": ";".join("|".join(u.problems) for u in response.items),
            "pageNumber": _show(response.page.number),
            "totalElements": _show(response.page.total_elements),
        },
    )


def _get_catalog_changes(base_url: str, config: dict) -> None:
    _report_changes(base_url, CatalogsApi(_client(base_url, config)).get_catalog_changes(*_catalog_version_address(config)))


def _mark_catalog_ready(base_url: str, config: dict) -> None:
    _report_changes(base_url, CatalogsApi(_client(base_url, config)).mark_catalog_ready(*_catalog_version_address(config)))


def _report_changes(base_url: str, changes) -> None:
    _report(
        base_url,
        {
            "base": _show(changes.base),
            "releasable": _show_bool(changes.releasable),
            "dependenciesChanged": _show_bool(changes.dependencies_changed),
            "changes": ",".join(f"{_show(c.type)}/{_show(c.key)}:{_show(_enum(c.change))}" for c in changes.changes),
        },
    )


def _list_environment_deployments(base_url: str, config: dict) -> None:
    items = (
        EnvironmentsApi(_client(base_url, config))
        .list_environment_deployments(config["tenantKey"], config["environmentKey"])
        .items
    )
    _report(
        base_url,
        {
            "registries": ",".join(_show(d.registry) for d in items),
            "catalogKeys": ",".join(_show(d.catalog_key) for d in items),
            "versionIds": ",".join(_show(d.version_id) for d in items),
            "kinds": ",".join(_show(_enum(d.kind)) for d in items),
            "releaseVersions": ",".join(_show(d.release_version) for d in items),
            "draftNames": "|".join(_show(d.draft_name) for d in items),
            "deployedAt": ",".join(_show_instant(d.deployed_at) for d in items),
            "lastUsedAt": ",".join(_show_instant(d.last_used_at) for d in items),
            "documentsRequested": ",".join(_show(d.documents_requested) for d in items),
        },
    )


def _list_deployment_history(base_url: str, config: dict) -> None:
    response = EnvironmentsApi(_client(base_url, config)).list_deployment_history(
        config["tenantKey"], config["environmentKey"], page=config["page"], size=config["size"]
    )
    items = response.items
    _report(
        base_url,
        {
            "catalogKeys": ",".join(_show(e.catalog_key) for e in items),
            "actions": ",".join(_show(_enum(e.action)) for e in items),
            "versionIds": ",".join(_show(e.version_id) for e in items),
            "kinds": ",".join(_show(_enum(e.kind)) for e in items),
            "releaseVersions": ",".join(_show(e.release_version) for e in items),
            "draftNames": "|".join(_show(e.draft_name) for e in items),
            "changedAt": ",".join(_show_instant(e.changed_at) for e in items),
            "changedBy": "|".join(_show(e.changed_by) for e in items),
            "totalElements": _show(response.page.total_elements),
        },
    )


def _create_environment(base_url: str, config: dict) -> None:
    """Creates an environment; ``production`` goes out only when the scenario sets it."""
    optional = {"production": config["production"]} if "production" in config else {}
    environment = EnvironmentsApi(_client(base_url, config)).create_environment(
        config["tenantKey"],
        CreateEnvironmentRequest(key=config["key"], name=config["name"], **optional),
    )
    _report_environment(base_url, environment)


def _update_environment(base_url: str, config: dict) -> None:
    """A partial update: each member goes out only when the scenario sets it. A refusal is
    reported by its problem."""
    optional = {name: config[name] for name in ("name", "production") if name in config}
    try:
        environment = EnvironmentsApi(_client(base_url, config)).update_environment(
            config["tenantKey"],
            config["environmentKey"],
            UpdateEnvironmentRequest(**optional),
        )
    except ProblemDetailException as exc:
        _report(base_url, {"problemTypeSlug": exc.type_slug or "<null>", "problemStatus": exc.status_code})
        return
    _report_environment(base_url, environment)


def _report_environment(base_url: str, environment) -> None:
    _report(
        base_url,
        {
            "key": _show(environment.key),
            "name": _show(environment.name),
            "production": _show_bool(environment.production),
        },
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


def _show_bool(value) -> str:
    """A boolean as JSON spells it; ``str()`` of one is ``True``."""
    return "null" if value is None else ("true" if value else "false")


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
