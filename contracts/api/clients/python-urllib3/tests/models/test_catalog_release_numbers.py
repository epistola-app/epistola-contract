# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""A version is a plain ``MAJOR.MINOR.PATCH``, or a patch release ``MAJOR.MINOR.PATCH-<namespace>.<N>``
(contract ADR 0003), and any draft can be released (ADR 0002). A draft
starts from a version or another release's ``releaseId``, and a dependency declaration in a draft
names an exact version or a draft, sending only the one the caller set."""

import json

import pytest

from epistola_client import (
    CatalogDependencyRequirement,
    CreateCatalogReleaseRequest,
    GenerateDocumentRequest,
    ReleaseCatalogDraftRequest,
)

RELEASE_ID = "r4t8w2nc"


@pytest.mark.parametrize("version", ["0.1.0", "1.4.0", "10.20.30", "1.3.1-gemeente-x.1", "2.0.0-redesign-2027.12"])
def test_a_release_number_is_accepted(version):
    assert ReleaseCatalogDraftRequest(version=version).version == version
    assert CreateCatalogReleaseRequest(name="Follow-up", **{"from": version}).var_from == version


def test_a_draft_starts_from_another_draft_by_its_id():
    request = CreateCatalogReleaseRequest(name="Redesign 2027", **{"from": RELEASE_ID})
    assert json.loads(request.to_json()) == {"name": "Redesign 2027", "from": RELEASE_ID}


@pytest.mark.parametrize(
    "version",
    [
        "1.4",
        "v1.4.0",
        "1.4.0+build",
        "2.0.0-rc.1",
        "1.3.1-gemeente-x.0",
        "1.3.1-gemeente-x.01",
        "1.3.1-Gemeente-x.1",
        "1.3.1-gemeente-x",
        "Next",
    ],
)
def test_anything_else_is_refused(version):
    with pytest.raises(ValueError, match="version"):
        ReleaseCatalogDraftRequest(version=version)
    with pytest.raises(ValueError):
        CreateCatalogReleaseRequest(name="Follow-up", **{"from": version})


def test_a_release_takes_its_dependencies_along_only_when_asked():
    # The generated model fills in the contract default, so an unset flag goes out as ``false``,
    # which the contract reads as omitted.
    assert json.loads(ReleaseCatalogDraftRequest().to_json()) == {"releaseDependencies": False}
    assert json.loads(ReleaseCatalogDraftRequest(releaseDependencies=True).to_json()) == {"releaseDependencies": True}


def test_a_dependency_declaration_pins_an_exact_release():
    pinned = CatalogDependencyRequirement(namespace="epistola", catalogKey="system", version="1.3.0")
    assert json.loads(pinned.to_json()) == {"namespace": "epistola", "catalogKey": "system", "version": "1.3.0"}


def test_a_dependency_declaration_may_give_the_catalog_an_alias():
    aliased = CatalogDependencyRequirement(alias="shared-branding", namespace="acme-corp", catalogKey="branding", version="1.3.0")
    assert json.loads(aliased.to_json()) == {
        "alias": "shared-branding",
        "namespace": "acme-corp",
        "catalogKey": "branding",
        "version": "1.3.0",
    }
    with pytest.raises(ValueError):
        CatalogDependencyRequirement(alias="x", namespace="acme-corp", catalogKey="branding", version="1.3.0")


def test_a_dependency_declaration_in_a_draft_names_a_draft_alone():
    on_draft = CatalogDependencyRequirement(namespace="acme-corp", catalogKey="branding", releaseId=RELEASE_ID)
    assert json.loads(on_draft.to_json()) == {"namespace": "acme-corp", "catalogKey": "branding", "releaseId": RELEASE_ID}


@pytest.mark.parametrize("release", ["^1.3.0", "1.x", "*", "01.3.0", "1.3.0-rc.1"])
def test_a_dependency_declaration_refuses_a_range(release):
    with pytest.raises(ValueError):
        CatalogDependencyRequirement(namespace="epistola", catalogKey="system", version=release)


@pytest.mark.parametrize("version_range", ["1.0." + "1" * 124, "x" * 256])
def test_a_version_range_holds_an_exact_release_number_of_any_valid_length(version_range):
    request = GenerateDocumentRequest(
        namespace="acme-corp", catalogKey="letters", templateKey="invoice", data={}, versionRange=version_range
    )
    assert request.version_range == version_range


def test_a_version_range_is_at_most_256_characters():
    with pytest.raises(ValueError):
        GenerateDocumentRequest(namespace="acme-corp", catalogKey="letters", templateKey="invoice", data={}, versionRange="x" * 257)


def test_local_is_the_one_registry_without_a_dot():
    request = GenerateDocumentRequest(registry="local", namespace="acme-hr", catalogKey="letters", templateKey="invoice", data={})
    assert request.registry == "local"


@pytest.mark.parametrize("registry", ["localhost", "Local", "locals"])
def test_a_single_label_other_than_local_is_no_registry(registry):
    with pytest.raises(ValueError):
        GenerateDocumentRequest(registry=registry, namespace="acme-hr", catalogKey="letters", templateKey="invoice", data={})
