# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""NDJSON generation-result collection with adaptive polling and partition routing."""

from epistola_client.collect.result_collector import (
    CollectResult,
    GenerationResult,
    MetricsListener,
    PartitionAssignment,
    ResultCollector,
)

__all__ = [
    "CollectResult",
    "GenerationResult",
    "MetricsListener",
    "PartitionAssignment",
    "ResultCollector",
]
