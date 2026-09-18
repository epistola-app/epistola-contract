#!/usr/bin/env node
// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import Ajv2020 from 'ajv/dist/2020.js';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const readJson = async (path) => JSON.parse(await readFile(resolve(root, path), 'utf8'));
const schemaNames = [
  'template-shared.schema.json',
  'template-document.schema.json',
  'theme.schema.json',
  'catalog-manifest-v5.schema.json',
  'resource-detail-v5.schema.json',
  'catalog-manifest-v6.schema.json',
  'resource-detail-v6.schema.json',
  'catalog-manifest-v7.schema.json',
  'resource-detail-v7.schema.json',
  'catalog-manifest.schema.json',
  'resource-detail.schema.json',
];

const schemas = await Promise.all(schemaNames.map((name) => readJson(`schemas/${name}`)));
const ajv = new Ajv2020({ allErrors: true, strict: true });
schemas.forEach((schema) => ajv.addSchema(schema));

const cases = [
  ['catalog-manifest-v5.schema.json', 'fixtures/v1/wire-v5/catalog.json'],
  ['resource-detail-v5.schema.json', 'fixtures/v1/wire-v5/resources/theme/default.json'],
  ['catalog-manifest-v6.schema.json', 'fixtures/v1/wire-v6/catalog.json'],
  ['resource-detail-v6.schema.json', 'fixtures/v1/wire-v6/resources/theme/default.json'],
  ['catalog-manifest-v6.schema.json', 'fixtures/v1/migrations/v6-to-v7/manifest-input.json'],
  ['catalog-manifest-v7.schema.json', 'fixtures/v1/wire-v7/catalog.json'],
  ['resource-detail-v7.schema.json', 'fixtures/v1/wire-v7/resources/theme/default.json'],
  ['catalog-manifest-v7.schema.json', 'fixtures/v1/migrations/v6-to-v7/manifest-expected.json'],
  ['catalog-manifest.schema.json', 'fixtures/v1/wire-v7/catalog.json'],
  ['resource-detail.schema.json', 'fixtures/v1/wire-v7/resources/theme/default.json'],
];

for (const [schemaName, fixtureName] of cases) {
  const validate = ajv.getSchema(`https://epistola.app/schemas/${schemaName}`);
  assert.ok(validate, `schema ${schemaName} was registered`);
  const valid = validate(await readJson(fixtureName));
  assert.equal(valid, true, `${fixtureName}: ${ajv.errorsText(validate.errors)}`);
}

const validateV6Manifest = ajv.getSchema('https://epistola.app/schemas/catalog-manifest-v6.schema.json');
const optionalMetadata = await readJson('fixtures/v1/wire-v6/catalog.json');
delete optionalMetadata.catalog.attributes;
delete optionalMetadata.catalog.keywords;
delete optionalMetadata.catalog.presentation;
delete optionalMetadata.catalog.license;
assert.equal(validateV6Manifest(optionalMetadata), true, 'catalog v6 discovery metadata remains optional in 1.x');

for (const keywords of [['documents', 'documents'], [' documents '], ['']]) {
  const invalid = await readJson('fixtures/v1/wire-v6/catalog.json');
  invalid.catalog.keywords = keywords;
  assert.equal(validateV6Manifest(invalid), false, `invalid keyword array was accepted: ${JSON.stringify(keywords)}`);
}

for (const attribute of [
  { catalog: 'System', key: 'locale', value: 'nl-NL' },
  { catalog: 'system', key: 'bad_key', value: 'nl-NL' },
]) {
  const invalid = await readJson('fixtures/v1/wire-v6/catalog.json');
  invalid.catalog.attributes = [attribute];
  assert.equal(validateV6Manifest(invalid), false, `invalid catalog attribute was accepted: ${JSON.stringify(attribute)}`);
}

for (const license of [
  { name: '' },
  { name: 'Example', url: 'not-a-url' },
  { name: 'Example', spdxExpression: ' CC-BY-4.0' },
]) {
  const invalid = await readJson('fixtures/v1/wire-v6/catalog.json');
  invalid.catalog.license = license;
  assert.equal(validateV6Manifest(invalid), false, `invalid catalog license was accepted: ${JSON.stringify(license)}`);
}

const validateV7Manifest = ajv.getSchema('https://epistola.app/schemas/catalog-manifest-v7.schema.json');
const withV7Keywords = async (keywords) => {
  const manifest = await readJson('fixtures/v1/wire-v7/catalog.json');
  manifest.catalog.keywords = keywords;
  return manifest;
};
const numbered = (count) => Array.from({ length: count }, (_, index) => `keyword-${index}`);

for (const keywords of [['1-loket', 'getting-started'], ['a'.repeat(30)], numbered(20), [], null]) {
  const manifest = await withV7Keywords(keywords);
  assert.equal(validateV7Manifest(manifest), true, `valid v7 keywords were rejected: ${JSON.stringify(keywords)}: ${ajv.errorsText(validateV7Manifest.errors)}`);
}

for (const keywords of [
  ['Government'],
  ['getting started'],
  ['financiële'],
  ['-a'],
  ['a-'],
  ['a--b'],
  ['snake_case'],
  [''],
  ['a'.repeat(31)],
  ['documents', 'documents'],
  numbered(21),
]) {
  const manifest = await withV7Keywords(keywords);
  assert.equal(validateV7Manifest(manifest), false, `invalid v7 keywords were accepted: ${JSON.stringify(keywords)}`);
}

const normalization = await readJson('fixtures/v1/migrations/v6-to-v7/keyword-normalization.json');
for (const { input, expected } of normalization.cases.filter((entry) => entry.expected !== null)) {
  const manifest = await withV7Keywords([expected]);
  assert.equal(validateV7Manifest(manifest), true, `normalization of ${JSON.stringify(input)} is not a valid v7 keyword: ${expected}`);
}
