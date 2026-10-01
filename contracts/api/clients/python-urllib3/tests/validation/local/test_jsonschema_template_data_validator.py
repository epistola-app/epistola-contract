# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""The reference local adapter, held to the contract ``TemplateDataValidator`` states — above all
that ``path`` is a JSON Pointer.

This is what makes the protocol's promise testable rather than aspirational: a real library, with
its own idea of how to name a location, converted to the one shape callers read.
"""

from types import SimpleNamespace

# By module name, not a package path: the repo's tests carry no `__init__.py`, so pytest's prepend
# import mode puts this directory on sys.path and `tests.validation.local...` does not resolve under
# `uv run pytest`, only under `python -m pytest` where the cwd happens to be there too.
from jsonschema_template_data_validator import JsonSchemaTemplateDataValidator

_INVOICE_SCHEMA = {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "type": "object",
    "required": ["customer", "invoiceNumber"],
    "properties": {
        "customer": {
            "type": "object",
            "required": ["name", "email"],
            "properties": {
                "name": {"type": "string", "minLength": 1},
                "email": {"type": "string", "format": "email"},
            },
        },
        "invoiceNumber": {"type": "string"},
        "lineItems": {
            "type": "array",
            "items": {
                "type": "object",
                "required": ["quantity"],
                "properties": {"quantity": {"type": "integer", "minimum": 1}},
            },
        },
    },
}

_VALID_DATA = {
    "customer": {"name": "Jane Smith", "email": "jane@example.com"},
    "invoiceNumber": "INV-2026-001",
    "lineItems": [{"quantity": 3}],
}


class _StubTemplatesApi:
    def __init__(self, schema):
        self._schema = schema
        self.fetches = 0

    def get_template(self, tenant_id, catalog_id, template_id):
        self.fetches += 1
        return SimpleNamespace(var_schema=self._schema)


def test_valid_data_yields_no_findings():
    validator = JsonSchemaTemplateDataValidator(_StubTemplatesApi(_INVOICE_SCHEMA))

    assert validator.validate("acme-corp", "default", "invoice", _VALID_DATA) == []


def test_paths_are_json_pointers_not_dotted_keys():
    validator = JsonSchemaTemplateDataValidator(_StubTemplatesApi(_INVOICE_SCHEMA))
    data = dict(_VALID_DATA, customer={"name": "", "email": "jane@example.com"})

    findings = validator.validate("acme-corp", "default", "invoice", data)

    # The regression this guards: the implementation this replaced joined segments with `.`,
    # reporting `customer.name`.
    assert [f.path for f in findings] == ["/customer/name"]
    assert findings[0].keyword == "minLength"


def test_a_pointer_into_an_array_uses_the_index_as_a_segment():
    validator = JsonSchemaTemplateDataValidator(_StubTemplatesApi(_INVOICE_SCHEMA))
    data = dict(_VALID_DATA, lineItems=[{"quantity": 0}])

    findings = validator.validate("acme-corp", "default", "invoice", data)

    assert [f.path for f in findings] == ["/lineItems/0/quantity"]
    assert findings[0].keyword == "minimum"


def test_a_missing_required_field_is_reported_where_the_library_puts_it():
    validator = JsonSchemaTemplateDataValidator(_StubTemplatesApi(_INVOICE_SCHEMA))

    findings = validator.validate("acme-corp", "default", "invoice", {"invoiceNumber": "INV-2026-001"})

    # `jsonschema` reports `required` against the object that lacks the property, so the pointer is
    # the parent — the root here. The server reports the absent field's own pointer in
    # `missingFields[].path`, so a consumer who needs exact parity normalizes this; recorded rather
    # than papered over.
    assert [f.path for f in findings] == [""]
    assert findings[0].keyword == "required"


def test_format_is_not_asserted_which_is_why_the_server_owns_the_verdict():
    validator = JsonSchemaTemplateDataValidator(_StubTemplatesApi(_INVOICE_SCHEMA))
    data = dict(_VALID_DATA, customer={"name": "Jane Smith", "email": "not-an-email"})

    # Not a defect in the adapter: `jsonschema` treats `format` as an annotation unless a format
    # checker is passed. The Node reference adapter registers ajv-formats and *does* reject this.
    # Two local libraries, two answers, same contract — which is the argument for the default
    # being the one that decides what renders.
    assert validator.validate("acme-corp", "default", "invoice", data) == []


def test_a_template_without_a_schema_claims_nothing_about_the_data():
    validator = JsonSchemaTemplateDataValidator(_StubTemplatesApi(None))

    assert validator.validate("acme-corp", "default", "invoice", {"anything": 1}) == []


def test_the_schema_is_fetched_once_and_reused():
    templates = _StubTemplatesApi(_INVOICE_SCHEMA)
    validator = JsonSchemaTemplateDataValidator(templates)

    validator.validate("acme-corp", "default", "invoice", _VALID_DATA)
    validator.validate("acme-corp", "default", "invoice", _VALID_DATA)

    # Without the cache a "local" validator costs a round trip per call, which is worse than
    # asking the server to validate outright.
    assert templates.fetches == 1


def test_it_takes_the_generation_preflight():
    assert JsonSchemaTemplateDataValidator(_StubTemplatesApi(_INVOICE_SCHEMA)).preflights_generation is True
