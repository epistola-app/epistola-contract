#!/usr/bin/env bash
# Guards the compatibility floor (info.x-min-compatible-version) — the one
# hand-maintained value in the version-compatibility system. A forgotten floor
# bump on a breaking release would make every downstream compatibility verdict
# silently wrong (false "compatible"), so this check gates it in CI.
#
# The invariant is the floor's staircase shape:
#   - the working spec has BREAKING changes vs the last release
#       → info.version must be bumped past the last release, and the floor
#         must be raised to exactly that new version (a breaking release is
#         compatible only with itself);
#   - the working spec has NO breaking changes vs the last release
#       → the floor must be unchanged from the last release (sticky). Raising
#         it without a break would falsely exclude working clients.
#
# Also checks compatibility-log.json freshness: its newest entry must be the
# newest release tag (regenerate with scripts/generate-compatibility-log.sh
# after a release).
#
# Breaking changes themselves are NOT blocked — this project ships intentional
# breaks under SemVer 0.x. Only an INCONSISTENT spec (break without the version
# + floor moving together) fails.
#
# Usage: scripts/check-compatibility-floor.sh
# Requires: git, jq, oasdiff (mise-pinned), pinned redocly (auto-installed).

set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
SPEC="${REPO_ROOT}/epistola-api.yaml"
LOG_FILE="${REPO_ROOT}/compatibility-log.json"

for tool in git jq oasdiff; do
  command -v "${tool}" >/dev/null 2>&1 || { echo "missing required tool: ${tool}" >&2; exit 3; }
done

REDOCLY="${REPO_ROOT}/tools/node_modules/.bin/redocly"
if [[ ! -x "${REDOCLY}" ]]; then
  echo "==> Installing pinned tools (redocly)..." >&2
  pnpm -C "${REPO_ROOT}/tools" install --frozen-lockfile >&2
fi

fail() { echo "FLOOR CHECK FAILED: $*" >&2; exit 1; }
log() { echo "[floor-check] $*" >&2; }

read_floor() { # read_floor FILE → x-min-compatible-version value, or empty
  grep -E '^\s*x-min-compatible-version:' "$1" | head -1 \
    | sed -E 's/.*x-min-compatible-version:\s*["'"'"']?([0-9]+\.[0-9]+\.[0-9]+)["'"'"']?.*/\1/' || true
}
ver_gt() { [[ "$1" != "$2" && "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -n1)" == "$1" ]]; }

VERSION="$("${REPO_ROOT}/scripts/spec-version.sh" --full)"
FLOOR="$(read_floor "${SPEC}")"
[[ -n "${FLOOR}" ]] || fail "the spec has no info.x-min-compatible-version — the compatibility floor is required"

LAST_TAG="$(git -C "${REPO_ROOT}" tag -l 'v*' | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -n1)"
[[ -n "${LAST_TAG}" ]] || fail "no release tag found to compare against"
LAST_VERSION="${LAST_TAG#v}"

tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

# Bundle the last release's spec and the working spec the same way `make breaking` does.
mkdir -p "${tmp}/base"
git -C "${REPO_ROOT}" archive "${LAST_TAG}" -- epistola-api.yaml spec/ | tar -x -C "${tmp}/base"
"${REDOCLY}" bundle "${tmp}/base/epistola-api.yaml" -o "${tmp}/base.yaml" >/dev/null 2>&1 \
  || fail "could not bundle the spec at ${LAST_TAG}"
"${REDOCLY}" bundle "${SPEC}" -o "${tmp}/head.yaml" >/dev/null 2>&1 \
  || fail "could not bundle the working spec"

oasdiff breaking --flatten-allof --flatten-params --format json \
  "${tmp}/base.yaml" "${tmp}/head.yaml" > "${tmp}/breaking.json" 2>/dev/null || true
BROKEN_OPS="$(jq -r '[.[]? | .operationId // "\(.operation) \(.path)"] | unique | join(", ")' "${tmp}/breaking.json")"

if [[ -n "${BROKEN_OPS}" ]]; then
  log "breaking changes vs ${LAST_TAG} touch: ${BROKEN_OPS}"
  ver_gt "${VERSION}" "${LAST_VERSION}" \
    || fail "the spec has breaking changes vs ${LAST_TAG} but info.version (${VERSION}) was not bumped past ${LAST_VERSION}"
  [[ "${FLOOR}" == "${VERSION}" ]] \
    || fail "breaking release: x-min-compatible-version must be raised to ${VERSION} (a breaking release is compatible only with itself), found ${FLOOR}"
  log "OK: breaking release ${VERSION}, floor raised to ${VERSION}"
else
  BASE_FLOOR="$(read_floor "${tmp}/base/epistola-api.yaml")"
  if [[ -z "${BASE_FLOOR}" ]]; then
    # The last release predates the floor extension — fall back to the newest
    # breaking release recorded in the committed compatibility log.
    [[ -f "${LOG_FILE}" ]] || fail "no floor at ${LAST_TAG} and no ${LOG_FILE} to derive the expected floor from"
    BASE_FLOOR="$(jq -r '[.entries[] | select(.breaking == true) | .version] | last // empty' "${LOG_FILE}")"
    [[ -n "${BASE_FLOOR}" ]] || fail "could not derive the expected floor from ${LOG_FILE}"
  fi
  [[ "${FLOOR}" == "${BASE_FLOOR}" ]] \
    || fail "no breaking changes vs ${LAST_TAG}: the floor is sticky and must stay ${BASE_FLOOR}, found ${FLOOR}"
  log "OK: no breaking changes vs ${LAST_TAG}, floor sticky at ${FLOOR}"
fi

# Log freshness: the newest release must have an entry.
if [[ -f "${LOG_FILE}" ]]; then
  NEWEST_LOGGED="$(jq -r '[.entries[].version] | last // empty' "${LOG_FILE}")"
  [[ "${NEWEST_LOGGED}" == "${LAST_VERSION}" ]] \
    || fail "compatibility-log.json is stale (newest entry ${NEWEST_LOGGED:-none}, newest release ${LAST_VERSION}) — run scripts/generate-compatibility-log.sh and commit the result"
  log "OK: compatibility-log.json covers ${LAST_VERSION}"
else
  fail "compatibility-log.json is missing — run scripts/generate-compatibility-log.sh and commit the result"
fi

echo "[floor-check] all checks passed (version=${VERSION}, floor=${FLOOR}, last release=${LAST_VERSION})"
