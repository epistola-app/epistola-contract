#!/usr/bin/env bash
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2
#
# Compiles the driver and writes the runtime classpath run.sh executes against. The client comes in
# from source via the includeBuild in ../settings.gradle.kts, so this builds it too. Runs once per
# suite, not once per scenario.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew --quiet :kotlin-driver:conformanceDriverClasspath
