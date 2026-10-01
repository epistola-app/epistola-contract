# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""Template-data validation over the shipped default, ``ServerTemplateDataValidator``.

The behaviour worth pinning is the mapping, not the HTTP: the server answers in three members of
one result and the client owes its callers a single shape, documented on ``TemplateDataValidator``.
"""

from types import SimpleNamespace

import pytest

from epistola_client import (
    ServerTemplateDataValidator,
    TemplateDataValidationError,
    TemplateSchemaValidator,
    ValidatingGenerationApi,
)
from epistola_client.error.problem_detail_exception import ProblemDetailException
from epistola_client_generated import InvalidDataField, MissingDataField, ProblemDetail


class _StubTemplatesApi:
    """Answers ``validate_template_data`` with a fixed result, recording what was sent."""

    def __init__(self, result):
        self._result = result
        self.calls = 0
        self.sent = []

    def validate_template_data(self, tenant_id, catalog_id, template_id, request):
        self.calls += 1
        self.sent.append(request)
        return self._result


class _StubGenerationApi:
    def __init__(self, failure=None):
        self._failure = failure
        self.submissions = 0

    def generate_document(self, tenant_id, request):
        self.submissions += 1
        if self._failure is not None:
            raise self._failure
        return SimpleNamespace(request_id="job-1")

    def generate_document_batch(self, tenant_id, request):
        self.submissions += 1
        if self._failure is not None:
            raise self._failure
        return SimpleNamespace(request_id="job-1")


class _LocalValidator:
    """A validator that answers in-process, so it takes the pre-flight."""

    def __init__(self, *paths):
        self._paths = paths
        self.calls = 0

    preflights_generation = True

    def validate(self, tenant_id, catalog_id, template_id, data):
        from epistola_client import ValidationFailure

        self.calls += 1
        return [
            ValidationFailure(path=path, message="is required but was not supplied", keyword="required")
            for path in self._paths
        ]


def _result(valid, errors=None, missing_fields=None, invalid_fields=None):
    return SimpleNamespace(
        valid=valid, errors=errors, missing_fields=missing_fields, invalid_fields=invalid_fields
    )


def _single_request():
    return SimpleNamespace(catalog_id="default", template_id="invoice", data={})


def _batch_request():
    return SimpleNamespace(
        items=[
            SimpleNamespace(catalog_id="default", template_id="invoice", data={}),
            SimpleNamespace(catalog_id="default", template_id="reminder", data={}),
        ]
    )


def _template_data_invalid(missing_fields=None, invalid_fields=None):
    return ProblemDetailException(
        problem=ProblemDetail(
            type="https://epistola.app/errors/template-data-invalid",
            title="Template data invalid",
            status=400,
            detail="The supplied data does not fit the template's data contract",
        ),
        errors=[],
        validation_errors={},
        status_code=400,
        raw_body="{}",
        missing_fields=missing_fields or [],
        invalid_fields=invalid_fields or [],
    )


def test_valid_data_passes_without_raising():
    api = _StubTemplatesApi(_result(valid=True))

    TemplateSchemaValidator(api).validate("acme-corp", "default", "invoice", {"name": "Jane"})

    assert api.calls == 1
    assert api.sent[0].data == {"name": "Jane"}


def test_invalid_fields_become_failures_keyed_by_their_json_pointer():
    api = _StubTemplatesApi(
        _result(
            valid=False,
            invalid_fields=[
                InvalidDataField(path="/customer/email", keyword="format", message="must be a valid email address"),
                InvalidDataField(path="/lineItems/0/quantity", keyword="minimum", message="must be at least 1"),
            ],
        )
    )

    with pytest.raises(TemplateDataValidationError) as raised:
        TemplateSchemaValidator(api).validate("acme-corp", "default", "invoice", {})

    assert [e.path for e in raised.value.errors] == ["/customer/email", "/lineItems/0/quantity"]
    assert [e.keyword for e in raised.value.errors] == ["format", "minimum"]


def test_a_missing_required_field_is_a_failure_and_a_missing_optional_field_is_not():
    api = _StubTemplatesApi(
        _result(
            valid=False,
            missing_fields=[
                MissingDataField(path="/customer/address", required=True, schema={"type": "object"}),
                MissingDataField(path="/customer/phone", required=False, schema={"type": "string"}),
            ],
        )
    )

    with pytest.raises(TemplateDataValidationError) as raised:
        TemplateSchemaValidator(api).validate("acme-corp", "default", "invoice", {})

    assert [e.path for e in raised.value.errors] == ["/customer/address"]
    assert raised.value.errors[0].keyword == "required"


def test_errors_is_used_when_the_server_sends_no_field_members():
    api = _StubTemplatesApi(
        _result(
            valid=False,
            errors=[SimpleNamespace(path="/invoiceNumber", message="does not match the required format", keyword="pattern")],
        )
    )

    with pytest.raises(TemplateDataValidationError) as raised:
        TemplateSchemaValidator(api).validate("acme-corp", "default", "invoice", {})

    assert raised.value.errors[0].path == "/invoiceNumber"
    assert raised.value.errors[0].keyword == "pattern"


def test_the_field_members_win_over_errors():
    # `errors[].path` documents itself as a JSON Pointer but is specified with a JSONPath example,
    # so preferring invalid_fields keeps the promise from depending on which member the server filled.
    api = _StubTemplatesApi(
        _result(
            valid=False,
            errors=[SimpleNamespace(path="$.customer.email", message="must be a valid email address", keyword="format")],
            invalid_fields=[InvalidDataField(path="/customer/email", keyword="format", message="must be a valid email address")],
        )
    )

    with pytest.raises(TemplateDataValidationError) as raised:
        TemplateSchemaValidator(api).validate("acme-corp", "default", "invoice", {})

    assert [e.path for e in raised.value.errors] == ["/customer/email"]


def test_an_invalid_result_with_nothing_to_report_still_raises():
    # `valid=False` is the verdict, and reporting no reason must not become "fine".
    api = _StubTemplatesApi(_result(valid=False))

    with pytest.raises(TemplateDataValidationError) as raised:
        TemplateSchemaValidator(api).validate("acme-corp", "default", "invoice", {})

    assert len(raised.value.errors) == 1
    assert raised.value.errors[0].path == ""


def test_the_version_selectors_are_passed_through_when_configured():
    api = _StubTemplatesApi(_result(valid=True))

    TemplateSchemaValidator(
        ServerTemplateDataValidator(api, variant_id="nl-nl", environment_id="production")
    ).validate("acme-corp", "default", "invoice", {})

    assert api.sent[0].variant_id == "nl-nl"
    assert api.sent[0].environment_id == "production"


def test_a_plugged_in_validator_replaces_the_server_entirely():
    api = _StubTemplatesApi(_result(valid=True))
    local = _LocalValidator("/name")

    with pytest.raises(TemplateDataValidationError) as raised:
        TemplateSchemaValidator(local).validate("acme-corp", "default", "invoice", {})

    assert raised.value.errors[0].path == "/name"
    assert api.calls == 0


def test_the_server_backed_validator_declines_generation_preflight():
    assert ServerTemplateDataValidator(_StubTemplatesApi(_result(valid=True))).preflights_generation is False
    assert _LocalValidator().preflights_generation is True


def test_the_default_validator_submits_without_a_preflight_request():
    templates = _StubTemplatesApi(_result(valid=True))
    generation = _StubGenerationApi()

    ValidatingGenerationApi(generation, templates).generate_document("acme-corp", _single_request())

    # The server validates what it is given, so asking it first would be a second round trip for
    # the same verdict.
    assert templates.calls == 0
    assert generation.submissions == 1


def test_a_rejected_submission_becomes_a_template_data_validation_error():
    rejected = _template_data_invalid(
        missing_fields=[
            MissingDataField(path="/invoiceNumber", required=True, schema={}),
            MissingDataField(path="/customer/phone", required=False, schema={}),
        ],
        invalid_fields=[InvalidDataField(path="/customer/email", keyword="format", message="must be a valid email address")],
    )
    api = ValidatingGenerationApi(_StubGenerationApi(rejected), _StubTemplatesApi(_result(valid=True)))

    with pytest.raises(TemplateDataValidationError) as raised:
        api.generate_document("acme-corp", _single_request())

    assert [e.path for e in raised.value.errors] == ["/customer/email", "/invoiceNumber"]


def test_any_other_problem_propagates_untouched():
    not_found = ProblemDetailException(
        problem=ProblemDetail(type="https://epistola.app/errors/not-found", title="Not Found", status=404),
        errors=[],
        validation_errors={},
        status_code=404,
        raw_body="{}",
    )
    api = ValidatingGenerationApi(_StubGenerationApi(not_found), _StubTemplatesApi(_result(valid=True)))

    with pytest.raises(ProblemDetailException) as raised:
        api.generate_document("acme-corp", _single_request())

    assert raised.value is not_found


def test_a_local_validator_raises_before_the_request_is_sent():
    generation = _StubGenerationApi()

    with pytest.raises(TemplateDataValidationError):
        ValidatingGenerationApi(generation, _LocalValidator("/name")).generate_document(
            "acme-corp", _single_request()
        )

    assert generation.submissions == 0


def test_a_local_validator_reports_every_item_of_a_batch_at_once():
    generation = _StubGenerationApi()

    with pytest.raises(TemplateDataValidationError) as raised:
        ValidatingGenerationApi(generation, _LocalValidator("/name")).generate_document_batch(
            "acme-corp", _batch_request()
        )

    assert [e.path for e in raised.value.errors] == ["items[0]/name", "items[1]/name"]
    assert generation.submissions == 0


def test_the_default_validator_preflights_nothing_per_batch_item_either():
    templates = _StubTemplatesApi(_result(valid=True))
    generation = _StubGenerationApi()

    ValidatingGenerationApi(generation, templates).generate_document_batch("acme-corp", _batch_request())

    assert templates.calls == 0
    assert generation.submissions == 1
