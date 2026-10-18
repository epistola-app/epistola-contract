# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""An environment override names an exact release or a draft (ADR 0002, amendment 2), and sends only
the one the caller set."""

import json

import pytest

from epistola_client import SetEnvironmentOverrideRequest, UpdateEnvironmentRequest


def test_a_release_override_sends_the_release_alone():
    assert json.loads(SetEnvironmentOverrideRequest(releaseVersion="1.3.0").to_json()) == {"releaseVersion": "1.3.0"}


@pytest.mark.parametrize("draft", ["next", "0193a6b2-8a10-7d2e-8b3c-4d5e6f708192"])
def test_a_draft_override_sends_the_draft_alone(draft):
    assert json.loads(SetEnvironmentOverrideRequest(catalogVersion=draft).to_json()) == {"catalogVersion": draft}


@pytest.mark.parametrize("release", ["^1.3.0", "1.3", "2.0.0-beta.1", "next"])
def test_anything_but_an_exact_release_is_refused(release):
    with pytest.raises(ValueError, match="release"):
        SetEnvironmentOverrideRequest(releaseVersion=release)


@pytest.mark.parametrize("draft", ["1.3.0", "Next", "draft-1"])
def test_anything_but_next_or_a_draft_id_is_refused(draft):
    with pytest.raises(ValueError):
        SetEnvironmentOverrideRequest(catalogVersion=draft)


def test_unmarking_production_sends_false_and_nothing_else():
    assert json.loads(UpdateEnvironmentRequest(production=False).to_json()) == {"production": False}
