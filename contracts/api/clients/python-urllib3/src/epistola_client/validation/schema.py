# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""Checks template data against a template's data contract.

The verdict comes from Epistola, through ``validateTemplateData``. Nothing is compiled here, so
there is no JSON Schema library to install, and the answer cannot disagree with what generation
will do.

Example::

    validator = TemplateSchemaValidator(templates_api)
    validator.validate("my-tenant", "my-catalog", "my-template", my_data)

To validate in-process instead, implement :class:`TemplateDataValidator` and pass it in place of
the API — see its docstring for the failure shape an implementation owes its callers.
"""

from __future__ import annotations

from typing import Any, List, Optional, Sequence, Union

from epistola_client_generated import (
    GenerateBatchRequest,
    GenerateDocumentRequest,
    GenerationApi,
    GenerationJobResponse,
    InvalidDataField,
    MissingDataField,
    TemplateDataValidationError as TemplateDataValidationErrorModel,
    TemplatesApi,
    ValidateTemplateDataRequest,
)

from epistola_client._generated.known_problem_slugs import KnownProblemSlugs
from epistola_client.error.problem_detail_exception import ProblemDetailException
from epistola_client.validation.template_data_validator import (
    TemplateDataValidationError,
    TemplateDataValidator,
    ValidationFailure,
)

#: The message for a required field the data does not supply.
MISSING_REQUIRED_MESSAGE = "is required but was not supplied"

#: The finding for a rejection that names nothing.
_UNSPECIFIED = ValidationFailure(
    path="",
    message="does not fit this template's data contract, which gave no field-level detail",
    keyword=None,
)


def to_validation_failures(
    errors: Optional[Sequence[Any]] = None,
    missing_fields: Optional[Sequence[Any]] = None,
    invalid_fields: Optional[Sequence[Any]] = None,
) -> List[ValidationFailure]:
    """Turn what the server reports about template data into the one shape the protocol pins.

    ``invalid_fields`` and ``missing_fields`` are preferred over ``errors`` where the server sends
    them, and not only because they carry more. Their ``path`` is specified as a JSON Pointer into
    the data, which is what :class:`TemplateDataValidator` promises callers; ``errors[].path``
    describes itself as a pointer but is documented with a JSONPath example
    (``$.customer.email``), so passing it through unexamined would make the promise depend on which
    member the server happened to fill. The fallback to ``errors`` exists so a server that sends
    only that is still reported rather than silently accepted, and the last resort exists because an
    empty result means "acceptable" to a caller — a rejection that names no field must not become a
    pass.

    An absent **optional** field is not a finding: the contract says so explicitly, and the server
    lists those in ``missing_fields`` too so a client can offer them.
    """
    from_fields: List[ValidationFailure] = []
    for field in invalid_fields or ():
        from_fields.append(
            ValidationFailure(
                path=field.path,
                message=field.message,
                keyword=field.keyword,
            )
        )
    for field in missing_fields or ():
        if field.required is False:
            continue
        from_fields.append(
            ValidationFailure(path=field.path, message=MISSING_REQUIRED_MESSAGE, keyword="required")
        )
    if from_fields:
        return from_fields

    from_errors = [
        ValidationFailure(path=error.path, message=error.message, keyword=error.keyword)
        for error in errors or ()
    ]
    return from_errors or [_UNSPECIFIED]


class ServerTemplateDataValidator:
    """The :class:`TemplateDataValidator` this package ships: it asks Epistola.

    This is the default, and it carries no JSON Schema library. The server already owns the verdict
    — it validates every generation request whatever the client did first — so asking it is the only
    answer that cannot disagree with what generation will do. It also knows things a schema alone
    does not: which optional fields the resolved version's template actually reads.

    Validation is a far cheaper call than rendering, so checking as data is entered is reasonable.
    It is still a network call, which is why :attr:`preflights_generation` is ``False``.

    **Server floor.** ``validateTemplateData`` arrived with contract **1.4.0**. Against an older
    server the call fails like any other unknown operation; it is not degraded into "valid",
    because silently reporting unvalidated data as acceptable is worse than failing.

    :param templates_api: the generated API used to reach ``validateTemplateData``
    :param variant_id: optional variant to check against
    :param version_id: optional explicit version number (mutually exclusive with
        ``environment_id``)
    :param environment_id: optional environment whose active version to check against
    """

    def __init__(
        self,
        templates_api: TemplatesApi,
        variant_id: Optional[str] = None,
        version_id: Optional[int] = None,
        environment_id: Optional[str] = None,
    ) -> None:
        self._templates_api = templates_api
        self._variant_id = variant_id
        self._version_id = version_id
        self._environment_id = environment_id

    @property
    def preflights_generation(self) -> bool:
        """``False`` — the server checks the same data when the generation request is submitted,
        so pre-flighting here would only spend a second round trip to learn the same thing.
        """
        return False

    def validate(
        self,
        tenant_id: str,
        catalog_id: str,
        template_id: str,
        data: Any,
    ) -> List[ValidationFailure]:
        result = self._templates_api.validate_template_data(
            tenant_id,
            catalog_id,
            template_id,
            ValidateTemplateDataRequest(
                data=data if data is not None else {},
                variantId=self._variant_id,
                versionId=self._version_id,
                environmentId=self._environment_id,
            ),
        )
        if result is None or result.valid:
            return []
        return to_validation_failures(result.errors, result.missing_fields, result.invalid_fields)


class TemplateSchemaValidator:
    """Checks template data against a template's data contract, raising when it does not fit.

    The check itself is delegated to a :class:`TemplateDataValidator`; this class is the thin,
    raising façade over it. By default that is :class:`ServerTemplateDataValidator`, so no JSON
    Schema library is involved and the verdict is the server's.
    """

    def __init__(self, validator_or_api: Union[TemplateDataValidator, TemplatesApi]) -> None:
        self._validator: TemplateDataValidator = (
            validator_or_api
            if _is_validator(validator_or_api)
            else ServerTemplateDataValidator(validator_or_api)  # type: ignore[arg-type]
        )

    def validate(self, tenant_id: str, catalog_id: str, template_id: str, data: Any) -> None:
        """Validate ``data`` against the template's data contract.

        Raises :class:`TemplateDataValidationError` when it does not fit. Against a server older
        than contract 1.4.0 the default validator raises the generated ``ApiException`` instead,
        because ``validateTemplateData`` does not exist there.
        """
        failures = list(self._validator.validate(tenant_id, catalog_id, template_id, data))
        if failures:
            raise TemplateDataValidationError(failures)


class ValidatingGenerationApi:
    """Wraps :class:`GenerationApi` and reports unacceptable template data as a
    :class:`TemplateDataValidationError` rather than a generic problem response.

    It gets there two ways, and which one applies is the validator's call:

    * **Before the request**, when the validator answers in-process
      (:attr:`TemplateDataValidator.preflights_generation`). Nothing is sent, and every item of a
      batch is reported at once with its ``items[<index>]`` prefix.
    * **From the response**, always. The server validates the data it is given, so a rejected
      request comes back as a ``template-data-invalid`` problem, which is translated into the same
      error with the same field pointers.

    The default validator asks the server, and therefore declines the pre-flight: checking first
    would spend an extra round trip — one per item, for a batch — to learn what submitting already
    tells us.
    """

    def __init__(
        self,
        generation_api: GenerationApi,
        validator_or_api: Union[TemplateDataValidator, TemplatesApi],
    ) -> None:
        self._delegate = generation_api
        self._validator: TemplateDataValidator = (
            validator_or_api
            if _is_validator(validator_or_api)
            else ServerTemplateDataValidator(validator_or_api)  # type: ignore[arg-type]
        )

    def generate_document(self, tenant_id: str, request: GenerateDocumentRequest) -> GenerationJobResponse:
        if self._validator.preflights_generation:
            failures = list(
                self._validator.validate(tenant_id, request.catalog_id, request.template_id, request.data)
            )
            if failures:
                raise TemplateDataValidationError(failures)
        return self._translating_problem(lambda: self._delegate.generate_document(tenant_id, request))

    def generate_document_batch(self, tenant_id: str, request: GenerateBatchRequest) -> GenerationJobResponse:
        if self._validator.preflights_generation:
            all_failures: List[ValidationFailure] = []
            for index, item in enumerate(request.items):
                for failure in self._validator.validate(
                    tenant_id, item.catalog_id, item.template_id, item.data
                ):
                    all_failures.append(
                        ValidationFailure(
                            path=f"items[{index}]{failure.path}",
                            message=failure.message,
                            keyword=failure.keyword,
                        )
                    )
            if all_failures:
                raise TemplateDataValidationError(all_failures)
        return self._translating_problem(
            lambda: self._delegate.generate_document_batch(tenant_id, request)
        )

    @staticmethod
    def _translating_problem(call: Any) -> GenerationJobResponse:
        """Rewrite the server's ``template-data-invalid`` problem into the error a caller of this
        class is already catching. Every other problem propagates untouched — this class narrows
        one failure mode, it does not swallow failures.
        """
        try:
            return call()
        except ProblemDetailException as exc:
            if exc.type_slug != KnownProblemSlugs.TEMPLATE_DATA_INVALID:
                raise
            raise TemplateDataValidationError(
                to_validation_failures(None, exc.missing_fields, exc.invalid_fields)
            ) from exc


def _is_validator(candidate: Any) -> bool:
    return hasattr(candidate, "validate") and hasattr(candidate, "preflights_generation")
