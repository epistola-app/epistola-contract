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
  'template-document-v7.schema.json',
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
  ['resource-detail-v7.schema.json', 'fixtures/v1/migrations/v7-to-v8/template-input.json'],
  ['resource-detail-v8.schema.json', 'fixtures/v1/migrations/v7-to-v8/template-expected.json'],
  ['catalog-manifest.schema.json', 'fixtures/v1/wire-v8/catalog.json'],
  ['resource-detail.schema.json', 'fixtures/v1/wire-v8/resources/theme/default.json'],
  ['resource-detail-v8.schema.json', 'fixtures/v1/wire-v8/resources/template/letter.json'],
  ['resource-detail.schema.json', 'fixtures/v1/wire-v8/resources/template/letter.json'],
  ['catalog-manifest.schema.json', 'fixtures/v1/conformance/catalog/valid-minimal/catalog.json'],
  ['resource-detail.schema.json', 'fixtures/v1/conformance/catalog/valid-minimal/resources/theme/default.json'],
  ['resource-detail.schema.json', 'fixtures/v1/conformance/catalog/missing-resource-reference/resources/attribute/locale.json'],
  ['catalog-manifest.schema.json', 'fixtures/v1/conformance/catalog/invalid-template-v8-members/catalog.json'],
  ['resource-detail.schema.json', 'fixtures/v1/conformance/catalog/invalid-template-v8-members/resources/template/letter.json'],
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

// Wire v7 keeps its release number rule: full SemVer. Only wire v8 narrows it to MAJOR.MINOR.PATCH.
{
  const manifest = await readJson('fixtures/v1/wire-v7/catalog.json');
  manifest.release.version = '1.0.0-beta.1+5';
  assert.equal(validateV7Manifest(manifest), true, `v7 manifest rejected a pre-release version: ${ajv.errorsText(validateV7Manifest.errors)}`);
}

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

const v8Targets = [
  ['catalog', (m) => m.catalog],
  ['dependency', (m) => m.dependencies[0]],
  ['catalog dependency', (m) => m.catalogDependencies[0]],
];

for (const namespace of ['acme-corp', 'abc', `a${'b'.repeat(62)}`]) {
  for (const [where, target] of v8Targets) {
    const manifest = await v8Manifest((m) => { target(m).namespace = namespace; });
    assert.equal(validateV8Manifest(manifest), true, `valid v8 ${where} namespace was rejected: ${namespace}: ${ajv.errorsText(validateV8Manifest.errors)}`);
  }
}

// A namespace is a slug within its registry; the joined pre-registry forms are not namespaces.
for (const namespace of ['acme.com', 'exchange.epistola.app/acme-corp', 'Acme', 'ab', '-acme', `a${'b'.repeat(63)}`, '']) {
  for (const [where, target] of v8Targets) {
    const manifest = await v8Manifest((m) => { target(m).namespace = namespace; });
    assert.equal(validateV8Manifest(manifest), false, `invalid v8 ${where} namespace was accepted: ${namespace}`);
  }
}

for (const registry of ['acme.com', 'exchange.epistola.app', 'a.b', `${'a'.repeat(249)}.com`]) {
  for (const [where, target] of v8Targets) {
    const manifest = await v8Manifest((m) => { target(m).registry = registry; });
    assert.equal(validateV8Manifest(manifest), true, `valid v8 ${where} registry was rejected: ${registry}: ${ajv.errorsText(validateV8Manifest.errors)}`);
  }
}

// An archive has no default registry, so it never writes `~` or a bare name; domain order is normal.
for (const registry of ['~', 'acme', 'Acme.com', 'acme.com/', 'acme.com/acme', '-acme.com', 'acme..com', 'acme.com.', `${'a'.repeat(250)}.com`, '']) {
  for (const [where, target] of v8Targets) {
    const manifest = await v8Manifest((m) => { target(m).registry = registry; });
    assert.equal(validateV8Manifest(manifest), false, `invalid v8 ${where} registry was accepted: ${registry}`);
  }
}

