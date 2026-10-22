# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""The Python client's conformance driver.

Asks the conformance server what to do, does it with the published client, and reports back. It
asserts nothing — the server judges the requests, so the five clients are held to one set of
expectations rather than five that drift. See ../../README.md for the driver contract.
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
    AttributesApi,
    CatalogDependencyRequirement,
    CatalogPresetDto,
    CatalogThemeReference,
    CatalogsApi,
    ClientIdentity,
    CodeListsApi,
    ConsumersApi,
    CreateCatalogReleaseRequest,
    CreateEnvironmentRequest,
    EnvironmentsApi,
    EpistolaClientBuilder,
    GenerateDocumentRequest,
    GenerationApi,
    ImagesApi,
    JwtSigner,
    MergeIntoCatalogDraftRequest,
    PingRequest,
    PublishReleaseToRegistryRequest,
    ProblemDetailException,
    ReleaseCatalogDraftRequest,
    ResultCollector,
    StencilsApi,
    SystemApi,
    TemplateDataValidationError,
    TemplateSchemaValidator,
    TemplatesApi,
    TenantsApi,
    UpdateAttributeRequest,
    UpdateAttributeRequestCodeListBinding,
    UpdateCatalogDependenciesRequest,
    UpdateCatalogReleaseRequest,
    UpdateCodeListEntryHiddenRequest,
    UpdateConsumerRequest,
    UpdateEnvironmentRequest,
    UpdateStencilContentRequest,
    UpdateTenantRequest,
    UpdateVariantContentRequest,
    VariantSelectionAttribute,
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
        "create-catalog-release": _create_catalog_release,
        "list-catalog-releases": _list_catalog_releases,
        "get-catalog-release": _get_catalog_release,
        "update-catalog-release": _update_catalog_release,
        "set-catalog-release-theme-settings": _set_catalog_release_theme_settings,
        "preview-catalog-draft-release": _preview_catalog_draft_release,
        "delete-catalog-release": _delete_catalog_release,
        "release-catalog-draft": _release_catalog_draft,
        "preview-catalog-draft-merge": _preview_catalog_draft_merge,
        "get-catalog-draft-merge-sides": _get_catalog_draft_merge_sides,
        "merge-into-catalog-draft": _merge_into_catalog_draft,
        "list-release-registries": _list_release_registries,
        "list-registry-releases": _list_registry_releases,
        "install-registry-release": _install_registry_release,
        "publish-release-to-registry": _publish_release_to_registry,
        "deploy-catalog-release": _deploy_catalog_release,
        "undeploy-catalog-release": _undeploy_catalog_release,
        "create-environment": _create_environment,
        "update-environment": _update_environment,
        "update-tenant": _update_tenant,
        "update-code-list-entry-hidden": _update_code_list_entry_hidden,
        "update-attribute": _update_attribute,
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
            config["tenantKey"], config["registry"], config["namespace"], config["catalogKey"], config["release"]
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
            config["tenantKey"], config["registry"], config["namespace"], config["catalogKey"], config["release"]
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
                f"{_show(r.registry)}/{_show(r.namespace)}/{_show(r.catalog_key)}@{_show(r.version)}"
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
    if config.get("attributes") is not None:
        optional["attributes"] = [
            VariantSelectionAttribute(
                key=attribute["key"],
                value=attribute["value"],
                **{
                    name: attribute[name]
                    for name in ("catalogKey", "required")
                    if name in attribute
                },
            )
            for attribute in config["attributes"]
        ]
    response = GenerationApi(_client(base_url, config)).generate_document_with_http_info(
        config["tenantKey"],
        GenerateDocumentRequest(
            namespace=config["namespace"],
            catalogKey=config["catalogKey"],
            templateKey=config["templateKey"],
            data=config["data"],
            **optional,
        ),
    )
    job = response.data
    _report(
        base_url,
        {
            "requestId": _show(job.request_id),
            "status": _show(_enum(job.status)),
            "jobType": _show(_enum(job.job_type)),
            "total": job.total,
            "location": _header(response, "Location"),
        },
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
            config["release"],
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
        config["release"],
        (config["filename"], base64.b64decode(config["fileBase64"])),
        name=config["name"],
    )
    _report(base_url, {"imageKey": image.key, "imageName": image.name})


