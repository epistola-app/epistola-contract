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
- **References by alias** (contract ADR 0003). A v7 catalog's content lived in one tenant, so every
  catalog it referenced sat beside it, in the same registry and namespace, named by its key. Each
  such catalog is declared under its key as the alias, so every reference -- a resource dependency,
  a reference inside content -- keeps its value and now names that alias. A reference qualified
  with a registry or namespace (the template document schema shared with v5 to v7 allowed one in a
  theme reference) is mapped to the alias of the catalog it names (`CATALOG_REFERENCE_ALIASED`); one
  naming a catalog in another namespace, which no v7 catalog declares, is a finding
  (`CATALOG_REFERENCE_ALIAS_UNRESOLVED`).
- **Catalog dependencies.** Each distinct other catalog the resource dependencies name is declared
  in `catalogDependencies` under its key as the alias, with the catalog's registry and namespace,
  pinning no release: `releaseVersion: null` and `releaseFingerprint: null`
  (`CATALOG_DEPENDENCY_CATALOG_DECLARED`). v7 never said which version was meant; the author pins a
  release rather than the migrator guessing one. A dependency on the catalog itself is not
  declared. Nothing required a v7 manifest to list every catalog its content named, so the archive
  reader, which sees the content, declares the others the same way, with the same notice; a key
  that cannot be an alias is a finding at the reference (`CATALOG_DEPENDENCY_ALIAS_INVALID`).
- **Stencil versions** are removed: a stencil's own `version`, and every stencil node's `version`
  and `draftVersion`, in template, variant, and stencil content (`CATALOG_STENCIL_VERSION_REMOVED`).
  A stencil node's `isDraft`, which v5 already rejected, is left in place and remains a validation
  finding.

The published `migrations/v7-to-v8/` fixtures pin the manifest and stencil conversions and their
notices.

`CatalogCanonicalizer` hashes canonical catalog content. It sorts resources and JSON object keys,
normalizes numeric JSON representation, includes streamed asset digests, and excludes the release
metadata (its timestamp, version, id, producer `metadata` and `base`), resource URLs, and ZIP
metadata. Therefore equivalent archives have identical fingerprints even when their entry order,
timestamps, compression, or JSON property order differ. What each algorithm covers of the manifest
differs; see the list below.

The current canonical form is V6. `CatalogCanonicalizer.currentFingerprint(catalog)` produces V6.
The existing `fingerprint(catalog)` method continues to produce V1 for source, binary, and
behavioral compatibility, and the versioned overload supports explicit selection. New fingerprints
should always use `currentFingerprint`.

