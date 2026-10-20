# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""Tests for parse_problem — the self-contained problem+json parser."""

import json

from epistola_client import KnownProblemSlugs, MissingDependency, UnreleasedDependency
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


def test_parses_catalog_source_unavailable_as_a_503_and_as_a_502():
    for status in (503, 502):
        body = json.dumps(
            {
                "type": "https://epistola.app/errors/catalog-source-unavailable",
                "title": "Catalog Source Unavailable",
                "status": status,
                "detail": "registry.gemeente-x.nl could not be used",
            }
        )
        exc = parse_problem(body, status)
        assert exc is not None
        assert exc.type_slug == KnownProblemSlugs.CATALOG_SOURCE_UNAVAILABLE
        assert exc.problem_status == status


def test_parses_validation_problem_with_errors_array():
    body = json.dumps(
        {
            "type": "https://epistola.app/errors/validation-error",
            "title": "Validation Failed",
            "status": 400,
            "errors": [
                {"field": "name", "message": "must not be blank"},
                {"field": "key", "message": "invalid", "rejectedValue": "BAD"},
            ],
        }
    )
    exc = parse_problem(body, 400)
    assert exc is not None
    assert exc.type_slug == KnownProblemSlugs.VALIDATION_ERROR
    assert exc.is_validation_problem
    assert [e.var_field for e in exc.errors] == ["name", "key"]
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


def test_parses_template_data_problem_with_its_field_pointers():
    # The members contract 1.4.0 added. They were specified and generated, but nothing here read
    # them, so a `template-data-invalid` response arrived with its field pointers discarded.
    body = json.dumps(
        {
            "type": "https://epistola.app/errors/template-data-invalid",
            "title": "Template data invalid",
            "status": 400,
            "detail": "The supplied data does not fit the template's data contract",
            "errors": [],
            "missingFields": [
                {"path": "/customer/address", "required": True, "schema": {"type": "object"}},
                {"path": "/customer/phone", "required": False, "schema": {"type": "string"}},
            ],
            "invalidFields": [
                {
                    "path": "/customer/age",
                    "keyword": "type",
                    "message": "string found, integer expected",
                    "schema": {"type": "integer"},
                }
            ],
        }
    )
    exc = parse_problem(body, 400)
    assert exc is not None
    assert exc.type_slug == KnownProblemSlugs.TEMPLATE_DATA_INVALID
    assert exc.is_template_data_problem
    assert [f.path for f in exc.missing_fields] == ["/customer/address", "/customer/phone"]
    assert [f.required for f in exc.missing_fields] == [True, False]
    assert [f.path for f in exc.invalid_fields] == ["/customer/age"]
    assert exc.invalid_fields[0].keyword == "type"


def test_another_problem_reports_no_template_data_fields():
    body = json.dumps(
        {"type": "https://epistola.app/errors/not-found", "title": "Not Found", "status": 404}
    )
    exc = parse_problem(body, 404)
    assert exc is not None
    assert not exc.is_template_data_problem
    assert exc.missing_fields == []
    assert exc.invalid_fields == []


def test_a_malformed_template_data_member_yields_no_findings_rather_than_a_guess():
    body = json.dumps(
        {
            "type": "https://epistola.app/errors/template-data-invalid",
            "title": "Template data invalid",
            "status": 400,
            "missingFields": "not an array",
            "invalidFields": [{"unexpected": "shape"}],
        }
    )
    exc = parse_problem(body, 400)
    # A bad problem body must not hide the problem it decorates, nor become a confident claim
    # about a particular field.
    assert exc is not None
    assert exc.type_slug == KnownProblemSlugs.TEMPLATE_DATA_INVALID
    assert exc.missing_fields == []
    assert exc.invalid_fields == []


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


def _release_dependencies_missing(missing_dependencies) -> str:
    body = {
        "type": "https://epistola.app/errors/release-dependencies-missing",
        "title": "Release dependencies missing",
        "status": 409,
        "detail": "letters 1.4.0 renders with releases production does not serve",
    }
    if missing_dependencies is not None:
        body["missingDependencies"] = missing_dependencies
    return json.dumps(body)


def test_parses_release_dependencies_missing_problem_in_order():
    body = _release_dependencies_missing(
        [
            {
                "registry": "exchange.epistola.app",
                "namespace": "epistola",
                "catalogKey": "system",
                "version": "3.2.0",
                "reason": "unavailable",
            },
            {
                "registry": "registry.gemeente-x.nl",
                "namespace": "huisstijl",
                "catalogKey": "branding",
                "version": "1.4.2",
                "reason": "unavailable",
            },
        ]
    )
    exc = parse_problem(body, 409)
    assert exc is not None
    assert exc.type_slug == KnownProblemSlugs.RELEASE_DEPENDENCIES_MISSING
    assert exc.is_release_dependencies_missing_problem
    assert exc.missing_dependencies == [
        MissingDependency("exchange.epistola.app", "epistola", "system", "3.2.0", "unavailable"),
        MissingDependency(
            "registry.gemeente-x.nl", "huisstijl", "branding", "1.4.2", "unavailable"
        ),
    ]
    assert exc.missing_dependencies[0].catalog_key == "system"
    assert exc.missing_dependencies[0].version == "3.2.0"


