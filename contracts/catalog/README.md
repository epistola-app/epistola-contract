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
generated from the current schemas.

Wire v8 identifies a catalog by three separate parts, **registry**, **namespace** and key, and
versions catalogs as a whole:

- `catalog.registry` and `catalog.namespace` are both required. The registry is an exchange
  (`exchange.epistola.app`) or a domain that publishes its own catalogs (`acme.com`), always
  written in full: an archive has no default registry, which is installation configuration. The
  namespace is a slug within that registry (`acme-corp`); the same name in two registries is two
  namespaces. (This registry is part of a catalog's identity, unrelated to the component and style
  registries below.)
- `catalogDependencies` declares each catalog the catalog depends on once, with its `registry`,
  `namespace` and `catalogKey`, a version selector (npm range grammar), the exact
  `releaseVersion` the release was cut with, and that release's fingerprint,
  `releaseFingerprint`, copied from its `release.fingerprint` in the form that names the algorithm
  (`v5:<hex>`), so a copy fetched from any registry or mirror can be checked. Both are null
  only for a catalog migrated from wire v7. Every catalog named by a resource dependency must be
  declared there, and each resource dependency names its catalog's `registry` and `namespace`.
- References inside resource content (a theme override, a stencil node, a code-list binding, a
  template's theme, a font) may add an optional `registry` and `namespace`; each omitted one means
  the owning catalog's, never an installation default. A `registry` without a `namespace` is
  invalid.
- Stencils carry no version: `StencilResource.version` is gone, and a stencil node carries no
  `version` or `draftVersion`. A stencil resolves through its catalog's dependency.

A v7 archive names no registry or namespace, so migrating one needs both from the importer: pass
`CatalogMigrationOptions(namespace, registry)` to `CatalogSchemaMigrator.migrateManifest`,
`CatalogArchiveReader.read`, or `CatalogValidationPolicy(migration = ...)`. Without them, migration
fails with `CATALOG_REGISTRY_MISSING` or `CATALOG_NAMESPACE_MISSING`.

Catalog keyword rules are defined once: `CatalogKeywords` in Kotlin (limits, pattern, and
normalization) and `MAX_CATALOG_KEYWORD_LENGTH`, `MAX_CATALOG_KEYWORDS`, and
`CATALOG_KEYWORD_PATTERN` in TypeScript, all pinned to the current schema. Registry and namespace
rules are defined the same way: `CatalogNamespaces` in Kotlin, and `CATALOG_REGISTRY_PATTERN`,
`MAX_CATALOG_REGISTRY_LENGTH`, `CATALOG_NAMESPACE_PATTERN`, `MIN_CATALOG_NAMESPACE_LENGTH`,
`MAX_CATALOG_NAMESPACE_LENGTH`, and `MAX_CATALOG_DEPENDENCY_VERSION_LENGTH` in TypeScript.
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