for (const [description, edit] of [
  ['catalog without a namespace', (m) => { delete m.catalog.namespace; }],
  ['catalog without a registry', (m) => { delete m.catalog.registry; }],
  ['dependency without a namespace', (m) => { delete m.dependencies[0].namespace; }],
  ['dependency without a registry', (m) => { delete m.dependencies[0].registry; }],
  ['catalog dependency without a namespace', (m) => { delete m.catalogDependencies[0].namespace; }],
  ['catalog dependency without a registry', (m) => { delete m.catalogDependencies[0].registry; }],
  ['catalog dependency with a version selector', (m) => { m.catalogDependencies[0].version = '^1.2.0'; }],
  ['catalog dependency without releaseVersion', (m) => { delete m.catalogDependencies[0].releaseVersion; }],
  ['catalog dependency without releaseFingerprint', (m) => { delete m.catalogDependencies[0].releaseFingerprint; }],
  ['catalog dependency with a release but no releaseFingerprint', (m) => { m.catalogDependencies[0].releaseFingerprint = null; }],
  ['catalog dependency with a releaseFingerprint but no release', (m) => { m.catalogDependencies[0].releaseVersion = null; }],
  ['catalog dependency with an uppercase releaseFingerprint', (m) => { m.catalogDependencies[0].releaseFingerprint = m.catalogDependencies[0].releaseFingerprint.toUpperCase(); }],
  ['catalog dependency with a short releaseFingerprint', (m) => { m.catalogDependencies[0].releaseFingerprint = m.catalogDependencies[0].releaseFingerprint.slice(1); }],
  ['catalog dependency with a prefixed releaseFingerprint', (m) => { m.catalogDependencies[0].releaseFingerprint = `sha256:${m.catalogDependencies[0].releaseFingerprint}`; }],
  ['catalog dependency with an unknown member', (m) => { m.catalogDependencies[0].range = '^1.0.0'; }],
  ['a pre-release release version', (m) => { m.release.version = '1.0.0-beta.1'; }],
  ['a release version with build metadata', (m) => { m.release.version = '1.0.0+5'; }],
  ['a release version with a leading zero', (m) => { m.release.version = '1.01.0'; }],
  ['a release version that is too long', (m) => { m.release.version = `1.0.${'1'.repeat(48)}`; }],
  ['a pre-release catalog dependency releaseVersion', (m) => { m.catalogDependencies[0].releaseVersion = '1.4.0-rc.1'; }],
  ['a range as catalog dependency releaseVersion', (m) => { m.catalogDependencies[0].releaseVersion = '^1.4.0'; }],
]) {
  assert.equal(validateV8Manifest(await v8Manifest(edit)), false, `v8 manifest accepted ${description}`);
}

for (const [description, edit] of [
  ['no catalog dependencies', (m) => { delete m.catalogDependencies; m.dependencies = []; }],
  ['null catalog dependencies', (m) => { m.catalogDependencies = null; m.dependencies = []; }],
  ['a migrated catalog dependency', (m) => { m.catalogDependencies[0].releaseVersion = null; m.catalogDependencies[0].releaseFingerprint = null; }],
]) {
  const manifest = await v8Manifest(edit);
  assert.equal(validateV8Manifest(manifest), true, `v8 manifest rejected ${description}: ${ajv.errorsText(validateV8Manifest.errors)}`);
}

// Wire v8 calls a resource's address its key (#84). An old name is not a v8 member, and the key is required.
for (const [description, edit] of [
  ['catalog slug', (m) => { m.catalog.slug = m.catalog.key; delete m.catalog.key; }],
  ['resource entry slug', (m) => { m.resources[0].slug = m.resources[0].key; delete m.resources[0].key; }],
  ['dependency slug', (m) => { m.dependencies[0].slug = m.dependencies[0].key; delete m.dependencies[0].key; }],
  ['presentation iconAssetSlug', (m) => { m.catalog.presentation = { iconAssetSlug: 'logo' }; }],
  ['presentation imageAssetSlugs', (m) => { m.catalog.presentation = { imageAssetSlugs: ['hero'] }; }],
  ['attribute catalog', (m) => { m.catalog.attributes = [{ catalog: 'system', key: 'locale', value: 'nl-NL' }]; }],
]) {
  assert.equal(validateV8Manifest(await v8Manifest(edit)), false, `v8 manifest accepted the old name: ${description}`);
}
assert.equal(
  validateV8Manifest(await v8Manifest((m) => { m.catalog.presentation = { iconImageKey: 'logo', imageKeys: ['hero'] }; })),
  true,
  `v8 manifest rejected a presentation icon: ${ajv.errorsText(validateV8Manifest.errors)}`,
);

