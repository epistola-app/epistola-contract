#!/usr/bin/env bash
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

#
# Regenerates the Epistola .NET client from the bundled OpenAPI spec.
#
#   1. The OpenAPI Generator (csharp / httpclient) emits Api/Model/Client sources
#      into Generated/  (mirrors the Kotlin openApiGenerate task).
#   2. The derived-source generator emits KnownProblemSlugs, KnownValidationCodes,
#      ModelValidation, and ContractVersion into src/Epistola.Client/Generated/ from
#      the spec's x-problem-types and x-validation-codes registries, schema
#      constraints, and info.version
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
# parameters, which the generator escapes before it consults the mapping (`varEnvironment`: the
# `environment` of MissingResource, whose class also has an `Environment` member).
PARAMETER_NAME_MAPPINGS="namespace=@namespace,varNamespace=@namespace,varBase=@base,varVersion=version,varEnvironment=environment"

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

# A minor version may add an enum value. The generated enums carry UnknownDefaultOpenApi
# (enumUnknownDefaultCase), but Newtonsoft's StringEnumConverter still throws on a value it does not
# know, failing the whole response. Point every generated enum at the hand-written converter that
# reads such a value as UnknownDefaultOpenApi.
echo "==> Reading unknown enum values as UnknownDefaultOpenApi"
find "$GEN_OUT/src" -name '*.cs' -print0 \
  | xargs -0 perl -pi -e 's/\[JsonConverter\(typeof\(StringEnumConverter\)\)\]/[JsonConverter(typeof(Epistola.Client.Json.TolerantStringEnumConverter))]/g'
if leftovers="$(grep -rnE 'typeof\(StringEnumConverter\)' "$GEN_OUT/src/Epistola.Client")"; then
  echo "Generated enums still use StringEnumConverter; extend the rewrite in generate.sh:" >&2
  echo "$leftovers" >&2
  exit 1
fi
if ! grep -rqE 'TolerantStringEnumConverter' "$GEN_OUT/src/Epistola.Client"; then
  echo "The enum converter rewrite matched nothing: the generator no longer emits StringEnumConverter" >&2
  exit 1
fi

# The generator numbers enum members in order, 1 to n, with UnknownDefaultOpenApi last. C# compiles
# an enum member into its caller as that number, so a value added in a minor version would take
# UnknownDefaultOpenApi's number and shift it: an application built against the older client and
# run with the newer one would match the new value in its `case UnknownDefaultOpenApi:`. Pin it to
# 0, which no generated member uses and which is also default(T).
echo "==> Pinning UnknownDefaultOpenApi to 0"
find "$GEN_OUT/src" -name '*.cs' -print0 \
  | xargs -0 perl -pi -e 's/\bUnknownDefaultOpenApi = \d+\b/UnknownDefaultOpenApi = 0/g'
if leftovers="$(grep -rnE '\bUnknownDefaultOpenApi = [1-9]' "$GEN_OUT/src/Epistola.Client")"; then
  echo "UnknownDefaultOpenApi is not 0 everywhere; extend the rewrite in generate.sh:" >&2
  echo "$leftovers" >&2
  exit 1
fi
if collisions="$(grep -rnE '^\s+[A-Z][A-Za-z0-9]* = 0,?$' --include='*.cs' "$GEN_OUT/src/Epistola.Client/Model" | grep -v UnknownDefaultOpenApi)"; then
  echo "A generated enum member other than UnknownDefaultOpenApi is numbered 0, colliding with it:" >&2
  echo "$collisions" >&2
  exit 1
fi

echo "==> Generating derived sources (KnownProblemSlugs, KnownValidationCodes, ModelValidation, ContractVersion)"
mkdir -p "$DERIVED_OUT"
dotnet run --project "$SCRIPT_DIR/src/Epistola.Client.Gen" -c Release -- "$SPEC" "$DERIVED_OUT"

echo "==> Done. Build with: dotnet build $SCRIPT_DIR/Epistola.Client.sln"
