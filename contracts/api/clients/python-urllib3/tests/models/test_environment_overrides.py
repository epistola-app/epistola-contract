# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""An environment override names an exact release or a draft (ADR 0002), and sends only
the one the caller set."""

import json

import pytest

from epistola_client import SetEnvironmentOverrideRequest, UpdateEnvironmentRequest


def test_a_release_override_sends_the_release_alone():
    assert json.loads(SetEnvironmentOverrideRequest(releaseVersion="1.3.0").to_json()) == {"releaseVersion": "1.3.0"}


@pytest.mark.parametrize("draft", ["r4t8w2nc"])
def test_a_draft_override_sends_the_draft_alone(draft):
    assert json.loads(SetEnvironmentOverrideRequest(versionId=draft).to_json()) == {"versionId": draft}


@pytest.mark.parametrize("release", ["^1.3.0", "1.3", "2.0.0-beta.1", "next"])
def test_anything_but_an_exact_release_is_refused(release):
    with pytest.raises(ValueError, match="release"):
        SetEnvironmentOverrideRequest(releaseVersion=release)


@pytest.mark.parametrize("draft", ["1.3.0", "Draft-1", "abc", "k7m2--x9"])
def test_anything_but_a_version_id_is_refused(draft):
    with pytest.raises(ValueError):
        SetEnvironmentOverrideRequest(versionId=draft)


def test_unmarking_production_sends_false_and_nothing_else():
    assert json.loads(UpdateEnvironmentRequest(production=False).to_json()) == {"production": False}
