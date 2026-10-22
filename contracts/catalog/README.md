# Epistola Catalog Contract

The catalog contract defines the portable content that moves between Epistola
Suite, Exchange, and other consumers. It is the source of truth for catalog
wire formats, template models, registries, conformance fixtures, validation,
canonicalization, migrations, and archive handling.

## Authoritative Sources

| Concern | Source |
| --- | --- |
| Template and theme shapes | [`schemas/`](schemas/) |
| Component and style vocabulary | [`registry/`](registry/) |
| Portable compatibility examples | [`fixtures/`](fixtures/) |
| Semantic validation and archive behavior | [`src/main/kotlin/`](src/main/kotlin/) |
| TypeScript public facade | [`ts/`](ts/) and generated sources in [`generated/`](generated/) |

The catalog currently writes wire `schemaVersion: 8`, migrates versions 4 through 7, and uses
template `modelVersion: 1`. Published Maven and npm artifacts are released on the
repository's coordinated release train.

The versioned v5 through v8 manifest and resource-detail schemas preserve each accepted wire shape.
Their unversioned counterparts point at v8, and the npm package root exports TypeScript declarations
generated from the current schemas. `template-document.schema.json` is the current (v8) template
document; wire v5 to v7 resolve theirs through `template-document-v7.schema.json`, which keeps the
theme reference's `themeId`.

Wire v8 identifies a catalog by three separate parts, **registry**, **namespace** and key, and
versions catalogs as a whole:

- `catalog.registry` and `catalog.namespace` are both required. The registry is an exchange
  (`exchange.epistola.app`) or a domain that publishes its own catalogs (`acme.com`), always
  written in full: an archive has no default registry, which is installation configuration. The
  namespace is a slug within that registry (`acme-corp`); the same name in two registries is two
  namespaces. (This registry is part of a catalog's identity, unrelated to the component and style
  registries below.)
- `catalogDependencies` declares each catalog the catalog depends on once: under an **alias**
  (`alias`, the catalog key rule, unique in the list, never the catalog's own key, and by default
  the depended-on catalog's own key), by its name (`registry`, `namespace` and `catalogKey`, which
  finds newer releases of it), and with the release it pins: `releaseFingerprint`, copied from that
  release's `release.fingerprint` in the form that names the algorithm (`v6:<hex>`), so a copy
  fetched from any registry or mirror can be checked, and `releaseVersion`, the number it had (the
  catalog renders with that release and was cut with it; a declaration names no range). Both are
  null only for a catalog migrated from wire v7.
- Content names another catalog by that **alias**, never by registry or namespace (contract ADR
  0003): a theme override's, a stencil or image node's, a font reference's and a code-list
  binding's `catalogKey`, a template's `themeCatalogKey`, the catalog's `defaultTheme.catalogKey`,
  the qualifier of a variant's attribute key (`system.locale`), and each resource dependency's
  `catalogKey`. Every alias must be declared; a reference naming no alias, or the catalog's own
  key, is to the catalog itself. Moving a dependency to another release changes one declaration,
  and renaming or mirroring it changes no content (`CatalogAliases`, `CATALOG_ALIAS_PATTERN`).
- A release is numbered `MAJOR.MINOR.PATCH` by the holder of the catalog's namespace; anyone else
  issues only a patch release labelled with a namespace they hold, `1.3.1-gemeente-x.1`, which sorts
  below the holder's next number (`CatalogReleaseVersions`, `CATALOG_RELEASE_VERSION_PATTERN`), at
  most 128 characters so that every namespace can label one. The format checks the shape, not who
  holds a namespace.
- A release is identified by its content: `release.fingerprint` is V6 (`v6:<hex>`), which covers
  the resources, the content settings (`defaultTheme`, `presets`, `license`), the dependencies by
  alias and pinned hash, and `compatibility`, but not the catalog's name, the release block, its
  presentation (`name`, `description`, `keywords`, `presentation`, `attributes`, `publisher`,
  `includes`), or the wire version (`schemaVersion`), so a wire version that only re-encodes content
  keeps every V6 value. Presentation is information about the name: it can change
  without a release, and the most recently received wins. The name, `release.version` and
  `release.fingerprint` are the archive's binding of a name and number to a hash; see
  [Canonicalization](docs/catalog-canonicalization.md) and [Archive format](docs/catalog-archive.md).
- Stencils carry no version: `StencilResource.version` is gone, and a stencil node carries no
  `version` or `draftVersion`. A stencil resolves through its catalog's dependency.
- A resource's public address is its `key`; an `id` is only ever a generated identifier that is
  not an address. Wire v8 therefore says `key` where v7 said `slug` (the catalog, resource entries,
  resource dependencies, every resource, variants, code-list bindings), `themeKey` for `themeId`,
  and, in content, `stencilKey` and `imageKey` for a stencil or image node's `stencilId` and
  `assetId`, `key` for a `fontFamily` font reference's `slug`, `iconImageKey` and `imageKeys` for
  the presentation's `iconAssetSlug` and `imageAssetSlugs`, and `catalogKey` for an attribute
  assignment's `catalog`. The migration renames them
  silently; a v8 document that uses an old name is refused. The Kotlin model still binds the old
  names so that documents stored under earlier versions keep loading.
