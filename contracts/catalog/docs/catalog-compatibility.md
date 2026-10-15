# Portable Catalog Compatibility

This document records the compatibility boundary introduced when
`epistola-model` became the complete `epistola-catalog` aggregate and portable
validation, archive handling, migration, and fingerprinting moved into the
contract repository.

The catalog artifact is stable. Wires v6 and v7 are minor-release evolutions: a new consumer can
read v4 through v8, while an old consumer is not expected to read a newly emitted archive at a newer
wire version. Wire v8 belongs to contract 2.0 and changes the Kotlin model incompatibly (see
"Wire v8" below).

## Explicit breaking changes

| Change | What is incompatible | Required migration |
| --- | --- | --- |
| Artifact rename | `app.epistola.contract:epistola-model`, `@epistola.app/epistola-model`, and `META-INF/epistola-model` are no longer published. | Depend on `app.epistola.contract:epistola-catalog`, `@epistola.app/epistola-catalog`, and `META-INF/epistola-catalog`. |
| npm public boundary | Implementation-specific `/generated/*` imports are no longer exported. | Import model, component, theme, and style types from the package root. |
| Explicit wire migration | Catalog-wide `schemaVersion: 8` is current and version 4 is the migration baseline. Pre-v4 and post-v8 archives are rejected. | Confirm the stepwise conversion to v8 or re-export from a current producer. |
| Catalog namespace | Wire v8 requires `catalog.namespace`, written in full (a domain, or `host/name` on an exchange), and a `namespace` on every resource dependency. An archive at v7 or earlier names none. | Migrating a v7 archive needs the namespace the importer chooses, passed as `CatalogMigrationOptions(namespace)`; without it the archive is refused with `CATALOG_NAMESPACE_MISSING`. |
| Declared catalog dependencies | Wire v8 requires every catalog named by a resource dependency to be declared once in `catalogDependencies`, with a version selector and the release it was cut with. | v7 archives migrate with one declaration per catalog, selector `*` and `releaseVersion: null`, with a notice; set the selector before re-releasing. |
| Bounded lowercase keywords | Wire v7 accepts catalog keywords only as lowercase ASCII letters and digits in hyphen-separated parts, at most 30 characters, and at most 20 per catalog. A v7 archive with other keywords is invalid. | v6 archives migrate with notices: keywords are normalized, shortened, merged, or removed. Producers normalize with `CatalogKeywords.normalize` before emitting v7. |
| Canonical rich text | A text component's `content` must be a ProseMirror document object. Historical string and bare-array forms are invalid. | Open and save the content with a current editor, or transform it to `{ "type": "doc", "content": [...] }` before export. |
| Unversioned stencils | From wire v8 a stencil carries no `version`, and a stencil node declares a valid `stencilId`, optionally `catalogKey` and `namespace`, and no `version` or `draftVersion` (they are reported as undeclared properties). A stencil resolves through its catalog's dependency, or within the same catalog. | v7 archives migrate with notices. Producers stop writing stencil versions; authoring consumers resolve a draft stencil through the catalog version being edited, not a node property. |
| Stricter semantic validation | Malformed graphs, unsupported nodes or property shapes, invalid slots, placeholders, parameter schemas or bindings, expressions, theme/style references, data schemas/examples, and unresolved catalog references that were previously accepted may now produce validation errors. | Correct the reported findings before saving, publishing, or importing the content. Ordinary invalidity is returned as stable findings rather than an I/O exception. |
| Stencil nesting limit | A stencil-instance ancestry chain may contain at most five stencil levels. Direct/transitive recursion and whole-catalog stencil cycles are invalid. | Flatten or split deeper composition and remove recursive references. |

Until wire v7, `StencilResource.version` was required on the wire. Wire v8 removes it; the model
ignores it when binding a stored v7 document.

## Deliberately preserved compatibility

- Kotlin model package names remain unchanged; only artifact coordinates and
  resource paths moved.
- The model retains Jackson 2 annotation classes because consumers using
  either Jackson 2 or the Suite's Jackson 3 runtime can inspect them. Public
  APIs do not expose an `ObjectMapper`.
- Existing V1 through V3 catalog fingerprints remain accepted when reading legacy v4/v5 input.
  v6 and v7 catalogs carry V4; a v6 fingerprint over keywords that the v7 migration rewrote, and a
  v7 fingerprint over stencil versions that the v8 migration removed, still verify. v8 catalogs use
  V5 through `currentFingerprint`. The existing `fingerprint` API retains its V1 result and the
  exact-version overload is additive.
