// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import type {
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
} from '../ts/index.js'
import { CATALOG_NAMESPACE_PATTERN, CATALOG_NAMESPACE_REFERENCE_PATTERN } from '../ts/index.js'

const manifest: CatalogManifest = {
  schemaVersion: 8,
  catalog: {
    namespace: 'exchange.epistola.app/epistola',
    slug: 'fixture',
    name: 'Fixture',
    attributes: [{ catalog: 'system', key: 'locale', value: 'nl-NL' }],
    keywords: ['documents'],
    license: { name: 'Creative Commons Attribution 4.0 International', spdxExpression: 'CC-BY-4.0' },
  },
  publisher: { name: 'Epistola' },
  release: { version: '1.0.0' },
  resources: [],
  dependencies: [{ type: 'theme', namespace: 'acme.com', catalogKey: 'shared', slug: 'corporate' }],
  catalogDependencies: [{ namespace: 'acme.com', catalogKey: 'shared', version: '^1.2.0', releaseVersion: '1.4.0' }],
}

// A catalog migrated from wire v7 records no release for its declared dependencies.
const migratedDependency: CatalogDependency = { namespace: 'acme.com', catalogKey: 'shared', version: '*', releaseVersion: null }

// A stencil carries no version from wire v8.
const stencil: Omit<StencilResource, 'content'> = { type: 'stencil', slug: 'letter', name: 'Letter' }
// @ts-expect-error a stencil version is not part of wire v8
const versionedStencil: Omit<StencilResource, 'content'> = { type: 'stencil', slug: 'letter', name: 'Letter', version: 3 }

// Content may name a namespace in the short form, or omit it for the owning catalog's.
const themeRef: ThemeRefOverride = { type: 'override', themeId: 'corporate', catalogKey: 'shared', namespace: 'acme-corp' }
const namespacePatterns: string[] = [CATALOG_NAMESPACE_PATTERN, CATALOG_NAMESPACE_REFERENCE_PATTERN]

// The schema bounds keywords at 20; the type must stay a plain array rather than a tuple union.
const authoredKeywords: string[] = ['documents', 'getting-started']
const keywordCatalog: CatalogInfo = { namespace: 'acme.com', slug: 'fixture', name: 'Fixture', keywords: authoredKeywords }
const keyword: CatalogKeyword = 'documents'

const locale: AttributeAssignment = { catalog: 'system', key: 'locale', value: 'nl-NL' }
const license: CatalogLicense = { name: 'Proprietary', url: 'https://example.test/license' }

const presentation: CatalogPresentation = { iconAssetSlug: 'logo', imageAssetSlugs: ['hero'] }

const asset: ImageResource = {
  type: 'image',
  slug: 'logo',
  name: 'Logo',
  mediaType: 'image/svg+xml',
  contentHash: '0000000000000000000000000000000000000000000000000000000000000000',
}

const detail: ResourceDetail = { schemaVersion: 8, resource: asset }
const acceptsTemplate = (resource: TemplateResource): TemplateResource => resource
const pdfaEnabled: TemplateResource['pdfaEnabled'] = false

void manifest
void migratedDependency
void stencil
void versionedStencil
void themeRef
void namespacePatterns
void keywordCatalog
void keyword
void locale
void license
void presentation
void detail
void acceptsTemplate
void pdfaEnabled
