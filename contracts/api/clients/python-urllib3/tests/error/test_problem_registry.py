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
from epistola_client._generated.known_validation_codes import KnownValidationCodes
from epistola_client.error import problem_types

# The canonical slugs and their documented string values (docs/error-types.md /
# the spec's x-problem-types registry). If the registry changes, this test — and
# the generated KnownProblemSlugs — must be updated together.
EXPECTED_SLUGS = {
    "VALIDATION_ERROR": "validation-error",
    "TEMPLATE_DATA_INVALID": "template-data-invalid",
    "BAD_REQUEST": "bad-request",
    "CATALOG_RENDERING_DEFAULTS_TOO_NEW": "catalog-rendering-defaults-too-new",
    "UNAUTHORIZED": "unauthorized",
    "API_KEY_AUTH_DISABLED": "api-key-auth-disabled",
    "FORBIDDEN": "forbidden",
    "NOT_FOUND": "not-found",
    "CONFLICT": "conflict",
    "RELEASE_REGISTRY_NOT_FOUND": "release-registry-not-found",
    "RELEASE_NUMBER_TAKEN": "release-number-taken",
    "NAMESPACE_NOT_GRANTED": "namespace-not-granted",
    "RELEASE_DEPENDENCIES_MISSING": "release-dependencies-missing",
    "RELEASE_DEPENDENCIES_UNRELEASED": "release-dependencies-unreleased",
    "CATALOG_READ_ONLY": "catalog-read-only",
    "MERGE_CONFLICTS_UNRESOLVED": "merge-conflicts-unresolved",
    "MERGE_REFUSED": "merge-refused",
    "MERGE_PREVIEW_OUTDATED": "merge-preview-outdated",
    "MERGE_BASE_MISSING": "merge-base-missing",
    "MERGE_NOT_APPLICABLE": "merge-not-applicable",
    "ENVIRONMENT_DEPLOYMENT_REFUSED": "environment-deployment-refused",
    "RESOURCE_MODIFIED": "resource-modified",
    "DATA_MODEL_VALIDATION_ERROR": "data-model-validation-error",
    "RATE_LIMITED": "rate-limited",
    "METHOD_NOT_ALLOWED": "method-not-allowed",
    "NOT_ACCEPTABLE": "not-acceptable",
    "CONTENT_TOO_LARGE": "content-too-large",
    "UNSUPPORTED_MEDIA_TYPE": "unsupported-media-type",
    "INTERNAL_ERROR": "internal-error",
    "CATALOG_SOURCE_UNAVAILABLE": "catalog-source-unavailable",
}


def test_handwritten_base_matches_generated_base():
    assert problem_types.TYPE_BASE == GENERATED_PROBLEM_TYPE_BASE


def test_all_canonical_slug_constants_have_the_documented_values():
    for const_name, expected in EXPECTED_SLUGS.items():
        assert getattr(KnownProblemSlugs, const_name) == expected


def test_no_canonical_slug_is_missing_from_the_generated_registry():
    for const_name in EXPECTED_SLUGS:
        assert hasattr(KnownProblemSlugs, const_name), f"missing slug constant: {const_name}"


def test_the_validation_codes_are_generated_with_their_documented_values():
    assert KnownValidationCodes.PATTERN == "pattern"
    assert KnownValidationCodes.STENCIL_RECURSION == "stencil-recursion"
    assert KnownValidationCodes.CATALOG_RESOURCES_NOT_READY == "catalog-resources-not-ready"
    assert len(KnownValidationCodes.ALL) == 51
    assert len(set(KnownValidationCodes.ALL)) == len(KnownValidationCodes.ALL)
