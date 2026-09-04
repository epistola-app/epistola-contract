#!/usr/bin/env bash
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

#
# Regenerates the Epistola Node.js client from the bundled OpenAPI spec.
#
#   1. The OpenAPI Generator (typescript-fetch) emits the stock client (apis/models/runtime)
#      into build/openapi-generator/, and its src/ tree is copied to src/generated/api/
#      (mirrors the Kotlin openApiGenerate task and the .NET / Python generator steps).
#   2. The derived-source generator emits contractVersion, contractIdentity,
#      contractMediaTypes, contractOperations, knownProblemSlugs and modelValidation into
#      src/generated/ from the spec's info.version, x-client-identity, x-problem-types, the
#      operations' media types, and the schema constraints (mirrors the Kotlin
#      generateContractVersionResource / generateClientIdentityConstants /
#      generateProblemSlugs / generateValidation tasks and the Python gen/generate_derived.py).
#
# Run `make bundle` first (this fails early if the bundled spec is missing), and
# `pnpm install --frozen-lockfile` once (the derived-source generator needs the yaml package).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
API_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SPEC="$API_ROOT/build/openapi.yaml"
GEN_OUT="$SCRIPT_DIR/build/openapi-generator"
GENERATED_SRC="$SCRIPT_DIR/src/generated"

if [[ ! -f "$SPEC" ]]; then
  cat >&2 <<MSG
Bundled OpenAPI spec not found at: $SPEC

Run from the repository root:
    make bundle
MSG
  exit 1
fi

if [[ ! -d "$SCRIPT_DIR/node_modules" ]]; then
  cat >&2 <<MSG
Dependencies not installed in: $SCRIPT_DIR

Run from this directory:
    pnpm install --frozen-lockfile
MSG
  exit 1
fi

echo "==> Generating Node.js client from $SPEC"
rm -rf "$GEN_OUT" "$GENERATED_SRC"
pnpm -C "$API_ROOT/tools" exec openapi-generator-cli generate \
  -i "$SPEC" \
  -g typescript-fetch \
  -o "$GEN_OUT" \
  -c "$SCRIPT_DIR/openapi-generator-typescript-config.yaml" \
  --ignore-file-override "$SCRIPT_DIR/openapi-generator-ignore"

# Without an npmName the generator writes the sources at the root of its output directory
# (apis/, models/, runtime.ts, index.ts) rather than under src/, which is exactly what we want:
# only those four are copied, so the generator's bookkeeping files stay behind under build/.
echo "==> Copying the stock client into src/generated/api"
mkdir -p "$GENERATED_SRC/api"
cp -R "$GEN_OUT/apis" "$GEN_OUT/models" "$GEN_OUT/runtime.ts" "$GEN_OUT/index.ts" "$GENERATED_SRC/api/"

echo "==> Generating derived sources (contractVersion, contractIdentity, contractMediaTypes, contractOperations, knownProblemSlugs, modelValidation)"
node "$SCRIPT_DIR/gen/generate-derived.mjs" "$SPEC" "$GENERATED_SRC"

echo "==> Done. Build and test with: pnpm build && pnpm test"
