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
  CatalogThemeRef,
  CatalogPresetDeclaration,
  PublisherInfo,
  ReleaseInfo,
  ReleaseBase,
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
 * (wire v8). A namespace is flat; an organisation may hold several. It appears where the manifest
 * names a catalog: the catalog itself and each catalog dependency.
 */
export const CATALOG_NAMESPACE_PATTERN = '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'

/** Longest catalog registry, the longest DNS name (wire v8). */
export const MAX_CATALOG_REGISTRY_LENGTH = 253

/**
 * Pattern a catalog registry must match: a lowercase DNS name with at least one dot, in normal
 * order (`exchange.epistola.app`, `acme.com`), or `local` (wire v8). It is the authority of a catalog's
 * name, the domain its namespace was granted under, never where a release is fetched from (contract
 * ADR 0003); `local` is the authority of names that never leave the organisation, which an Exchange
 * refuses. An archive always writes it in full, for the catalog itself and each catalog dependency;
 * content never names one.
 */
export const CATALOG_REGISTRY_PATTERN = '^(local|[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+)$'

/** The authority of names that never leave the organisation (contract ADR 0003): the one registry that is not a domain. */
export const CATALOG_LOCAL_REGISTRY = 'local'

/**
 * Pattern an alias must match (wire v8, contract ADR 0003): the catalog key rule. A catalog's content names
 * another catalog by the alias its `catalogDependencies` declares for it (a reference's
 * `catalogKey`, a template's `themeCatalogKey`, the qualifier of a variant attribute key), never by
 * registry or namespace. Unique within the list, never the catalog's own key, and by default the
 * depended-on catalog's own key.
 */
export const CATALOG_ALIAS_PATTERN = '^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$'

/** Shortest alias (wire v8). */
export const MIN_CATALOG_ALIAS_LENGTH = 3

/** Longest alias (wire v8). */
export const MAX_CATALOG_ALIAS_LENGTH = 50

/**
 * Longest release number (wire v8), a patch label included: 128 characters, so that every valid
 * namespace (up to 63 characters) can label a patch release. The same limit as the API's `ReleaseVersion`.
 */
export const MAX_CATALOG_RELEASE_VERSION_LENGTH = 128

/**
 * Pattern a release number must match in wire v8: `MAJOR.MINOR.PATCH` without leading zeros, or, for
 * a patch release by someone who does not hold the catalog's namespace, that core followed by a
 * label naming a namespace they hold and a sequence number from 1: `1.3.1-gemeente-x.1` (which sorts
 * above `1.3.0` and below the holder's `1.3.1`). No other pre-release or build part (`1.4.0-rc.1`,
 * `1.4.0+5`). It applies to `release.version`, a catalog dependency's `releaseVersion`,
 * `release.base.version` and a carried release's path. The format checks the shape only; who holds a
 * namespace is not its to know. Wire v7 and earlier accepted full SemVer. The same rule as the API's
 * `ReleaseVersion`.
 */
export const CATALOG_RELEASE_VERSION_PATTERN = '^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-(?=[a-z0-9-]{3,63}\\.)([a-z][a-z0-9]*(?:-[a-z0-9]+)*)\\.([1-9][0-9]*))?$'

/**
 * Pattern of a release number without a patch label (wire v8): `MAJOR.MINOR.PATCH`, as the holder of
 * the catalog's namespace issues it. The API's `ReleaseVersion` is the full rule,
 * `CATALOG_RELEASE_VERSION_PATTERN`.
 */
export const CATALOG_RELEASE_VERSION_CORE_PATTERN = '^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$'

/** Shortest release id (wire v8), the same limit as the API's `CatalogReleaseId`. */
export const MIN_CATALOG_RELEASE_ID_LENGTH = 4

/** Longest release id (wire v8), the same limit as the API's `CatalogReleaseId`. */
export const MAX_CATALOG_RELEASE_ID_LENGTH = 36

