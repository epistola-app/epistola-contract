#!/usr/bin/env bash
#
# Compatibility matrix — render aggregate.json to a human-readable Markdown table.
#
# aggregate.json (produced by ./aggregate.sh) is the source of truth; this
# renders a view of it — the table is never hand-edited. Deterministic (no
# timestamps): the git history of MATRIX.md is its change log.
#
# Usage:
#   compatibility/render.sh                       # aggregate.json → MATRIX.md
#   compatibility/render.sh --out -               # print to stdout
#
# Inputs (flags override env):
#   --in   IN   aggregate JSON to read  (default: compatibility/aggregate.json)
#   --out  OUT  Markdown file to write, or `-` (default: compatibility/MATRIX.md)
#
# Requires: jq.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
IN="${IN:-${SCRIPT_DIR}/aggregate.json}"
OUT="${OUT:-${SCRIPT_DIR}/MATRIX.md}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --in)  IN="$2";  shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

command -v jq >/dev/null 2>&1 || { echo "missing required tool: jq" >&2; exit 3; }
[[ -f "${IN}" ]] || { echo "aggregate not found: ${IN} (run compatibility/aggregate.sh)" >&2; exit 2; }

markdown="$(jq -r '
  "# Epistola compatibility matrix",
  "",
  "<!-- Generated from `compatibility/aggregate.json` by `compatibility/render.sh`. Do not edit by hand. -->",
  "",
  "Anchor: **epistola-contract** (the wire contract every artifact speaks). Each artifact publishes its own `compatibility.json` feed; this matrix joins those feeds with [`compatibility-log.json`](../compatibility-log.json) (which contract releases broke which operations).",
  "",
  "A pairing is judged **operation-level** when possible — incompatible only if a breaking contract release between the client'"'"'s target and the server'"'"'s contract touches an operation the client uses — and falls back to the **range** rule (`floor <= target <= serverContract`) otherwise. The _Judged by_ column says which rule decided each row.",
  "",
  (
    if (.rows | length) == 0 then
      "_No rows: no feeds could be read. See `compatibility/feeds.txt`._"
    else
      (
        "| Client | Target contract | Server | Server contract | Judged by | Compatible |",
        "| --- | --- | --- | --- | --- | --- |"
      ),
      ( .rows[]
        | "| \(.client) `\(.clientVersion // "?")` | `\(.targetContract)` | \(.server) `\(.serverVersion // "?")` | `\(.serverContract)` (floor `\(.floor)`) | \(.basis) | \(if .compatible then "✅ yes" else "❌ no" end) — \(.reason) |"
      )
    end
  ),
  "",
  "### Legend",
  "",
  "- **Judged by** — `operations`: verdict from the breaking-change log joined with the client'"'"'s declared operations. `range`: the coarse fallback rule (the client declared no operations, or the log does not cover the whole version window).",
  "- **Floor** — the newest breaking contract release at or below the server'"'"'s contract version, derived from the log (never hand-maintained here).",
  "- Verification that a published suite image really serves what it declares lives in `epistola-suite` (`compatibility/smoke.sh`); this matrix is the judged view over declarations.",
  "" ' "${IN}")"

if [[ "${OUT}" == "-" ]]; then
  printf '%s\n' "${markdown}"
else
  mkdir -p "$(dirname -- "${OUT}")"
  printf '%s\n' "${markdown}" > "${OUT}"
  echo "[render] wrote ${OUT} ($(jq '.rows | length' "${IN}") row(s))" >&2
fi
