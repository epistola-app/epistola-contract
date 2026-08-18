// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import type {
  AssetResource,
  AttributeAssignment,
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
  },
  publisher: { name: 'Epistola' },
  release: { version: '1.0.0' },
  resources: [],
}

const locale: AttributeAssignment = { catalog: 'system', key: 'locale', value: 'nl-NL' }

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

void manifest
void locale
void presentation
void detail
void acceptsTemplate
