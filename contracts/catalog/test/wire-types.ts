// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import type {
  DataExampleEntry,
  ImageResource,
  AttributeAssignment,
  CatalogDependency,
  CatalogInfo,
  CatalogKeyword,
  CatalogLicense,
  CatalogManifest,
  CatalogPresentation,
  ResourceDetail,
  StencilResource,
  TemplateResource,
  ThemeRefOverride,
  VariantEntry,
} from '../ts/index.js'
import type { CatalogNamespace, CatalogRegistry } from '../ts/index.js'
import { CATALOG_NAMESPACE_PATTERN, CATALOG_REGISTRY_PATTERN, CATALOG_RELEASE_FINGERPRINT_PATTERN } from '../ts/index.js'
import {
  CATALOG_ARCHIVE_PATH_PATTERN,
  CATALOG_RESOURCE_KEY_PATTERN,
  CATALOG_VARIANT_METADATA_NAMESPACE_PATTERN,
  MAX_CATALOG_RESOURCE_KEY_LENGTH,
  MAX_CATALOG_VARIANT_METADATA_BYTES,
  MAX_CATALOG_VARIANT_METADATA_NAMESPACE_LENGTH,
  MAX_CATALOG_VARIANT_METADATA_NAMESPACES,
  MAX_TEMPLATE_DESCRIPTION_LENGTH,
  MIN_CATALOG_RESOURCE_KEY_LENGTH,
} from '../ts/index.js'

const manifest: CatalogManifest = {
  schemaVersion: 8,
  catalog: {
    registry: 'exchange.epistola.app',
    namespace: 'epistola',
    key: 'fixture',
    name: 'Fixture',
    attributes: [{ catalogKey: 'system', key: 'locale', value: 'nl-NL' }],
    keywords: ['documents'],
    license: { name: 'Creative Commons Attribution 4.0 International', spdxExpression: 'CC-BY-4.0' },
  },
  publisher: { name: 'Epistola' },
  release: { version: '1.0.0' },
  resources: [],
  dependencies: [{ type: 'theme', registry: 'acme.com', namespace: 'acme', catalogKey: 'shared', key: 'corporate' }],
  catalogDependencies: [{ registry: 'acme.com', namespace: 'acme', catalogKey: 'shared', version: '^1.2.0', releaseVersion: '1.4.0', releaseFingerprint: 'v5:69e540ae7bea571f81ff51919c8fd7d4dfca8d792da9fcc5a559034218feaa42' }],
}

// Registry and namespace are separate, required members wherever the manifest names a catalog.
// @ts-expect-error a catalog dependency names its registry
const unregisteredDependency: CatalogDependency = { namespace: 'acme', catalogKey: 'shared', version: '*', releaseVersion: null, releaseFingerprint: null }
const registry: CatalogRegistry = 'exchange.epistola.app'
const namespace: CatalogNamespace = 'acme-corp'

// A catalog migrated from wire v7 records no release, and so no release fingerprint, for its
// declared dependencies.
const migratedDependency: CatalogDependency = { registry: 'acme.com', namespace: 'acme', catalogKey: 'shared', version: '*', releaseVersion: null, releaseFingerprint: null }
// @ts-expect-error a catalog dependency always writes its release fingerprint member
const unfingerprintedDependency: CatalogDependency = { registry: 'acme.com', namespace: 'acme', catalogKey: 'shared', version: '*', releaseVersion: null }
const fingerprintPattern: string = CATALOG_RELEASE_FINGERPRINT_PATTERN

// A stencil carries no version from wire v8.
const stencil: Omit<StencilResource, 'content'> = { type: 'stencil', key: 'letter', name: 'Letter' }
// @ts-expect-error a stencil version is not part of wire v8
const versionedStencil: Omit<StencilResource, 'content'> = { type: 'stencil', key: 'letter', name: 'Letter', version: 3 }

// Content may omit the registry and namespace, each meaning the owning catalog's.
const themeRef: ThemeRefOverride = { type: 'override', themeKey: 'corporate', catalogKey: 'shared', namespace: 'acme-corp' }
const qualifiedThemeRef: ThemeRefOverride = { type: 'override', themeKey: 'corporate', catalogKey: 'shared', namespace: 'acme', registry: 'acme.com' }
const identityPatterns: string[] = [CATALOG_NAMESPACE_PATTERN, CATALOG_REGISTRY_PATTERN]

