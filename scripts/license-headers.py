#!/usr/bin/env python3
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""Apply or check SPDX headers for first-party commentable files."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

COPYRIGHT_TAG = "SPDX-" + "FileCopyrightText:"
LICENSE_TAG = "SPDX-" + "License-Identifier:"
COPYRIGHT = f"{COPYRIGHT_TAG} Epistola Nederland B.V."
LICENSE = f"{LICENSE_TAG} EUPL-1.2"
MARKERS = (COPYRIGHT_TAG, LICENSE_TAG)

LINE_COMMENT_EXTENSIONS = {
    ".cs": "//",
    ".js": "//",
    ".kt": "//",
    ".kts": "//",
    ".mjs": "//",
    ".py": "#",
    ".sh": "#",
    ".ts": "//",
}

SPECIAL_LINE_COMMENT_FILES = {
    "contracts/api/mock-server/Dockerfile": "#",
}

SKIP_PREFIXES = (
    ".agents/",
    ".claude/",
    ".codex/",
    ".github/",
    ".husky/",
    "LICENSES/",
)

SKIP_FILES = {
    ".editorconfig",
    ".gitattributes",
    ".gitignore",
    "LICENSE",
    "Makefile",
}

SKIP_NAMES = {
    ".editorconfig",
    ".gitignore",
    ".nojekyll",
    ".openapi-generator-ignore",
    "gradlew",
    "gradlew.bat",
    "openapi-generator-ignore",
}

SKIP_EXTENSIONS = {
    ".bat",
    ".csproj",
    ".html",
    ".jar",
    ".json",
    ".md",
    ".properties",
    ".props",
    ".sln",
    ".toml",
    ".xml",
    ".yaml",
    ".yml",
}


def git_files() -> list[Path]:
    output = subprocess.check_output(["git", "ls-files", "-z"])
    return [Path(item.decode()) for item in output.split(b"\0") if item]


def is_skipped(path: Path) -> bool:
    name = path.as_posix()
    return (
        name in SKIP_FILES
        or path.name in SKIP_NAMES
        or any(name.startswith(prefix) for prefix in SKIP_PREFIXES)
        or path.suffix in SKIP_EXTENSIONS
    )


def line_header(prefix: str) -> str:
    return f"{prefix} {COPYRIGHT}\n{prefix}\n{prefix} {LICENSE}\n\n"


def header_for(path: Path) -> str | None:
    name = path.as_posix()
    if name in SPECIAL_LINE_COMMENT_FILES:
        return line_header(SPECIAL_LINE_COMMENT_FILES[name])
    if path.suffix in LINE_COMMENT_EXTENSIONS:
        return line_header(LINE_COMMENT_EXTENSIONS[path.suffix])
    return None


def insertion_offset(text: str) -> int:
    if text.startswith("#!"):
        first_newline = text.find("\n")
        if first_newline != -1:
            return first_newline + 1
    return 0


def has_spdx_header(text: str) -> bool:
    head = "\n".join(text.splitlines()[:12])
    return all(marker in head for marker in MARKERS)


def process_file(path: Path, check: bool) -> bool:
    if is_skipped(path):
        return True

    header = header_for(path)
    if header is None:
        print(f"unsupported comment style: {path}", file=sys.stderr)
        return False

    text = path.read_text()
    if has_spdx_header(text):
        return True

    if check:
        print(f"missing SPDX header: {path}", file=sys.stderr)
        return False

    offset = insertion_offset(text)
    path.write_text(text[:offset] + header + text[offset:])
    return True


def main() -> int:
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true", help="fail if a header is missing")
    mode.add_argument("--fix", action="store_true", help="insert missing headers")
    args = parser.parse_args()

    results = [process_file(path, check=args.check) for path in git_files()]
    ok = all(results)
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
