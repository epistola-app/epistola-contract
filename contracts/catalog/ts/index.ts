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
  CatalogNamespace,
  CatalogKeyword,
  AttributeAssignment,
  CatalogPresentation,
  CatalogLicense,
  PublisherInfo,
  ReleaseInfo,
  CompatibilityInfo,
  IncludeEntry,
  ResourceEntry,
  DependencyRef,
  CatalogDependencyRef,
  CatalogDependency,
} from '../generated/catalog-manifest.js'

export type {
  ResourceDetail,
  CatalogResource,
  TemplateResource,
  ThemeResource,
  StencilResource,
  AttributeResource,
  ImageResource,
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

/** Maximum characters in one catalog keyword (wire v7). */
export const MAX_CATALOG_KEYWORD_LENGTH = 30

/** Maximum keywords one catalog may list (wire v7). */
export const MAX_CATALOG_KEYWORDS = 20

/** Pattern a catalog keyword must match in full: lowercase ASCII letters and digits in hyphen-separated parts (wire v7). */
export const CATALOG_KEYWORD_PATTERN = '^[a-z0-9]+(?:-[a-z0-9]+)*$'

/** Shortest catalog namespace, in either form (wire v8). */
export const MIN_CATALOG_NAMESPACE_LENGTH = 3

/** Longest catalog namespace, in either form (wire v8). */
export const MAX_CATALOG_NAMESPACE_LENGTH = 320

/**
 * Pattern a namespace written in full must match: a domain, optionally followed by `/name`. Required
 * wherever an archive names a catalog at its top level (`catalog.namespace`, dependencies) (wire v8).
 */
export const CATALOG_NAMESPACE_PATTERN =
  '^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+(/[a-z][a-z0-9]*(-[a-z0-9]+)*)?$'

/**
 * Pattern a namespace inside resource content must match: the full form, or a bare name on the
 * installation's default exchange (wire v8).
 */
export const CATALOG_NAMESPACE_REFERENCE_PATTERN =
  '^([a-z][a-z0-9]*(-[a-z0-9]+)*|[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+(/[a-z][a-z0-9]*(-[a-z0-9]+)*)?)$'

/** Longest version selector a catalog dependency may declare (wire v8). */
export const MAX_CATALOG_DEPENDENCY_VERSION_LENGTH = 100