/**
 * Pattern a release id must match in wire v8 (`release.releaseId`): lowercase letters, digits and
 * single hyphens, the same rule as the API's `CatalogReleaseId`. It is unique among the releases of
 * the catalog's name and kept when the release moves to another installation. The fingerprint does
 * not cover it.
 */
export const CATALOG_RELEASE_ID_PATTERN = '^[a-z0-9]+(-[a-z0-9]+)*$'

/**
 * Pattern a catalog fingerprint must match in wire v8: the algorithm that computed it, a colon and
 * lowercase SHA-256 hex (`v6:<hex>`). It applies to `release.fingerprint`, which must name the
 * current algorithm (`v6`, which covers the release's content only: not the catalog's name, the
 * release block or its presentation), to a catalog dependency's `releaseFingerprint`, the pin,
 * copied from that release's own `release.fingerprint`, and to `release.base.fingerprint`. A verifier
 * computes exactly the named algorithm. `releaseFingerprint` is null exactly when `releaseVersion`
 * is null, which only a catalog migrated from wire v7 has.
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

/**
 * Pattern every resource key must match in wire v8: lowercase ASCII letters and digits in
 * hyphen-separated parts, a leading digit allowed. One rule for the keys of templates, variants,
 * stencils, themes, attributes, code lists, fonts, images and data examples, and for every position
 * that names one: references in content (`themeKey`, `stencilKey`, `imageKey`, a `fontFamily` key),
 * resource dependencies, code-list bindings, attribute assignments, and the presentation's `iconImageKey` and `imageKeys`. The catalog's own key is not a resource key.
 */
export const CATALOG_RESOURCE_KEY_PATTERN = '^[a-z0-9]+(-[a-z0-9]+)*$'

/**
 * Shortest resource key (wire v8). The v7-to-v8 migration pads a shorter catalog-v7 key on the right
 * with `0` (`a` becomes `a00`), and every reference to it alike.
 */
export const MIN_CATALOG_RESOURCE_KEY_LENGTH = 3

/**
 * Longest resource key (wire v8). The v7-to-v8 migration cuts a longer catalog-v7 key to this length
 * without a trailing hyphen, and every reference to it alike.
 */
export const MAX_CATALOG_RESOURCE_KEY_LENGTH = 50

/**
 * Pattern of a variant's attribute key (wire v8): an attribute key, optionally qualified by the alias
 * of the catalog that defines the attribute (`system.locale`). Each part follows its own rule: the
 * catalog part the alias's, the attribute part the resource key's.
 */
export const CATALOG_ATTRIBUTE_KEY_PATTERN = '^(?:[a-z][a-z0-9]*(?:-[a-z0-9]+)*\\.)?[a-z0-9]+(-[a-z0-9]+)*$'

/** Longest variant attribute key (wire v8): a catalog key, the separator and a resource key. */
export const MAX_CATALOG_ATTRIBUTE_KEY_LENGTH = 101

/**
 * Pattern a declared preset's key must match (wire v8, ADR 0007): what a block's `stylePreset` names
 * and what a theme's `blockStylePresets` styles it under. A leading lowercase letter, then lowercase
 * letters and digits in hyphen-separated parts. Keys are unique within one catalog's `presets`.
 */
export const CATALOG_PRESET_KEY_PATTERN = '^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$'

/** Longest declared preset key (wire v8). */
export const MAX_CATALOG_PRESET_KEY_LENGTH = 50

/** Longest declared preset name (wire v8). */
export const MAX_CATALOG_PRESET_NAME_LENGTH = 100

/** Longest declared preset description (wire v8). */
export const MAX_CATALOG_PRESET_DESCRIPTION_LENGTH = 500

/** Longest template description, in characters (wire v8). */
export const MAX_TEMPLATE_DESCRIPTION_LENGTH = 2000

/**
 * Pattern a producer metadata namespace must match (wire v8): a lowercase reverse-DNS name the
 * producer owns, such as `app.epistola.suite`. Only the owner writes into its namespace; every other
 * reader keeps it as it is. A resource's and a release's `metadata` follow these rules (and the three
 * limits below). No fingerprint covers metadata: it is what a producer says about the content, not
 * the content.
 */
