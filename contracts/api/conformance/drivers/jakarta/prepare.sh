#!/usr/bin/env bash
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2
#
# Compiles the Jakarta EE client and its conformance driver, and writes the runtime classpath that
# run.sh executes against. Runs once per suite, not once per scenario.
set -euo pipefail
cd "$(dirname "$0")/../../../clients/jakarta"
./gradlew --quiet conformanceDriverClasspath
