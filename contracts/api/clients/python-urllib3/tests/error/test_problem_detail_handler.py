# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""Tests for parse_problem — the self-contained problem+json parser."""

import json

from epistola_client import KnownProblemSlugs
from epistola_client.error.problem_detail_handler import parse_problem


def test_parses_base_problem_and_exposes_type_slug():
    body = json.dumps(
        {
            "type": "https://epistola.app/errors/not-found",
            "title": "Not Found",
            "status": 404,
            "detail": "tenant acme not found",
        }
    )
    exc = parse_problem(body, 404)
    assert exc is not None
    assert exc.type_slug == KnownProblemSlugs.NOT_FOUND
    assert exc.problem_status == 404
    assert exc.detail == "tenant acme not found"
    assert not exc.is_validation_problem
    assert not exc.is_data_model_validation_problem


def test_parses_validation_problem_with_errors_array():
    body = json.dumps(
        {
            "type": "https://epistola.app/errors/validation-error",
            "title": "Validation Failed",
            "status": 400,
            "errors": [
                {"field": "name", "message": "must not be blank"},
                {"field": "slug", "message": "invalid", "rejectedValue": "BAD"},
            ],
        }
    )
    exc = parse_problem(body, 400)
    assert exc is not None
    assert exc.type_slug == KnownProblemSlugs.VALIDATION_ERROR
    assert exc.is_validation_problem
    assert [e.var_field for e in exc.errors] == ["name", "slug"]
    assert exc.errors[1].rejected_value == "BAD"


def test_parses_data_model_validation_problem_with_validation_errors_map():
    body = json.dumps(
        {
            "type": "https://epistola.app/errors/data-model-validation-error",
            "title": "Unprocessable",
            "status": 422,
            "validationErrors": {
                "example-a": [{"path": "#/customer/name", "message": "required"}],
            },
        }
    )
    exc = parse_problem(body, 422)
    assert exc is not None
    assert exc.type_slug == KnownProblemSlugs.DATA_MODEL_VALIDATION_ERROR
    assert exc.is_data_model_validation_problem
    assert exc.validation_errors["example-a"][0].path == "#/customer/name"


def test_about_blank_type_has_none_slug():
    body = json.dumps({"type": "about:blank", "title": "Server Error", "status": 500})
    exc = parse_problem(body, 500)
    assert exc is not None
    assert exc.type_slug is None
    assert exc.type == "about:blank"


def test_malformed_json_returns_none():
    assert parse_problem("{ not json", 400) is None


def test_non_object_body_returns_none():
    assert parse_problem("[1, 2, 3]", 400) is None
