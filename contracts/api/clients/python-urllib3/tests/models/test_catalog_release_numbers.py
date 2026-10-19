# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""A release number is a plain ``MAJOR.MINOR.PATCH``, and any draft can be released (ADR 0002). A
draft starts from a release number or another draft's id, and a dependency declaration in a draft
names an exact release or a draft, sending only the one the caller set."""

import json

import pytest

from epistola_client import (
    CatalogDependencyRequirement,
    CreateCatalogDraftRequest,
    ReleaseCatalogDraftRequest,
)

DRAFT_ID = "0193a6b2-8a10-7d2e-8b3c-4d5e6f708192"


@pytest.mark.parametrize("version", ["0.1.0", "1.4.0", "10.20.30"])
def test_a_release_number_is_accepted(version):
    assert ReleaseCatalogDraftRequest(version=version).version == version
    assert CreateCatalogDraftRequest(name="Follow-up", **{"from": version}).var_from == version


def test_a_draft_starts_from_another_draft_by_its_id():
    request = CreateCatalogDraftRequest(name="Redesign 2027", **{"from": DRAFT_ID})
    assert json.loads(request.to_json()) == {"name": "Redesign 2027", "from": DRAFT_ID}


@pytest.mark.parametrize("version", ["1.4", "v1.4.0", "1.4.0+build", "2.0.0-beta.1", "2.0.0-redesign-2027.1", "next"])
def test_anything_else_is_refused(version):
    with pytest.raises(ValueError, match="version"):
        ReleaseCatalogDraftRequest(version=version)
    with pytest.raises(ValueError):
        CreateCatalogDraftRequest(name="Follow-up", **{"from": version})


def test_a_release_takes_its_dependencies_along_only_when_asked():
    # The generated model fills in the contract default, so an unset flag goes out as ``false``,
    # which the contract reads as omitted.
    assert json.loads(ReleaseCatalogDraftRequest().to_json()) == {"releaseDependencies": False}
    assert json.loads(ReleaseCatalogDraftRequest(releaseDependencies=True).to_json()) == {"releaseDependencies": True}


def test_a_dependency_declaration_pins_an_exact_release():
    pinned = CatalogDependencyRequirement(namespace="epistola", catalogKey="system", releaseVersion="1.3.0")
    assert json.loads(pinned.to_json()) == {"namespace": "epistola", "catalogKey": "system", "releaseVersion": "1.3.0"}


def test_a_dependency_declaration_in_a_draft_names_a_draft_alone():
    on_draft = CatalogDependencyRequirement(namespace="acme-corp", catalogKey="branding", draftId=DRAFT_ID)
    assert json.loads(on_draft.to_json()) == {"namespace": "acme-corp", "catalogKey": "branding", "draftId": DRAFT_ID}


@pytest.mark.parametrize("release", ["^1.3.0", "1.x", "*", "01.3.0", "1.3.0-rc.1"])
def test_a_dependency_declaration_refuses_a_range(release):
    with pytest.raises(ValueError):
        CatalogDependencyRequirement(namespace="epistola", catalogKey="system", releaseVersion=release)
