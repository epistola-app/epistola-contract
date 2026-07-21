#!/usr/bin/env bash
#
# Regenerates the Epistola Python client from the bundled OpenAPI spec.
#
#   1. The OpenAPI Generator (python / urllib3) emits the stock client package
#      (api/models/rest/…) into generated/  (mirrors the Kotlin openApiGenerate
#      task and the .NET csharp generator step).
#   2. The derived-source generator emits contract_version, known_problem_slugs,
#      and model_validation into src/epistola_client/_generated/ from the spec's
#      info.version, x-problem-types registry, and schema constraints
#      (mirrors the Kotlin generateContractVersionResource / generateProblemSlugs /
#       generateValidation tasks and the .NET Epistola.Client.Gen program).
#
# Run `make bundle` first (this fails early if openapi.yaml is missing).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SPEC="$REPO_ROOT/openapi.yaml"
GEN_OUT="$SCRIPT_DIR/generated"
DERIVED_OUT="$SCRIPT_DIR/src/epistola_client/_generated"

if [[ ! -f "$SPEC" ]]; then
  cat >&2 <<EOF
Bundled OpenAPI spec not found at: $SPEC

Run from the repository root:
    make bundle
EOF
  exit 1
fi

echo "==> Generating Python client from $SPEC"
rm -rf "$GEN_OUT"
pnpm -C "$REPO_ROOT/tools" exec openapi-generator-cli generate \
  -i "$SPEC" \
  -g python \
  -o "$GEN_OUT" \
  -c "$SCRIPT_DIR/openapi-generator-python-config.yaml" \
  --ignore-file-override "$SCRIPT_DIR/openapi-generator-ignore"

# The python generator emits packaging scaffolding (setup.py, pyproject.toml,
# README, docs, test/) that the --ignore-file-override does not reliably suppress.
# The hand-written module owns packaging, so prune everything except the stock
# package so `generated/` contains only importable client sources.
echo "==> Pruning generated packaging scaffolding"
find "$GEN_OUT" -mindepth 1 -maxdepth 1 \
  ! -name 'epistola_client_generated' \
  ! -name '.openapi-generator' \
  -exec rm -rf {} +

echo "==> Generating derived sources (contract_version, known_problem_slugs, model_validation)"
mkdir -p "$DERIVED_OUT"
# Run the generator in an isolated uv environment. Do not run it as the project:
# hatchling reads the generated contract_version.py for the package version, and
# that file is exactly what this step is creating.
uv run --no-project --with "PyYAML>=6.0" python "$SCRIPT_DIR/gen/generate_derived.py" "$SPEC" "$DERIVED_OUT"

echo "==> Done. Install/test with: uv run --project $SCRIPT_DIR pytest"
