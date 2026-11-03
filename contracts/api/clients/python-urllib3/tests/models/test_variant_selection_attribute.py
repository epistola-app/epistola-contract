# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""An attribute criterion names its attribute one way: ``key`` is the attribute's resource key, and
the catalog that defines it is named by ``catalogKey``, the alias the rendering release's dependency
declarations give it, never by a dotted ``<catalogKey>.<key>``, a namespace or a registry."""

import json

import pydantic
import pytest

from epistola_client import VariantSelectionAttribute


def test_a_criterion_names_another_catalogs_attribute_by_its_alias():
    criterion = VariantSelectionAttribute(key="locale", value="nl-NL", catalogKey="system")
    assert json.loads(criterion.to_json()) == {
        "key": "locale",
        "value": "nl-NL",
        "catalogKey": "system",
        "required": True,
    }


def test_an_alias_follows_the_catalog_key_rule():
    with pytest.raises(pydantic.ValidationError):
        VariantSelectionAttribute(key="locale", value="nl-NL", catalogKey="System")


def test_a_criterion_without_a_catalog_names_the_templates_own_and_its_key_may_start_with_a_digit():
    assert VariantSelectionAttribute(key="2026-campaign", value="spring").catalog_key is None


def test_a_dotted_key_is_refused():
    with pytest.raises(pydantic.ValidationError):
        VariantSelectionAttribute(key="system.locale", value="nl-NL")
