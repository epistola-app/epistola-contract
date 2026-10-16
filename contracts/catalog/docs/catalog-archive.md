# Portable Catalog Archive

`CatalogArchiveReader` and `CatalogArchiveWriter` define the portable ZIP
transport for a complete catalog. They accept streams and expose binary entry
content through `ArchiveContentProvider`; the API does not expose filesystems or
JSON mappers.

## Safety Policy

The default `CatalogArchivePolicy` enforces:

- 10 MiB compressed input/output
- 20 MiB expanded content
- 10,000 entries
- a maximum 100:1 expansion ratio

The reader rejects absolute and drive-qualified paths, traversal segments,
backslashes, NULs, duplicate normalized paths, symbolic links, and encrypted
entries. It spools bounded compressed input and expanded entries to temporary
storage so central-directory metadata can be checked without buffering the
complete archive in memory. The returned archive owns that storage and must be
closed.

`CatalogArchive.paths` contains normalized regular files that can be opened
through `ArchiveContentProvider`. Explicit ZIP directory entries are accepted,
safety-checked, included in duplicate and entry-count enforcement, and then
omitted from `paths`; they are not readable content. A future explicit
directory metadata model is tracked in
[issue #54](https://github.com/epistola-app/epistola-contract/issues/54).

Archive-safety and malformed-content failures are returned as stable findings.
Transient or local I/O failures remain `IOException`s and are not mislabeled as
invalid catalog content.

## Migrating Older Archives

The reader passes `catalog.json` and every resource document through `CatalogSchemaMigrator`, so
an archive at an older wire version is returned at the current one, with a notice for each repair
in `CatalogArchiveReadResult.migrationNotices`. An archive from before wire v8 names no catalog
registry or namespace, and nothing in it says where it belongs, so the caller supplies both:

```kotlin
CatalogArchiveReader.read(
    input,
    migration = CatalogMigrationOptions(namespace = "acme-corp", registry = "exchange.epistola.app"),
)
```

The registry is written in full, the installation's default by its real name: an archive has no
default registry. An importer typically chooses the tenant's own namespace. Without either, a v7 or
older archive is refused with `CATALOG_REGISTRY_MISSING` or `CATALOG_NAMESPACE_MISSING`; an archive
at v8 names its own and ignores the options. `CatalogValidator.validate(input, policy)` takes it as
`CatalogValidationPolicy(migration = ...)`.

The writer only emits the current wire version. Re-exporting a migrated catalog that carried a
fingerprint replaces it with the current algorithm's.

## Dependency Release Fingerprints

Each `catalogDependencies` entry records the release of the dependency the archive's release was
cut with, `releaseVersion`, and that release's canonical fingerprint, `releaseFingerprint`: the
current algorithm's (V5) lowercase SHA-256 hex string, as `CatalogCanonicalizer.currentFingerprint`
produces it. An installation that fetches the dependency release from any registry or mirror
checks the copy against it. Both are null for a dependency of an archive migrated from wire v7,
which never recorded a release; otherwise both are set. The migrator's wire check and
`CatalogValidator` report any other pairing, or a malformed fingerprint, as
`CATALOG_DEPENDENCY_CATALOG_FINGERPRINT_INVALID`. The Kotlin model binds either alone, so manifests
stored without the member still load.

## Deterministic Output

The writer emits normalized forward-slash paths in sorted order with fixed
metadata, UTF-8 names, stable JSON property/map ordering, and stable
compression. Equivalent catalog content therefore produces identical ZIP
bytes. Catalog fingerprints are separate and are based on canonical catalog
content rather than ZIP layout.
