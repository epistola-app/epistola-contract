#!/usr/bin/env bash
#
# Compatibility matrix — aggregate step (the matrix's home).
#
# The contract is the anchor every artifact speaks, so the matrix is judged and
# published HERE, neutrally: the suite and external plugins are equal peers,
# each contributing the same kind of feed. Verification (booting a published
# suite image and watching it serve) stays with the suite — this step only
# reads declarations and applies the rules.
#
# Inputs:
#   - CLIENT feeds (role "client", e.g. valtimo-epistola-plugin): the contract
#     version they target and, optionally, the `operations` they call;
#   - SERVER feeds (role "server", e.g. epistola-suite): the contract version
#     they implement (`contractVersion`);
#   - this repo's compatibility-log.json: per released contract version,
#     whether it broke wire compatibility and which operations it broke.
#
# For every (client, server) pair the verdict is:
#   1. client target above the server's contract → incompatible (a newer
#      client may call operations the server does not have yet);
#   2. OPERATION-LEVEL, when the client declares operations and the log fully
#      covers the window (target .. serverContract] with computed entries:
#      incompatible iff a breaking release in the window touches an operation
#      the client uses;
#   3. otherwise the RANGE rule: floor <= target <= serverContract, where the
#      floor is derived from the log (the newest breaking release at or below
#      the server's contract version).
# Every row records which rule judged it (`basis`).
#
# Feed sources are local paths or http(s) URLs, fetched BEST-EFFORT (a repo
# that has not merged its feed yet 404s → warned and skipped, never fatal).
# The log is THIS repo's own file, so unlike the feeds it is required.
#
# The output is DETERMINISTIC (no timestamps): re-running over the same inputs
# yields the same bytes, so the scheduled workflow only commits real changes.
#
# Usage:
#   compatibility/aggregate.sh                          # feeds.txt → aggregate.json
#   compatibility/aggregate.sh --feed ./some-feed.json --out -
#
# Inputs (flags override env):
#   --matrix-log LOG   compatibility log (default: ../compatibility-log.json)
#   --feed       FEED  a feed (local path or http[s] URL); repeatable
#   --feeds-file FILE  feed sources, one per line (default: compatibility/feeds.txt)
#   --out        OUT   aggregate JSON to write, or `-` (default: compatibility/aggregate.json)
#
# Requires: jq (and curl when any source is a URL).

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
LOG_FILE="${LOG_FILE:-${SCRIPT_DIR}/../compatibility-log.json}"
FEEDS_FILE="${FEEDS_FILE:-${SCRIPT_DIR}/feeds.txt}"
OUT="${OUT:-${SCRIPT_DIR}/aggregate.json}"
FEEDS=()          # explicit --feed sources (missing/malformed local path = error)

while [[ $# -gt 0 ]]; do
  case "$1" in
    --matrix-log) LOG_FILE="$2";   shift 2 ;;
    --feed)       FEEDS+=("$2");   shift 2 ;;
    --feeds-file) FEEDS_FILE="$2"; shift 2 ;;
    --out)        OUT="$2";        shift 2 ;;
    -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

command -v jq >/dev/null 2>&1 || { echo "missing required tool: jq" >&2; exit 3; }
[[ -f "${LOG_FILE}" ]] || { echo "compatibility log not found: ${LOG_FILE} (run scripts/generate-compatibility-log.sh)" >&2; exit 2; }
jq -e '(.schemaVersion == 1) and (.entries | type == "array") and (.entries | length > 0)' "${LOG_FILE}" >/dev/null 2>&1 \
  || { echo "not a valid v1 compatibility log: ${LOG_FILE}" >&2; exit 2; }

log() { echo "[aggregate] $*" >&2; }

tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

