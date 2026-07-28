#!/usr/bin/env bash
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

#
# Regenerates the Epistola .NET client from the bundled OpenAPI spec.
#
#   1. The OpenAPI Generator (csharp / httpclient) emits Api/Model/Client sources
#      into Generated/  (mirrors the Kotlin openApiGenerate task).
#   2. The derived-source generator emits KnownProblemSlugs, ModelValidation, and
#      ContractVersion into src/Epistola.Client/Generated/ from the spec's
#      x-problem-types registry, schema constraints, and info.version
#      (mirrors the Kotlin generateProblemSlugs / generateValidation /
#       generateContractVersionResource tasks).
#
# Run `make bundle` first (this fails early if openapi.yaml is missing).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
API_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SPEC="$API_ROOT/build/openapi.yaml"
GEN_OUT="$SCRIPT_DIR/Generated"
DERIVED_OUT="$SCRIPT_DIR/src/Epistola.Client/Generated"

if [[ ! -f "$SPEC" ]]; then
  cat >&2 <<EOF
Bundled OpenAPI spec not found at: $SPEC

Run from the repository root:
    make bundle
EOF
  exit 1
fi

echo "==> Generating C# client from $SPEC"
rm -rf "$GEN_OUT"
pnpm -C "$API_ROOT/tools" exec openapi-generator-cli generate \
  -i "$SPEC" \
  -g csharp \
  -o "$GEN_OUT" \
  -c "$SCRIPT_DIR/openapi-generator-config.yaml" \
  --ignore-file-override "$SCRIPT_DIR/openapi-generator-ignore"

echo "==> Generating derived sources (KnownProblemSlugs, ModelValidation, ContractVersion)"
mkdir -p "$DERIVED_OUT"
dotnet run --project "$SCRIPT_DIR/src/Epistola.Client.Gen" -c Release -- "$SPEC" "$DERIVED_OUT"

echo "==> Done. Build with: dotnet build $SCRIPT_DIR/Epistola.Client.sln"
