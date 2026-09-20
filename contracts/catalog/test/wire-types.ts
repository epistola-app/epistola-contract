// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import type {
  ImageResource,
  AttributeAssignment,
  CatalogInfo,
  CatalogKeyword,
  CatalogLicense,
  CatalogManifest,
  CatalogPresentation,
  ResourceDetail,
  TemplateResource,
} from '../ts/index.js'

const manifest: CatalogManifest = {
  schemaVersion: 7,
  catalog: {
    slug: 'fixture',
    name: 'Fixture',
    attributes: [{ catalog: 'system', key: 'locale', value: 'nl-NL' }],
    keywords: ['documents'],
    license: { name: 'Creative Commons Attribution 4.0 International', spdxExpression: 'CC-BY-4.0' },
  },
  publisher: { name: 'Epistola' },
  release: { version: '1.0.0' },
  resources: [],
}

// The schema bounds keywords at 20; the type must stay a plain array rather than a tuple union.
const authoredKeywords: string[] = ['documents', 'getting-started']
const keywordCatalog: CatalogInfo = { slug: 'fixture', name: 'Fixture', keywords: authoredKeywords }
const keyword: CatalogKeyword = 'documents'

const locale: AttributeAssignment = { catalog: 'system', key: 'locale', value: 'nl-NL' }
const license: CatalogLicense = { name: 'Proprietary', url: 'https://example.test/license' }

const presentation: CatalogPresentation = { iconAssetSlug: 'logo', imageAssetSlugs: ['hero'] }

const asset: ImageResource = {
  type: 'image',
  slug: 'logo',
  name: 'Logo',
  mediaType: 'image/svg+xml',
  contentUrl: './resources/asset/logo.svg',
  contentHash: '0000000000000000000000000000000000000000000000000000000000000000',
}

const detail: ResourceDetail = { schemaVersion: 7, resource: asset }
const acceptsTemplate = (resource: TemplateResource): TemplateResource => resource
const pdfaEnabled: TemplateResource['pdfaEnabled'] = false

void manifest
void keywordCatalog
void keyword
void locale
void license
void presentation
void detail
void acceptsTemplate
void pdfaEnabled
