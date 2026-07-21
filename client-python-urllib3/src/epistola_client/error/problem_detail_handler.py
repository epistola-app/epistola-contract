"""Parses ``application/problem+json`` bodies into a :class:`ProblemDetailException`.

The handler itself is installed on the client via the Epistola client builder, which
returns an :class:`~epistola_client.http.client.EpistolaApiClient` that turns
``application/problem+json`` error responses into a typed
:class:`~epistola_client.error.problem_detail_exception.ProblemDetailException` before
the generated client raises its generic ``ApiException``.

Error responses that are **not** parseable problem+json (a different content type, an
empty body, or malformed JSON) pass through untouched, so behaviour is never worse than
the generated default. This module holds the self-contained, unit-testable parse step.
"""

from __future__ import annotations

import json
from typing import Dict, List, Optional

from epistola_client_generated import (
    DataModelValidationError,
    ProblemDetail,
    ValidationError,
)

from epistola_client.error.problem_detail_exception import ProblemDetailException

#: The RFC 9457 problem media type.
PROBLEM_JSON = "application/problem+json"


def parse_problem(
    body: str,
    status_code: int,
    headers: Optional[object] = None,
) -> Optional[ProblemDetailException]:
    """Parse a problem+json ``body`` into a :class:`ProblemDetailException`, or return
    ``None`` on any parse failure (so the caller can fall back to the generic exception).

    Parses the base :class:`ProblemDetail` plus the field-level ``errors`` array
    (``ValidationProblemDetail``) and the per-example ``validationErrors`` map
    (``DataModelValidationProblemDetail``). The three generated models are independent,
    so the base fields and each extension are carried separately.
    """
    try:
        tree = json.loads(body)
    except (ValueError, TypeError):
        return None

    if not isinstance(tree, dict):
        return None

    try:
        problem = ProblemDetail.from_dict(tree)
    except Exception:
        return None
    if problem is None:
        return None

    errors: List[ValidationError] = []
    raw_errors = tree.get("errors")
    if isinstance(raw_errors, list):
        try:
            errors = [ValidationError.from_dict(e) for e in raw_errors if isinstance(e, dict)]
        except Exception:
            errors = []

    validation_errors: Dict[str, List[DataModelValidationError]] = {}
    raw_validation = tree.get("validationErrors")
    if isinstance(raw_validation, dict):
        try:
            validation_errors = {
                key: [DataModelValidationError.from_dict(v) for v in value if isinstance(v, dict)]
                for key, value in raw_validation.items()
                if isinstance(value, list)
            }
        except Exception:
            validation_errors = {}

    return ProblemDetailException(
        problem=problem,
        errors=errors,
        validation_errors=validation_errors,
        status_code=status_code,
        raw_body=body,
        headers=headers,
    )
