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
namespace, and nothing in it says which one it belongs to, so the caller supplies it:

```kotlin
CatalogArchiveReader.read(input, migration = CatalogMigrationOptions("exchange.epistola.app/acme-corp"))
```

The namespace is written in full. An importer typically chooses the tenant's own. Without one, a
v7 or older archive is refused with `CATALOG_NAMESPACE_MISSING`; an archive at v8 names its own and
ignores the option. `CatalogValidator.validate(input, policy)` takes it as
`CatalogValidationPolicy(migration = ...)`.

The writer only emits the current wire version. Re-exporting a migrated catalog that carried a
fingerprint replaces it with the current algorithm's.

## Deterministic Output

The writer emits normalized forward-slash paths in sorted order with fixed
metadata, UTF-8 names, stable JSON property/map ordering, and stable
compression. Equivalent catalog content therefore produces identical ZIP
bytes. Catalog fingerprints are separate and are based on canonical catalog
content rather than ZIP layout.
