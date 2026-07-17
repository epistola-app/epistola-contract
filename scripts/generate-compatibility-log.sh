#!/usr/bin/env bash
# Regenerates compatibility-log.json — the machine-computed record of which
# released contract versions broke wire compatibility, and exactly which
# operations they broke.
#
# For every consecutive pair of release tags (v0.9.0 → v0.10.0, …) the spec at
# each tag is bundled (redocly) and compared (oasdiff breaking, same flags as
# `make breaking`). The result is one entry per released version:
#
#   { "version": "0.10.0", "breaking": true,
#     "brokenOperations": ["listConsumers", "listDocuments", ...] }
#
# The log is the anchor-side feed of the version-compatibility matrix: consumers
# join it with a client's declared operations to answer "does any breaking
# change between the client's contract version and the server's actually touch
# an operation this client uses?" — an operation-level verdict instead of the
# coarse floor-only rule. The x-min-compatible-version floor in the spec stays
# (it feeds the runtime /ping range) and is cross-checked against this log by
# scripts/check-compatibility-floor.sh.
#
# The output is DETERMINISTIC (no timestamps): regenerating over the same tags
# yields the same bytes, so CI can verify freshness by regenerate-and-diff.
#
# A tag whose spec cannot be bundled or compared (very old layouts) is recorded
# with "computed": false — consumers must treat versions in such a window as
# not judgeable at the operation level (fall back to the floor rule).
#
# Usage:
#   scripts/generate-compatibility-log.sh                # writes compatibility-log.json
#   scripts/generate-compatibility-log.sh --out -        # print to stdout
#
# Requires: git, jq, oasdiff (mise-pinned), and the pinned redocly
# (tools/node_modules — installed automatically when missing).

set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${REPO_ROOT}/compatibility-log.json"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --out) OUT="$2"; shift 2 ;;
    -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

for tool in git jq oasdiff; do
  command -v "${tool}" >/dev/null 2>&1 || { echo "missing required tool: ${tool}" >&2; exit 3; }
done

REDOCLY="${REPO_ROOT}/tools/node_modules/.bin/redocly"
if [[ ! -x "${REDOCLY}" ]]; then
  echo "==> Installing pinned tools (redocly)..." >&2
  pnpm -C "${REPO_ROOT}/tools" install --frozen-lockfile >&2
fi
[[ -x "${REDOCLY}" ]] || { echo "pinned redocly not found at ${REDOCLY}" >&2; exit 3; }

tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT
log() { echo "[compat-log] $*" >&2; }

# Release tags, oldest first. Only plain vX.Y.Z tags are releases.
mapfile -t TAGS < <(git -C "${REPO_ROOT}" tag -l 'v*' | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sort -V)
[[ "${#TAGS[@]}" -ge 1 ]] || { echo "no release tags found" >&2; exit 2; }

# bundle_tag TAG → prints the bundled spec path, or nothing if not bundleable.
bundle_tag() {
  local tag="$1" dir="${tmp}/src-$1" out="${tmp}/bundle-$1.yaml"
  [[ -f "${out}" ]] && { printf '%s' "${out}"; return 0; }
  mkdir -p "${dir}"
  if ! git -C "${REPO_ROOT}" archive "${tag}" -- epistola-api.yaml spec/ 2>/dev/null | tar -x -C "${dir}" 2>/dev/null; then
    git -C "${REPO_ROOT}" archive "${tag}" -- epistola-api.yaml 2>/dev/null | tar -x -C "${dir}" 2>/dev/null || return 1
  fi
  [[ -f "${dir}/epistola-api.yaml" ]] || return 1
  "${REDOCLY}" bundle "${dir}/epistola-api.yaml" -o "${out}" >/dev/null 2>&1 || return 1
  printf '%s' "${out}"
}

entries="${tmp}/entries.jsonl"
: > "${entries}"

prev_tag=""
prev_bundle=""
for tag in "${TAGS[@]}"; do
  version="${tag#v}"
  bundle="$(bundle_tag "${tag}" || true)"

  if [[ -z "${bundle}" ]]; then
    log "${tag}: spec not bundleable — recording computed=false"
    jq -cn --arg v "${version}" \
      '{version: $v, computed: false, note: "spec at this tag could not be bundled for comparison"}' >> "${entries}"
    prev_tag="${tag}"; prev_bundle=""
    continue
  fi

  if [[ -z "${prev_tag}" ]]; then
    jq -cn --arg v "${version}" \
      '{version: $v, breaking: false, brokenOperations: [], baseline: true}' >> "${entries}"
  elif [[ -z "${prev_bundle}" ]]; then
    log "${tag}: previous tag ${prev_tag} not comparable — recording computed=false"
    jq -cn --arg v "${version}" --arg p "${prev_tag}" \
      '{version: $v, computed: false, note: ("previous release " + $p + " could not be bundled for comparison")}' >> "${entries}"
  else
    diff_json="${tmp}/diff-${tag}.json"
    if ! oasdiff breaking --flatten-allof --flatten-params --format json \
        "${prev_bundle}" "${bundle}" > "${diff_json}" 2>/dev/null; then
      log "${tag}: oasdiff failed — recording computed=false"
      jq -cn --arg v "${version}" \
        '{version: $v, computed: false, note: "oasdiff could not compare this release with the previous one"}' >> "${entries}"
      prev_tag="${tag}"; prev_bundle="${bundle}"
      continue
    fi
    jq -c --arg v "${version}" '
      ([.[]? | .operationId // "\(.operation) \(.path)"] | unique) as $ops
      | {version: $v, breaking: ($ops | length > 0), brokenOperations: $ops}
    ' "${diff_json}" >> "${entries}"
    count="$(jq -r '.[-1:] | .[0].brokenOperations | length' <(jq -s '.' "${entries}"))"
    log "${tag}: ${count} broken operation(s)"
  fi

  prev_tag="${tag}"
  prev_bundle="${bundle}"
done

out_tmp="${tmp}/log.json"
jq -s '{
  schemaVersion: 1,
  anchor: "epistola-contract",
  generatedBy: "scripts/generate-compatibility-log.sh",
  entries: .
}' "${entries}" > "${out_tmp}"

if [[ "${OUT}" == "-" ]]; then
  cat "${out_tmp}"
else
  cp "${out_tmp}" "${OUT}"
  log "wrote ${OUT} ($(jq '.entries | length' "${OUT}") release(s), $(jq '[.entries[] | select(.breaking == true)] | length' "${OUT}") breaking)"
fi
