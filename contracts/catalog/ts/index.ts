// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

export type {
  NodeId,
  SlotId,
  TemplateDocument,
  Node,
  Slot,
  ThemeRef,
  ThemeRefInherit,
  ThemeRefOverride,
  PageFormat,
  Orientation,
  Margins,
  PageSettings,
  DocumentStyles,
  ExpressionLanguage,
  Expression,
} from './model.js'

export type {
  ComponentRegistry,
  ComponentManifest,
  SlotTemplate,
  InspectorField,
  InspectorOption,
  ComponentExample,
  ComponentExampleFragment,
} from '../generated/component-manifest.js'

export type {
  StyleRegistry,
  StyleGroup,
  StyleProperty,
} from '../generated/style-registry.js'

export type {
  Theme,
  BlockStylePreset,
} from '../generated/theme.js'

export type {
  CatalogManifest,
  CatalogInfo,
  CatalogPresentation,
  PublisherInfo,
  ReleaseInfo,
  CompatibilityInfo,
  IncludeEntry,
  ResourceEntry,
  DependencyRef,
  CatalogDependencyRef,
} from '../generated/catalog-manifest.js'

export type {
  ResourceDetail,
  CatalogResource,
  TemplateResource,
  ThemeResource,
  StencilResource,
  AttributeResource,
  AssetResource,
  CodeListResource,
  FontResource,
  CodeListBindingRef,
  CodeListEntryEntry,
  FontVariantEntry,
  DataExampleEntry,
  VariantEntry,
} from '../generated/resource-detail.js'

/** Maximum number of stencil instances allowed in one ancestor chain. */
export const MAX_STENCIL_NESTING_DEPTH = 5
