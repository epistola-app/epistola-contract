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

// One release number rule, the API's ReleaseVersion, wherever wire v8 names a release.
for (const [where, property] of [
  ['release.version', manifestSchema.$defs.ReleaseInfo.properties.version],
  ['catalog dependency releaseVersion', manifestSchema.$defs.CatalogDependency.properties.releaseVersion],
]) {
  assert.equal(catalog.CATALOG_RELEASE_VERSION_PATTERN, property.pattern, `npm and schema ${where} patterns must agree`);
  assert.equal(catalog.MAX_CATALOG_RELEASE_VERSION_LENGTH, property.maxLength, `npm and schema ${where} maximum lengths must agree`);
}
const apiCatalogSchemas = await readFile(new URL('../../api/components/schemas/catalogs.yaml', import.meta.url), 'utf8');
const apiReleaseVersion = apiCatalogSchemas.slice(apiCatalogSchemas.indexOf('\nReleaseVersion:\n'));
assert.equal(
  apiReleaseVersion.match(/\n  pattern: '([^']+)'/)?.[1],
  catalog.CATALOG_RELEASE_VERSION_PATTERN,
  "npm release number pattern and the API's ReleaseVersion must agree",
);
assert.equal(
  Number(apiReleaseVersion.match(/\n  maxLength: (\d+)/)?.[1]),
  catalog.MAX_CATALOG_RELEASE_VERSION_LENGTH,
  "npm release number limit and the API's ReleaseVersion must agree",
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
const sharedSchema = JSON.parse(
  await readFile(new URL('../schemas/template-shared.schema.json', import.meta.url), 'utf8'),
);
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
