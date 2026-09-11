# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""Tests for EpistolaApiClient.select_header_accept.

The generated implementation returns the first entry matching ``json``. That drops
``application/problem+json`` from every operation that also declares a JSON success body, so the
client does not ask for the problem document it is built to parse. On a binary download the only
JSON entry is the problem document, so keeping only JSON types asks for no PDF or image at all. A
server doing strict content negotiation answers 406 in both cases.
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


def test_a_binary_download_asks_for_what_it_returns_as_well_as_the_problem_document(client):
    declared = ["image/png", "image/jpeg", "image/svg+xml", "image/webp", PROBLEM_JSON]
    assert client.select_header_accept(declared) == ", ".join(declared)


def test_every_declared_type_is_sent_when_none_is_json(client):
    assert (
        client.select_header_accept(["application/pdf", "application/octet-stream"])
        == "application/pdf, application/octet-stream"
    )


def test_no_declared_types_means_no_header(client):
    assert client.select_header_accept([]) is None
