# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""Epistola Python client.

Re-exports the stock generated client (API classes, models, exceptions) from
``epistola_client_generated`` alongside the hand-written Epistola glue: the client
builder, identity headers, self-signed JWT auth, RFC 9457 problem-detail handling,
NDJSON result collection, and client-side schema validation.

Typical use::

    from epistola_client import (
        EpistolaClientBuilder, ClientIdentity, JwtSigner,
        TemplatesApi, ProblemDetailException, KnownProblemSlugs,
    )

    http = (
        EpistolaClientBuilder()
        .base_url("https://epistola.example.com/api")
        .identity(ClientIdentity.builder().node_id("my-pod").build())
        .jwt_signer(JwtSigner.builder().consumer_id("svc").private_key(key).build())
        # or .api_key("epk_...") for Authorization: ApiKey <key>
        .install_problem_detail_handler()
        .build()
    )
    templates = TemplatesApi(http)
"""

from __future__ import annotations

# Re-export the full stock generated surface (API classes, models, exceptions,
# ApiClient, Configuration, ApiResponse) so consumers import everything from
# `epistola_client`.
from epistola_client_generated import *  # noqa: F401,F403
from epistola_client_generated import __all__ as _generated_all

# Derived sources.
from epistola_client._generated import CONTRACT_VERSION, KnownProblemSlugs
from epistola_client._generated import validate as validate_model

# Hand-written glue.
from epistola_client.auth import JwtSigner
from epistola_client.collect import (
    CollectResult,
    GenerationResult,
    MetricsListener,
    PartitionAssignment,
    ResultCollector,
)
from epistola_client.error import (
    BLANK_TYPE,
    TYPE_BASE,
    ProblemDetailException,
    parse_problem,
    slug_for,
)
from epistola_client.http import EpistolaApiClient, EpistolaClientBuilder
from epistola_client.identity import ClientIdentity
from epistola_client.validation import (
    SchemaCache,
    TemplateDataValidationError,
    TemplateSchemaValidator,
    TtlSchemaCache,
    ValidatingGenerationApi,
    ValidationFailure,
)

__version__ = CONTRACT_VERSION

_epistola_exports = [
    "CONTRACT_VERSION",
    "__version__",
    "KnownProblemSlugs",
    "validate_model",
    "JwtSigner",
    "ClientIdentity",
    "EpistolaApiClient",
    "EpistolaClientBuilder",
    "ProblemDetailException",
    "parse_problem",
    "TYPE_BASE",
    "BLANK_TYPE",
    "slug_for",
    "ResultCollector",
    "GenerationResult",
    "CollectResult",
    "PartitionAssignment",
    "MetricsListener",
    "TemplateSchemaValidator",
    "ValidatingGenerationApi",
    "TemplateDataValidationError",
    "ValidationFailure",
    "SchemaCache",
    "TtlSchemaCache",
]

__all__ = list(_generated_all) + _epistola_exports