// Wire v8 calls a resource's address its key (#84); the old names are not part of it.
// @ts-expect-error a theme reference names its theme by themeKey, not themeId
const legacyThemeRef: ThemeRefOverride = { type: 'override', themeId: 'corporate' }
// @ts-expect-error a resource is addressed by key, not slug
const legacyStencil: Omit<StencilResource, 'content'> = { type: 'stencil', slug: 'letter', name: 'Letter' }
// @ts-expect-error the catalog icon is iconImageKey, not iconAssetSlug
const legacyPresentation: CatalogPresentation = { iconAssetSlug: 'logo' }
// @ts-expect-error an attribute assignment names its catalog by catalogKey, not catalog
const legacyAttribute: AttributeAssignment = { catalog: 'system', key: 'locale', value: 'nl-NL' }

// The schema bounds keywords at 20; the type must stay a plain array rather than a tuple union.
const authoredKeywords: string[] = ['documents', 'getting-started']
const keywordCatalog: CatalogInfo = { registry: 'acme.com', namespace: 'acme', key: 'fixture', name: 'Fixture', keywords: authoredKeywords }
const keyword: CatalogKeyword = 'documents'

const locale: AttributeAssignment = { catalogKey: 'system', key: 'locale', value: 'nl-NL' }
const license: CatalogLicense = { name: 'Proprietary', url: 'https://example.test/license' }

const presentation: CatalogPresentation = { iconImageKey: 'logo', imageKeys: ['hero'] }

const asset: ImageResource = {
  type: 'image',
  key: 'logo',
  name: 'Logo',
  mediaType: 'image/svg+xml',
  contentHash: '0000000000000000000000000000000000000000000000000000000000000000',
}

const detail: ResourceDetail = { schemaVersion: 8, resource: asset }
const acceptsTemplate = (resource: TemplateResource): TemplateResource => resource
const pdfaEnabled: TemplateResource['pdfaEnabled'] = false

// A data example has a key from wire v8; its name stays the display name.
const example: DataExampleEntry = { key: 'standard-letter', name: 'Standard letter', data: { name: 'Jan' } }
// @ts-expect-error a wire-v8 data example has a key
const unkeyedExample: DataExampleEntry = { name: 'Standard letter', data: {} }

// A variant may carry producer-specific metadata by reverse-DNS namespace (#96), each an object.
const variant: VariantEntry = { key: 'english', metadata: { 'app.epistola.suite': { renderingDefaultsVersion: 6 } } }
const plainVariant: VariantEntry = { key: 'english' }
// @ts-expect-error a namespace holds an object
const scalarMetadata: VariantEntry = { key: 'english', metadata: { 'app.epistola.suite': 6 } }

// A template may describe itself in plain text (#97).
const description: TemplateResource['description'] = 'A one-page letter.'
const noDescription: TemplateResource['description'] = null

const v8Rules: Array<string | number> = [
  CATALOG_ARCHIVE_PATH_PATTERN,
  CATALOG_RESOURCE_KEY_PATTERN,
  MIN_CATALOG_RESOURCE_KEY_LENGTH,
  MAX_CATALOG_RESOURCE_KEY_LENGTH,
  MAX_TEMPLATE_DESCRIPTION_LENGTH,
  CATALOG_VARIANT_METADATA_NAMESPACE_PATTERN,
  MAX_CATALOG_VARIANT_METADATA_NAMESPACE_LENGTH,
  MAX_CATALOG_VARIANT_METADATA_NAMESPACES,
  MAX_CATALOG_VARIANT_METADATA_BYTES,
]

void example
void unkeyedExample
void variant
void plainVariant
void scalarMetadata
void description
void noDescription
void v8Rules
void manifest
void migratedDependency
void unfingerprintedDependency
void fingerprintPattern
void stencil
void versionedStencil
void unregisteredDependency
void registry
void namespace
void themeRef
void qualifiedThemeRef
void legacyThemeRef
void legacyStencil
void legacyPresentation
void legacyAttribute
void identityPatterns
void keywordCatalog
void keyword
void locale
void license
void presentation
void detail
void acceptsTemplate
void pdfaEnabled
