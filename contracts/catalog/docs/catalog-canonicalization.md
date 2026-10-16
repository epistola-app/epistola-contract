# Catalog migration and canonicalization

`epistola-catalog` owns catalog wire-version gating and canonical fingerprints. Consumers should
pass manifest and resource-detail streams through `CatalogSchemaMigrator` before product-specific
import or persistence. The API reports stable migration findings for unsupported or inconsistent
wire versions and never exposes a JSON mapper.

The migration baseline is wire version 4 and the current wire version is 8. Each version migrates
one step at a time to 8. Versions below 4 and above 8 are rejected even when their JSON happens to
bind to the current model. The v5-to-v6 migration supplies empty catalog attribute and keyword
collections when those fields are absent; it does not invent a locale.

The v6-to-v7 migration normalizes catalog keywords with `CatalogKeywords.normalize`. It reduces
diacritics and ligatures to ASCII, lowercases, and turns every run of other characters into one
hyphen. It shortens a result longer than 30 characters, merges keywords that collide, and removes a
keyword that keeps no letters or digits. After sorting, it keeps the first 20 keywords and removes
the rest. Each change produces a `CATALOG_KEYWORD_NORMALIZED`, `CATALOG_KEYWORD_TRUNCATED`, or
`CATALOG_KEYWORD_REMOVED` notice at the keyword's source index. Keywords that were already invalid
v6 (blank, untrimmed, or exact duplicates) remain findings. The published
`migrations/v6-to-v7/keyword-normalization.json` fixture defines normalization for other
implementations.

The v7-to-v8 migration gives the catalog an identity and versions it as a whole:

- **Registry and namespace.** A v7 archive names neither, and nothing in it says where it belongs,
  so the caller supplies both with `CatalogMigrationOptions(namespace, registry)`: a slug, and a
  registry written in full. The catalog takes them with `CATALOG_REGISTRY_ASSIGNED` and
  `CATALOG_NAMESPACE_ASSIGNED` notices. Without one the manifest fails with
  `CATALOG_REGISTRY_MISSING` or `CATALOG_NAMESPACE_MISSING`, and with a malformed one (such as the
  joined `exchange.epistola.app/acme-corp` as a namespace, or `~` as a registry) with
  `CATALOG_REGISTRY_INVALID` or `CATALOG_NAMESPACE_INVALID`. The migrator never invents an
  identity. The options are ignored for an archive at v8, which names its own.
- **Resource dependencies** take the catalog's registry and namespace
  (`CATALOG_DEPENDENCY_REGISTRY_ASSIGNED`, `CATALOG_DEPENDENCY_NAMESPACE_ASSIGNED`): a v7 catalog's
  content lived in one tenant, so every catalog it referenced sat beside it.
- **Catalog dependencies.** Each distinct other catalog the resource dependencies name is declared
  in `catalogDependencies` with that registry and namespace, the unpinned selector `*`,
  `releaseVersion: null` and `releaseFingerprint: null` (`CATALOG_DEPENDENCY_CATALOG_DECLARED`). v7
  never said which version was meant; the author sets the selector rather than the migrator
  guessing one. A dependency on the catalog itself is not declared.
- **Stencil versions** are removed: a stencil's own `version`, and every stencil node's `version`
  and `draftVersion`, in template, variant, and stencil content (`CATALOG_STENCIL_VERSION_REMOVED`).
  A stencil node's `isDraft`, which v5 already rejected, is left in place and remains a validation
  finding.

The published `migrations/v7-to-v8/` fixtures pin the manifest and stencil conversions and their
notices.

`CatalogCanonicalizer` hashes canonical catalog content. It sorts resources and JSON object keys,
normalizes numeric JSON representation, includes streamed asset digests, dependency identity,
publisher metadata, compatibility declarations, and includes, and excludes volatile release
timestamps, release versions, resource URLs, and ZIP metadata. Therefore equivalent archives have
identical fingerprints even when their entry order, timestamps, compression, or JSON property order
differ.

The current canonical form is V5. `CatalogCanonicalizer.currentFingerprint(catalog)` produces V5.
The existing `fingerprint(catalog)` method continues to produce V1 for source, binary, and
behavioral compatibility, and the versioned overload supports explicit selection. New fingerprints
should always use `currentFingerprint`.