const v8Theme = await readJson('fixtures/v1/wire-v8/resources/theme/default.json');
const { key: themeKey, ...themeWithoutKey } = v8Theme.resource;
assert.equal(
  validateV8Detail({ ...v8Theme, resource: { ...themeWithoutKey, slug: themeKey } }),
  false,
  'v8 resource accepted slug in place of key',
);
const v8Template = (resource, templateModel) => ({
  schemaVersion: 8,
  resource: {
    type: 'template',
    key: 'letter',
    name: 'Letter',
    templateModel: {
      modelVersion: 1,
      root: 'root',
      nodes: { root: { id: 'root', type: 'root', slots: [] } },
      slots: {},
      themeRef: { type: 'inherit' },
      ...templateModel,
    },
    variants: [{ key: 'default' }],
    ...resource,
  },
});
assert.equal(validateV8Detail(v8Template({ themeKey: 'corporate' }, { themeRef: { type: 'override', themeKey: 'corporate' } })), true,
  `v8 template rejected themeKey: ${ajv.errorsText(validateV8Detail.errors)}`);
for (const [description, detail] of [
  ['template themeId', v8Template({ themeId: 'corporate' })],
  ['theme reference themeId', v8Template({}, { themeRef: { type: 'override', themeId: 'corporate' } })],
  ['variant slug', v8Template({ variants: [{ slug: 'default' }] })],
]) {
  assert.equal(validateV8Detail(detail), false, `v8 template accepted the old name: ${description}`);
}
// Wire v5 to v7 keep naming a theme reference's theme themeId.
const validateV7Detail = ajv.getSchema('https://epistola.app/schemas/resource-detail-v7.schema.json');
const v7Template = v8Template({ slug: 'letter', variants: [{ slug: 'default' }] }, { themeRef: { type: 'override', themeId: 'corporate' } });
v7Template.schemaVersion = 7;
delete v7Template.resource.key;
assert.equal(validateV7Detail(v7Template), true, `v7 template rejected themeId: ${ajv.errorsText(validateV7Detail.errors)}`);

// Stencils carry no version from v8; content references may omit registry and namespace.
const v8Stencil = await readJson('fixtures/v1/migrations/v7-to-v8/stencil-expected.json');
assert.equal(validateV8Detail({ ...v8Stencil, resource: { ...v8Stencil.resource, version: 3 } }), false, 'v8 stencil accepted a version');
const v7Stencil = await readJson('fixtures/v1/migrations/v7-to-v8/stencil-input.json');
assert.equal(validateV8Detail({ ...v7Stencil, schemaVersion: 8 }), false, 'v8 accepted a stencil with its v7 version');

const attribute = await readJson('fixtures/v1/conformance/catalog/missing-resource-reference/resources/attribute/locale.json');
const withBinding = (binding) => ({ ...attribute, resource: { ...attribute.resource, codeListBinding: { key: 'countries', ...binding } } });
for (const binding of [
  {},
  { catalogKey: 'shared' },
  { catalogKey: 'shared', namespace: 'acme-corp' },
  { catalogKey: 'shared', namespace: 'acme', registry: 'acme.com' },
]) {
  assert.equal(validateV8Detail(withBinding(binding)), true, `valid content reference was rejected: ${JSON.stringify(binding)}: ${ajv.errorsText(validateV8Detail.errors)}`);
}
for (const binding of [
  { namespace: 'Acme' },
  { namespace: 'acme.com' },
  { namespace: 'exchange.epistola.app/acme-corp' },
  { namespace: 'ab' },
  { namespace: 'acme', registry: '~' },
  { namespace: 'acme', registry: 'acme' },
  // A registry without a namespace names no catalog.
  { registry: 'acme.com' },
]) {
  assert.equal(validateV8Detail(withBinding(binding)), false, `invalid content reference was accepted: ${JSON.stringify(binding)}`);
}

