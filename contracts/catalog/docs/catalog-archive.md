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

An archive that carries releases (see "Dependencies inside an archive") is held to these limits for
its own content, and each release it carries to the same limits on its own (a carried archive's size
is its compressed size). The whole is further bounded by:

- at most 50 carried releases (`maxDependencies`)
- 100 MiB compressed for the archive together with the releases it carries (`maxTotalCompressedBytes`)
- 200 MiB expanded content of the archive and every release it carries (`maxTotalExpandedBytes`)

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

## Keys and File Names

A resource detail is filed at `resources/<type>/<key>.json`, and every resource key follows one rule
from wire v8: `^[a-z0-9]+(-[a-z0-9]+)*$`, 3 to 50 characters. The v7-to-v8 migration pads a shorter
catalog-v7 key on the right with `0` (`nl` becomes `nl0`) and cuts a longer one to 50 characters
without a trailing hyphen, and rewrites every reference to it the same way, in the manifest, in
resource details and in template content, references into other catalogs included. The rewrite
depends on the key alone, so two catalogs migrated independently still agree about each other's
keys; a rewrite that would collide with another key of its scope is refused, never resolved with a
different key (`catalog-compatibility.md` has the codes).

A migration rewrites documents but cannot move files, so a migrated catalog's `detailUrl` still names
the file under its source key. `CatalogArchiveReader` keys each detail by its manifest entry's
(rewritten) key, the validator accepts the source file name for a catalog read before wire v8, and
`CatalogArchiveWriter` files every detail under its key and points the entry's `detailUrl` at it.

## Paths Into the Catalog

