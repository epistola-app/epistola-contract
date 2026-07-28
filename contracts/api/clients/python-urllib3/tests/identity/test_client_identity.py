# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""Tests for ClientIdentity header assembly."""

import pytest

from epistola_client import ClientIdentity
from epistola_client._generated.contract_version import CONTRACT_VERSION


def test_user_agent_starts_with_contract_token_and_appends_products():
    identity = (
        ClientIdentity.builder()
        .node_id("my-pod")
        .product("valtimo-epistola-plugin", "1.2.0")
        .product("gzac", "5.0.0")
        .build()
    )
    assert identity.user_agent == (
        f"epistola-contract/{CONTRACT_VERSION} valtimo-epistola-plugin/1.2.0 gzac/5.0.0"
    )
    assert identity.node_id == "my-pod"


def test_node_id_defaults_to_hostname():
    identity = ClientIdentity.builder().build()
    assert identity.node_id  # some non-empty hostname


def test_headers_dict_contains_both_headers():
    identity = ClientIdentity.builder().node_id("n1").build()
    headers = identity.headers()
    assert headers["X-EP-Node-Id"] == "n1"
    assert headers["User-Agent"].startswith("epistola-contract/")


@pytest.mark.parametrize("name", ["bad/name", "bad name", "", "   "])
def test_invalid_product_name_is_rejected(name):
    with pytest.raises(ValueError):
        ClientIdentity.builder().product(name, "1.0.0")


def test_blank_product_version_is_rejected():
    with pytest.raises(ValueError):
        ClientIdentity.builder().product("ok", "")
