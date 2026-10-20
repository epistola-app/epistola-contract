# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""Updating an environment is a partial update: only the members the caller set are sent, and
it is conditional on an `If-Match` only when the caller passes one."""

import json

from epistola_client import UpdateEnvironmentRequest


def test_unmarking_production_sends_false_and_nothing_else():
    assert json.loads(UpdateEnvironmentRequest(production=False).to_json()) == {"production": False}


def _serialized_headers(if_match):
    from epistola_client import EnvironmentsApi, EpistolaClientBuilder

    api = EnvironmentsApi(EpistolaClientBuilder().base_url("https://x.example/api").build())
    _, _, headers, _, _ = api._update_environment_serialize(
        "acme-corp", "staging", UpdateEnvironmentRequest(name="Staging"), if_match, None, None, None, 0
    )
    return headers


def test_an_update_sends_the_etag_it_was_given_as_if_match_byte_for_byte():
    assert _serialized_headers('"7"')["If-Match"] == '"7"'


def test_an_unconditional_update_sends_no_if_match():
    assert "If-Match" not in _serialized_headers(None)
