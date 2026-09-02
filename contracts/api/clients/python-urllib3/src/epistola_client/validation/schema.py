# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""Validates template data against the JSON Schema defined on the template.

Fetches the template from the server on first use and caches the compiled schema.

Example::

    validator = TemplateSchemaValidator(templates_api)
    validator.validate("my-tenant", "my-catalog", "my-template", my_data)
"""

from __future__ import annotations

import threading
import time
from dataclasses import dataclass
from typing import Any, Callable, Dict, List, Optional, Protocol, Tuple

from jsonschema.validators import validator_for

from epistola_client_generated import (
    GenerateBatchRequest,
    GenerateDocumentRequest,
    GenerationApi,
    GenerationJobResponse,
    TemplatesApi,
)


@dataclass(frozen=True)
class ValidationFailure:
    """A single field-level validation failure."""

    #: JSON path to the invalid field, e.g. ``customer.name``.
    path: str
    #: Human-readable error description.
    message: str
    #: JSON Schema keyword that failed, e.g. ``required``, ``type``.
    keyword: Optional[str] = None


class TemplateDataValidationError(Exception):
    """Raised when template data fails JSON Schema validation on the client side.

    Mirrors the server's validation error structure.
    """

    def __init__(self, errors: List[ValidationFailure], message: Optional[str] = None) -> None:
        self.errors = errors
        super().__init__(message or f"Template data validation failed with {len(errors)} error(s)")

    def format_errors(self) -> str:
        """Format all failures as a multi-line string."""
        return "\n".join(f"  {e.path}: {e.message}" for e in self.errors)


class SchemaCache(Protocol):
    """Cache for JSON Schemas keyed by (tenant_id, catalog_id, template_id).

    The catalog is part of the key, not decoration: the same template id in two catalogs of one
    tenant is two different templates with two different schemas.
    """

    def get_or_load(
        self,
        tenant_id: str,
        catalog_id: str,
        template_id: str,
        loader: Callable[[], Optional[Dict[str, Any]]],
    ) -> Optional[Dict[str, Any]]:
        """Return a cached schema, or invoke ``loader`` on a miss and store the result.
        A ``None`` result means the template has no schema defined.
        """
        ...


class TtlSchemaCache:
    """Default TTL-based cache. Entries expire after ``ttl`` seconds from when stored."""

    def __init__(self, ttl_seconds: float = 300.0) -> None:
        self._ttl = ttl_seconds
        self._lock = threading.Lock()
        self._cache: Dict[Tuple[str, str, str], Tuple[Optional[Dict[str, Any]], float]] = {}

    def get_or_load(
        self,
        tenant_id: str,
        catalog_id: str,
        template_id: str,
        loader: Callable[[], Optional[Dict[str, Any]]],
    ) -> Optional[Dict[str, Any]]:
        key = (tenant_id, catalog_id, template_id)
        now = time.monotonic()
        with self._lock:
            entry = self._cache.get(key)
            if entry is not None and now < entry[1] + self._ttl:
                return entry[0]
        schema = loader()
        with self._lock:
            self._cache[key] = (schema, time.monotonic())
        return schema

    def evict(self, tenant_id: str, catalog_id: str, template_id: str) -> None:
        """Evict a specific entry (useful after template updates)."""
        with self._lock:
            self._cache.pop((tenant_id, catalog_id, template_id), None)

    def evict_all(self) -> None:
        """Evict all entries."""
        with self._lock:
            self._cache.clear()


class TemplateSchemaValidator:
    """Validates template data against the JSON Schema defined on the template."""

    def __init__(self, templates_api: TemplatesApi, cache: Optional[SchemaCache] = None) -> None:
        self._templates_api = templates_api
        self._cache: SchemaCache = cache or TtlSchemaCache()

    def validate(self, tenant_id: str, catalog_id: str, template_id: str, data: Any) -> None:
        """Validate ``data`` against the schema of the specified template.

        No-op when the template has no schema. Raises :class:`TemplateDataValidationError`
        on failure.
        """
        schema = self._cache.get_or_load(
            tenant_id,
            catalog_id,
            template_id,
            lambda: self._load_schema(tenant_id, catalog_id, template_id),
        )
        if schema is None:
            return  # No schema defined on the template — nothing to validate.

        validator_cls = validator_for(schema)
        validator_cls.check_schema(schema)
        validator = validator_cls(schema)

        failures = [
            ValidationFailure(
                path=_format_path(error.absolute_path),
                message=error.message,
                keyword=str(error.validator) if error.validator is not None else None,
            )
            for error in sorted(validator.iter_errors(data), key=lambda e: list(e.absolute_path))
        ]
        if failures:
            raise TemplateDataValidationError(failures)

    def _load_schema(self, tenant_id: str, catalog_id: str, template_id: str) -> Optional[Dict[str, Any]]:
        template = self._templates_api.get_template(tenant_id, catalog_id, template_id)
        return template.var_schema


def _format_path(path) -> str:
    parts = [str(p) for p in path]
    return ".".join(parts) if parts else ""


class ValidatingGenerationApi:
    """A wrapper around :class:`GenerationApi` that validates request data against the
    template's JSON Schema before sending it to the server.

    For single-document requests, validation errors are raised immediately. For batch
    requests, all items are validated and errors are collected into a single
    :class:`TemplateDataValidationError`.
    """

    def __init__(
        self,
        generation_api: GenerationApi,
        templates_api: TemplatesApi,
        cache: Optional[SchemaCache] = None,
    ) -> None:
        self._delegate = generation_api
        self._validator = TemplateSchemaValidator(templates_api, cache or TtlSchemaCache())

    def generate_document(self, tenant_id: str, request: GenerateDocumentRequest) -> GenerationJobResponse:
        self._validator.validate(tenant_id, request.catalog_id, request.template_id, request.data)
        return self._delegate.generate_document(tenant_id, request)

    def generate_document_batch(self, tenant_id: str, request: GenerateBatchRequest) -> GenerationJobResponse:
        self._validate_batch(tenant_id, request)
        return self._delegate.generate_document_batch(tenant_id, request)

    def _validate_batch(self, tenant_id: str, request: GenerateBatchRequest) -> None:
        all_errors: List[ValidationFailure] = []
        for index, item in enumerate(request.items):
            try:
                self._validator.validate(tenant_id, item.catalog_id, item.template_id, item.data)
            except TemplateDataValidationError as exc:
                all_errors.extend(
                    ValidationFailure(path=f"items[{index}].{e.path}" if e.path else f"items[{index}]",
                                      message=e.message, keyword=e.keyword)
                    for e in exc.errors
                )
        if all_errors:
            raise TemplateDataValidationError(all_errors)
