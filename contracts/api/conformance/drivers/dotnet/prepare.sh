#!/usr/bin/env bash
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2
#
# Generates and builds the .NET client and the conformance driver. Runs once per suite, not once
# per scenario.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CLIENT_DIR="$HERE/../../../clients/dotnet-httpclient"

# The client compiles generated sources that are not committed, so generate them if they are absent.
if [ ! -d "$CLIENT_DIR/Generated" ]; then
  "$CLIENT_DIR/generate.sh"
fi

dotnet build "$HERE/Epistola.Conformance.Driver.csproj" -c Release --nologo -v quiet
