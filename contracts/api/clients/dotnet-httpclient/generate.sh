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

# Parameters whose name is a C# keyword or a generator reserved word are escaped with a "var" prefix
# (`varNamespace`, `varBase`, `varVersion`). Map them to their plain name instead; a C# keyword
# takes the verbatim prefix `@`, so callers write `@namespace:` for a named argument. The first
# key is the operation parameter, named from the spec; the `var…` keys are the model constructor
# parameters, which the generator escapes before it consults the mapping.
PARAMETER_NAME_MAPPINGS="namespace=@namespace,varNamespace=@namespace,varBase=@base,varVersion=version"

echo "==> Generating C# client from $SPEC"
rm -rf "$GEN_OUT"
pnpm -C "$API_ROOT/tools" exec openapi-generator-cli generate \
  -i "$SPEC" \
  -g csharp \
  -o "$GEN_OUT" \
  -c "$SCRIPT_DIR/openapi-generator-config.yaml" \
  --ignore-file-override "$SCRIPT_DIR/openapi-generator-ignore" \
  --parameter-name-mappings "$PARAMETER_NAME_MAPPINGS"

# The generator prefixes a model property whose PascalCase name is one of its own reserved words
# with "Var" (`version` -> `VarVersion`). It escapes after applying --name-mappings, and its
# reserved-word list is not configurable, so no option avoids it: rename the members back here.
# The JSON names ([DataMember(Name = "version")]) are untouched, so the wire format is unchanged.
echo "==> Renaming Var-prefixed members"
find "$GEN_OUT/src" -name '*.cs' -print0 \
  | xargs -0 perl -pi -e 's/\b(regex)?Var([A-Z][A-Za-z0-9]*)\b/$1$2/g'

# A `@` verbatim parameter is named without the `@` wherever it is not code: in its XML doc tag
# (`<param name="@namespace">` is CS1572 and leaves the parameter undocumented) and in messages.
echo "==> Naming @-escaped parameters plainly in docs and messages"
find "$GEN_OUT/src" -name '*.cs' -print0 \
  | xargs -0 perl -pi -e 's/<param name="\@(\w+)">/<param name="$1">/g; s/\x27\@(\w+)\x27/\x27$1\x27/g; s/"\@(\w+)( is a required property|" is required)/"$1$2/g'

# Guard: no Var-/var-prefixed identifier may survive, in a member or a parameter.
if leftovers="$(grep -rnoE '\b[Vv]ar[A-Z][A-Za-z0-9]*\b' --include='*.cs' "$GEN_OUT/src/Epistola.Client")"; then
  echo "Var-prefixed identifiers remain in the generated code; extend the mappings in generate.sh:" >&2
  echo "$leftovers" | sort -u -t: -k3 >&2
  exit 1
fi

echo "==> Generating derived sources (KnownProblemSlugs, ModelValidation, ContractVersion)"
mkdir -p "$DERIVED_OUT"
dotnet run --project "$SCRIPT_DIR/src/Epistola.Client.Gen" -c Release -- "$SPEC" "$DERIVED_OUT"

echo "==> Done. Build with: dotnet build $SCRIPT_DIR/Epistola.Client.sln"
