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
fingerprint replaces it with the current algorithm's, in the form that names it (`v5:<hex>`).

## Fingerprints Name Their Algorithm

From wire v8 a fingerprint is written as the algorithm that computed it, a colon and the lowercase
SHA-256 hex: `v5:58f4…` (`QualifiedCatalogFingerprint`, pattern `^v[1-9][0-9]*:[0-9a-f]{64}$`). An
archive's own `release.fingerprint` must name the current algorithm, `v5`. Before wire v8 it is
bare hex, and the wire version implies the algorithm.

The algorithm is in the value because a fingerprint outlives the archive it was written in: a
catalog dependency's `releaseFingerprint` is kept when the current algorithm moves on, and has to
be checked with the algorithm that computed it. A verifier computes exactly the named algorithm
(`CatalogCanonicalizer.matchesQualifiedFingerprint`) and never tries the others, because an older
algorithm covers less: V4 covers no identity and no dependencies, so a copy that differs only there
would match it.

## Dependency Release Fingerprints

Each `catalogDependencies` entry records the release of the dependency the archive's release was
cut with, `releaseVersion`, and that release's fingerprint, `releaseFingerprint`, copied verbatim
from that release's own `release.fingerprint` (`v5:<hex>`). An installation that fetches the
dependency release from any registry or mirror checks the copy against it, with the algorithm it
names. A `releaseFingerprint` naming an algorithm this library does not know is invalid. Both are null for a dependency of an archive migrated from wire v7,
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
