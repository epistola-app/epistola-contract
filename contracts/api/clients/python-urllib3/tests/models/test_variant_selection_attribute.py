# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""An attribute criterion names its attribute one way: ``key`` is the attribute's resource key, and
the catalog that defines it is named by ``catalogKey``, ``namespace`` and ``registry``, never by a
dotted ``<catalogKey>.<key>``."""

import json

import pydantic
import pytest

from epistola_client import VariantSelectionAttribute


def test_a_criterion_names_another_catalogs_attribute_in_members_of_its_own():
    criterion = VariantSelectionAttribute(key="locale", value="nl-NL", catalogKey="system", namespace="epistola")
    assert json.loads(criterion.to_json()) == {
        "key": "locale",
        "value": "nl-NL",
        "catalogKey": "system",
        "namespace": "epistola",
        "required": True,
    }


def test_a_criterion_without_a_catalog_names_the_templates_own_and_its_key_may_start_with_a_digit():
    assert VariantSelectionAttribute(key="2026-campaign", value="spring").catalog_key is None


def test_a_dotted_key_is_refused():
    with pytest.raises(pydantic.ValidationError):
        VariantSelectionAttribute(key="system.locale", value="nl-NL")
