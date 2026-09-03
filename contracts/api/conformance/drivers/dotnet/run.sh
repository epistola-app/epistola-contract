#!/usr/bin/env bash
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2
#
# Runs one scenario. $1 is the conformance server's base URL; the driver asks it for the rest.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
exec dotnet "$HERE/bin/Release/net8.0/Epistola.Conformance.Driver.dll" "$1"