Wire v8 writes a fingerprint in the form that names its algorithm, `v6:<hex>`
(`QualifiedCatalogFingerprint`; `currentQualifiedFingerprint` and `qualifiedFingerprint` produce it,
`matchesQualifiedFingerprint` checks one with exactly the algorithm it names). The label is part of
the stored value, not of what is hashed: each algorithm's own domain prefix is unchanged. See
[the archive format](catalog-archive.md#fingerprints-name-their-algorithm) for why.

## V6: a release is identified by its content

V6 is the current algorithm (contract ADR 0003, amendment of 2026-10-22; Epistola Suite ADR 0034).
A release's identity is a hash over its **content**: what it renders with, and nothing that names or
presents it. So two releases with the same V6 fingerprint are the same release, wherever they come
from and whatever they are called, and a release fetched by its hash from any registry, mirror or
file verifies itself. A dependency renamed, mirrored under another name, or promoted to a real name
keeps its hash, and so does every release that pins it.

V6 covers, under its own domain prefix (`algorithm sha256-v6`):

| What | Members |
| --- | --- |
| Every resource, as V5 covers it | each resource detail, an image's or font face's bytes included; references to other catalogs name an alias, so the resource says nothing about another catalog's name |
| The resource index | each entry's `type`, `key`, `name` and `description`, as V5 |
| The catalog's content settings | `catalog.defaultTheme` (`key`, `catalogKey` alias), `catalog.presets` (sorted by key) and `catalog.license` |
| The dependencies | each resource dependency by type, alias and key; each `catalogDependencies` entry as its `alias` and the pinned `releaseFingerprint`, sorted by alias |
| What the content needs | `compatibility` (the Epistola versions it declares) |

V6 does not cover:

- **the catalog's name**: `catalog.registry` (the authority), `catalog.namespace` and `catalog.key`;
- **a dependency's name and number**: a `catalogDependencies` entry's `registry`, `namespace`,
  `catalogKey` and `releaseVersion`. The alias and the pinned hash say what the content renders
  with; the name and number are how to find it;
- **the release block**: `release.version`, `releasedAt`, `fingerprint`, `releaseId`, `metadata`
  and `base`. Together with the name they are the archive's binding of a name and number to the
  hash ([the archive format](catalog-archive.md#bindings-a-name-at-a-number-is-a-hash));
- **the catalog's presentation**: `catalog.name`, `description`, `keywords`, `presentation` (which
  images to show; the image resources themselves are content), `attributes` (discovery labels),
  and the manifest's `publisher` and `includes`;
- **the wire version**: `schemaVersion`.

**Why** (Suite ADR 0034): when the name is the identity, giving a release another name -- a mirror
under another name, a promotion from a provisional name, a channel, a rename -- makes it a new
release and changes every reference to it. V5 covered the catalog's own registry and namespace and
those of every dependency, so it had that problem. With content addressing, names point at
releases (bindings) and content names other catalogs by alias, so a name can change without the
content changing.

**Presentation is information about the name, not about a release.** It can be updated without
making a release, and, of the releases of a catalog an installation receives, the presentation most
recently received wins. A producer may therefore change a catalog's display name, description,
keywords or icon without minting a new release; two archives of the same release with different
presentation verify against the same fingerprint.

**The wire version is not content.** The hash identifies a release across wire versions: the V6
algorithm itself -- its domain prefix and its canonical form -- already fixes how the content is
encoded, so the same content stated under another `schemaVersion` has the same V6 fingerprint, and
a wire version that only re-encodes content keeps every release's V6 value, its pins and bases
included. A wire version that changes what the content means gets an algorithm of its own.

- V3 retains the semantic v5 resource projection.
- V4 adds an algorithm domain prefix and includes qualified catalog attributes sorted by catalog
  and key, sorted `keywords`, catalog presentation asset references, and catalog-wide license
  metadata. It was the current algorithm for wires v6 and v7.
- V5, introduced with wire v8 and current until V6, is V4 under its own domain prefix (`algorithm sha256-v5`) plus the
  catalog's identity and declared dependencies: `catalog.registry` and `catalog.namespace`, each
  resource dependency's catalog by registry, namespace and key (dependencies sort by type, registry,
  namespace, catalog key, and key), and every `catalogDependencies` entry with its `registry`,
  `namespace`, `releaseVersion` and `releaseFingerprint`, sorted by registry, namespace and catalog
  key. A resource dependency names its catalog by alias since contract ADR 0003, so V5 takes that
  catalog's registry, namespace and key from the declaration the alias names: a manifest written
  before aliases, whose dependencies named their catalog in full and whose declarations had no alias
  (and so the catalog key as theirs), keeps its V5 value. V5 does not cover an alias.
  Registry and namespace are separate members, never one joined string. They are identity, and a
  declaration's pinned release (`releaseVersion`) is exactly the release of the dependency the
  catalog renders with, so two releases differing only there are different content. That
  release's fingerprint is part of what the release was cut with, so V5 covers it too. Resource entries are V4's; a stencil carries no version at v8, so
  V5 covers none. A reference inside content names an alias and no registry or namespace (an
  earlier draft of wire v8 allowed optional ones, which the model now drops when binding), so a
  resource hashes as it did whenever its references named none.

**The catalog's default theme and presets (ADR 0007).** V5 covers `catalog.defaultTheme` (its `key`
and `catalogKey`, with the `namespace` and `registry` an earlier draft allowed written as null, as
they were when absent) and `catalog.presets` (each
`key`, `name` and `description`, sorted by key, so their order carries no meaning). Each is written
into the canonical catalog section only when present: a catalog without a default theme and with no
declared presets hashes exactly as it did before the members existed. V1 to V4 never cover them.

