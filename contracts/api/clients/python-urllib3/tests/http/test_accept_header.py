# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""Tests for EpistolaApiClient.select_header_accept.

The generated implementation returns the first entry matching ``json``, which drops
``application/problem+json`` from every operation that also declares a success body — so the client
asks for a document it is built to parse and cannot be sent. A server doing strict content
negotiation answers 406, and the typed ``ProblemDetailException`` never gets a body.
"""

import pytest

from epistola_client import EpistolaClientBuilder

VENDOR_JSON = "application/vnd.epistola.v1+json"
PROBLEM_JSON = "application/problem+json"


@pytest.fixture
def client():
    return EpistolaClientBuilder().base_url("http://localhost/api").build()


def test_asks_for_the_problem_document_alongside_the_success_body(client):
    assert client.select_header_accept([VENDOR_JSON, PROBLEM_JSON]) == f"{VENDOR_JSON}, {PROBLEM_JSON}"


def test_keeps_the_order_the_operation_declares(client):
    assert client.select_header_accept([PROBLEM_JSON, VENDOR_JSON]) == f"{PROBLEM_JSON}, {VENDOR_JSON}"


def test_a_single_declared_type_is_sent_alone(client):
    # ping declares no error responses, so asking only for the success type is correct there.
    assert client.select_header_accept([VENDOR_JSON]) == VENDOR_JSON


def test_non_json_types_fall_back_to_the_first(client):
    assert client.select_header_accept(["application/pdf", "application/octet-stream"]) == "application/pdf"


def test_no_declared_types_means_no_header(client):
    assert client.select_header_accept([]) is None
