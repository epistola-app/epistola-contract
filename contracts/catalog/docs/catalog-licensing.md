# Catalog licensing

Wire v6 lets a publisher make an optional catalog-wide license declaration in `catalog.json`:

```json
{
  "catalog": {
    "license": {
      "name": "Creative Commons Attribution 4.0 International",
      "spdxExpression": "CC-BY-4.0",
      "url": "https://creativecommons.org/licenses/by/4.0/",
      "copyrightText": "Copyright 2026 Example Publisher"
    }
  }
}
```

`name` is required when the license object is present and gives consumers a human-readable label.
`spdxExpression` carries a publisher-authored SPDX license expression when one applies. `url` points
to the complete license terms, and `copyrightText` carries the publisher's copyright statement.
The last three properties are optional so custom and proprietary licenses remain representable.

The declaration applies to the catalog as a whole, including its portable resources and bundled
asset content. Wire v6 does not define resource-level overrides. A publisher must only declare terms
it has the authority to grant; the contract validates structure but does not determine rights or
SPDX-list membership.

License metadata participates in V4 canonical fingerprints, so changing a declaration changes the
catalog content identity. Older catalogs and v6 catalogs that omit `license` remain valid. Omission
means that the catalog makes no portable license declaration; it must not be interpreted as public
domain, unrestricted use, or permission to redistribute.
