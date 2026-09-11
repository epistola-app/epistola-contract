#!/usr/bin/env bash
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2
#
# Generates and builds the Node.js client. Runs once per suite, not once per scenario.
set -euo pipefail
CLIENT_DIR="$(cd "$(dirname "$0")/../../../clients/nodejs-fetch" && pwd)"

cd "$CLIENT_DIR"
pnpm install --frozen-lockfile --silent

# The client imports generated sources that are not committed, so generate them if they are absent.
if [ ! -d "$CLIENT_DIR/src/generated" ]; then
  "$CLIENT_DIR/generate.sh"
fi

pnpm --silent build
