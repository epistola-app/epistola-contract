#!/usr/bin/env bash
# Single source of truth for parsing the spec version out of openapi.yaml.
#
# Usage:
#   contracts/api/scripts/spec-version.sh [--full|--api|--env]
#     --full  print the full version, e.g. 0.9.0 (default)
#     --api   print the major.minor API version, e.g. 0.9
#     --env   print eval-able assignments: SPEC_VERSION=... and API_VERSION=...
#
# The spec file can be overridden with SPEC_FILE=path.
set -euo pipefail

SPEC_FILE="${SPEC_FILE:-$(cd "$(dirname "$0")/.." && pwd)/openapi.yaml}"

SPEC_VERSION=$(grep -E '^[[:space:]]*version:' "$SPEC_FILE" | head -1 \
  | sed -E 's/.*version:[[:space:]]*["'"'"']?([0-9]+\.[0-9]+\.[0-9]+)["'"'"']?.*/\1/')

if ! echo "$SPEC_VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
  echo "error: could not parse info.version from $SPEC_FILE (got: '$SPEC_VERSION')" >&2
  exit 1
fi

API_VERSION="${SPEC_VERSION%.*}"

case "${1:---full}" in
  --full) echo "$SPEC_VERSION" ;;
  --api)  echo "$API_VERSION" ;;
  --env)
    echo "SPEC_VERSION=$SPEC_VERSION"
    echo "API_VERSION=$API_VERSION"
    ;;
  *)
    echo "usage: $0 [--full|--api|--env]" >&2
    exit 2
    ;;
esac