# A usable feed: schemaVersion 1, a string artifact, and per role the fields the
# join needs. Anything else is skipped (lenient sources) or an error (--feed),
# never passed into the join.
feed_valid() {
  jq -e '(.schemaVersion == 1)
         and (.artifact | type == "string")
         and (
           ((.role // "client") == "client"
             and (.targetContractVersion | type == "string")
             and ((.operations == null) or ((.operations | type == "array") and all(.operations[]?; type == "string"))))
           or (.role == "server" and (.contractVersion | type == "string"))
         )' "$1" >/dev/null 2>&1
}

# --- resolve feed sources to local JSON files (best effort) --------------------
SOURCES=()
if [[ "${#FEEDS[@]}" -gt 0 ]]; then SOURCES=("${FEEDS[@]}"); fi
STRICT_COUNT="${#SOURCES[@]}"   # first N sources are strict (missing/malformed local = error)
if [[ -f "${FEEDS_FILE}" ]]; then
  while IFS= read -r line; do
    line="${line%%#*}"; line="${line#"${line%%[![:space:]]*}"}"; line="${line%"${line##*[![:space:]]}"}"
    [[ -n "${line}" ]] && SOURCES+=("${line}")
  done < "${FEEDS_FILE}"
fi
[[ "${#SOURCES[@]}" -ge 1 ]] || { echo "no feed source given (use --feed, or list sources in ${FEEDS_FILE})" >&2; exit 2; }

resolved=()
i=0
for src in "${SOURCES[@]}"; do
  strict=$(( i < STRICT_COUNT )); i=$(( i + 1 ))
  dest="${tmp}/feed-${i}.json"
  if [[ "${src}" =~ ^https?:// ]]; then
    command -v curl >/dev/null 2>&1 || { echo "curl required to fetch URL feeds" >&2; exit 3; }
    if ! curl -fsSL --max-time 30 "${src}" -o "${dest}" 2>/dev/null; then
      log "WARN: could not fetch ${src} — skipping (feed absent from the matrix)"
    elif ! feed_valid "${dest}"; then
      log "WARN: ${src} is not a valid v1 feed — skipping (feed absent from the matrix)"
    else
      resolved+=("${dest}"); log "fetched ${src}"
    fi
  elif [[ -f "${src}" ]]; then
    if feed_valid "${src}"; then
      resolved+=("${src}")
    elif [[ "${strict}" -eq 1 ]]; then
      echo "feed is not a valid v1 feed: ${src}" >&2; exit 2
    else
      log "WARN: ${src} is not a valid v1 feed — skipping"
    fi
  elif [[ "${strict}" -eq 1 ]]; then
    echo "feed not found: ${src}" >&2; exit 2
  else
    log "WARN: feed not found: ${src} — skipping"
  fi
done

if [[ "${#resolved[@]}" -ge 1 ]]; then
  feeds_json="$(jq -s '.' "${resolved[@]}")"
else
  log "no feeds resolved — writing an empty aggregate"
  feeds_json="[]"
fi
out_tmp="${tmp}/out.json"

# Version comparison: strip a build/pre-release qualifier (from the first `-`),
# split into numeric parts padded to 3, compare arrays numerically.
jq -n \
  --argjson feeds "${feeds_json}" \
  --slurpfile compatLog "${LOG_FILE}" '
  def parts: (sub("-.*$"; "") | split(".") | map(tonumber? // 0)) + [0,0,0] | .[0:3];
  def le($a; $b): ($a | parts) <= ($b | parts);
  def lt($a; $b): ($a | parts) < ($b | parts);
  def inRange($mn; $v; $mx): le($mn; $v) and le($v; $mx);

  (($compatLog[0].entries) | sort_by(.version | parts)) as $entries
  | ($feeds | map(select((.role // "client") == "client"))) as $clients
  | ($feeds | map(select(.role == "server"))) as $servers
  | {
      schemaVersion: 1,
      anchor: "epistola-contract",
      rule: "no breaking change in (target .. serverContract] touches a used operation; else floor <= target <= serverContract",
      rows: [
        $clients[] as $f
        | $servers[] as $s
        | ($s.contractVersion) as $max
        | ($f.targetContractVersion) as $target
        | ($f.operations) as $ops
        # Floor derived from the log: the newest breaking release at or below
        # the server contract; the oldest known release if nothing broke.
        | ([$entries[] | select(.breaking == true and le(.version; $max))]
           | if length > 0 then .[-1].version else $entries[0].version end) as $floor
        | ([$entries[] | select(lt($target; .version) and le(.version; $max))]) as $window
        | (
            ($ops != null)
            and le($entries[0].version; $target)
            and le($max; $entries[-1].version)
            and ($window | all(.computed != false))
          ) as $opCapable
        | ([$window[]
            | select(.breaking == true)
            | {version, ops: [.brokenOperations[]? | select(. as $o | ($ops // []) | index($o) != null)]}
            | select(.ops | length > 0)
           ]) as $hits
        | (
            if (le($target; $max) | not) then
              {compatible: false, basis: "range",
               reason: "target \($target) above server contract \($max)"}
            elif $opCapable then
              (if ($hits | length) == 0 then
                {compatible: true, basis: "operations",
                 reason: "no breaking change in (\($target) .. \($max)] touches an operation it uses"}
              else
                {compatible: false, basis: "operations",
                 reason: ("breaks operation(s) it uses: " + ($hits | map("\(.version) breaks \(.ops | join(", "))") | join("; ")))}
              end)
            elif inRange($floor; $target; $max) then
              {compatible: true, basis: "range", reason: "target \($target) within [\($floor) .. \($max)]"}
            else
              {compatible: false, basis: "range", reason: "target \($target) below floor \($floor)"}
            end
          ) as $verdict
        | {
            client: $f.artifact,
            clientVersion: $f.version,
            targetContract: $target,
            operations: $ops,
            server: $s.artifact,
            serverVersion: $s.version,
            serverContract: $max,
            floor: $floor,
            compatible: $verdict.compatible,
            basis: $verdict.basis,
            reason: $verdict.reason
          }
      ]
      | sort_by(.client, .clientVersion, .server, .serverVersion)
    }
  ' > "${out_tmp}"

if [[ "${OUT}" == "-" ]]; then
  cat "${out_tmp}"
else
  mkdir -p "$(dirname -- "${OUT}")"
  cp "${out_tmp}" "${OUT}"
  rows="$(jq '.rows | length' "${OUT}")"
  compat="$(jq '[.rows[] | select(.compatible)] | length' "${OUT}")"
  log "wrote ${OUT} (${rows} row(s), ${compat} compatible)"
fi