export const CATALOG_METADATA_NAMESPACE_PATTERN = '^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+$'

/** Longest metadata namespace (wire v8). */
export const MAX_CATALOG_METADATA_NAMESPACE_LENGTH = 100

/** Most namespaces one resource's or release's metadata may hold (wire v8). Empty metadata is omitted, not written. */
export const MAX_CATALOG_METADATA_NAMESPACES = 16

/** Largest serialized metadata of one resource or release, in UTF-8 bytes of its compact JSON (wire v8). */
export const MAX_CATALOG_METADATA_BYTES = 8192

/** The directory, at a catalog archive's root, that holds the releases the archive carries (wire v8). */
export const CATALOG_DEPENDENCY_ARCHIVE_DIRECTORY = 'dependencies/'

/**
 * Pattern the path of a release a catalog archive carries must match in full (wire v8):
 * `dependencies/{registry}/{namespace}/{catalogKey}/{releaseVersion}.zip`, the pin's own values, each
 * segment by its own rule (and its length limits: a registry at most 253 characters, a namespace 3 to
 * 63, a catalog key 3 to 50, a release number, a patch release's included, at most 128). The groups are, in order, the registry,
 * namespace, catalog key and release number. Anything else under `dependencies/` refuses the archive.
 * The manifest has no member for it: a release's manifest and fingerprint are the same whether it is
 * exported thin or with the releases it pins.
 */
export const CATALOG_DEPENDENCY_ARCHIVE_PATH_PATTERN =
  '^dependencies/(local|[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+)/([a-z][a-z0-9]*(?:-[a-z0-9]+)*)/([a-z][a-z0-9]*(?:-[a-z0-9]+)*)/((?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:-(?=[a-z0-9-]{3,63}\\.)[a-z][a-z0-9]*(?:-[a-z0-9]+)*\\.[1-9][0-9]*)?)\\.zip$'

/** Most releases one catalog archive carries by default; each is held to the per-archive limits on its own. */
export const MAX_CATALOG_ARCHIVE_DEPENDENCIES = 50

/** The release a carried archive's path names. */
export interface CatalogDependencyArchiveCoordinates {
  registry: string
  namespace: string
  catalogKey: string
  releaseVersion: string
}

/**
 * The release `path` names, or `undefined` when it is not a valid carried-release path
 * ({@link CATALOG_DEPENDENCY_ARCHIVE_PATH_PATTERN} and each segment's length limits).
 */
export function parseCatalogDependencyArchivePath(path: string): CatalogDependencyArchiveCoordinates | undefined {
  const match = new RegExp(CATALOG_DEPENDENCY_ARCHIVE_PATH_PATTERN).exec(path)
  if (!match) return undefined
  const [, registry, namespace, catalogKey, releaseVersion] = match
  if (registry.length > MAX_CATALOG_REGISTRY_LENGTH) return undefined
  if (namespace.length < MIN_CATALOG_NAMESPACE_LENGTH || namespace.length > MAX_CATALOG_NAMESPACE_LENGTH) return undefined
  if (catalogKey.length < 3 || catalogKey.length > 50) return undefined
  if (releaseVersion.length > MAX_CATALOG_RELEASE_VERSION_LENGTH) return undefined
  return { registry, namespace, catalogKey, releaseVersion }
}

/** The path at which a catalog archive carries the given pinned release; throws when a segment breaks its rule. */
export function catalogDependencyArchivePath(release: CatalogDependencyArchiveCoordinates): string {
  const path = `${CATALOG_DEPENDENCY_ARCHIVE_DIRECTORY}${release.registry}/${release.namespace}/${release.catalogKey}/${release.releaseVersion}.zip`
  if (!parseCatalogDependencyArchivePath(path)) throw new Error(`not a valid carried catalog release path: ${path}`)
  return path
}

