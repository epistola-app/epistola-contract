# Portable Catalog Compatibility

This document records the compatibility boundary introduced when
`epistola-model` became the complete `epistola-catalog` aggregate and portable
validation, archive handling, migration, and fingerprinting moved into the
contract repository.

The artifacts are still pre-1.0. The changes below intentionally prefer one
canonical model over permanent compatibility aliases or duplicate classes.

## Explicit breaking changes

| Change | What is incompatible | Required migration |
| --- | --- | --- |
| Artifact rename | `app.epistola.contract:epistola-model`, `@epistola.app/epistola-model`, and `META-INF/epistola-model` are no longer published. | Depend on `app.epistola.contract:epistola-catalog`, `@epistola.app/epistola-catalog`, and `META-INF/epistola-catalog`. |
| npm public boundary | Implementation-specific `/generated/*` imports are no longer exported. | Import model, component, theme, and style types from the package root. |
| Explicit wire migration | Catalog-wide `schemaVersion: 5` is current and version 4 is the migration baseline. Pre-v4 and post-v5 archives are rejected. | Confirm the v4-to-v5 conversion or re-export from a current producer. |
| Canonical rich text | A text component's `content` must be a ProseMirror document object. Historical string and bare-array forms are invalid. | Open and save the content with a current editor, or transform it to `{ "type": "doc", "content": [...] }` before export. |
| Exact stencil provenance | Every stencil node declares a valid `stencilId`; published references carry `version`, while authoring references carry exact `draftVersion` and optionally their published base `version`. | Re-save with a current authoring client. Portable catalog content must omit `draftVersion` and include the matching published stencil resource version. |
| Stricter semantic validation | Malformed graphs, unsupported nodes or property shapes, invalid slots, placeholders, parameter schemas or bindings, expressions, theme/style references, data schemas/examples, and unresolved catalog references that were previously accepted may now produce validation errors. | Correct the reported findings before saving, publishing, or importing the content. Ordinary invalidity is returned as stable findings rather than an I/O exception. |
| Stencil nesting limit | A stencil-instance ancestry chain may contain at most five stencil levels. Direct/transitive recursion and whole-catalog stencil cycles are invalid. | Flatten or split deeper composition and remove recursive references. |

`StencilResource.version` is also required on the wire. That requirement
predates this aggregate move, but it is relevant when upgrading a producer
that still emits the older stencil-resource shape.

## Deliberately preserved compatibility

- Kotlin model package names remain unchanged; only artifact coordinates and
  resource paths moved.
- The model retains Jackson 2 annotation classes because consumers using
  either Jackson 2 or the Suite's Jackson 3 runtime can inspect them. Public
  APIs do not expose an `ObjectMapper`.
- Existing V1 and V2 catalog fingerprints remain accepted. New producers use
  the semantic V3 fingerprint through `currentFingerprint`; the existing
  `fingerprint` API retains its V1 result.
- Nested published-stencil composition is additive at the portable contract
  level. A consumer may expose a smaller authoring feature set while still
  recognizing the portable model.

## Authoring documents versus catalog exports

`TemplateDocument` is the shared document model. An authoring consumer may use
`draftVersion` while a stencil instance is being edited. A catalog is the
portable release/export aggregate: `CatalogValidator` rejects draft stencil
references and requires exact published versions.

The contract does not define a product's database upgrade policy. It validates
the document or catalog presented to it; it does not scan or rewrite stored
documents when a consumer upgrades the dependency.

## Future wire evolution

A non-round-trip-compatible wire change must introduce a new catalog-wide
`schemaVersion`, preserve the previous version's fixtures, and add an explicit
migration before the older version is accepted. Do not silently bind an older
version to newer classes or add compatibility defaults that make an incomplete
reference appear published.
