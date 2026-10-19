# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

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
from typing import Any, Dict, List, Optional

from epistola_client_generated import (
    DataModelValidationError,
    InvalidDataField,
    MissingDataField,
    ProblemDetail,
    ValidationError,
)

from epistola_client.error.problem_detail_exception import (
    MissingDependency,
    ProblemDetailException,
    UnreleasedDependency,
)

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
    (``ValidationProblemDetail``), the per-example ``validationErrors`` map
    (``DataModelValidationProblemDetail``), and the ``missingFields`` / ``invalidFields``
    arrays (``TemplateDataValidationProblemDetail``), the ``missingDependencies`` array
    (``ReleaseDependenciesMissingProblemDetail``), and the ``unreleasedDependencies`` array
    (``ReleaseDependenciesUnreleasedProblemDetail``). The generated models are independent,
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
        missing_fields=_members(tree, "missingFields", MissingDataField),
        invalid_fields=_members(tree, "invalidFields", InvalidDataField),
        missing_dependencies=_missing_dependencies(tree),
        unreleased_dependencies=_unreleased_dependencies(tree),
    )


def _missing_dependencies(tree: Dict[str, Any]) -> List[MissingDependency]:
    """The ``missingDependencies`` member, or ``[]`` when absent or malformed.

    Read by hand rather than through the generated model: that model types ``reason`` as a closed
    enum, so a reason a later contract adds would fail the whole member. Here it stays a string.
    As with :func:`_members`, an entry missing one of its members makes the member yield nothing.
    """
    raw = tree.get("missingDependencies")
    if not isinstance(raw, list):
        return []
    dependencies: List[MissingDependency] = []
    for entry in raw:
        if not isinstance(entry, dict):
            continue
        values = [
            entry.get(name)
            for name in ("registry", "namespace", "catalogKey", "releaseVersion", "reason")
        ]
        if not all(isinstance(value, str) for value in values):
            return []
        dependencies.append(MissingDependency(*values))
    return dependencies


def _unreleased_dependencies(tree: Dict[str, Any]) -> List[UnreleasedDependency]:
    """The ``unreleasedDependencies`` member, or ``[]`` when absent or malformed.

    As with :func:`_missing_dependencies`, an entry missing one of its members makes the member
    yield nothing.
    """
    raw = tree.get("unreleasedDependencies")
    if not isinstance(raw, list):
        return []
    dependencies: List[UnreleasedDependency] = []
    for entry in raw:
        if not isinstance(entry, dict):
            continue
        values = [
            entry.get(name)
            for name in ("registry", "namespace", "catalogKey", "versionId", "draftName")
        ]
        if not all(isinstance(value, str) for value in values):
            return []
        dependencies.append(UnreleasedDependency(*values))
    return dependencies


def _members(tree: Dict[str, Any], member: str, model: Any) -> List[Any]:
    """One extension member that is an array of objects, or ``[]`` when absent or malformed.

    A malformed member yields nothing rather than a guess: a bad problem body should not become a
    confident claim about a particular field, and it must not hide the problem it decorates.
    """
    raw = tree.get(member)
    if not isinstance(raw, list):
        return []
    try:
        return [model.from_dict(entry) for entry in raw if isinstance(entry, dict)]
    except Exception:
        return []
