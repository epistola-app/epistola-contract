# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""Typed exception carrying a parsed RFC 9457 problem-detail body."""

from __future__ import annotations

from typing import Dict, List, Optional

from epistola_client_generated import (
    ApiException,
    DataModelValidationError,
    InvalidDataField,
    MissingDataField,
    ProblemDetail,
    ValidationError,
)

from epistola_client.error import problem_types


class ProblemDetailException(ApiException):
    """An :class:`ApiException` carrying a parsed `RFC 9457 <https://www.rfc-editor.org/rfc/rfc9457>`_
    :class:`ProblemDetail` (``application/problem+json``) body.

    Raised by the opt-in problem-detail handler (installed via the Epistola client
    builder). It extends the generated :class:`ApiException` on purpose: existing
    ``except ApiException`` sites keep working and consumers retain the inherited
    ``status`` / ``body`` / ``headers`` attributes.

    The machine-readable discriminator is the problem :attr:`type` URI; switch on
    :attr:`type_slug`. Field-level validation errors (the ``ValidationProblemDetail``
    shape) are surfaced via :attr:`errors`; per-example data-model validation failures
    (the ``DataModelValidationProblemDetail`` shape, ``data-model-validation-error``)
    via :attr:`validation_errors`; and a ``template-data-invalid`` problem's field-by-field
    account of template data via :attr:`missing_fields` and :attr:`invalid_fields`.
    """

    def __init__(
        self,
        problem: ProblemDetail,
        errors: List[ValidationError],
        validation_errors: Dict[str, List[DataModelValidationError]],
        status_code: int,
        raw_body: Optional[str] = None,
        headers: Optional[object] = None,
        missing_fields: Optional[List[MissingDataField]] = None,
        invalid_fields: Optional[List[InvalidDataField]] = None,
    ) -> None:
        super().__init__(
            status=status_code,
            reason=_build_message(status_code, problem),
            body=raw_body,
        )
        self.headers = headers
        #: The parsed base problem (``type``, ``title``, ``status``, ``detail``, ``instance``).
        self.problem = problem
        #: Field-level validation errors when the body was a ``ValidationProblemDetail``, else ``[]``.
        self.errors = errors
        #: Per-example data-model validation failures (example name -> failures) when the
        #: body was a ``DataModelValidationProblemDetail`` (422), else ``{}``.
        self.validation_errors = validation_errors
        #: Absent fields when the body was a ``TemplateDataValidationProblemDetail``, else ``[]``.
        #: Each ``path`` is a JSON Pointer into the request's ``data`` and ``schema`` is the
        #: contract's schema for that field, so a caller can ask for exactly what is missing. An
        #: entry whose ``required`` is ``False`` is informational: the resolved version's template
        #: reads the field, but leaving it out does not make the data invalid.
        self.missing_fields = missing_fields or []
        #: Supplied values the template's data contract rejected, else ``[]``.
        self.invalid_fields = invalid_fields or []
        #: The HTTP status of the error response.
        self.status_code = status_code

    @property
    def type(self) -> str:
        """The problem ``type`` URI (``about:blank`` when unspecified)."""
        return self.problem.type or problem_types.BLANK_TYPE

    @property
    def type_slug(self) -> Optional[str]:
        """Kebab-case slug derived from :attr:`type` by stripping
        :data:`~epistola_client.error.problem_types.TYPE_BASE`, or ``None`` for
        ``about:blank`` and non-Epistola types. Compare against ``KnownProblemSlugs``.
        """
        return problem_types.slug_for(self.problem.type)

    @property
    def title(self) -> Optional[str]:
        """Short human-readable summary of the problem type (RFC 9457 ``title``)."""
        return self.problem.title

    @property
    def problem_status(self) -> int:
        """The HTTP status carried in the problem body (RFC 9457 ``status``)."""
        return self.problem.status

    @property
    def detail(self) -> Optional[str]:
        """Occurrence-specific explanation (RFC 9457 ``detail``), if the server provided one."""
        return self.problem.detail

    @property
    def is_validation_problem(self) -> bool:
        """True when this problem carried field-level validation errors."""
        return len(self.errors) > 0

    @property
    def is_template_data_problem(self) -> bool:
        """True when this problem described template data field by field."""
        return bool(self.missing_fields or self.invalid_fields)

    @property
    def is_data_model_validation_problem(self) -> bool:
        """True when this problem carried per-example data-model validation failures."""
        return len(self.validation_errors) > 0

    def __str__(self) -> str:
        return _build_message(self.status_code, self.problem)


def _build_message(status: int, problem: ProblemDetail) -> str:
    title = problem.title or str(status)
    if not problem.detail:
        return f"{status} {title}"
    return f"{status} {title}: {problem.detail}"
