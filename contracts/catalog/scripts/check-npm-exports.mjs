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
  await readFile(new URL('../schemas/catalog-manifest-v7.schema.json', import.meta.url), 'utf8'),
);
const keywordSchema = manifestSchema.$defs.CatalogKeyword;
const keywordsSchema = manifestSchema.$defs.CatalogInfo.properties.keywords.oneOf[0];
assert.equal(catalog.MAX_CATALOG_KEYWORD_LENGTH, keywordSchema.maxLength, 'npm and schema keyword length limits must agree');
assert.equal(catalog.CATALOG_KEYWORD_PATTERN, keywordSchema.pattern, 'npm and schema keyword patterns must agree');
assert.equal(catalog.MAX_CATALOG_KEYWORDS, keywordsSchema.maxItems, 'npm and schema keyword count limits must agree');

const registry = await import(`${packageName}/registry`);
assert.ok(registry.componentRegistry, 'component registry must be exported');
assert.ok(registry.styleRegistry, 'style registry must be exported');

await assert.rejects(
  import(`${packageName}/generated/theme`),
  (error) => error?.code === 'ERR_PACKAGE_PATH_NOT_EXPORTED',
  'generator output must not be a public npm entry point',
);
