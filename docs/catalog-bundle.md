<!--
SPDX-FileCopyrightText: Epistola Nederland B.V.

SPDX-License-Identifier: EUPL-1.2
-->

# Catalog bundle (fat export)

A catalog release pins exact releases of the catalogs it uses (ADR 0003), and installing it needs
those releases. An exchange installs them first; a **catalog bundle** carries them along in one file,
for an installation that receives catalogs as files (acceptance and production installations that do
not reach an exchange).

## Format

A ZIP file with, at its root:

- `bundle.json`, the index;
- `catalogs/`, one ordinary **catalog archive** per catalog, byte for byte what exporting that release
  alone writes, named `NN-<catalogKey>-<version>.zip` (`NN` its place in the index, from `01`).

Nothing else. The index:

```json
{
  "bundleVersion": 1,
  "catalogs": [
    { "registry": "exchange.epistola.app", "namespace": "acme", "catalogKey": "base",
      "version": "1.0.0", "fingerprint": "v5:…", "path": "catalogs/01-base-1.0.0.zip" },
    { "registry": "exchange.epistola.app", "namespace": "acme", "catalogKey": "shared",
      "version": "1.0.0", "fingerprint": "v5:…", "path": "catalogs/02-shared-1.0.0.zip" },
    { "registry": "exchange.epistola.app", "namespace": "acme", "catalogKey": "letters",
      "version": "1.0.0", "fingerprint": null, "path": "catalogs/03-letters-1.0.0.zip" }
  ]
}
```

- `catalogs` lists every catalog in **install order**: each catalog before every catalog that uses it,
  the bundled catalog **last**.
- It holds **one release per catalog**: the one the bundled catalog's release is pinned to, followed
  transitively (a dependency's own pins). A release already refuses two paths needing different
  releases of one catalog, so the pins agree.
- `fingerprint` is the pinned `releaseFingerprint` for a dependency, and may be null for the bundled
  catalog (its archive carries its own `release.fingerprint`).

## Installing

A reader installs the dependencies first, then the bundled catalog:

- a catalog the installation has at the bundled release is left as it is;
- a catalog it lacks is installed (subscribed) from its archive;
- a catalog it has at **another** release, or under another registry or namespace, is a conflict:
  installing the bundled release would move that catalog for everything else using it. The whole
  bundle is refused, naming each conflict, before anything changes.

A reader refuses a bundle whose `bundleVersion` it does not know, an entry the index does not list or
a path outside `catalogs/`, and applies the same size limits as to a single archive.

A pre-release (ADR 0002, amendment 2) travels in a bundle like any release; it is never published to
an exchange, so a bundle or a single export is how it moves between installations.
