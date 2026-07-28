# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""Guard test: the hand-written problem-type constants must not drift from the
build-time generated ones (which come straight from the spec's x-problem-types).

The Python analogue of the Kotlin/.NET ProblemRegistryTest.
"""

from epistola_client._generated.known_problem_slugs import (
    GENERATED_PROBLEM_TYPE_BASE,
    KnownProblemSlugs,
)
from epistola_client.error import problem_types

# The canonical slugs and their documented string values (docs/error-types.md /
# the spec's x-problem-types registry). If the registry changes, this test — and
# the generated KnownProblemSlugs — must be updated together.
EXPECTED_SLUGS = {
    "VALIDATION_ERROR": "validation-error",
    "BAD_REQUEST": "bad-request",
    "UNAUTHORIZED": "unauthorized",
    "API_KEY_AUTH_DISABLED": "api-key-auth-disabled",
    "FORBIDDEN": "forbidden",
    "NOT_FOUND": "not-found",
    "CONFLICT": "conflict",
    "DATA_MODEL_VALIDATION_ERROR": "data-model-validation-error",
    "RATE_LIMITED": "rate-limited",
}


def test_handwritten_base_matches_generated_base():
    assert problem_types.TYPE_BASE == GENERATED_PROBLEM_TYPE_BASE


def test_all_canonical_slug_constants_have_the_documented_values():
    for const_name, expected in EXPECTED_SLUGS.items():
        assert getattr(KnownProblemSlugs, const_name) == expected


def test_no_canonical_slug_is_missing_from_the_generated_registry():
    for const_name in EXPECTED_SLUGS:
        assert hasattr(KnownProblemSlugs, const_name), f"missing slug constant: {const_name}"
