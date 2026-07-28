# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""RFC 9457 problem-detail error handling for the Epistola client."""

from epistola_client.error.problem_detail_exception import ProblemDetailException
from epistola_client.error.problem_detail_handler import parse_problem
from epistola_client.error.problem_types import BLANK_TYPE, TYPE_BASE, slug_for

__all__ = [
    "ProblemDetailException",
    "parse_problem",
    "BLANK_TYPE",
    "TYPE_BASE",
    "slug_for",
]
