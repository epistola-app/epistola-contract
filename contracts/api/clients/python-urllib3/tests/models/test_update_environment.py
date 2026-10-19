# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""Updating an environment is a partial update: only the members the caller set are sent."""

import json

from epistola_client import UpdateEnvironmentRequest


def test_unmarking_production_sends_false_and_nothing_else():
    assert json.loads(UpdateEnvironmentRequest(production=False).to_json()) == {"production": False}
