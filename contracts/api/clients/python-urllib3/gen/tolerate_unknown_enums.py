# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""Make the generated models read an enum value this client does not know, instead of failing.

A minor version of the contract may add an enum value, so a newer 2.x server can send one this
client was not generated with. The generated code refuses it in two places, and with it the whole
response:

- a named enum (``class GenerationJobStatus(str, Enum)``) raises ``ValueError`` from its
  constructor. It gains a ``_missing_`` hook that returns a pseudo-member holding the value as
  received: it equals that string and no known member, and ``.value`` gives the value back.
- an enum declared inline on a property is a ``str`` with a ``*_validate_enum`` field validator
  that raises. The raise is removed, so the property keeps the value as received.

Writing is unchanged. The server validates every request, so a value the server does not know is
still refused there, with 400.

Usage: tolerate_unknown_enums.py <generated models directory>
"""

import re
import sys
from pathlib import Path

ENUM_CLASS = re.compile(r"^class (\w+)\(str, Enum\):\n", re.MULTILINE)
FROM_JSON = "    @classmethod\n    def from_json(cls, json_str: str) -> Self:\n"
MISSING_HOOK = '''    @classmethod
    def _missing_(cls, value):
        """A value this client does not know, from a newer server: kept as received."""
        if not isinstance(value, str):
            return None
        member = str.__new__(cls, value)
        member._name_ = "UNKNOWN"
        member._value_ = value
        return member

'''
INLINE_RAISE = re.compile(
    r"(    def \w+_validate_enum\(cls, value\):\n"
    r'        """Validates the enum"""\n'
    r"(?:        if value is None:\n            return value\n\n)?)"
    r"        if value not in set\(\[[^\]]*\]\):\n"
    r'            raise ValueError\("must be one of enum values \([^)]*\)"\)\n'
)


def main(models_dir: Path) -> None:
    enums = validators = 0
    for path in sorted(models_dir.glob("*.py")):
        source = path.read_text()
        updated = source

        if ENUM_CLASS.search(updated):
            if FROM_JSON not in updated:
                sys.exit(f"{path.name}: enum class without the expected from_json; update {Path(__file__).name}")
            updated = updated.replace(FROM_JSON, MISSING_HOOK + FROM_JSON, 1)
            enums += 1

        updated, count = INLINE_RAISE.subn(r"\1", updated)
        validators += count
        if "raise ValueError(\"must be one of enum values" in updated:
            sys.exit(f"{path.name}: an inline enum validator did not match; update {Path(__file__).name}")

        if updated != source:
            path.write_text(updated)

    if enums == 0 or validators == 0:
        sys.exit(f"matched {enums} enum classes and {validators} inline validators: the generated code changed shape")
    print(f"Taught {enums} enum class(es) and {validators} inline enum validator(s) to keep an unknown value")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
