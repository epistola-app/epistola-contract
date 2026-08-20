// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import type {
  AssetResource,
  AttributeAssignment,
  CatalogLicense,
  CatalogManifest,
  CatalogPresentation,
  ResourceDetail,
  TemplateResource,
} from '../ts/index.js'

const manifest: CatalogManifest = {
  schemaVersion: 6,
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

const locale: AttributeAssignment = { catalog: 'system', key: 'locale', value: 'nl-NL' }
const license: CatalogLicense = { name: 'Proprietary', url: 'https://example.test/license' }

const presentation: CatalogPresentation = { iconAssetSlug: 'logo', imageAssetSlugs: ['hero'] }

const asset: AssetResource = {
  type: 'asset',
  slug: 'logo',
  name: 'Logo',
  mediaType: 'image/svg+xml',
  contentUrl: './resources/asset/logo.svg',
}

const detail: ResourceDetail = { schemaVersion: 6, resource: asset }
const acceptsTemplate = (resource: TemplateResource): TemplateResource => resource
const pdfaEnabled: TemplateResource['pdfaEnabled'] = false

void manifest
void locale
void license
void presentation
void detail
void acceptsTemplate
void pdfaEnabled