Wire v8 states what a path into the catalog looks like (#95). It applies to a manifest resource
entry's `detailUrl` and to the deprecated `contentUrl` of an image or font face, the only members
that name a file in the catalog (`includes[].url`, `publisher.url` and `license.url` are absolute
URLs to elsewhere, and catalog presentation names images by key). Each is a **relative path below
the manifest's own directory**:

- an optional leading `./`, then one or more segments separated by `/`;
- a segment is RFC 3986 unreserved characters (`A-Z a-z 0-9 - . _ ~`) and percent-encodings,
  except an encoded `.`, `/` or `\` (`%2e`, `%2f`, `%5c`, in any case);
- no scheme (`https:`, `file:`, `C:`), no leading `/` (which also rules out `//host`), no backslash,
  no empty segment, and no `.` or `..` segment.

`./resources/template/letter.json`, `resources/letter.json` and `bin/<sha256>` are paths into the
catalog; `https://other.example/x.json`, `/x.json`, `../x.json`, `resources//x.json` and
`%2e%2e/x.json` are not. The rule is exported once, as `CatalogArchivePaths.PATTERN` in Kotlin and
`CATALOG_ARCHIVE_PATH_PATTERN` in TypeScript, and both are held to the schema.

A reader resolves the path against the **archive root** for a zip, and against the **manifest's URL**
for a catalog read from a URL (so `./resources/template/letter.json` next to
`https://catalogs.example/acme/catalog.json` is
`https://catalogs.example/acme/resources/template/letter.json`). Either way the result stays inside
the catalog: a path that passes the rule cannot climb out of the manifest's directory, and it is
resolved as written, never percent-decoded into something the rule forbids.

The migrator's wire check refuses a v8 document that breaks the rule
(`CATALOG_MANIFEST_DETAIL_URL_INVALID` for a `detailUrl`, `CATALOG_ASSET_PATH_INVALID` for a
`contentUrl`), and `CatalogValidator` reports the same codes for a catalog read at v8. Earlier wire
versions did not constrain these members and stay readable: the validator keeps holding them to
what it always did (`detailUrl` names the resource's canonical file; a `contentUrl` starts with `./`
and has no `..` segment).

Whatever an archive declares, at any wire version, `CatalogArchiveReader` never resolves a path
outside the archive. It finds resource documents by the archive's own `resources/**.json` entries,
never by following `detailUrl`, and its `ArchiveContentProvider` refuses a path that is absolute,
drive-qualified, contains a backslash, NUL, empty, `.` or `..` segment, or would resolve outside the
extraction root. The writer refuses to place a binary at such a path too.

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
default registry. Both are the catalog's name, which must not change when its releases move between
installations, so the importer passes the registry and namespace the catalog was given when it was
created (ADR 0003, amendment of 2026-10-21), never one derived from a tenant key or an installation
host on the way. Without either, a v7 or older archive is refused with `CATALOG_REGISTRY_MISSING` or
`CATALOG_NAMESPACE_MISSING`; an archive at v8 names its own and ignores the options. `CatalogValidator.validate(input, policy)` takes it as
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

Each `catalogDependencies` entry pins the exact release of the dependency the catalog renders
with, and that the archive's release was cut with, `releaseVersion`, and that release's fingerprint, `releaseFingerprint`, copied verbatim
from that release's own `release.fingerprint` (`v5:<hex>`). An installation that fetches the
dependency release from any registry or mirror checks the copy against it, with the algorithm it
names. A `releaseFingerprint` naming an algorithm this library does not know is invalid. Both are null for a dependency of an archive migrated from wire v7,
which never recorded a release; otherwise both are set. The migrator's wire check and
`CatalogValidator` report any other pairing, or a malformed fingerprint, as
`CATALOG_DEPENDENCY_CATALOG_FINGERPRINT_INVALID`. The Kotlin model binds either alone, so manifests
stored without the member still load.

## Dependencies Inside an Archive

A release pins exact releases of the catalogs it uses (`catalogDependencies`), and installing it
needs those releases. An exchange installs them first; an archive can also carry them, for an
installation that receives catalogs as files. From wire v8 a catalog archive **may** contain, for each
pin, an ordinary catalog archive of the pinned release at a path derived from the pin:

```text
dependencies/{registry}/{namespace}/{catalogKey}/{releaseVersion}.zip
```

The manifest has no member for it: the pin (`registry`, `namespace`, `catalogKey`, `releaseVersion`,
`releaseFingerprint`) says where to look. So a release's `catalog.json` and its fingerprint are the
same whether it is exported thin or with its dependencies; V5 covers the manifest and the resources,
never `dependencies/`. `CatalogArchiveWriter` writes the releases in `CatalogArchive.dependencies`
there and leaves `catalog.json` byte for byte as a thin export writes it.

- **Flat and transitive.** Every release reachable through the pins, a carried release's own pins
  included, is carried in the **outer** archive's `dependencies/`, once. A carried archive is itself
  thin: one that contains `dependencies/` refuses the archive (`CATALOG_ARCHIVE_DEPENDENCY_NESTED`).
  A reader resolves every pin, at any depth, against the outer archive's `dependencies/`.
- **Optional, all or nothing per pin.** A pin may have no carried release; the installation then
  resolves it from what it has or can fetch (ADR 0003), and reports it unavailable otherwise. A
  carried release must be the release its path names, by its own manifest's registry, namespace,
  key and `release.version` (`CATALOG_ARCHIVE_DEPENDENCY_IDENTITY_MISMATCH`), and match the
  `releaseFingerprint` of every pin that reaches it, checked with the algorithm the fingerprint names
  (`CATALOG_ARCHIVE_DEPENDENCY_FINGERPRINT_MISMATCH`). Otherwise the whole archive is refused.
- **Nothing else.** Every file under `dependencies/` must be such a path, each segment by its own
  rule and length limits (`CatalogDependencyArchivePaths`, `CATALOG_DEPENDENCY_ARCHIVE_PATH_PATTERN`;
  `CATALOG_ARCHIVE_DEPENDENCY_PATH_INVALID`), so a path cannot traverse or name anything else. A
  carried release no pin reaches is refused (`CATALOG_ARCHIVE_DEPENDENCY_UNREFERENCED`), as a resource
  detail the manifest does not declare is. Two pins in the graph naming different releases of one
  catalog refuse the archive (`CATALOG_ARCHIVE_DEPENDENCY_CONFLICT`); a release already refuses two
  paths needing different releases of one catalog, so a well-formed archive never has them. More
  releases than the policy allows is `CATALOG_ARCHIVE_DEPENDENCY_COUNT_EXCEEDED`.

`CatalogArchiveReader` reads each carried release as an archive of its own, under the per-archive
limits, and reports what is wrong inside it under its path (`dependencies/…/1.0.0.zip!/catalog.json`).
It exposes the carried releases that name themselves correctly and that a pin reaches as
`CatalogArchive.dependencies` (`EmbeddedCatalogRelease`), in **install order**: each before every
release that pins it. Closing the archive closes them. `CatalogValidator` applies the same rules,
also to an archive assembled in memory, and validates each carried release as a catalog of its own,
under its path.

Wire v5 to v7 archives never carry releases. Their rules are unchanged: a `dependencies/` directory
in one is an ordinary directory, held to the per-archive limits and not interpreted.

### Installing

An installation installs the carried releases first, in install order, then the archive's own
release. A catalog's releases are immutable and stay installed side by side (ADR 0003), so it adds
only the releases it lacks:

- a release the installation has (the same catalog, by registry, namespace and key, and the same
  number and fingerprint) is left as it is;
- a release it lacks is installed from its carried archive, next to any other releases of that
  catalog it has, which are left as they are: everything that renders with them keeps doing so;
- a release it has with the same number but a **different fingerprint** is different content under
  one name: the whole archive is refused, naming each, before anything changes.

The archive's own release is installed by the same rules, last. Installing never fills or creates a
draft.

## Deterministic Output

The writer emits normalized forward-slash paths in sorted order with fixed
metadata, UTF-8 names, stable JSON property/map ordering, and stable
compression. Equivalent catalog content therefore produces identical ZIP
bytes. Catalog fingerprints are separate and are based on canonical catalog
content rather than ZIP layout.
