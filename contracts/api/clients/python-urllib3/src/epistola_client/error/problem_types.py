# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""Constants and helpers describing the Epistola RFC 9457 problem ``type`` URI scheme.

Intentionally duplicated from the server module's problem-type base: the client is
published as a standalone package and must not depend on the server. Keep the two in
sync — the ``ProblemRegistryTest`` guard asserts :data:`TYPE_BASE` equals the value the
build-time generator wrote to ``GENERATED_PROBLEM_TYPE_BASE`` from the spec's
``x-problem-types``.

The machine-readable discriminator is the problem ``type`` URI — there is no separate
``code`` member. Application-level errors use a ``https://epistola.app/errors/{slug}``
type; framework errors keep RFC 9457's default ``about:blank``.
"""

from __future__ import annotations

from typing import Optional

#: Base URI for Epistola problem ``type`` values, e.g. ``https://epistola.app/errors/not-found``.
TYPE_BASE = "https://epistola.app/errors/"

#: The RFC 9457 default problem type, used when no specific type is supplied.
BLANK_TYPE = "about:blank"


def slug_for(problem_type: Optional[str]) -> Optional[str]:
    """Extract the kebab-case slug from an Epistola problem ``type`` URI (the part
    after :data:`TYPE_BASE`), or ``None`` when ``problem_type`` is ``about:blank``,
    empty, or any non-Epistola URI.
    """
    if not problem_type:
        return None
    if not problem_type.startswith(TYPE_BASE):
        return None
    slug = problem_type[len(TYPE_BASE):]
    return slug or None
