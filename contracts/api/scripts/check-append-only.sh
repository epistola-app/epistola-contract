#!/usr/bin/env bash
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

# Checks that the spec adds members and parameters only after the existing ones, compared with the
# latest release of the same major version (see check-append-only.mjs for why). A major version may
# reorder them, so before the first release of a major there is nothing to compare with.
#
# Usage: contracts/api/scripts/check-append-only.sh   (from anywhere; needs the release tags)
#   BASE_REF=<git ref> overrides the release compared with.
set -euo pipefail

API_DIR="$(cd "$(dirname "$0")/.." && pwd)"
REPO_ROOT="$(cd "$API_DIR/../.." && pwd)"
REDOCLY="$API_DIR/tools/node_modules/.bin/redocly"
cd "$REPO_ROOT"

MAJOR="$("$API_DIR/scripts/spec-version.sh" --full | cut -d. -f1)"
if [[ -z "${BASE_REF:-}" ]]; then
  # Releases only: a pre-release of the major's first version precedes the compatibility promise.
  BASE_REF="$(git tag -l "v${MAJOR}.*" --sort=-v:refname | grep -Ev -- '-' | head -1 || true)"
fi
if [[ -z "$BASE_REF" ]]; then
  echo "append-only: no release of ${MAJOR}.x yet, nothing to compare with"
  exit 0
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

git archive "$BASE_REF" -- contracts/api/openapi.yaml contracts/api/paths contracts/api/components \
  contracts/api/scripts/normalize-bundle.mjs contracts/catalog/schemas | tar -xC "$WORK"
# Each side bundled the way the generators see it: bundled, normalized, then read as JSON.
quietly() {
  local log="$WORK/step.log"
  if ! "$@" >"$log" 2>&1; then
    cat "$log" >&2
    return 1
  fi
}
bundle() {
  local root="$1" out="$2"
  quietly "$REDOCLY" bundle "$root/contracts/api/openapi.yaml" -o "$WORK/$out.yaml"
  if [[ -f "$root/contracts/api/scripts/normalize-bundle.mjs" ]]; then
    quietly node "$root/contracts/api/scripts/normalize-bundle.mjs" "$WORK/$out.yaml"
  fi
  quietly "$REDOCLY" bundle "$WORK/$out.yaml" --ext json -o "$WORK/$out.json"
}
bundle "$WORK" base
bundle "$REPO_ROOT" head

echo "==> Checking member and parameter order against $BASE_REF"
node "$API_DIR/scripts/check-append-only.mjs" "$WORK/base.json" "$WORK/head.json"