- The 1.0.1 `CatalogInfo(slug, name, description)` construction shape remains source-compatible.
  New discovery metadata is exposed through `create` and `copyWithMetadata`. Consumers must
  recompile when upgrading the Kotlin artifact; drop-in compatibility with JVM bytecode compiled
  against an older JAR is not part of the catalog compatibility guarantee.
- Nested published-stencil composition is additive at the portable contract
  level. A consumer may expose a smaller authoring feature set while still
  recognizing the portable model.

## Authoring documents versus catalog exports

`TemplateDocument` is the shared document model. From wire v8 a stencil node
names its stencil by `stencilId`, `catalogKey` and `namespace` only, in authoring
and in exports alike: which version is used follows from the catalog version the
document belongs to and its catalog dependencies. `templateModelVersion` stays 1:
the template-document schema keeps node `props` free-form, and the stencil-node
rules live in `TemplateValidator`.

The contract does not define a product's database upgrade policy. It validates
the document or catalog presented to it; it does not scan or rewrite stored
documents when a consumer upgrades the dependency.

## Future wire evolution

A non-round-trip-compatible wire change must introduce a new catalog-wide
`schemaVersion`, preserve the previous version's fixtures, and add an explicit
migration before the older version is accepted. Do not silently bind an older
version to newer classes or add compatibility defaults that make an incomplete
reference appear published.

### Planned for contract 2.0: require a data example

Contract 2.0 will require every template data contract to contain at least one
named data example. The REST API and portable catalog representation must make
`dataExamples` required and non-null, with `minItems: 1`. Every example remains
subject to validation against the template's `dataModel`.

This requirement is deliberately deferred because it is not backwards
compatible. A migration cannot reliably synthesize a valid example from an
arbitrary JSON Schema. When the requirement is introduced, the corresponding
catalog wire version must preserve the preceding schema and use an explicit
migration finding for templates without examples. Producers must add a valid
example and re-export those catalogs; the migration must not invent placeholder
data or silently omit the template.

This is a 2.0 planning decision only. It does not tighten the current wire
schema or prevent 1.x consumers from importing existing catalogs with absent,
null, or empty `dataExamples`.

Wire v6 intentionally keeps `attributes`, `keywords`, `presentation`, and `license` optional so the
artifact can ship as a backwards-compatible minor release. Catalog attributes describe the catalog
using the same qualified attribute vocabulary as other Epistola entities; no attribute, including
`system.locale`, is mandatory. An omitted license means that the portable catalog makes no license
declaration; it does not mean public domain or unrestricted use. A future artifact major may make
selected fields required after consumers have adopted v6; that tightening must not be backported to
the 1.x contract.

Wire v7 keeps `keywords` optional and changes only which values it accepts. It is not a tightening
of v6: a v6 archive keeps its meaning and migrates, because keywords are discovery metadata that
nothing references, and the migration repairs them instead of rejecting the catalog. `CatalogInfo`
still binds any keywords, so consumers can rebind manifests they stored under v6. The migrator's
wire check and `CatalogValidator` enforce the v7 rules instead.

### Wire v8

Wire v8 identifies a catalog by namespace and key and versions catalogs as a whole. It is not
round-trip compatible with v7 -- a namespace is new required identity, and stencil versions are
gone -- so it is a new wire version with an explicit migration, and v7 stays valid at 7.

The Kotlin model changes with it, which is why v8 belongs to contract 2.0:

- `StencilResource.version`, `CatalogResourceReference.version` and `.draftVersion`,
  `TemplateValidationContext.allowDraftStencilReferences`, and the `version` parameter of
  `TemplateValidationContext.forStencil` are removed.
- `CatalogInfo.namespace`, `CatalogManifest.catalogDependencies` (`CatalogDependency`), and
  `namespace` on every `DependencyRef`, `CatalogResourceReference`, `CodeListBindingRef`, `FontRef`,
  and `ThemeRefOverride`, plus `TemplateResource.themeNamespace`, are added.

The new members that the wire requires (`CatalogInfo.namespace`, `DependencyRef.namespace`) are
nullable in the model, as keywords were at v7: consumers rebind manifests they stored under v7 with
plain Jackson, and those name no namespace. The migrator's wire check and `CatalogValidator`
enforce them. `StencilResource` likewise ignores a stored `version` when binding.

Here, backwards compatibility applies to catalog JSON. Common Kotlin construction and property-access
patterns remain source-compatible where practical, but the Kotlin API is a recompile-on-upgrade
boundary. Epistola Suite and Exchange recompile against each upgrade; the 1.x artifact does not
promise that an already-compiled JVM application can replace the catalog JAR without recompilation.