/**
 * How a catalog release is kept in a container registry (OCI distribution), the `container` kind of
 * release registry (contract ADR 0008, amendment of 2026-10-22): the repository is
 * `<prefix>/<authority>/<namespace>/<catalogKey>`, the tag is the release number, and a receiver
 * resolves the tag to the manifest's digest once and fetches by digest. The same layout as the
 * Kotlin `CatalogContainerLayout`.
 */
export const CATALOG_CONTAINER_MANIFEST_MEDIA_TYPE = 'application/vnd.oci.image.manifest.v1+json'
export const CATALOG_CONTAINER_ARTIFACT_TYPE = 'application/vnd.epistola.catalog.release.v1'
export const CATALOG_CONTAINER_CONFIG_MEDIA_TYPE = 'application/vnd.epistola.catalog.release.config.v1+json'
export const CATALOG_CONTAINER_ARCHIVE_MEDIA_TYPE = 'application/vnd.epistola.catalog.archive.v1+zip'

/** The annotations a release's manifest carries: labels for listings, never evidence. */
export const CATALOG_CONTAINER_ANNOTATIONS = {
  version: 'app.epistola.catalog.version',
  fingerprint: 'app.epistola.catalog.fingerprint',
  contentHash: 'app.epistola.catalog.contentHash',
  registry: 'app.epistola.catalog.registry',
  namespace: 'app.epistola.catalog.namespace',
  catalogKey: 'app.epistola.catalog.catalogKey',
  imageVersion: 'org.opencontainers.image.version',
  imageTitle: 'org.opencontainers.image.title',
} as const

/** A repository path component, as the OCI distribution specification names it. */
export const CATALOG_CONTAINER_PATH_COMPONENT_PATTERN = '^[a-z0-9]+((\\.|_|__|-+)[a-z0-9]+)*$'

/** A tag, as the OCI distribution specification names it; every release number is one. */
export const CATALOG_CONTAINER_TAG_PATTERN = '^[a-zA-Z0-9_][a-zA-Z0-9._-]{0,127}$'

/** Longest repository name, prefix included, that registries are required to accept. */
export const MAX_CATALOG_CONTAINER_REPOSITORY_LENGTH = 255

/** The catalog a container repository keeps the releases of. */
export interface CatalogContainerCoordinates {
  registry: string
  namespace: string
  catalogKey: string
}

/**
 * The repository that keeps the releases of `catalog` under `prefix` (empty, or path components
 * joined by `/`): `<prefix>/<authority>/<namespace>/<catalogKey>`. Throws when a part breaks its rule.
 */
export function catalogContainerRepository(prefix: string, catalog: CatalogContainerCoordinates): string {
  const component = new RegExp(CATALOG_CONTAINER_PATH_COMPONENT_PATTERN)
  if (prefix !== '' && !prefix.split('/').every((part) => component.test(part))) throw new Error(`'${prefix}' is not a repository prefix`)
  if (catalog.registry.length > MAX_CATALOG_REGISTRY_LENGTH || !new RegExp(CATALOG_REGISTRY_PATTERN).test(catalog.registry)) {
    throw new Error(`'${catalog.registry}' is not a registry`)
  }
  const { namespace, catalogKey } = catalog
  if (namespace.length < MIN_CATALOG_NAMESPACE_LENGTH || namespace.length > MAX_CATALOG_NAMESPACE_LENGTH || !new RegExp(CATALOG_NAMESPACE_PATTERN).test(namespace)) {
    throw new Error(`'${namespace}' is not a namespace`)
  }
  if (catalogKey.length < 3 || catalogKey.length > 50 || !new RegExp(CATALOG_ALIAS_PATTERN).test(catalogKey)) throw new Error(`'${catalogKey}' is not a catalog key`)
  const name = [prefix, catalog.registry, namespace, catalogKey].filter((part) => part !== '').join('/')
  if (name.length > MAX_CATALOG_CONTAINER_REPOSITORY_LENGTH) throw new Error(`repository name '${name}' is longer than ${MAX_CATALOG_CONTAINER_REPOSITORY_LENGTH} characters`)
  return name
}
