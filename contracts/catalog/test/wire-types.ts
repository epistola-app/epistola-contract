// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import type {
  AssetResource,
  CatalogManifest,
  ResourceDetail,
  TemplateResource,
} from '../ts/index.js'

const manifest: CatalogManifest = {
  schemaVersion: 5,
  catalog: { slug: 'fixture', name: 'Fixture' },
  publisher: { name: 'Epistola' },
  release: { version: '1.0.0' },
  resources: [],
}

const asset: AssetResource = {
  type: 'asset',
  slug: 'logo',
  name: 'Logo',
  mediaType: 'image/svg+xml',
  contentUrl: './resources/asset/logo.svg',
}

const detail: ResourceDetail = { schemaVersion: 5, resource: asset }
const acceptsTemplate = (resource: TemplateResource): TemplateResource => resource

void manifest
void detail
void acceptsTemplate
