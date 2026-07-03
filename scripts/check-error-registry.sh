#!/usr/bin/env bash
# Fails when the human-facing problem-type registry (docs/error-types.md) and
# the machine-readable one (x-problem-types in epistola-api.yaml) disagree.
# The Kotlin modules are guarded separately: the client generates its constants
# from the spec, and the server has a unit test comparing its constants to it.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SPEC="$ROOT/epistola-api.yaml"
DOCS="$ROOT/docs/error-types.md"

# "slug status" pairs from the spec's x-problem-types block (ends at the next
# top-level key).
spec_pairs=$(awk '
  /^x-problem-types:/ { in_block = 1; next }
  in_block && /^[a-zA-Z]/ { in_block = 0 }
  in_block && /- slug:/ { slug = $NF }
  in_block && /status:/ { print slug, $NF }
' "$SPEC" | sort)

# "slug status" pairs from the markdown table rows in the canonical registry
# (rows whose first cell is an https://epistola.app/errors/... type URI).
docs_pairs=$(awk -F'|' '
  $2 ~ /https:\/\/epistola\.app\/errors\// {
    gsub(/[` ]/, "", $3); gsub(/[` ]/, "", $4)
    print $3, $4
  }
' "$DOCS" | sort)

if [ -z "$spec_pairs" ]; then
  echo "error: no x-problem-types entries found in $SPEC" >&2
  exit 1
fi
if [ -z "$docs_pairs" ]; then
  echo "error: no registry rows found in $DOCS" >&2
  exit 1
fi

if [ "$spec_pairs" != "$docs_pairs" ]; then
  echo "error: problem-type registries disagree (slug status):" >&2
  echo "--- epistola-api.yaml x-problem-types" >&2
  echo "+++ docs/error-types.md" >&2
  diff <(echo "$spec_pairs") <(echo "$docs_pairs") >&2 || true
  exit 1
fi

echo "error registry OK: $(echo "$spec_pairs" | wc -l | tr -d ' ') problem types in sync"
