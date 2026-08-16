// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import type {
  AssetResource,
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
    defaultLanguage: 'nl-NL',
    keywords: ['documents'],
  },
  publisher: { name: 'Epistola' },
  release: { version: '1.0.0' },
  resources: [],
}

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
void presentation
void detail
void acceptsTemplate
