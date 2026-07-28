# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""Tests for client-side template-data schema validation."""

from types import SimpleNamespace

import pytest

from epistola_client import (
    TemplateDataValidationError,
    TemplateSchemaValidator,
    ValidatingGenerationApi,
)

_SCHEMA = {
    "type": "object",
    "required": ["name"],
    "properties": {
        "name": {"type": "string"},
        "age": {"type": "integer", "minimum": 0},
    },
}


class _StubTemplatesApi:
    def __init__(self, schema):
        self._schema = schema
        self.calls = 0

    def get_template(self, tenant_id, catalog_id, template_id):
        self.calls += 1
        return SimpleNamespace(var_schema=self._schema)


class _StubGenerationApi:
    def __init__(self):
        self.generated = []

    def generate_document(self, tenant_id, request):
        self.generated.append(request)
        return "ok"

    def generate_document_batch(self, tenant_id, request):
        self.generated.append(request)
        return "ok"


def test_valid_data_passes():
    validator = TemplateSchemaValidator(_StubTemplatesApi(_SCHEMA))
    validator.validate("t", "c", "tpl", {"name": "Ada", "age": 30})  # no raise


def test_invalid_data_raises_with_field_failures():
    validator = TemplateSchemaValidator(_StubTemplatesApi(_SCHEMA))
    with pytest.raises(TemplateDataValidationError) as excinfo:
        validator.validate("t", "c", "tpl", {"age": -1})
    messages = excinfo.value.format_errors()
    assert "name" in messages or any(f.keyword == "required" for f in excinfo.value.errors)


def test_no_schema_is_a_noop():
    validator = TemplateSchemaValidator(_StubTemplatesApi(None))
    validator.validate("t", "c", "tpl", {"whatever": True})  # no raise


def test_schema_is_cached_between_calls():
    api = _StubTemplatesApi(_SCHEMA)
    validator = TemplateSchemaValidator(api)
    validator.validate("t", "c", "tpl", {"name": "a"})
    validator.validate("t", "c", "tpl", {"name": "b"})
    assert api.calls == 1


def test_validating_generation_api_validates_before_delegating():
    gen = _StubGenerationApi()
    api = ValidatingGenerationApi(gen, _StubTemplatesApi(_SCHEMA))
    request = SimpleNamespace(catalog_id="c", template_id="tpl", data={"age": -5})
    with pytest.raises(TemplateDataValidationError):
        api.generate_document("t", request)
    assert gen.generated == []  # server never called


def test_validating_generation_api_batch_aggregates_errors_with_item_index():
    gen = _StubGenerationApi()
    api = ValidatingGenerationApi(gen, _StubTemplatesApi(_SCHEMA))
    request = SimpleNamespace(
        items=[
            SimpleNamespace(catalog_id="c", template_id="tpl", data={"name": "ok"}),
            SimpleNamespace(catalog_id="c", template_id="tpl", data={}),  # missing name
        ]
    )
    with pytest.raises(TemplateDataValidationError) as excinfo:
        api.generate_document_batch("t", request)
    assert any(f.path.startswith("items[1]") for f in excinfo.value.errors)
