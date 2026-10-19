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

A reader installs the dependencies first, then the bundled catalog. A catalog's releases are
immutable and stay installed side by side (ADR 0003), so installing a bundle only adds the releases
the installation lacks:

- a release the installation has (the same catalog, by registry, namespace and key, and the same
  number and fingerprint) is left as it is;
- a release it lacks is installed (subscribed) from its archive, next to any other releases of that
  catalog it has, which are left as they are: everything that renders with them keeps doing so;
- a release it has with the same number but a **different fingerprint** is different content under
  one name: the whole bundle is refused, naming each, before anything changes.

A reader refuses a bundle whose `bundleVersion` it does not know, an entry the index does not list or
a path outside `catalogs/`, and applies the same size limits as to a single archive.
