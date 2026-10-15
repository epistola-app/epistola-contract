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
  'catalog-manifest-v8.schema.json',
  'resource-detail-v8.schema.json',
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
  ['catalog-manifest-v7.schema.json', 'fixtures/v1/migrations/v7-to-v8/manifest-input.json'],
  ['resource-detail-v7.schema.json', 'fixtures/v1/migrations/v7-to-v8/stencil-input.json'],
  ['catalog-manifest-v8.schema.json', 'fixtures/v1/wire-v8/catalog.json'],
  ['resource-detail-v8.schema.json', 'fixtures/v1/wire-v8/resources/theme/default.json'],
  ['catalog-manifest-v8.schema.json', 'fixtures/v1/migrations/v7-to-v8/manifest-expected.json'],
  ['resource-detail-v8.schema.json', 'fixtures/v1/migrations/v7-to-v8/stencil-expected.json'],
  ['catalog-manifest.schema.json', 'fixtures/v1/wire-v8/catalog.json'],
  ['resource-detail.schema.json', 'fixtures/v1/wire-v8/resources/theme/default.json'],
  ['catalog-manifest.schema.json', 'fixtures/v1/conformance/catalog/valid-minimal/catalog.json'],
  ['resource-detail.schema.json', 'fixtures/v1/conformance/catalog/valid-minimal/resources/theme/default.json'],
  ['resource-detail.schema.json', 'fixtures/v1/conformance/catalog/missing-resource-reference/resources/attribute/locale.json'],
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

const validateV8Manifest = ajv.getSchema('https://epistola.app/schemas/catalog-manifest-v8.schema.json');
const validateV8Detail = ajv.getSchema('https://epistola.app/schemas/resource-detail-v8.schema.json');
const v8Manifest = async (edit) => {
  const manifest = await readJson('fixtures/v1/wire-v8/catalog.json');
  edit(manifest);
  return manifest;
};

for (const namespace of ['acme.com', 'exchange.epistola.app/acme-corp', 'a.b', `${'a'.repeat(300)}.com`]) {
  const manifest = await v8Manifest((m) => { m.catalog.namespace = namespace; });
  assert.equal(validateV8Manifest(manifest), true, `valid v8 namespace was rejected: ${namespace}: ${ajv.errorsText(validateV8Manifest.errors)}`);
}

// The short form depends on the installation's default exchange, so an archive never writes it.
for (const namespace of ['acme-corp', 'Acme.com', 'acme.com/', 'acme.com/Acme', '-acme.com', 'acme..com', `${'a'.repeat(320)}.com`, '']) {
  const manifest = await v8Manifest((m) => { m.catalog.namespace = namespace; });
  assert.equal(validateV8Manifest(manifest), false, `invalid v8 catalog namespace was accepted: ${namespace}`);
  const dependency = await v8Manifest((m) => { m.dependencies[0].namespace = namespace; });
  assert.equal(validateV8Manifest(dependency), false, `invalid v8 dependency namespace was accepted: ${namespace}`);
  const declared = await v8Manifest((m) => { m.catalogDependencies[0].namespace = namespace; });
  assert.equal(validateV8Manifest(declared), false, `invalid v8 catalog dependency namespace was accepted: ${namespace}`);
}

for (const [description, edit] of [
  ['catalog without a namespace', (m) => { delete m.catalog.namespace; }],
  ['dependency without a namespace', (m) => { delete m.dependencies[0].namespace; }],
  ['catalog dependency without a version', (m) => { delete m.catalogDependencies[0].version; }],
  ['catalog dependency with an empty version', (m) => { m.catalogDependencies[0].version = ''; }],
  ['catalog dependency with a 101-character version', (m) => { m.catalogDependencies[0].version = '1'.repeat(101); }],
  ['catalog dependency without releaseVersion', (m) => { delete m.catalogDependencies[0].releaseVersion; }],
  ['catalog dependency with an unknown member', (m) => { m.catalogDependencies[0].range = '^1.0.0'; }],
]) {
  assert.equal(validateV8Manifest(await v8Manifest(edit)), false, `v8 manifest accepted ${description}`);
}

for (const [description, edit] of [
  ['no catalog dependencies', (m) => { delete m.catalogDependencies; m.dependencies = []; }],
  ['null catalog dependencies', (m) => { m.catalogDependencies = null; m.dependencies = []; }],
  ['a migrated catalog dependency', (m) => { m.catalogDependencies[0].version = '*'; m.catalogDependencies[0].releaseVersion = null; }],
  ['a range union selector', (m) => { m.catalogDependencies[0].version = '>=1.0.0 <2.0.0 || ^3.1.0'; }],
]) {
  const manifest = await v8Manifest(edit);
  assert.equal(validateV8Manifest(manifest), true, `v8 manifest rejected ${description}: ${ajv.errorsText(validateV8Manifest.errors)}`);
}

// Stencils carry no version from v8; content references may name a namespace in either form.
const v8Stencil = await readJson('fixtures/v1/migrations/v7-to-v8/stencil-expected.json');
assert.equal(validateV8Detail({ ...v8Stencil, resource: { ...v8Stencil.resource, version: 3 } }), false, 'v8 stencil accepted a version');
const v7Stencil = await readJson('fixtures/v1/migrations/v7-to-v8/stencil-input.json');
assert.equal(validateV8Detail({ ...v7Stencil, schemaVersion: 8 }), false, 'v8 accepted a stencil with its v7 version');

const attribute = await readJson('fixtures/v1/conformance/catalog/missing-resource-reference/resources/attribute/locale.json');
for (const namespace of ['acme-corp', 'acme.com', 'exchange.epistola.app/acme-corp']) {
  const detail = { ...attribute, resource: { ...attribute.resource, codeListBinding: { slug: 'countries', catalogKey: 'shared', namespace } } };
  assert.equal(validateV8Detail(detail), true, `valid content namespace was rejected: ${namespace}: ${ajv.errorsText(validateV8Detail.errors)}`);
}
for (const namespace of ['Acme', 'acme.com/', 'ab']) {
  const detail = { ...attribute, resource: { ...attribute.resource, codeListBinding: { slug: 'countries', namespace } } };
  assert.equal(validateV8Detail(detail), false, `invalid content namespace was accepted: ${namespace}`);
}