def _import_catalog(base_url: str, config: dict) -> None:
    """Imports a catalog archive, the file as a ``(filename, bytes)`` pair like an image upload.
    ``registry`` and ``namespace`` go out only when the scenario sets them. A refusal is reported by
    its problem."""
    optional = {
        name: config[key]
        for key, name in (("registry", "registry"), ("namespace", "namespace"))
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
            "version": _show(imported.version),
            "newlyInstalled": _show_bool(imported.newly_installed),
            "total": _show(imported.total),
        },
    )


def _install_catalog_release(base_url: str, config: dict) -> None:
    """Installs a release a catalog's source offers, named in the path. A refusal or a
    failing source is reported by its problem, missing dependencies included."""
    try:
        installed = CatalogsApi(_client(base_url, config)).install_catalog_release(
            config["tenantKey"],
            config["registry"],
            config["namespace"],
            config["catalogKey"],
            config["version"],
        )
    except ProblemDetailException as exc:
        _report(
            base_url,
            {
                "problemTypeSlug": exc.type_slug or "<null>",
                "problemStatus": exc.status_code,
                "problemMissingDependencies": ",".join(
                    f"{d.registry}/{d.namespace}/{d.catalog_key}@{d.version}:{d.reason}"
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
            "version": _show(installed.version),
            "newlyInstalled": _show_bool(installed.newly_installed),
            "total": _show(installed.total),
            "installedDependencies": ",".join(installed.installed_dependencies or []),
            "presentDependencies": ",".join(installed.present_dependencies or []),
        },
    )


def _list_available_catalog_releases(base_url: str, config: dict) -> None:
    """Lists the releases a catalog's source offers that the tenant lacks, reporting
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
            "availableComparedTo": ";".join(f"{_show(r.version)}={_show(r.compared_to)}" for r in releases.available),
            "availableChanges": ";".join(
                f"{_show(r.version)}="
                + "+".join(f"{_show(c.type)}/{_show(c.key)}:{_show(_enum(c.change))}" for c in r.changes)
                for r in releases.available
            ),
            "availableDependencies": ";".join(
                f"{_show(r.version)}="
                + "+".join(
                    f"{_show(d.registry)}/{_show(d.namespace)}/{_show(d.catalog_key)}"
                    f"@{_show(d.version)}:{_show_bool(d.present)}"
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
        config["release"],
        config["imageKey"],
    )
    _report_bytes(base_url, bytes(content))


def _delete_image(base_url: str, config: dict) -> None:
    ImagesApi(_client(base_url, config)).delete_image(
        config["tenantKey"],
        config["registry"],
        config["namespace"],
        config["catalogKey"],
        config["release"],
        config["imageKey"],
        force=config["force"],
    )


def _list_catalog_dependencies(base_url: str, config: dict) -> None:
    response = CatalogsApi(_client(base_url, config)).list_catalog_dependencies(
        config["tenantKey"], config["registry"], config["namespace"], config["catalogKey"], config["release"]
    )
    _report_dependencies(base_url, response.items)


def _update_catalog_dependencies(base_url: str, config: dict) -> None:
    """Replaces a release's dependency declarations, conditional on ``ifMatch`` when the scenario
    sets it. A refusal is reported by its problem."""
    request = UpdateCatalogDependenciesRequest(
        dependencies=[
            CatalogDependencyRequirement(
                # Only when the entry names one: omitted means the catalog's key.
                **({"alias": dependency["alias"]} if "alias" in dependency else {}),
                # Only when the entry names one: omitted means the server's default registry.
                **({"registry": dependency["registry"]} if "registry" in dependency else {}),
                namespace=dependency["namespace"],
                catalogKey=dependency["catalogKey"],
                # Exactly one of these names what the declaration uses: only the one given.
                **{name: dependency[name] for name in ("version", "releaseId") if name in dependency},
            )
            for dependency in config["dependencies"]
        ]
    )
    try:
        response = CatalogsApi(_client(base_url, config)).update_catalog_dependencies_with_http_info(
            *_release_address(config),
            request,
            if_match=config.get("ifMatch"),
        )
    except ProblemDetailException as exc:
        _report_problem(base_url, exc)
        return
    _report_dependencies(base_url, response.data.items, {"etag": _header(response, "ETag")})


def _report_dependencies(base_url: str, dependencies, extra: dict | None = None) -> None:
    _report(
        base_url,
        {
            **(extra or {}),
            "aliases": ",".join(_show(d.alias) for d in dependencies),
            "registries": ",".join(_show(d.registry) for d in dependencies),
            "namespaces": ",".join(_show(d.namespace) for d in dependencies),
            "catalogKeys": ",".join(_show(d.catalog_key) for d in dependencies),
            # A declaration that pins no release (migrated from wire v7) reports ``null``.
            "versions": ",".join(_show(d.version) for d in dependencies),
            "releaseIds": ",".join(_show(d.release_id) for d in dependencies),
            "contentHashes": ",".join(_show(d.content_hash) for d in dependencies),
        },
    )


def _release_address(config: dict) -> tuple:
    return (config["tenantKey"], config["registry"], config["namespace"], config["catalogKey"], config["release"])


def _create_catalog_release(base_url: str, config: dict) -> None:
    """Starts a draft. ``from`` is a Python keyword, so the generated model names the member
    ``var_from``; it is passed by its alias, and only when the scenario sets it."""
    optional = {"from": config["from"]} if "from" in config else {}
    response = CatalogsApi(_client(base_url, config)).create_catalog_release_with_http_info(
        config["tenantKey"],
        config["registry"],
        config["namespace"],
        config["catalogKey"],
        CreateCatalogReleaseRequest(name=config["name"], **optional),
    )
    created = response.data
    _report(
        base_url,
        {
            "version": _show(created.version),
            "releaseId": _show(created.release_id),
            "name": _show(created.name),
            "status": _show(_enum(created.status)),
            "base": _show(created.base),
            "createdFrom": _show(created.created_from),
            "releasedAt": _show_instant(created.released_at),
            "location": _header(response, "Location"),
        },
    )


def _list_catalog_releases(base_url: str, config: dict) -> None:
    # Each filter only when the scenario names it: an absent one stays off the query.
    optional = {arg: config[name] for name, arg in (("status", "status"), ("contentHash", "content_hash")) if name in config}
    releases = (
        CatalogsApi(_client(base_url, config))
        .list_catalog_releases(
            config["tenantKey"], config["registry"], config["namespace"], config["catalogKey"], **optional
        )
        .items
    )
    _report(
        base_url,
        {
            "versions": ",".join(_show(v.version) for v in releases),
            "releaseIds": ",".join(_show(v.release_id) for v in releases),
            "names": "|".join(_show(v.name) for v in releases),
            "statuses": ",".join(_show(_enum(v.status)) for v in releases),
            "bases": ",".join(_show(v.base) for v in releases),
            "createdFrom": ",".join(_show(v.created_from) for v in releases),
            "behindCounts": ",".join(str(len(v.behind)) for v in releases),
            "contentHashes": ",".join(_show(v.content_hash) for v in releases),
        },
    )


def _get_catalog_release(base_url: str, config: dict) -> None:
    release = CatalogsApi(_client(base_url, config)).get_catalog_release(
        *_release_address(config)
    )
    _report(
        base_url,
        {
            "version": _show(release.version),
            "releaseId": _show(release.release_id),
            "name": _show(release.name),
            "status": _show(_enum(release.status)),
            "releasedAt": _show_instant(release.released_at),
            "fingerprint": _show(release.fingerprint),
            "contentHash": _show(release.content_hash),
            "dependencies": ",".join(
                f"{_show(d.registry)}/{_show(d.namespace)}/{_show(d.catalog_key)}@{_show(d.version)}"
                for d in release.dependencies
            ),
            "dependencyAliases": ",".join(_show(d.alias) for d in release.dependencies),
            "dependencyContentHashes": ",".join(_show(d.content_hash) for d in release.dependencies),
            "deployedTo": ",".join(release.deployed_to),
            "behind": ";".join(
                f"{_show(b.version)}="
                + "+".join(f"{_show(c.type)}/{_show(c.key)}:{_show(_enum(c.change))}" for c in b.changes)
                for b in release.behind
            ),
        },
    )


def _update_catalog_release(base_url: str, config: dict) -> None:
    """Renames a draft, conditional on ``ifMatch`` when the scenario sets it. A refusal is
    reported by its problem."""
    try:
        response = CatalogsApi(_client(base_url, config)).update_catalog_release_with_http_info(
            *_release_address(config),
            UpdateCatalogReleaseRequest(name=config["name"]),
            if_match=config.get("ifMatch"),
        )
    except ProblemDetailException as exc:
        _report_problem(base_url, exc)
        return
    release = response.data
    _report(
        base_url,
        {
            "version": _show(release.version),
            "releaseId": _show(release.release_id),
            "name": _show(release.name),
            "status": _show(_enum(release.status)),
            "base": _show(release.base),
            "etag": _header(response, "ETag"),
        },
    )


def _set_catalog_release_theme_settings(base_url: str, config: dict) -> None:
    """Sets or removes a draft's default theme and presets; no name goes out, and a member or
    qualifier the scenario does not set stays off the wire. An empty preset list goes out as is."""
    members = {}
    if config.get("defaultThemeKey") is not None:
        theme_qualifiers = {"catalog_key": config["defaultThemeCatalogKey"]} if config.get("defaultThemeCatalogKey") is not None else {}
        members["default_theme"] = CatalogThemeReference(key=config["defaultThemeKey"], **theme_qualifiers)
    if config.get("clearDefaultTheme") is not None:
        members["clear_default_theme"] = config["clearDefaultTheme"]
    if config.get("presets") is not None:
        members["presets"] = [
            CatalogPresetDto(
                key=entry["key"],
                name=entry["name"],
                **({"description": entry["description"]} if entry.get("description") is not None else {}),
            )
            for entry in config["presets"]
        ]
    release = CatalogsApi(_client(base_url, config)).update_catalog_release(
        *_release_address(config),
        UpdateCatalogReleaseRequest(**members),
    )
    parsed = release.presets or []
    theme = release.default_theme
    _report(
        base_url,
        {
            "releaseId": _show(release.release_id),
            "name": _show(release.name),
            "defaultThemeKey": _show(theme.key if theme else None),
            "defaultThemeCatalogKey": _show(theme.catalog_key if theme else None),
            "presetKeys": ",".join(p.key for p in parsed),
            "presetNames": "|".join(p.name for p in parsed),
            "presetDescriptions": "|".join(_show(p.description) for p in parsed),
        },
    )


def _preview_catalog_draft_release(base_url: str, config: dict) -> None:
    preview = CatalogsApi(_client(base_url, config)).preview_catalog_draft_release(
        *_release_address(config)
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


def _delete_catalog_release(base_url: str, config: dict) -> None:
    CatalogsApi(_client(base_url, config)).delete_catalog_release(*_release_address(config))


def _release_catalog_draft(base_url: str, config: dict) -> None:
    """Releases a draft; each optional member goes out only when the scenario sets it. A refusal is
    reported by its problem, with the dependency drafts it names."""
    optional = {
        name: config[name]
        for name in ("version", "notes", "acknowledgedBehind", "releaseDependencies")
        if name in config
    }
    try:
        response = CatalogsApi(_client(base_url, config)).release_catalog_draft(
            *_release_address(config),
            ReleaseCatalogDraftRequest(**optional),
        )
    except ProblemDetailException as exc:
        _report(
            base_url,
            {
                "problemTypeSlug": exc.type_slug or "<null>",
                "problemStatus": exc.status_code,
                "problemUnreleasedDependencies": "|".join(
                    f"{d.registry}/{d.namespace}/{d.catalog_key}#{d.release_id}={d.release_name}"
                    for d in exc.unreleased_dependencies
                ),
            },
        )
        return
    _report(
        base_url,
        {
            "version": _show(response.release.version),
            "status": _show(_enum(response.release.status)),
            "releaseId": _show(response.release.release_id),
            "contentHash": _show(response.release.content_hash),
            "unchangedContent": _show_bool(response.unchanged_content),
            "draftsBehind": "|".join(response.drafts_behind),
            "releasedDependencies": ",".join(
                f"{_show(d.registry)}/{_show(d.namespace)}/{_show(d.catalog_key)}@{_show(d.version)}"
                for d in response.released_dependencies
            ),
        },
    )


def _preview_catalog_draft_merge(base_url: str, config: dict) -> None:
    """Previews merging the release ``config.version`` into the draft ``config.release`` (alpha),
    the release as the ``version`` query parameter."""
    # The generator puts the operation's own query parameter before the path's parameters.
    preview = CatalogsApi(_client(base_url, config)).preview_catalog_draft_merge(
        config["version"], *_release_address(config)
    )
    _report(
        base_url,
        {
            "draft": _show(preview.draft),
            "base": _show(preview.base),
            "release": _show(preview.release),
            "alreadyMerged": _show_bool(preview.already_merged),
            "resources": ",".join(
                f"{_show(r.resource_type)}/{_show(r.resource_key)}:{_show(_enum(r.outcome))}"
                f":{_show(_enum(r.draft_change))}:{_show(_enum(r.release_change))}"
                for r in preview.resources
            ),
            "resourceNames": "|".join(_show(r.name) for r in preview.resources),
            "resourceNeeds": ";".join(
                f"{_show(r.resource_type)}/{_show(r.resource_key)}={'+'.join(r.needs)}"
                for r in preview.resources
                if r.needs
            ),
            "unchanged": _show(preview.unchanged),
            "token": _show(preview.token),
            "missingBase": (
                f"{preview.missing_base.version}@{preview.missing_base.fingerprint}"
                if preview.missing_base is not None
                else "null"
            ),
            "missingBaseContentHash": _show(None if preview.missing_base is None else preview.missing_base.content_hash),
        },
    )


def _merge_side(content: dict | None) -> str:
    """One side of a merge entry as ``type/key:name``, or ``null`` when the side lacks it."""
    if content is None:
        return "null"
    return f"{_show(content.get('type'))}/{_show(content.get('key'))}:{_show(content.get('name'))}"


def _get_catalog_draft_merge_sides(base_url: str, config: dict) -> None:
    """Reads the three sides of ``config.entry`` in merging ``config.version`` into the draft
    ``config.release`` (alpha)."""
    # The generator puts the operation's own query parameters before the path's parameters.
    sides = CatalogsApi(_client(base_url, config)).get_catalog_draft_merge_sides(
        config["version"], config["entry"], *_release_address(config)
    )
    _report(
        base_url,
        {
            "entry": _show(sides.entry),
            "mergeBase": _show(sides.merge_base),
            "token": _show(sides.token),
            "base": _merge_side(sides.base),
            "draft": _merge_side(sides.draft),
            "release": _merge_side(sides.release),
            "draftMembers": "null" if sides.draft is None else ",".join(sorted(sides.draft)),
        },
    )


def _report_problem_only(base_url: str, exc: ProblemDetailException) -> None:
    _report(base_url, {"problemTypeSlug": exc.type_slug or "<null>", "problemStatus": exc.status_code})


def _list_release_registries(base_url: str, config: dict) -> None:
    """Lists the release registries (alpha), each as ``name:kind``."""
    registries = CatalogsApi(_client(base_url, config)).list_release_registries(config["tenantKey"])
    _report(
        base_url,
        {"registries": ",".join(f"{_show(r.name)}:{_show(_enum(r.kind))}" for r in registries.items)},
    )


def _list_registry_releases(base_url: str, config: dict) -> None:
    """Lists the releases of a catalog in a release registry (alpha), each as
    ``version:fingerprint:releaseId:base``, the base as ``version@fingerprint``, a missing value as
    ``null``."""
    try:
        releases = CatalogsApi(_client(base_url, config)).list_registry_releases(
            config["tenantKey"], config["name"], config["namespace"], config["catalogKey"]
        )
    except ProblemDetailException as exc:
        _report_problem_only(base_url, exc)
        return

    def entry(r) -> str:
        base = "null" if r.base is None else f"{_show(r.base.version)}@{_show(r.base.fingerprint)}"
        return f"{_show(r.version)}:{_show(r.fingerprint)}:{_show(r.release_id)}:{base}"

    _report(
        base_url,
        {
            "registry": _show(releases.registry),
            "namespace": _show(releases.namespace),
            "catalogKey": _show(releases.catalog_key),
            "releases": ",".join(entry(r) for r in releases.items),
            "contentHashes": ",".join(_show(r.content_hash) for r in releases.items),
            "baseContentHashes": ",".join(_show(None if r.base is None else r.base.content_hash) for r in releases.items),
        },
    )


def _install_registry_release(base_url: str, config: dict) -> None:
    """Installs a release from a release registry (alpha). A refusal is reported by its problem."""
    try:
        installed = CatalogsApi(_client(base_url, config)).install_registry_release(
            config["tenantKey"], config["name"], config["namespace"], config["catalogKey"], config["version"]
        )
    except ProblemDetailException as exc:
        _report_problem_only(base_url, exc)
        return
    _report(
        base_url,
        {
            "registry": _show(installed.registry),
            "namespace": _show(installed.namespace),
            "catalogKey": _show(installed.catalog_key),
            "version": _show(installed.version),
            "newlyInstalled": _show_bool(installed.newly_installed),
            "total": _show(installed.total),
        },
    )


def _publish_release_to_registry(base_url: str, config: dict) -> None:
    """Publishes a released release to the release registry ``config.to`` (alpha)."""
    try:
        published = CatalogsApi(_client(base_url, config)).publish_release_to_registry(
            config["tenantKey"],
            config["registry"],
            config["namespace"],
            config["catalogKey"],
            config["release"],
            PublishReleaseToRegistryRequest(to=config["to"]),
        )
    except ProblemDetailException as exc:
        _report_problem_only(base_url, exc)
        return
    _report(
        base_url,
        {
            "status": _show(_enum(published.status)),
            "registry": _show(published.registry),
            "version": _show(published.version),
            "digest": _show(published.digest),
        },
    )


def _merge_into_catalog_draft(base_url: str, config: dict) -> None:
    """Merges the release ``config.version`` into the draft ``config.release`` (alpha), with
    ``config.resolutions`` when the scenario sets them. A refusal is reported by its problem, with
    the conflicts it lists and the read-only reason it gives."""
    optional = {
        field: config[key]
        for key, field in (
            ("resolutions", "resolutions"),
            ("mergedContent", "merged_content"),
            ("expectedToken", "expected_token"),
        )
        if key in config
    }
    try:
        merged = CatalogsApi(_client(base_url, config)).merge_into_catalog_draft(
            *_release_address(config),
            MergeIntoCatalogDraftRequest(release=config["version"], **optional),
        )
    except ProblemDetailException as exc:
        _report(
            base_url,
            {
                "problemTypeSlug": exc.type_slug or "<null>",
                "problemStatus": exc.status_code,
                "problemUnresolvedConflicts": ",".join(exc.unresolved_conflicts),
                "problemReadOnlyReason": _show(exc.read_only_reason),
                "problemMissingBase": (
                    f"{exc.missing_base_version}@{exc.missing_base_fingerprint}"
                    if exc.missing_base_version is not None
                    else "null"
                ),
            },
        )
        return
    _report(
        base_url,
        {
            "release": _show(merged.release),
            "taken": ",".join(merged.taken),
            "removed": ",".join(merged.removed),
            "kept": ",".join(merged.kept),
            "merged": ",".join(merged.merged or []),
        },
    )


def _deploy_catalog_release(base_url: str, config: dict) -> None:
    """Deploys one release of a catalog; the request has no body, the release (a version or a
    draft's ``releaseId``) is the last path segment.

    ``includeDependencies`` and ``replace`` are passed only when the scenario sets them, so an unset
    one sends no query parameter. A refusal is reported by the dependency releases it names.
    """
    optional = {}
    if "includeDependencies" in config:
        optional["include_dependencies"] = config["includeDependencies"]
    if "replace" in config:
        optional["replace"] = config["replace"]
    try:
        deployment = EnvironmentsApi(_client(base_url, config)).deploy_catalog_release(
            config["tenantKey"],
            config["environmentKey"],
            config["registry"],
            config["namespace"],
            config["catalogKey"],
            config["release"],
            **optional,
        )
    except ProblemDetailException as exc:
        _report(
            base_url,
            {
                "problemTypeSlug": exc.type_slug or "<null>",
                "problemStatus": exc.status_code,
                "problemMissingDependencies": ",".join(
                    f"{d.registry}/{d.namespace}/{d.catalog_key}@{d.version}:{d.reason}"
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
            "releaseId": _show(deployment.release_id),
            "status": _show(_enum(deployment.status)),
            "version": _show(deployment.version),
            "releaseName": _show(deployment.release_name),
            "lastUsedAt": _show_instant(deployment.last_used_at),
            "documentsRequested": _show(deployment.documents_requested),
        },
    )


def _undeploy_catalog_release(base_url: str, config: dict) -> None:
    EnvironmentsApi(_client(base_url, config)).undeploy_catalog_release(
        config["tenantKey"],
        config["environmentKey"],
        config["registry"],
        config["namespace"],
        config["catalogKey"],
        config["release"],
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
            config["release"],
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
        *_release_address(config), config["templateKey"], config["variantKey"]
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
        *_release_address(config), config["templateKey"], config["variantKey"]
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
        *_release_address(config), config["templateKey"]
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
        *_release_address(config), config["stencilKey"]
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
        *_release_address(config),
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
        *_release_address(config), config["stencilKey"]
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
        *_release_address(config), config["stencilKey"], page=config["page"], size=config["size"]
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
            "total": _show(response.page.total),
        },
    )


def _get_catalog_changes(base_url: str, config: dict) -> None:
    _report_changes(base_url, CatalogsApi(_client(base_url, config)).get_catalog_changes(*_release_address(config)))


def _mark_catalog_ready(base_url: str, config: dict) -> None:
    _report_changes(base_url, CatalogsApi(_client(base_url, config)).mark_catalog_ready(*_release_address(config)))


def _report_changes(base_url: str, changes) -> None:
    _report(
        base_url,
        {
            "base": _show(changes.base),
            "releasable": _show_bool(changes.releasable),
            "dependenciesChanged": _show_bool(changes.dependencies_changed),
            "changes": ",".join(f"{_show(c.type)}/{_show(c.key)}:{_show(_enum(c.change))}" for c in changes.changes),
            "mergedForReview": ",".join(f"{_show(c.type)}/{_show(c.key)}" for c in changes.changes if c.merged_for_review),
            "catalogChanges": ",".join(
                f"{_show(c.type)}/{_show(c.key)}:{_show(_enum(c.change))}" for c in changes.catalog_changes
            ),
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
            "releaseIds": ",".join(_show(d.release_id) for d in items),
            "statuses": ",".join(_show(_enum(d.status)) for d in items),
            "versions": ",".join(_show(d.version) for d in items),
            "releaseNames": "|".join(_show(d.release_name) for d in items),
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
            "releaseIds": ",".join(_show(e.release_id) for e in items),
            "statuses": ",".join(_show(_enum(e.status)) for e in items),
            "versions": ",".join(_show(e.version) for e in items),
            "releaseNames": "|".join(_show(e.release_name) for e in items),
            "changedAt": ",".join(_show_instant(e.changed_at) for e in items),
            "changedBy": "|".join(_show(e.changed_by) for e in items),
            "total": _show(response.page.total),
        },
    )


def _create_environment(base_url: str, config: dict) -> None:
    """Creates an environment; ``production`` goes out only when the scenario sets it."""
    optional = {"production": config["production"]} if "production" in config else {}
    response = EnvironmentsApi(_client(base_url, config)).create_environment_with_http_info(
        config["tenantKey"],
        CreateEnvironmentRequest(key=config["key"], name=config["name"], **optional),
    )
    _report_environment(base_url, response.data, {"location": _header(response, "Location")})


def _update_environment(base_url: str, config: dict) -> None:
    """A partial update: each member goes out only when the scenario sets it, conditional on
    ``ifMatch`` when the scenario sets it. A refusal is reported by its problem."""
    optional = {name: config[name] for name in ("name", "production") if name in config}
    try:
        response = EnvironmentsApi(_client(base_url, config)).update_environment_with_http_info(
            config["tenantKey"],
            config["environmentKey"],
            UpdateEnvironmentRequest(**optional),
            if_match=config.get("ifMatch"),
        )
    except ProblemDetailException as exc:
        _report_problem(base_url, exc)
        return
    _report_environment(base_url, response.data, {"etag": _header(response, "ETag")})


def _update_tenant(base_url: str, config: dict) -> None:
    """Renames a tenant, conditional on ``ifMatch`` when the scenario sets it. A refusal is
    reported by its problem."""
    try:
        response = TenantsApi(_client(base_url, config)).update_tenant_with_http_info(
            config["tenantKey"],
            UpdateTenantRequest(name=config["name"]),
            if_match=config.get("ifMatch"),
        )
    except ProblemDetailException as exc:
        _report_problem(base_url, exc)
        return
    tenant = response.data
    _report(
        base_url,
        {"key": _show(tenant.key), "name": _show(tenant.name), "etag": _header(response, "ETag")},
    )


def _update_code_list_entry_hidden(base_url: str, config: dict) -> None:
    """Hides or shows one code-list entry, conditional on the code list's ``ifMatch`` when the
    scenario sets it. A refusal is reported by its problem."""
    try:
        response = CodeListsApi(_client(base_url, config)).update_code_list_entry_hidden_with_http_info(
            *_release_address(config),
            config["codeListKey"],
            config["code"],
            UpdateCodeListEntryHiddenRequest(hidden=config["hidden"]),
            if_match=config.get("ifMatch"),
        )
    except ProblemDetailException as exc:
        _report_problem(base_url, exc)
        return
    entry = response.data
    _report(
        base_url,
        {"code": _show(entry.code), "hidden": _show_bool(entry.hidden), "etag": _header(response, "ETag")},
    )


def _update_attribute(base_url: str, config: dict) -> None:
    """Changes an attribute's constraint with only the members the scenario names, conditional on
    ``ifMatch`` when it sets one. A refusal is reported by its problem."""
    members = {
        attr: config[key]
        for key, attr in (("allowedValues", "allowed_values"), ("clearCodeListBinding", "clear_code_list_binding"))
        if config.get(key) is not None
    }
    # A binding names its catalog by alias, or by nothing for the attribute's own.
    if config.get("codeListBinding") is not None:
        members["code_list_binding"] = UpdateAttributeRequestCodeListBinding(
            key=config["codeListBinding"]["key"],
            **({"catalogKey": config["codeListBinding"]["catalogKey"]} if "catalogKey" in config["codeListBinding"] else {}),
        )
    try:
        response = AttributesApi(_client(base_url, config)).update_attribute_with_http_info(
            *_release_address(config),
            config["attributeKey"],
            UpdateAttributeRequest(**members),
            if_match=config.get("ifMatch"),
        )
    except ProblemDetailException as exc:
        _report_problem(base_url, exc)
        return
    attribute = response.data
    binding = attribute.code_list_binding
    _report(
        base_url,
        {
            "key": _show(attribute.key),
            "allowedValues": ",".join(attribute.allowed_values or []),
            "codeListBinding": "null" if binding is None else f"{_show(binding.catalog_key)}/{binding.key}",
            "etag": _header(response, "ETag"),
        },
    )


def _report_environment(base_url: str, environment, extra: dict | None = None) -> None:
    _report(
        base_url,
        {
            **(extra or {}),
            "key": _show(environment.key),
            "name": _show(environment.name),
            "production": _show_bool(environment.production),
        },
    )


def _report_bytes(base_url: str, data: bytes) -> None:
    _report(base_url, {"byteLength": len(data), "sha256": hashlib.sha256(data).hexdigest()})


def _update_consumer(base_url: str, config: dict) -> None:
    """A partial update that sets only the members the scenario names.

    Everything the caller did not name must stay off the wire: a server built to a contract before
    2.0 reads a null on these as "clear this", so a serializer that writes nulls for unset
    properties turns "rename this consumer" into "rename it and erase its description, contact and
    expiry". An empty ``description`` is a value, and goes out as is.
    """
    members = {
        attr: config[key]
        for key, attr in (("name", "name"), ("description", "description"), ("clearExpiresAt", "clear_expires_at"))
        if config.get(key) is not None
    }
    try:
        response = ConsumersApi(_client(base_url, config)).update_consumer_with_http_info(
            config["tenantKey"],
            config["consumerId"],
            UpdateConsumerRequest(**members),
            if_match=config.get("ifMatch"),
        )
    except ProblemDetailException as exc:
        _report_problem(base_url, exc)
        return
    consumer = response.data
    _report(
        base_url,
        {
            "id": _show(consumer.id),
            "name": _show(consumer.name),
            "lastModified": _show_instant(consumer.last_modified),
            "etag": _header(response, "ETag"),
        },
    )


def _routing(base_url: str, config: dict) -> None:
    """One poll to learn the partition assignment from the ``_meta`` line, then the routing helpers.

    The values are reported rather than asserted here: the harness holds all five clients to the
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


def _report_problem(base_url: str, exc: ProblemDetailException) -> None:
    _report(base_url, {"problemTypeSlug": exc.type_slug or "<null>", "problemStatus": exc.status_code})


def _header(response, name: str) -> str:
    """A response header exactly as received, quotes included; ``null`` when absent. urllib3's
    header dict matches names case-insensitively."""
    return _show((response.headers or {}).get(name))


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
