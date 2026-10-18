# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""Long-term work is released as a pre-release (ADR 0002, amendment 2), so a release number in a
request may name one, and promoting the work sends only the members the caller set."""

import json

import pytest

from epistola_client import CreateCatalogDraftRequest, PromoteCatalogDraftRequest, ReleaseCatalogDraftRequest


@pytest.mark.parametrize("version", ["1.4.0", "2.0.0-beta.1", "2.0.0-redesign-2027.1"])
def test_a_release_or_pre_release_number_is_accepted(version):
    assert ReleaseCatalogDraftRequest(version=version).version == version
    assert CreateCatalogDraftRequest(name="Follow-up", **{"from": version}).var_from == version


@pytest.mark.parametrize("version", ["1.4", "v1.4.0", "1.4.0+build", "1.4.0-", "1.4.0-rc..1", "next"])
def test_anything_else_is_refused(version):
    with pytest.raises(ValueError, match="version"):
        ReleaseCatalogDraftRequest(version=version)


def test_a_promotion_sends_only_what_was_set():
    assert json.loads(PromoteCatalogDraftRequest().to_json()) == {}
    assert json.loads(
        PromoteCatalogDraftRequest(keepPreviousAs="Before the redesign", acknowledgedBehind=["1.5.0"]).to_json()
    ) == {"keepPreviousAs": "Before the redesign", "acknowledgedBehind": ["1.5.0"]}


@pytest.mark.parametrize("name", ["", "x" * 101])
def test_the_previous_next_version_is_kept_under_a_name_within_bounds(name):
    with pytest.raises(ValueError):
        PromoteCatalogDraftRequest(keepPreviousAs=name)
