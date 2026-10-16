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
  StyleMap,
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
  CatalogRegistry,
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

/** Shortest catalog namespace (wire v8). */
export const MIN_CATALOG_NAMESPACE_LENGTH = 3

/** Longest catalog namespace (wire v8). */
export const MAX_CATALOG_NAMESPACE_LENGTH = 63

/**
 * Pattern a catalog namespace must match: a lowercase kebab-case slug, within its registry
 * (wire v8). The same pattern applies wherever a namespace appears, in the manifest or in content.
 */
export const CATALOG_NAMESPACE_PATTERN = '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'

/** Longest catalog registry, the longest DNS name (wire v8). */
export const MAX_CATALOG_REGISTRY_LENGTH = 253

/**
 * Pattern a catalog registry must match: a lowercase DNS name with at least one dot, in normal
 * order (`exchange.epistola.app`, `acme.com`) (wire v8). An archive always writes it in full; a
 * reference inside content may omit it to mean the owning catalog's registry, but never names one
 * without a namespace.
 */
export const CATALOG_REGISTRY_PATTERN = '^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$'

/** Longest version selector a catalog dependency may declare (wire v8). */
export const MAX_CATALOG_DEPENDENCY_VERSION_LENGTH = 100

/**
 * Pattern a catalog fingerprint must match in wire v8: the algorithm that computed it, a colon and
 * lowercase SHA-256 hex (`v5:<hex>`). It applies to `release.fingerprint`, which must name the
 * current algorithm (`v5`), and to a catalog dependency's `releaseFingerprint`, which is copied
 * from that release's own `release.fingerprint`. A verifier computes exactly the named algorithm.
 * `releaseFingerprint` is null exactly when `releaseVersion` is null, which only a catalog migrated
 * from wire v7 has.
 */
export const CATALOG_RELEASE_FINGERPRINT_PATTERN = '^v[1-9][0-9]*:[0-9a-f]{64}$'

/**
 * Pattern every path into the catalog must match in wire v8 (#95): a manifest resource entry's
 * `detailUrl`, and the deprecated `contentUrl` of an image or font face. A relative path below the
 * manifest's own directory: an optional leading `./`, then segments of unreserved characters
 * (letters, digits, `-` `.` `_` `~`) or percent-encodings other than of `.` `/` `\`, separated by
 * `/`. It rejects a scheme, a leading `/` (and so `//host`), a backslash, an empty segment, and a `.`
 * or `..` segment. Resolve it against the archive root for a zip, and against the manifest's URL for
 * a catalog read from a URL.
 */
export const CATALOG_ARCHIVE_PATH_PATTERN = '^(?:\\./)?(?!\\.{1,2}(?:/|$))(?:[A-Za-z0-9._~-]|%(?!2[EeFf]|5[Cc])[0-9A-Fa-f]{2})+(?:/(?!\\.{1,2}(?:/|$))(?:[A-Za-z0-9._~-]|%(?!2[EeFf]|5[Cc])[0-9A-Fa-f]{2})+)*$'

/** Pattern a template data example's key must match (wire v8): the same as a manifest resource key. */
export const CATALOG_DATA_EXAMPLE_KEY_PATTERN = '^[a-z0-9]+(?:-[a-z0-9]+)*$'

/** Shortest template data example key (wire v8). */
export const MIN_CATALOG_DATA_EXAMPLE_KEY_LENGTH = 1

/** Longest template data example key (wire v8). */
export const MAX_CATALOG_DATA_EXAMPLE_KEY_LENGTH = 64

/** Longest template description, in characters (wire v8). */
export const MAX_TEMPLATE_DESCRIPTION_LENGTH = 2000

/**
 * Pattern a variant metadata namespace must match (wire v8, #96): a lowercase reverse-DNS name the
 * producer owns, such as `app.epistola.suite`. Only the owner writes into its namespace; every other
 * reader keeps it as it is.
 */
export const CATALOG_VARIANT_METADATA_NAMESPACE_PATTERN = '^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+$'

/** Longest variant metadata namespace (wire v8). */
export const MAX_CATALOG_VARIANT_METADATA_NAMESPACE_LENGTH = 100

/** Most namespaces one variant's metadata may hold (wire v8). Empty metadata is omitted, not written. */
export const MAX_CATALOG_VARIANT_METADATA_NAMESPACES = 16

/** Largest serialized metadata of one variant, in UTF-8 bytes of its compact JSON (wire v8). */
export const MAX_CATALOG_VARIANT_METADATA_BYTES = 8192
