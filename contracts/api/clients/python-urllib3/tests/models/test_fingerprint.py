# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""The generated models validate a fingerprint's pattern when they parse a response, so a
fingerprint the contract allows must parse as sent, prefix and all, and one that does not name
its algorithm (a bare hex value) is refused."""

import pytest

from epistola_client import CatalogReleaseDto

HEX = "9f3a1c2b4d5e6f70819a2b3c4d5e6f70819a2b3c4d5e6f70819a2b3c4d5e6f70"


def _version(fingerprint):
    return {
        "version": "1.4.0",
        "releaseId": "m3q8v5tk",
        "status": "released",
        "createdAt": "2026-01-01T00:00:00Z",
        "behind": [],
        "fingerprint": fingerprint,
        "dependencies": [],
        "deployedTo": [],
    }


@pytest.mark.parametrize("fingerprint", [f"v5:{HEX}", f"v12:{HEX}", None])
def test_a_fingerprint_the_contract_allows_parses_as_sent(fingerprint):
    assert CatalogReleaseDto.from_dict(_version(fingerprint)).fingerprint == fingerprint


@pytest.mark.parametrize("fingerprint", [HEX, f"v0:{HEX}", f"V5:{HEX}", HEX.upper(), f"v5:{HEX[:-1]}", f"v5:{HEX.upper()}"])
def test_a_fingerprint_the_contract_does_not_allow_is_refused(fingerprint):
    with pytest.raises(ValueError, match="fingerprint"):
        CatalogReleaseDto.from_dict(_version(fingerprint))
