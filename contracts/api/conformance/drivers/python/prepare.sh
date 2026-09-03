#!/usr/bin/env bash
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2
#
# Generates the Python client and resolves its environment. Runs once per suite, not once per
# scenario.
set -euo pipefail
CLIENT_DIR="$(cd "$(dirname "$0")/../../../clients/python-urllib3" && pwd)"

# The client imports generated sources that are not committed, so generate them if they are absent.
if [ ! -d "$CLIENT_DIR/generated" ]; then
  "$CLIENT_DIR/generate.sh"
fi

cd "$CLIENT_DIR"
uv sync --group dev --quiet
