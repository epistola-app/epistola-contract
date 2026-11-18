# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""Rendering again is a generation request with an earlier item's or result's ``data`` (alpha,
contract ADR 0011). That works only if the response's ``data`` has the type the request's
``data`` takes, and the request serializes it back exactly: every JSON type kept, nested objects
and arrays intact, a null inside the data still a null, and numbers still the numbers they were.
"""

import json

from epistola_client import (
    DocumentGenerationItemDto,
    EpistolaClientBuilder,
    GenerateDocumentRequest,
    GenerationApi,
)
from epistola_client.collect.result_collector import _parse_result

DATA_JSON = (
    '{"caseNumber": "Z-2026-0412", "amountDue": 19.99, "lineCount": 3, "whole": 2.0, '
    '"large": 12345678901234567890, "paid": false, "reference": null, '
    '"citizen": {"name": "J. Jansen", "address": {"street": "Kerkstraat 11", "unit": null}}, '
    '"tags": ["objection", 1, null, {"nested": true}], "noTags": [], "nothing": {}}'
)


def _sent_body(data) -> str:
    """The body ``generate_document`` puts on the wire for a request carrying ``data``."""
    api = GenerationApi(EpistolaClientBuilder().base_url("https://x.example/api").build())
    request = GenerateDocumentRequest(
        namespace="acme-corp", catalogKey="letters", templateKey="objection-decision", data=data
    )
    _, _, _, body, _ = api._generate_document_serialize("acme-corp", request, None, None, None, 0)
    return json.dumps(body)


def _sent_data(data) -> str:
    return json.dumps(json.loads(_sent_body(data))["data"])


def _item(data):
    return DocumentGenerationItemDto.from_dict(
        {
            "itemId": "660e8400-e29b-41d4-a716-446655440001",
            "registry": "exchange.epistola.app",
            "namespace": "acme-corp",
            "catalogKey": "letters",
            "templateKey": "objection-decision",
            "variantKey": "objection-decision-en",
            "status": "completed",
            "createdAt": "2026-11-18T09:12:00Z",
            "data": data,
            "editedFields": ["/citizen/address/street"],
        }
    )


def test_an_items_data_goes_back_into_a_request_byte_for_byte():
    item = _item(json.loads(DATA_JSON))

    assert _sent_data(item.data) == json.dumps(json.loads(DATA_JSON))


def test_a_collected_results_data_goes_back_into_a_request_byte_for_byte():
    result = _parse_result({"sequence": 1, "requestId": "r", "status": "completed", "data": json.loads(DATA_JSON)})

    assert _sent_data(result.data) == json.dumps(json.loads(DATA_JSON))


def test_numbers_keep_their_json_type_through_the_round_trip():
    sent = json.loads(_sent_body(_item(json.loads(DATA_JSON)).data))["data"]

    assert type(sent["amountDue"]) is float and sent["amountDue"] == 19.99
    assert type(sent["lineCount"]) is int
    assert type(sent["whole"]) is float
    assert sent["large"] == 12345678901234567890


def test_an_item_without_data_reads_data_and_edited_fields_as_none():
    item = DocumentGenerationItemDto.from_dict(
        {
            "itemId": "660e8400-e29b-41d4-a716-446655440002",
            "registry": "exchange.epistola.app",
            "namespace": "acme-corp",
            "catalogKey": "letters",
            "templateKey": "objection-decision",
            "variantKey": "objection-decision-nl",
            "status": "cancelled",
            "createdAt": "2026-11-18T09:13:00Z",
            "cancelReason": "expired",
            "data": None,
            "editedFields": None,
        }
    )

    assert (item.data, item.edited_fields) == (None, None)
    assert item.cancel_reason.value == "expired"
