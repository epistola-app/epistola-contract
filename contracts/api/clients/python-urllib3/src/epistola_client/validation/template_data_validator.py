# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""The strategy that decides whether template data fits a template's data contract."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, List, Optional, Protocol, Sequence, runtime_checkable


@dataclass(frozen=True)
class ValidationFailure:
    """A single field-level validation failure."""

    #: JSON Pointer (RFC 6901) into the data, e.g. ``/customer/email``; ``""`` for the root.
    #:
    #: Not a dotted key and not a JSONPath — see :class:`TemplateDataValidator` for why this is
    #: pinned rather than left to each implementation.
    path: str
    #: Human-readable error description.
    message: str
    #: JSON Schema keyword that failed, e.g. ``required``, ``type``.
    keyword: Optional[str] = None


class TemplateDataValidationError(Exception):
    """Raised when template data does not fit a template's data contract."""

    def __init__(self, errors: List[ValidationFailure], message: Optional[str] = None) -> None:
        self.errors = errors
        super().__init__(message or f"Template data validation failed with {len(errors)} error(s)")

    def format_errors(self) -> str:
        """Format all failures as a multi-line string."""
        return "\n".join(f"  {e.path}: {e.message}" for e in self.errors)


@runtime_checkable
class TemplateDataValidator(Protocol):
    """Decides whether template data satisfies a template's data contract.

    This package ships exactly one implementation,
    :class:`~epistola_client.validation.schema.ServerTemplateDataValidator`, and uses it by
    default. That is deliberate: nothing here pins a JSON Schema library, so no consumer installs
    one for a feature they may never call. A consumer who wants the check to run in-process —
    pre-flighting a large batch without a request per item, say — implements this protocol over the
    library of their choice and keeps the parts worth sharing: the typed error, and the batch
    aggregation in :class:`~epistola_client.validation.schema.ValidatingGenerationApi`.

    **The failures an implementation returns.** An empty sequence means the data is acceptable.
    Anything else is a finding, and the three members are a *contract rather than a convention* —
    :class:`~epistola_client.validation.schema.TemplateSchemaValidator` puts them straight into a
    :class:`TemplateDataValidationError`, and callers read them to point at the field that is wrong:

    * ``path`` is a **JSON Pointer (RFC 6901) into the data**: ``/customer/email``,
      ``/lineItems/0/quantity``, and ``""`` for the document root. Libraries genuinely disagree
      here — ``jsonschema`` reports an ``absolute_path`` deque of segments, ajv an already-pointer
      ``instancePath``, networknt a JSONPath — so converting is the adapter's job, not the caller's.
    * ``keyword`` is the JSON Schema keyword that failed, or ``None``.
    * ``message`` is human-readable text. It is shown to people, so it should not contain the raw
      schema or the failing pattern.

    Holding every implementation to one shape is the reason this protocol exists. Without it a
    :class:`TemplateDataValidationError` means something different per library, which is the drift
    that shipping a validator in each of five clients had already produced: this client reported
    ``customer.name``, the JVM ones ``$.customer.name``, under docs promising a pointer.

    One deviation, and it is not part of the pointer:
    :class:`~epistola_client.validation.schema.ValidatingGenerationApi` prefixes a batch item's
    failures with ``items[<index>]`` to say which item they came from.
    """

    def validate(
        self,
        tenant_id: str,
        catalog_id: str,
        template_id: str,
        data: Any,
    ) -> Sequence[ValidationFailure]:
        """Return the findings for ``data``, empty when there are none.

        ``catalog_id`` is part of the identity, not decoration: the same template id in two
        catalogs of one tenant is two different templates with two different contracts.
        """
        ...

    @property
    def preflights_generation(self) -> bool:
        """Whether generation requests should be checked with this validator *before* submitting.

        ``True`` for a validator that answers in-process, where checking first is nearly free and
        reports every item of a batch at once. ``False`` for one that asks the server, because the
        server validates the very same data when the job is submitted: pre-flighting would double
        the requests for a single document and add one per item for a batch, to reach the same
        verdict. A validator that declines pre-flight still produces a
        :class:`TemplateDataValidationError` out of the generation call itself —
        :class:`~epistola_client.validation.schema.ValidatingGenerationApi` translates the server's
        ``template-data-invalid`` problem into one either way.
        """
        ...
