# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""A release number is a plain ``MAJOR.MINOR.PATCH``: only the next version is released, and long-term
work becomes the next version first (ADR 0002, amendment 2). Promoting the work sends only the
members the caller set."""

import json

import pytest

from epistola_client import (
    CatalogDependencyRequirement,
    CreateCatalogDraftRequest,
    PromoteCatalogDraftRequest,
    ReleaseCatalogDraftRequest,
)


@pytest.mark.parametrize("version", ["0.1.0", "1.4.0", "10.20.30"])
def test_a_release_number_is_accepted(version):
    assert ReleaseCatalogDraftRequest(version=version).version == version
    assert CreateCatalogDraftRequest(name="Follow-up", **{"from": version}).var_from == version


@pytest.mark.parametrize("version", ["1.4", "v1.4.0", "1.4.0+build", "2.0.0-beta.1", "2.0.0-redesign-2027.1", "next"])
def test_anything_else_is_refused(version):
    with pytest.raises(ValueError, match="version"):
        ReleaseCatalogDraftRequest(version=version)
    with pytest.raises(ValueError):
        CreateCatalogDraftRequest(name="Follow-up", **{"from": version})


def test_a_promotion_sends_only_what_was_set():
    assert json.loads(PromoteCatalogDraftRequest().to_json()) == {}
    assert json.loads(
        PromoteCatalogDraftRequest(keepPreviousAs="Before the redesign", acknowledgedBehind=["1.5.0"]).to_json()
    ) == {"keepPreviousAs": "Before the redesign", "acknowledgedBehind": ["1.5.0"]}


@pytest.mark.parametrize("name", ["", "x" * 101])
def test_the_previous_next_version_is_kept_under_a_name_within_bounds(name):
    with pytest.raises(ValueError):
        PromoteCatalogDraftRequest(keepPreviousAs=name)


def test_a_dependency_declaration_pins_an_exact_release():
    pinned = CatalogDependencyRequirement(namespace="epistola", catalogKey="system", releaseVersion="1.3.0")
    assert json.loads(pinned.to_json()) == {"namespace": "epistola", "catalogKey": "system", "releaseVersion": "1.3.0"}


@pytest.mark.parametrize("release", ["^1.3.0", "1.x", "*", "01.3.0", "1.3.0-rc.1"])
def test_a_dependency_declaration_refuses_a_range(release):
    with pytest.raises(ValueError):
        CatalogDependencyRequirement(namespace="epistola", catalogKey="system", releaseVersion=release)
