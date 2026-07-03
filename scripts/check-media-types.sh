#!/usr/bin/env bash
# Guards against typo'd or unversioned media types creeping into the spec.
# OpenAPI cannot $ref a content-type key, so consistency is enforced by this
# allowlist check instead. Add a genuinely new media type here deliberately.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Media types the API is allowed to use.
allowed=$(cat <<'EOF'
application/vnd.epistola.v1+json
application/vnd.epistola.v1+ndjson
application/problem+json
application/pdf
multipart/form-data
EOF
)

# Every media-type-looking token that appears as a content key or in prose.
used=$(grep -rhoE '(application|multipart|text)/[a-zA-Z0-9.+_-]+' \
  "$ROOT/spec" "$ROOT/epistola-api.yaml" | sort -u)

unknown=$(comm -23 <(echo "$used") <(echo "$allowed" | sort -u))

if [ -n "$unknown" ]; then
  echo "error: media type(s) not in the allowlist (scripts/check-media-types.sh):" >&2
  echo "$unknown" | sed 's/^/  /' >&2
  echo "If a new media type is intended, add it to the allowlist in this script." >&2
  exit 1
fi

echo "media types OK: $(echo "$used" | wc -l | tr -d ' ') distinct, all allowlisted"
