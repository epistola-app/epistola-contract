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

const presetSchema = manifestSchema.$defs.CatalogPresetDeclaration.properties;
assert.equal(catalog.CATALOG_PRESET_KEY_PATTERN, presetSchema.key.pattern, 'npm and schema preset key patterns must agree');
assert.equal(catalog.MAX_CATALOG_PRESET_KEY_LENGTH, presetSchema.key.maxLength, 'npm and schema preset key lengths must agree');
assert.equal(catalog.MAX_CATALOG_PRESET_NAME_LENGTH, presetSchema.name.maxLength, 'npm and schema preset name lengths must agree');
assert.equal(
  catalog.MAX_CATALOG_PRESET_DESCRIPTION_LENGTH,
  presetSchema.description.maxLength,
  'npm and schema preset description lengths must agree',
);
const defaultThemeKey = manifestSchema.$defs.CatalogThemeRef.properties.key;
assert.equal(catalog.CATALOG_RESOURCE_KEY_PATTERN, defaultThemeKey.pattern, 'a default theme names a theme by its resource key');

const namespaceSchema = manifestSchema.$defs.CatalogNamespace;
assert.equal(catalog.CATALOG_NAMESPACE_PATTERN, namespaceSchema.pattern, 'npm and schema namespace patterns must agree');
assert.equal(catalog.MIN_CATALOG_NAMESPACE_LENGTH, namespaceSchema.minLength, 'npm and schema namespace minimum lengths must agree');
assert.equal(catalog.MAX_CATALOG_NAMESPACE_LENGTH, namespaceSchema.maxLength, 'npm and schema namespace maximum lengths must agree');
const registrySchema = manifestSchema.$defs.CatalogRegistry;
assert.equal(catalog.CATALOG_REGISTRY_PATTERN, registrySchema.pattern, 'npm and schema registry patterns must agree');
assert.equal(catalog.MAX_CATALOG_REGISTRY_LENGTH, registrySchema.maxLength, 'npm and schema registry maximum lengths must agree');
assert.equal(
  catalog.MAX_CATALOG_DEPENDENCY_VERSION_LENGTH,
  undefined,
  'a catalog dependency pins an exact release: the npm package exports no version selector limit',
);
assert.equal(
  manifestSchema.$defs.CatalogDependency.properties.version,
  undefined,
  'a catalog dependency carries no version selector',
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

// One release number rule wherever wire v8 names a release: the API's ReleaseVersion, or a patch release.
const releaseNumber = new RegExp(catalog.CATALOG_RELEASE_VERSION_PATTERN);
for (const valid of ['0.0.0', '1.4.0', '1.3.1-gemeente-x.1', '2.0.0-abc.12']) assert.ok(releaseNumber.test(valid), `${valid} is a release number`);
for (const invalid of ['1.4', '1.4.0-rc.1', '1.4.0-beta', '1.4.0-gemeente-x.0', '1.4.0-gemeente-x.01', '1.4.0+5', '1.4.0-Gemeente.1', '1.4.0-gemeente-x.1+5']) {
  assert.ok(!releaseNumber.test(invalid), `${invalid} is not a release number`);
}
assert.ok(catalog.CATALOG_RELEASE_VERSION_PATTERN.startsWith(catalog.CATALOG_RELEASE_VERSION_CORE_PATTERN.slice(0, -1)), 'a release number is its core and an optional patch label');
assert.equal(catalog.MAX_CATALOG_RELEASE_VERSION_LENGTH, 128, 'a release number is at most 128 characters');
assert.ok(releaseNumber.test(`0.0.1-${'a'.repeat(catalog.MAX_CATALOG_NAMESPACE_LENGTH)}.1`), 'the longest namespace can label a patch release');
for (const [where, property] of [
  ['release.version', manifestSchema.$defs.ReleaseInfo.properties.version],
  ['catalog dependency releaseVersion', manifestSchema.$defs.CatalogDependency.properties.releaseVersion],
]) {
  assert.equal(catalog.CATALOG_RELEASE_VERSION_PATTERN, property.pattern, `npm and schema ${where} patterns must agree`);
  assert.equal(catalog.MAX_CATALOG_RELEASE_VERSION_LENGTH, property.maxLength, `npm and schema ${where} maximum lengths must agree`);
}
// One release id rule, the API's CatalogReleaseId.
const releaseIdSchema = manifestSchema.$defs.ReleaseInfo.properties.releaseId;
assert.equal(catalog.CATALOG_RELEASE_ID_PATTERN, releaseIdSchema.pattern, 'npm and schema release.releaseId patterns must agree');
assert.equal(catalog.MIN_CATALOG_RELEASE_ID_LENGTH, releaseIdSchema.minLength, 'npm and schema release.releaseId minimum lengths must agree');
assert.equal(catalog.MAX_CATALOG_RELEASE_ID_LENGTH, releaseIdSchema.maxLength, 'npm and schema release.releaseId maximum lengths must agree');
// A release's base (wire v8): identity only, its version and fingerprint by the release's own rules.
const releaseBaseSchema = manifestSchema.$defs.ReleaseBase;
assert.equal(catalog.CATALOG_RELEASE_VERSION_PATTERN, releaseBaseSchema.properties.version.pattern, 'npm and schema release.base.version patterns must agree');
assert.equal(catalog.MAX_CATALOG_RELEASE_VERSION_LENGTH, releaseBaseSchema.properties.version.maxLength, 'npm and schema release.base.version maximum lengths must agree');
assert.equal(catalog.CATALOG_RELEASE_FINGERPRINT_PATTERN, releaseBaseSchema.properties.fingerprint.pattern, 'npm and schema release.base.fingerprint patterns must agree');
assert.deepEqual(Object.keys(releaseBaseSchema.properties), ['version', 'fingerprint'], 'a release base is identity only');
// The API defines each key and number rule once, by name, in identifiers.yaml. Those the catalog
// archive shares must be the catalog's own.
const apiIdentifiers = await readFile(new URL('../../api/components/schemas/identifiers.yaml', import.meta.url), 'utf8');
const apiRule = (name) => {
  const start = apiIdentifiers.indexOf(`\n${name}:\n`);
  assert.notEqual(start, -1, `identifiers.yaml defines no ${name}`);
  const end = apiIdentifiers.indexOf('\n\n', start + 1);
  const rule = apiIdentifiers.slice(start, end === -1 ? undefined : end);
  const number = (key) => {
    const value = rule.match(new RegExp(`\\n  ${key}: (\\d+)`))?.[1];
    return value === undefined ? undefined : Number(value);
  };
  return { pattern: rule.match(/\n  pattern: '([^']+)'/)?.[1], minLength: number('minLength'), maxLength: number('maxLength') };
};
for (const [name, pattern, minLength, maxLength] of [
  // The API reports releases by their plain number; a patch release's label is the archive's alone for now.
  ['ReleaseVersion', catalog.CATALOG_RELEASE_VERSION_CORE_PATTERN, undefined, catalog.MAX_CATALOG_RELEASE_VERSION_LENGTH],
  ['ResourceKey', catalog.CATALOG_RESOURCE_KEY_PATTERN, catalog.MIN_CATALOG_RESOURCE_KEY_LENGTH, catalog.MAX_CATALOG_RESOURCE_KEY_LENGTH],
  ['Namespace', catalog.CATALOG_NAMESPACE_PATTERN, catalog.MIN_CATALOG_NAMESPACE_LENGTH, catalog.MAX_CATALOG_NAMESPACE_LENGTH],
  ['Registry', catalog.CATALOG_REGISTRY_PATTERN, undefined, catalog.MAX_CATALOG_REGISTRY_LENGTH],
  ['Fingerprint', catalog.CATALOG_RELEASE_FINGERPRINT_PATTERN, undefined, undefined],
  ['CatalogReleaseId', catalog.CATALOG_RELEASE_ID_PATTERN, catalog.MIN_CATALOG_RELEASE_ID_LENGTH, catalog.MAX_CATALOG_RELEASE_ID_LENGTH],
]) {
  assert.deepEqual(apiRule(name), { pattern, minLength, maxLength }, `the npm rule and the API's ${name} must agree`);
}

const detailSchema = JSON.parse(
  await readFile(new URL('../schemas/resource-detail-v8.schema.json', import.meta.url), 'utf8'),
);
const bindingSchema = detailSchema.$defs.CodeListBindingRef.properties;
const templateSchema = detailSchema.$defs.TemplateResource.properties;
const sharedTemplateSchema = JSON.parse(
  await readFile(new URL('../schemas/template-shared.schema.json', import.meta.url), 'utf8'),
);
// Wire v8 names another catalog by an alias (contract ADR 0003), one rule wherever it does, and never by
// registry or namespace in a reference: those are the declaration's.
for (const [where, alias] of [
  ['catalog dependency alias', manifestSchema.$defs.CatalogDependency.properties.alias],
  ['resource dependency catalogKey', manifestSchema.$defs.CatalogDependencyRef.properties.catalogKey],
  ['default theme catalogKey', manifestSchema.$defs.CatalogThemeRef.properties.catalogKey],
  ['template themeCatalogKey', templateSchema.themeCatalogKey],
  ['code list binding catalogKey', bindingSchema.catalogKey],
  ['theme reference catalogKey', sharedTemplateSchema.$defs.ThemeRef.oneOf[1].properties.catalogKey],
]) {
  assert.equal(catalog.CATALOG_ALIAS_PATTERN, alias.pattern, `npm and schema ${where} patterns must agree`);
  assert.equal(catalog.MIN_CATALOG_ALIAS_LENGTH, alias.minLength, `npm and schema ${where} minimum lengths must agree`);
  assert.equal(catalog.MAX_CATALOG_ALIAS_LENGTH, alias.maxLength, `npm and schema ${where} maximum lengths must agree`);
}
assert.ok(manifestSchema.$defs.CatalogDependency.required.includes('alias'), 'a catalog dependency is declared under an alias');
for (const [where, properties] of [
  ['resource dependency', manifestSchema.$defs.CatalogDependencyRef.properties],
  ['default theme', manifestSchema.$defs.CatalogThemeRef.properties],
  ['code list binding', bindingSchema],
  ['theme reference', sharedTemplateSchema.$defs.ThemeRef.oneOf[1].properties],
]) {
  assert.equal(properties.registry, undefined, `a ${where} names no registry`);
  assert.equal(properties.namespace, undefined, `a ${where} names no namespace`);
}
assert.equal(templateSchema.themeRegistry, undefined, 'a template names its theme\'s catalog by alias alone');
assert.equal(templateSchema.themeNamespace, undefined, 'a template names its theme\'s catalog by alias alone');

// Wire v8 (#95, #96, #97): paths into the catalog, data example keys, variant metadata, template descriptions.
const manifestPaths = [['manifest detailUrl', manifestSchema.$defs.ResourceEntry.properties.detailUrl]];
const detailPaths = [
  ['image contentUrl', detailSchema.$defs.ImageResource.properties.contentUrl],
  ['font face contentUrl', detailSchema.$defs.FontVariantEntry.properties.contentUrl],
];
for (const [where, property] of [...manifestPaths, ...detailPaths]) {
  assert.equal(catalog.CATALOG_ARCHIVE_PATH_PATTERN, property.pattern, `npm and schema ${where} patterns must agree`);
}
// One rule for every resource key, wherever wire v8 names one.
const sharedSchema = sharedTemplateSchema;
const detailDefs = detailSchema.$defs;
const manifestDefs = manifestSchema.$defs;
const resourceKeys = [
  ...['TemplateResource', 'ThemeResource', 'StencilResource', 'AttributeResource', 'ImageResource', 'CodeListResource', 'FontResource']
    .map((type) => [`${type} key`, detailDefs[type].properties.key]),
  ['template themeKey', detailDefs.TemplateResource.properties.themeKey],
  ['code list binding key', detailDefs.CodeListBindingRef.properties.key],
  ['data example key', detailDefs.DataExampleEntry.properties.key],
  ['variant key', detailDefs.VariantEntry.properties.key],
  ['manifest resource entry key', manifestDefs.ResourceEntry.properties.key],
  ['dependency key', manifestDefs.CatalogDependencyRef.properties.key],
  ['attribute assignment key', manifestDefs.AttributeAssignment.properties.key],
  ['presentation iconImageKey', manifestDefs.CatalogPresentation.properties.iconImageKey],
  ['presentation imageKeys', manifestDefs.CatalogPresentation.properties.imageKeys.items],
  ['theme reference themeKey', sharedSchema.$defs.ThemeRef.oneOf[1].properties.themeKey],
];
// A variant's attribute key may be qualified by a catalog key (`system.locale`), so it has a rule of its own.
const variantAttributeKey = detailDefs.VariantEntry.properties.attributes.oneOf[0].propertyNames;
assert.equal(catalog.CATALOG_ATTRIBUTE_KEY_PATTERN, variantAttributeKey.pattern, 'npm and schema variant attribute key patterns must agree');
assert.equal(catalog.MIN_CATALOG_RESOURCE_KEY_LENGTH, variantAttributeKey.minLength, 'npm and schema variant attribute key minimum lengths must agree');
assert.equal(catalog.MAX_CATALOG_ATTRIBUTE_KEY_LENGTH, variantAttributeKey.maxLength, 'npm and schema variant attribute key maximum lengths must agree');
for (const [where, key] of resourceKeys) {
  assert.equal(catalog.CATALOG_RESOURCE_KEY_PATTERN, key.pattern, `npm and schema ${where} patterns must agree`);
  assert.equal(catalog.MIN_CATALOG_RESOURCE_KEY_LENGTH, key.minLength, `npm and schema ${where} minimum lengths must agree`);
  assert.equal(catalog.MAX_CATALOG_RESOURCE_KEY_LENGTH, key.maxLength, `npm and schema ${where} maximum lengths must agree`);
}
assert.equal(
  catalog.MAX_TEMPLATE_DESCRIPTION_LENGTH,
  templateSchema.description.maxLength,
  'npm and schema template description limits must agree',
);
const metadataSchema = detailSchema.$defs.VariantEntry.properties.metadata;
assert.deepEqual(
  Object.keys(metadataSchema.patternProperties),
  [catalog.CATALOG_VARIANT_METADATA_NAMESPACE_PATTERN],
  'npm and schema variant metadata namespace patterns must agree',
);
assert.equal(
  catalog.MAX_CATALOG_VARIANT_METADATA_NAMESPACE_LENGTH,
  metadataSchema.propertyNames.maxLength,
  'npm and schema variant metadata namespace lengths must agree',
);
assert.equal(
  catalog.MAX_CATALOG_VARIANT_METADATA_NAMESPACES,
  metadataSchema.maxProperties,
  'npm and schema variant metadata namespace counts must agree',
);
assert.equal(catalog.MAX_CATALOG_VARIANT_METADATA_BYTES, 8 * 1024, 'variant metadata is limited to 8 KiB serialized');
// A release's metadata follows the variant metadata rules.
const releaseMetadataSchema = manifestSchema.$defs.ReleaseInfo.properties.metadata;
for (const member of ['type', 'patternProperties', 'propertyNames', 'additionalProperties', 'minProperties', 'maxProperties']) {
  assert.deepEqual(releaseMetadataSchema[member], metadataSchema[member], `release and variant metadata ${member} must agree`);
}

const registry = await import(`${packageName}/registry`);
assert.ok(registry.componentRegistry, 'component registry must be exported');
assert.ok(registry.styleRegistry, 'style registry must be exported');

// #97: `"all"` leaves out the opt-in properties, which apply only where they are listed by key.
assert.deepEqual([...registry.optInStyleKeys], ['minHeight', 'verticalAlign'], 'opt-in style keys follow includedInAll');
const componentByType = new Map(registry.componentRegistry.components.map((component) => [component.type, component]));
const applicable = (type, key) => registry.isStyleApplicable(key, componentByType.get(type).applicableStyles);
assert.equal(applicable('text', 'minHeight'), false, '"all" must not include minHeight');
assert.equal(applicable('text', 'verticalAlign'), false, '"all" must not include verticalAlign');
assert.equal(applicable('text', 'paddingTop'), true, '"all" still includes the general styles');
assert.equal(applicable('container', 'minHeight'), true, 'the container accepts minHeight');
assert.equal(applicable('container', 'borderRadius'), true, 'the container keeps what "all" gave it');
assert.equal(applicable('datatable-column', 'verticalAlign'), true, 'a datatable column accepts verticalAlign');
const cellStyles = componentByType.get('table').styleMaps.find((styleMap) => styleMap.key === 'cellStyles');
const columnStyles = componentByType.get('columns').styleMaps.find((styleMap) => styleMap.key === 'columnStyles');
assert.equal(cellStyles.shape, 'keyed');
assert.ok(new RegExp(cellStyles.keyPattern).test('10-2') && !new RegExp(cellStyles.keyPattern).test('a-2'), 'cell keys are <row>-<col>');
assert.equal(columnStyles.shape, 'indexed');
for (const styleMap of [cellStyles, columnStyles]) {
  assert.equal(registry.isStyleApplicable('borderBottom', styleMap.applicableStyles), true, `${styleMap.key} accepts the border keys`);
  assert.equal(registry.isStyleApplicable('verticalAlign', styleMap.applicableStyles), true, `${styleMap.key} accepts verticalAlign`);
  assert.equal(registry.isStyleApplicable('fontSize', styleMap.applicableStyles), false, `${styleMap.key} does not accept fontSize`);
}

await assert.rejects(
  import(`${packageName}/generated/theme`),
  (error) => error?.code === 'ERR_PACKAGE_PATH_NOT_EXPORTED',
  'generator output must not be a public npm entry point',
);

// Wire v8: the releases a catalog archive carries, held to the published fixture the Kotlin library is held to.
const carried = JSON.parse(
  await readFile(new URL('../fixtures/v1/carried-releases/carried-release-cases.json', import.meta.url), 'utf8'),
);
assert.equal(catalog.CATALOG_DEPENDENCY_ARCHIVE_PATH_PATTERN, carried.pathPattern, 'npm and fixture carried-release path patterns must agree');
assert.equal(catalog.MAX_CATALOG_ARCHIVE_DEPENDENCIES, carried.defaultPolicy.maxDependencies, 'npm and fixture carried-release limits must agree');
for (const path of carried.pathCases.valid) {
  assert.ok(catalog.parseCatalogDependencyArchivePath(path), `${path} is a carried-release path`);
  assert.equal(catalog.catalogDependencyArchivePath(catalog.parseCatalogDependencyArchivePath(path)), path);
}
for (const path of carried.pathCases.invalid) {
  assert.equal(catalog.parseCatalogDependencyArchivePath(path), undefined, `${path} is not a carried-release path`);
}
