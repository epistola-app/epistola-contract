#!/usr/bin/env bash
# Verifies the exact Maven and npm artifacts prepared for an epistola-catalog release.
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "usage: $0 <version> <npm-tarball>" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="$1"
NPM_TARBALL="$2"
LIBS="$ROOT/build/libs"

MAIN_JAR="$LIBS/epistola-catalog-$VERSION.jar"
SOURCES_JAR="$LIBS/epistola-catalog-$VERSION-sources.jar"
JAVADOC_JAR="$LIBS/epistola-catalog-$VERSION-javadoc.jar"

for artifact in "$MAIN_JAR" "$SOURCES_JAR" "$JAVADOC_JAR" "$NPM_TARBALL"; do
  if [ ! -s "$artifact" ]; then
    echo "error: missing or empty catalog release artifact: $artifact" >&2
    exit 1
  fi
done

require_zip_entry() {
  local archive="$1"
  local entry="$2"
  if ! unzip -Z1 "$archive" | grep -Fx "$entry" >/dev/null; then
    echo "error: $archive does not contain $entry" >&2
    exit 1
  fi
}

require_tar_entry() {
  local archive="$1"
  local entry="$2"
  if ! tar -tzf "$archive" | grep -Fx "$entry" >/dev/null; then
    echo "error: $archive does not contain $entry" >&2
    exit 1
  fi
}

require_zip_entry "$MAIN_JAR" "app/epistola/catalog/validation/CatalogValidator.class"
require_zip_entry "$MAIN_JAR" "META-INF/epistola-catalog/component-registry.json"
require_zip_entry "$MAIN_JAR" "META-INF/epistola-catalog/style-registry.json"
require_zip_entry "$MAIN_JAR" "META-INF/epistola-catalog/schemas/template-document.schema.json"
require_zip_entry "$MAIN_JAR" "META-INF/epistola-catalog/fixtures/v1/template-validation.json"
require_zip_entry "$MAIN_JAR" "META-INF/epistola-catalog/fixtures/v1/conformance/catalog-cases.json"
require_zip_entry "$MAIN_JAR" "META-INF/epistola-catalog/fixtures/v1/conformance/catalog/valid-minimal/expected-report.json"
require_zip_entry "$SOURCES_JAR" "app/epistola/catalog/validation/CatalogValidator.kt"
require_zip_entry "$JAVADOC_JAR" "index.html"

require_tar_entry "$NPM_TARBALL" "package/package.json"
require_tar_entry "$NPM_TARBALL" "package/dist/ts/index.js"
require_tar_entry "$NPM_TARBALL" "package/dist/ts/index.d.ts"
require_tar_entry "$NPM_TARBALL" "package/dist/generated/registry.js"
require_tar_entry "$NPM_TARBALL" "package/registry/component-registry.json"
require_tar_entry "$NPM_TARBALL" "package/registry/style-registry.json"
require_tar_entry "$NPM_TARBALL" "package/schemas/template-document.schema.json"
require_tar_entry "$NPM_TARBALL" "package/fixtures/v1/template-validation.json"
require_tar_entry "$NPM_TARBALL" "package/fixtures/v1/conformance/catalog-cases.json"
require_tar_entry "$NPM_TARBALL" "package/fixtures/v1/conformance/catalog/valid-minimal/expected-report.json"

PACKAGE_JSON="$(tar -xOzf "$NPM_TARBALL" package/package.json)"
PACKAGE_JSON="$PACKAGE_JSON" EXPECTED_VERSION="$VERSION" node <<'NODE'
const packageJson = JSON.parse(process.env.PACKAGE_JSON)
if (packageJson.name !== '@epistola.app/epistola-catalog') {
  throw new Error(`unexpected npm package name: ${packageJson.name}`)
}
if (packageJson.version !== process.env.EXPECTED_VERSION) {
  throw new Error(
    `unexpected npm package version: ${packageJson.version}; expected ${process.env.EXPECTED_VERSION}`,
  )
}
NODE

echo "catalog release artifacts OK: Maven and npm $VERSION"