Wire v8 writes a fingerprint in the form that names its algorithm, `v5:<hex>`
(`QualifiedCatalogFingerprint`; `currentQualifiedFingerprint` and `qualifiedFingerprint` produce it,
`matchesQualifiedFingerprint` checks one with exactly the algorithm it names). The label is part of
the stored value, not of what is hashed: V5's own domain prefix is unchanged. See
[the archive format](catalog-archive.md#fingerprints-name-their-algorithm) for why.

- V3 retains the semantic v5 resource projection.
- V4 adds an algorithm domain prefix and includes qualified catalog attributes sorted by catalog
  and key, sorted `keywords`, catalog presentation asset references, and catalog-wide license
  metadata. It was the current algorithm for wires v6 and v7.
- V5, introduced with wire v8, is V4 under its own domain prefix (`algorithm sha256-v5`) plus the
  catalog's identity and declared dependencies: `catalog.registry` and `catalog.namespace`, each
  resource dependency's `registry` and `namespace` (dependencies sort by type, registry, namespace,
  catalog key, and key), and every `catalogDependencies` entry with its `registry`, `namespace`,
  `version` selector, `releaseVersion` and `releaseFingerprint`, sorted by registry, namespace and
  catalog key. Registry and namespace are separate members, never one joined string. They are
  identity, and a selector decides which release of a dependency the catalog renders with, so two
  releases differing only there are different content. A dependency's recorded release and its
  fingerprint are part of what the release was cut with, so V5 covers them too. Resource entries are V4's; a stencil carries no version at v8, so
  V5 covers none. Optional content registries and namespaces (`ThemeRef.registry`/`.namespace`,
  `CodeListBindingRef.registry`/`.namespace`, `FontRef.registry`/`.namespace`,
  `TemplateResource.themeRegistry`/`.themeNamespace`) are omitted from the canonical resource JSON
  when absent, so resources that do not use them hash exactly as before.

**Names.** Wire v8 calls a resource's address its `key` (`slug` until v7; likewise `themeKey`,
`stencilKey`, `imageKey`, `iconImageKey` and `imageKeys` for `themeId`, `stencilId`, `assetId`,
`iconAssetSlug` and `imageAssetSlugs`; a font reference's `slug` becomes `key`, and an attribute
assignment's `catalog` `catalogKey`). V5 canonicalizes the new names. V1 to V4 canonicalize those members under their
old names whatever the archive calls them, both in the manifest projection and in each resource's
canonical JSON (`CatalogKeyRenames` lists the positions), so the rename moves no published V1 to V4
fingerprint, and a v7 archive's V4 fingerprint still verifies after migration. Per-resource
fingerprints (`perResourceFingerprints`, `currentPerResourceFingerprints`) belong to V1 and V3 and
keep the old names too.

**Members wire v8 added to a template.** A data example's `key`, a template's `description` and a
variant's `metadata` are release content, and V5 covers them like the rest of the resource: object
keys sorted, numbers as JSON binding normalizes them, as for any other free-form JSON such as
`dataModel`. `description` and `metadata` are omitted from the canonical JSON when absent (and
`metadata` when empty), so a template that uses neither hashes as before; a data example's `key` is
part of every v8 example. V1 to V4 leave all three out (`CatalogV8Additions`): they canonicalize a
resource as a v7 producer serialized it, so the key the v7-to-v8 migration derives for each example
moves no published fingerprint.

**Why a new algorithm rather than extending V4.** V4 fingerprints are already published and must
keep verifying. Adding the registry, namespace and catalog dependencies to V4 would change every V4 value; a
new version keeps V4 reproducible and makes the new coverage explicit.

Whole-catalog validation chooses the rule by the archive's source wire version:

- v4/v5 input accepts V1 through V3 and the equivalent legacy-v4 projection.
- v6 and v7 input must carry V4 as its producer computed it. The migrations since rewrote content V4
  covers, so the validator reproduces the source form: it tries the keywords the source
  `catalog.json` listed (the v7 migration may have normalized them), and it restores the stencil
  versions the v8 migration removed, a stencil's own `version` and each stencil node's `version` and
  `draftVersion`, from the raw resource documents in the archive
  (`CatalogCanonicalizer.matchesSourceV4Fingerprint`). V4 never covered a registry, a namespace or
  a catalog dependency, so what v8 adds needs no undoing. A fingerprint over different content still
  mismatches.
- For any input read before v8, V1 to V4 are computed over the source keys. The v8 migration pads
  keys shorter than 3 characters and cuts keys longer than 50, with every reference to them, and the
  migrated key alone does not say what it was (`ab0` may have been `ab` or `ab0`), so the
  canonicalizer restores each from the raw `catalog.json` and resource documents in the archive:
  wherever the migrated value is exactly the rewrite of a different raw value at the same position.
  V5 covers the keys as migrated.
- Native v8 input must carry `v5:<hex>` over its own content. Naming another algorithm is
  `CATALOG_RELEASE_FINGERPRINT_INVALID`, even when it matches: an older one covers less.

Re-exporting a migrated catalog replaces a present legacy fingerprint with V5, as `v5:<hex>`.

Authoritative versioned inputs and expected hashes are published below
`META-INF/epistola-catalog/fixtures/v1` in the Maven artifact and
`@epistola.app/epistola-catalog/fixtures/v1/*` in the npm artifact. `fingerprints.json` pins every
algorithm's fingerprint of the wire-v4 golden catalog and, as `currentWireV8CatalogFingerprint`,
the V5 fingerprint of the wire-v8 golden catalog, whose declared dependency records a release and
its fingerprint, and whose template carries a description, a keyed data example and variant
metadata.