- Every resource key follows one rule, `^[a-z0-9]+(-[a-z0-9]+)*$` with 3 to 50 characters (a
  leading digit allowed for every type): the keys of templates, variants, stencils, themes,
  attributes, code lists, fonts, images and data examples, and every position that names one
  (content references, resource dependencies, code-list bindings, attribute assignments, a
  variant's attribute keys, the presentation's image keys). The catalog's own key keeps its rule
  (a leading letter, 3 to 50). The v7-to-v8 migration pads a shorter v7 key on the right with `0`
  (`nl` becomes `nl0`) and cuts a longer one to 50 characters, rewriting every reference to it the
  same way, with one notice per key (`CATALOG_RESOURCE_KEY_PADDED`,
  `CATALOG_RESOURCE_KEY_TRUNCATED`). The rewrite depends on the key alone, so catalogs migrated
  independently, and references between them, still agree; a rewritten key that collides with
  another of its scope is refused (`CATALOG_RESOURCE_KEY_PADDING_COLLISION`,
  `CATALOG_RESOURCE_KEY_TRUNCATION_COLLISION`) rather than given a different key.
- A template's data examples each have a `key`, their address within the template, next to the
  display `name`; the v7-to-v8 migration derives one from the name with a notice
  (`CATALOG_DATA_EXAMPLE_KEY_ASSIGNED`). A template may carry a plain-text `description` (at most
  2000 characters), the source of its manifest entry's `description`.
- A template variant may carry `metadata`: producer-specific JSON objects keyed by a reverse-DNS
  namespace the producer owns (`{"app.epistola.suite": {"renderingDefaultsVersion": 6}}`), which
  the contract carries without interpreting and every reader keeps.
- A manifest `detailUrl`, and a binary's deprecated `contentUrl`, is a relative path below the
  manifest's directory (`./resources/template/letter.json`, `bin/<sha256>`), resolved against the
  archive root or the manifest's URL; see [Archive format](docs/catalog-archive.md).

A v7 archive names no registry or namespace, so migrating one needs both from the importer: pass
`CatalogMigrationOptions(namespace, registry)` to `CatalogSchemaMigrator.migrateManifest`,
`CatalogArchiveReader.read`, or `CatalogValidationPolicy(migration = ...)`. Without them, migration
fails with `CATALOG_REGISTRY_MISSING` or `CATALOG_NAMESPACE_MISSING`. Every other catalog a v7
archive names, in its resource dependencies or (read whole) in its content, is declared in that
registry and namespace under its key as the alias, so its references keep their values.

Catalog keyword rules are defined once: `CatalogKeywords` in Kotlin (limits, pattern, and
normalization) and `MAX_CATALOG_KEYWORD_LENGTH`, `MAX_CATALOG_KEYWORDS`, and
`CATALOG_KEYWORD_PATTERN` in TypeScript, all pinned to the current schema. Registry and namespace
rules are defined the same way: `CatalogNamespaces` in Kotlin, and `CATALOG_REGISTRY_PATTERN`,
`MAX_CATALOG_REGISTRY_LENGTH`, `CATALOG_NAMESPACE_PATTERN`, `MIN_CATALOG_NAMESPACE_LENGTH`,
and `MAX_CATALOG_NAMESPACE_LENGTH` in TypeScript; and the alias rule, `CatalogAliases` and
`CATALOG_ALIAS_PATTERN`, `MIN_CATALOG_ALIAS_LENGTH` and `MAX_CATALOG_ALIAS_LENGTH`.
Paths into the catalog are `CatalogArchivePaths` and `CATALOG_ARCHIVE_PATH_PATTERN`; variant
metadata is `CatalogVariantMetadata` and the `…VARIANT_METADATA…` constants, whose rules a
release's `metadata` follows too; a release id is `CatalogReleaseIds` and
`CATALOG_RELEASE_ID_PATTERN`, `MIN_CATALOG_RELEASE_ID_LENGTH` and `MAX_CATALOG_RELEASE_ID_LENGTH`; a
release's base (`ReleaseBase`) follows the release number and fingerprint rules
(`ReleaseBase.FINGERPRINT_PATTERN` and `isValidFingerprint`); resource keys, data
example keys among them, are `CatalogKeys` (`PATTERN`, `MIN_LENGTH`, `MAX_LENGTH`, `RESOURCE`,
`isValid`, the migration's `migrate`, and `deriveDataExampleKey`) and
`CATALOG_RESOURCE_KEY_PATTERN`, `MIN_CATALOG_RESOURCE_KEY_LENGTH` and
`MAX_CATALOG_RESOURCE_KEY_LENGTH`; the template description limit is
`TemplateResource.MAX_DESCRIPTION_LENGTH` and `MAX_TEMPLATE_DESCRIPTION_LENGTH`; where an archive
carries a release it pins is `CatalogDependencyArchivePaths` and
`CATALOG_DEPENDENCY_ARCHIVE_PATH_PATTERN`, `parseCatalogDependencyArchivePath` and
`catalogDependencyArchivePath`.
Producers should use these rather than their own copies.

## Build

```bash
./gradlew build sourcesJar dokkaJavadocJar
pnpm install --frozen-lockfile
pnpm generate:types
pnpm build
npm pack --dry-run
```

Run the same checks from the repository root with:

```bash
make build-epistola-catalog
```

## Documentation

- [Archive format](docs/catalog-archive.md)
- [Canonicalization and fingerprints](docs/catalog-canonicalization.md)
- [Compatibility](docs/catalog-compatibility.md)
- [Catalog licensing](docs/catalog-licensing.md)
- [Registry](docs/catalog-registry.md)
- [Portable validation](docs/portable-catalog-validation.md)