def test_an_unknown_missing_dependency_reason_is_kept_as_sent():
    # The reasons are an open set: a reason a later contract adds must reach the caller, not
    # fail the member the way the generated enum would.
    body = _release_dependencies_missing(
        [
            {
                "registry": "exchange.epistola.app",
                "namespace": "epistola",
                "catalogKey": "system",
                "version": "3.2.0",
                "reason": "quarantined",
            }
        ]
    )
    exc = parse_problem(body, 409)
    assert exc is not None
    assert [d.reason for d in exc.missing_dependencies] == ["quarantined"]


def test_a_release_dependencies_problem_without_the_member_lists_nothing():
    exc = parse_problem(_release_dependencies_missing(None), 409)
    assert exc is not None
    assert exc.type_slug == KnownProblemSlugs.RELEASE_DEPENDENCIES_MISSING
    assert exc.missing_dependencies == []
    assert not exc.is_release_dependencies_missing_problem


def test_another_problem_reports_no_missing_dependencies():
    body = json.dumps(
        {"type": "https://epistola.app/errors/conflict", "title": "Conflict", "status": 409}
    )
    exc = parse_problem(body, 409)
    assert exc is not None
    assert exc.missing_dependencies == []
    assert not exc.is_release_dependencies_missing_problem


def test_a_malformed_missing_dependencies_member_yields_nothing_rather_than_a_guess():
    not_an_array = parse_problem(_release_dependencies_missing("not an array"), 409)
    assert not_an_array is not None
    assert not_an_array.type_slug == KnownProblemSlugs.RELEASE_DEPENDENCIES_MISSING
    assert not_an_array.missing_dependencies == []

    incomplete_entry = parse_problem(
        _release_dependencies_missing(
            [
                {
                    "registry": "exchange.epistola.app",
                    "namespace": "epistola",
                    "catalogKey": "system",
                    "version": "3.2.0",
                    "reason": "unavailable",
                },
                {"registry": "exchange.epistola.app", "catalogKey": 42},
            ]
        ),
        409,
    )
    assert incomplete_entry is not None
    assert incomplete_entry.type_slug == KnownProblemSlugs.RELEASE_DEPENDENCIES_MISSING
    assert incomplete_entry.missing_dependencies == []


def _release_dependencies_unreleased(unreleased_dependencies) -> str:
    body = {
        "type": "https://epistola.app/errors/release-dependencies-unreleased",
        "title": "Release dependencies unreleased",
        "status": 409,
        "detail": "letters uses drafts of other catalogs",
    }
    if unreleased_dependencies is not None:
        body["unreleasedDependencies"] = unreleased_dependencies
    return json.dumps(body)


def test_parses_release_dependencies_unreleased_problem_in_order():
    body = _release_dependencies_unreleased(
        [
            {
                "registry": "exchange.epistola.app",
                "namespace": "acme-corp",
                "catalogKey": "branding",
                "releaseId": "r4t8w2nc",
                "releaseName": "House style 2027",
            },
            {
                "registry": "registry.gemeente-x.nl",
                "namespace": "huisstijl",
                "catalogKey": "fonts",
                "releaseId": "h3v9p6db",
                "releaseName": "New typefaces, round 2",
            },
        ]
    )
    exc = parse_problem(body, 409)
    assert exc is not None
    assert exc.type_slug == KnownProblemSlugs.RELEASE_DEPENDENCIES_UNRELEASED
    assert exc.is_release_dependencies_unreleased_problem
    assert not exc.is_release_dependencies_missing_problem
    assert exc.unreleased_dependencies == [
        UnreleasedDependency(
            "exchange.epistola.app",
            "acme-corp",
            "branding",
            "r4t8w2nc",
            "House style 2027",
        ),
        UnreleasedDependency(
            "registry.gemeente-x.nl",
            "huisstijl",
            "fonts",
            "h3v9p6db",
            "New typefaces, round 2",
        ),
    ]
    assert exc.unreleased_dependencies[0].release_id == "r4t8w2nc"
    assert exc.unreleased_dependencies[1].release_name == "New typefaces, round 2"


def test_another_problem_reports_no_unreleased_dependencies():
    exc = parse_problem(_release_dependencies_missing(None), 409)
    assert exc is not None
    assert exc.unreleased_dependencies == []
    assert not exc.is_release_dependencies_unreleased_problem


def test_a_malformed_unreleased_dependencies_member_yields_nothing_rather_than_a_guess():
    not_an_array = parse_problem(_release_dependencies_unreleased("not an array"), 409)
    assert not_an_array is not None
    assert not_an_array.type_slug == KnownProblemSlugs.RELEASE_DEPENDENCIES_UNRELEASED
    assert not_an_array.unreleased_dependencies == []

    incomplete_entry = parse_problem(
        _release_dependencies_unreleased(
            [
                {
                    "registry": "exchange.epistola.app",
                    "namespace": "acme-corp",
                    "catalogKey": "branding",
                    "releaseId": "r4t8w2nc",
                    "releaseName": "House style 2027",
                },
                {"registry": "exchange.epistola.app", "releaseId": 42},
            ]
        ),
        409,
    )
    assert incomplete_entry is not None
    assert incomplete_entry.unreleased_dependencies == []
