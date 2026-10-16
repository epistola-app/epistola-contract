// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const packageName = '@epistola.app/epistola-catalog';

const catalog = await import(packageName);
const validationFixture = JSON.parse(
  await readFile(new URL('../fixtures/v1/template-validation.json', import.meta.url), 'utf8'),
);
assert.equal(
  catalog.MAX_STENCIL_NESTING_DEPTH,
  validationFixture.limits.maxStencilNestingDepth,
  'npm and conformance fixture stencil-depth limits must agree',
);

const manifestSchema = JSON.parse(
  await readFile(new URL('../schemas/catalog-manifest-v8.schema.json', import.meta.url), 'utf8'),
);
const keywordSchema = manifestSchema.$defs.CatalogKeyword;
const keywordsSchema = manifestSchema.$defs.CatalogInfo.properties.keywords.oneOf[0];
assert.equal(catalog.MAX_CATALOG_KEYWORD_LENGTH, keywordSchema.maxLength, 'npm and schema keyword length limits must agree');
assert.equal(catalog.CATALOG_KEYWORD_PATTERN, keywordSchema.pattern, 'npm and schema keyword patterns must agree');
assert.equal(catalog.MAX_CATALOG_KEYWORDS, keywordsSchema.maxItems, 'npm and schema keyword count limits must agree');

const namespaceSchema = manifestSchema.$defs.CatalogNamespace;
assert.equal(catalog.CATALOG_NAMESPACE_PATTERN, namespaceSchema.pattern, 'npm and schema namespace patterns must agree');
assert.equal(catalog.MIN_CATALOG_NAMESPACE_LENGTH, namespaceSchema.minLength, 'npm and schema namespace minimum lengths must agree');
assert.equal(catalog.MAX_CATALOG_NAMESPACE_LENGTH, namespaceSchema.maxLength, 'npm and schema namespace maximum lengths must agree');
const registrySchema = manifestSchema.$defs.CatalogRegistry;
assert.equal(catalog.CATALOG_REGISTRY_PATTERN, registrySchema.pattern, 'npm and schema registry patterns must agree');
assert.equal(catalog.MAX_CATALOG_REGISTRY_LENGTH, registrySchema.maxLength, 'npm and schema registry maximum lengths must agree');
assert.equal(
  catalog.MAX_CATALOG_DEPENDENCY_VERSION_LENGTH,
  manifestSchema.$defs.CatalogDependency.properties.version.maxLength,
  'npm and schema version selector limits must agree',
);
assert.equal(
  catalog.CATALOG_RELEASE_FINGERPRINT_PATTERN,
  manifestSchema.$defs.CatalogDependency.properties.releaseFingerprint.pattern,
  'npm and schema release fingerprint patterns must agree',
);
assert.equal(
  catalog.CATALOG_RELEASE_FINGERPRINT_PATTERN,
  manifestSchema.$defs.ReleaseInfo.properties.fingerprint.pattern,
  'npm and schema release.fingerprint patterns must agree',
);

const detailSchema = JSON.parse(
  await readFile(new URL('../schemas/resource-detail-v8.schema.json', import.meta.url), 'utf8'),
);
const bindingSchema = detailSchema.$defs.CodeListBindingRef.properties;
const templateSchema = detailSchema.$defs.TemplateResource.properties;
for (const [where, namespace, registry] of [
  ['code list binding', bindingSchema.namespace, bindingSchema.registry],
  ['template theme', templateSchema.themeNamespace, templateSchema.themeRegistry],
]) {
  assert.equal(catalog.CATALOG_NAMESPACE_PATTERN, namespace.pattern, `npm and schema ${where} namespace patterns must agree`);
  assert.equal(catalog.CATALOG_REGISTRY_PATTERN, registry.pattern, `npm and schema ${where} registry patterns must agree`);
}

const registry = await import(`${packageName}/registry`);
assert.ok(registry.componentRegistry, 'component registry must be exported');
assert.ok(registry.styleRegistry, 'style registry must be exported');

await assert.rejects(
  import(`${packageName}/generated/theme`),
  (error) => error?.code === 'ERR_PACKAGE_PATH_NOT_EXPORTED',
  'generator output must not be a public npm entry point',
);