// Wire v8 paths into the catalog are relative and below the manifest's directory (#95).
const acceptedPaths = [
  './resources/template/a.json',
  'resources/a.json',
  `bin/${'0'.repeat(64)}`,
  './a',
  'resources/~user/.hidden/a_b-c.json',
  'resources/a%20b.json',
  'a..b/c',
  '...',
];
const rejectedPaths = [
  'https://other.example/x.json',
  'file:resources/a.json',
  'C:resources/a.json',
  '/abs/x.json',
  '//host/x.json',
  'resources\\a.json',
  '../x.json',
  'resources/../../x.json',
  './../x.json',
  '././x.json',
  'resources/./a.json',
  'resources/..',
  'resources//a.json',
  'resources/',
  '',
  './',
  '%2e%2e/x.json',
  '%2E%2E/x.json',
  'resources%2fa.json',
  'resources%2Fa.json',
  'resources%5ca.json',
  'resources%5Ca.json',
  'resources/a b.json',
  'resources/a?b.json',
  'resources/%zz.json',
];
const zeroHash = '0'.repeat(64);
const withPath = {
  'manifest detailUrl': (path) => v8Manifest((m) => { m.resources[0].detailUrl = path; }),
  'image contentUrl': async (path) => ({ schemaVersion: 8, resource: { type: 'image', key: 'logo', name: 'Logo', mediaType: 'image/png', contentHash: zeroHash, contentUrl: path } }),
  'font face contentUrl': async (path) => ({
    schemaVersion: 8,
    resource: { type: 'font', key: 'inter', name: 'Inter', kind: 'sans', variants: [{ weight: 400, italic: false, mediaType: 'font/ttf', contentHash: zeroHash, contentUrl: path }] },
  }),
};
for (const [where, build] of Object.entries(withPath)) {
  const validate = where === 'manifest detailUrl' ? validateV8Manifest : validateV8Detail;
  for (const path of acceptedPaths) {
    assert.equal(validate(await build(path)), true, `v8 ${where} rejected ${JSON.stringify(path)}: ${ajv.errorsText(validate.errors)}`);
  }
  for (const path of rejectedPaths) {
    assert.equal(validate(await build(path)), false, `v8 ${where} accepted ${JSON.stringify(path)}`);
  }
}
// Earlier wire versions did not constrain these paths, and their schemas stay as they were.
assert.equal(
  validateV7Detail({ schemaVersion: 7, resource: { type: 'image', slug: 'logo', name: 'Logo', mediaType: 'image/png', contentHash: zeroHash, contentUrl: '../logo.png' } }),
  true,
  `v7 schema was tightened: ${ajv.errorsText(validateV7Detail.errors)}`,
);

// Wire v8 data examples have a key, a template may have a description, and a variant may carry
// namespaced metadata (#96, #97).
const v8Letter = await readJson('fixtures/v1/wire-v8/resources/template/letter.json');
const editLetter = (edit) => {
  const detail = structuredClone(v8Letter);
  edit(detail.resource);
  return detail;
};
for (const [description, edit] of [
  ['an example without a key', (r) => { delete r.dataExamples[0].key; }],
  ['an uppercase example key', (r) => { r.dataExamples[0].key = 'Standard'; }],
  ['a 65-character example key', (r) => { r.dataExamples[0].key = 'a'.repeat(65); }],
  ['a 2001-character description', (r) => { r.description = 'a'.repeat(2001); }],
  ['empty metadata', (r) => { r.variants[0].metadata = {}; }],
  ['a namespace that is not reverse-DNS', (r) => { r.variants[0].metadata.suite = {}; }],
  ['an uppercase namespace', (r) => { r.variants[0].metadata['App.epistola.suite'] = {}; }],
  ['a 101-character namespace', (r) => { r.variants[0].metadata[`a.${'b'.repeat(99)}`] = {}; }],
  ['a namespace whose value is not an object', (r) => { r.variants[0].metadata['com.example.x'] = 6; }],
  ['17 namespaces', (r) => { for (let i = 1; i <= 16; i++) r.variants[0].metadata[`com.example.n${i}`] = {}; }],
]) {
  assert.equal(validateV8Detail(editLetter(edit)), false, `v8 template accepted ${description}`);
}
for (const [description, edit] of [
  ['no description', (r) => { delete r.description; }],
  ['a null description', (r) => { r.description = null; }],
  ['a 2000-character description', (r) => { r.description = 'a'.repeat(2000); }],
  ['no metadata', (r) => { delete r.variants[0].metadata; }],
  ['16 namespaces', (r) => { for (let i = 1; i <= 15; i++) r.variants[0].metadata[`com.example.n${i}`] = {}; }],
  ['a namespace of any content', (r) => { r.variants[0].metadata['com.example.x'] = { list: [1, 'two', null, { x: false }], n: 1.5 }; }],
  ['an example key with a leading digit', (r) => { r.dataExamples[0].key = '1e-brief'; }],
]) {
  assert.equal(validateV8Detail(editLetter(edit)), true, `v8 template rejected ${description}: ${ajv.errorsText(validateV8Detail.errors)}`);
}
// The v7 migration fixture's derived keys are valid v8 keys.
const exampleKeys = await readJson('fixtures/v1/migrations/v7-to-v8/data-example-keys.json');
for (const { keys } of exampleKeys.cases) {
  for (const key of keys) {
    assert.equal(validateV8Detail(editLetter((r) => { r.dataExamples[0].key = key; })), true, `derived example key is not a valid v8 key: ${key}`);
  }
}