**Names.** Wire v8 calls a resource's address its `key` (`slug` until v7; likewise `themeKey`,
`stencilKey`, `imageKey`, `iconImageKey` and `imageKeys` for `themeId`, `stencilId`, `assetId`,
`iconAssetSlug` and `imageAssetSlugs`; a font reference's `slug` becomes `key`, and an attribute
assignment's `catalog` `catalogKey`). V5 canonicalizes the new names. V1 to V4 canonicalize those members under their
old names whatever the archive calls them, both in the manifest projection and in each resource's
canonical JSON (`CatalogKeyRenames` lists the positions), so the rename moves no published V1 to V4
fingerprint, and a v7 archive's V4 fingerprint still verifies after migration. The per-resource
fingerprints `perResourceFingerprints` and `currentPerResourceFingerprints` belong to V1 and V3 and
keep the old names too.

**Members wire v8 added to a template.** A data example's `key`, a template's `description` and a
variant's `metadata` are release content, and V5 covers them like the rest of the resource: object
keys sorted, numbers as JSON binding normalizes them, as for any other free-form JSON such as
`dataModel`. `description` and `metadata` are omitted from the canonical JSON when absent (and
`metadata` when empty), so a template that uses neither hashes as before; a data example's `key` is
part of every v8 example. V1 to V4 leave all three out (`CatalogV8Additions`): they canonicalize a
resource as a v7 producer serialized it, so the key the v7-to-v8 migration derives for each example
moves no published fingerprint.

**Per-resource fingerprints.** Three forms map every `type/key` resource to a lowercase hex
SHA-256 over that resource alone, an image's bytes included:

| Function | Algorithm | Covers |
| --- | --- | --- |
| `perResourceFingerprints` | V1 | the resource as a v7 producer wrote it, under the names before v8 |
| `currentPerResourceFingerprints` | V3 | the same, with a stencil node's `isDraft` left out, as V3 does |
| `completePerResourceFingerprints` | V5 | the resource's complete v8 content, under the v8 names, as V5 covers it |

**To tell resources apart when merging or diffing, use `completePerResourceFingerprints`.** It is
the per-resource counterpart of V5 and of the current catalog fingerprint, V6, which covers each
resource exactly as V5 does: it names every member as wire
v8 does and covers what v8 added (a template's `description`, a variant's `metadata`, a data
example's `key`), so a change to only those changes the value of that resource and of no other.
The V1 and V3 forms leave them out (`CatalogV8Additions`), so a resource changed only there looks
unchanged. Its values are hashed under their own domain prefix, `resource sha256-v5`, and never
equal a V1 or V3 value; compare them only with each other. The older forms are kept, with the values
they always had, for consumers that stored them.

**Why a new algorithm rather than extending V4.** V4 fingerprints are already published and must
keep verifying. Adding the registry, namespace and catalog dependencies to V4 would change every V4 value; a
new version keeps V4 reproducible and makes the new coverage explicit. V6 is a new algorithm for the
same reason: it covers less than V5 (no names, no presentation), and V5 values already written into
releases, pins and bases keep verifying under V5.

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
- Native v8 input must carry `v6:<hex>` over its own content. Naming another algorithm is
  `CATALOG_RELEASE_FINGERPRINT_INVALID`, even when it matches. A pin (`releaseFingerprint`) or a base
  (`release.base.fingerprint`) may name any known algorithm, and is checked with exactly that one.

Re-exporting a migrated catalog replaces a present legacy fingerprint with V6, as `v6:<hex>`.

Authoritative versioned inputs and expected hashes are published below
`META-INF/epistola-catalog/fixtures/v1` in the Maven artifact and
`@epistola.app/epistola-catalog/fixtures/v1/*` in the npm artifact. `fingerprints.json` pins every
algorithm's fingerprint of the wire-v4 golden catalog (`catalogFingerprints`; `currentCatalogFingerprint`
is the V6 one) and, as `wireV8CatalogFingerprints`, the V5 and V6 fingerprints of the wire-v8 golden
catalog (`currentWireV8CatalogFingerprint` is the V6 one; the V5 value is the one published before
V6 and aliases existed), whose declared dependency records a release and
its fingerprint, and whose template carries a description, a keyed data example and variant
metadata. `completeWireV8ResourceFingerprints` pins `completePerResourceFingerprints` of that
catalog.
