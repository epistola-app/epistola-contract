# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""A :class:`TemplateDataValidator` that validates in-process, on ``jsonschema``.

**This lives in test sources on purpose.** The published client ships no JSON Schema library and
declares no dependency on one, so nobody installs a schema compiler for a feature they may never
call. This exists to prove the protocol is implementable against a real library rather than a stub,
to drive the conformance scenario, and to be copied by a consumer who wants local validation —
pre-flighting a large batch is the case that justifies it, since :attr:`preflights_generation`
stays ``True`` and one cached schema then answers every item without a request per item.

Two details are the whole reason a copyable example is worth keeping:

* **The path has to be converted.** ``jsonschema`` reports ``absolute_path`` as a deque of
  segments — property names and array indices — while the protocol pins ``path`` to a JSON Pointer.
  :func:`_to_pointer` is that conversion, escaping ``~`` and ``/`` as RFC 6901 requires. The
  validator this replaced joined the segments with ``.`` instead, so it reported ``customer.name``
  under a docstring promising a pointer.
* **The schema is fetched, so it is cached.** A validator is only "local" after a round trip for
  the template; without a cache this is slower than asking the server outright.

And one thing a copy cannot fix, which is the argument for the default being the server: **a local
library's verdict is its own.** ``jsonschema`` treats ``format`` as an annotation unless a format
checker is passed, and its draft support is its own; Epistola decides what it will render, and only
asking it cannot disagree with that.
"""

from __future__ import annotations

import threading
import time
from typing import Any, Dict, List, Optional, Tuple

from jsonschema.validators import validator_for

from epistola_client.validation import ValidationFailure


class JsonSchemaTemplateDataValidator:
    """Validates template data in-process with ``jsonschema``."""

    def __init__(self, templates_api: Any, ttl_seconds: float = 300.0) -> None:
        self._templates_api = templates_api
        self._ttl = ttl_seconds
        self._lock = threading.Lock()
        self._cache: Dict[Tuple[str, str, str], Tuple[Optional[Dict[str, Any]], float]] = {}

    @property
    def preflights_generation(self) -> bool:
        """``True`` — an in-process check before submitting is nearly free, and reports every item
        of a batch at once.
        """
        return True

    def validate(
        self,
        tenant_id: str,
        catalog_id: str,
        template_id: str,
        data: Any,
    ) -> List[ValidationFailure]:
        schema = self._schema_for(tenant_id, catalog_id, template_id)
        # No schema on the template means nothing is claimed about the data, so nothing is wrong
        # with it. Same answer the server gives.
        if schema is None:
            return []

        validator_cls = validator_for(schema)
        validator_cls.check_schema(schema)
        validator = validator_cls(schema)

        return [
            ValidationFailure(
                path=_to_pointer(error.absolute_path),
                message=error.message,
                keyword=str(error.validator) if error.validator is not None else None,
            )
            for error in sorted(validator.iter_errors(data), key=lambda e: list(e.absolute_path))
        ]

    def _schema_for(self, tenant_id: str, catalog_id: str, template_id: str) -> Optional[Dict[str, Any]]:
        key = (tenant_id, catalog_id, template_id)
        now = time.monotonic()
        with self._lock:
            entry = self._cache.get(key)
            if entry is not None and now < entry[1] + self._ttl:
                return entry[0]
        template = self._templates_api.get_template(tenant_id, catalog_id, template_id)
        schema = template.var_schema
        with self._lock:
            self._cache[key] = (schema, time.monotonic())
        return schema


def _to_pointer(path) -> str:
    """A ``jsonschema`` ``absolute_path`` deque as a JSON Pointer.

    An empty path is the document root, which RFC 6901 writes as the empty string rather than
    ``/``. Array indices become plain segments, so ``['lineItems', 0, 'quantity']`` is
    ``/lineItems/0/quantity``.
    """
    return "".join(f"/{_escape(str(segment))}" for segment in path)


def _escape(segment: str) -> str:
    return segment.replace("~", "~0").replace("/", "~1")
