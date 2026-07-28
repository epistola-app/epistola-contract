# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""Tests for EpistolaApiClient.response_deserialize — the installed problem handler."""

import json

import pytest
import urllib3

from epistola_client import (
    ApiException,
    EpistolaClientBuilder,
    ProblemDetailException,
)


class _FakeResponse:
    """Minimal RESTResponse stand-in for response_deserialize."""

    def __init__(self, status, content_type, body: str):
        self.status = status
        self.reason = "error"
        self.headers = urllib3.HTTPHeaderDict({"content-type": content_type})
        self.data = body.encode("utf-8")


def _client(install_handler: bool):
    builder = EpistolaClientBuilder().base_url("https://x.example/api")
    if install_handler:
        builder = builder.install_problem_detail_handler()
    return builder.build()


def test_problem_json_error_raises_typed_problem_detail_exception():
    client = _client(install_handler=True)
    body = json.dumps(
        {
            "type": "https://epistola.app/errors/not-found",
            "title": "Not Found",
            "status": 404,
        }
    )
    resp = _FakeResponse(404, "application/problem+json", body)
    with pytest.raises(ProblemDetailException) as excinfo:
        client.response_deserialize(resp, {})
    assert excinfo.value.type_slug == "not-found"


def test_non_problem_error_falls_through_to_generic_api_exception():
    client = _client(install_handler=True)
    resp = _FakeResponse(404, "application/json", '{"message":"nope"}')
    with pytest.raises(ApiException) as excinfo:
        client.response_deserialize(resp, {})
    assert not isinstance(excinfo.value, ProblemDetailException)


def test_handler_not_installed_never_raises_problem_detail_exception():
    client = _client(install_handler=False)
    body = json.dumps({"type": "https://epistola.app/errors/not-found", "title": "x", "status": 404})
    resp = _FakeResponse(404, "application/problem+json", body)
    with pytest.raises(ApiException) as excinfo:
        client.response_deserialize(resp, {})
    assert not isinstance(excinfo.value, ProblemDetailException)


def test_content_type_with_charset_is_recognized():
    client = _client(install_handler=True)
    body = json.dumps({"type": "https://epistola.app/errors/conflict", "title": "c", "status": 409})
    resp = _FakeResponse(409, "application/problem+json; charset=utf-8", body)
    with pytest.raises(ProblemDetailException) as excinfo:
        client.response_deserialize(resp, {})
    assert excinfo.value.type_slug == "conflict"


def test_api_key_auth_header_is_added_to_generated_requests():
    client = EpistolaClientBuilder().base_url("https://x.example/api").api_key(" epk_test ").build()
    _, _, headers, _, _ = client.param_serialize("GET", "/tenants", {}, None, [])
    assert headers["Authorization"] == "ApiKey epk_test"


def test_api_key_auth_header_is_available_for_raw_collect_requests():
    client = EpistolaClientBuilder().api_key("epk_test").build()
    assert client.epistola_request_headers()["Authorization"] == "ApiKey epk_test"


def test_blank_api_key_is_rejected():
    with pytest.raises(ValueError, match="api_key must not be blank"):
        EpistolaClientBuilder().api_key(" ")
