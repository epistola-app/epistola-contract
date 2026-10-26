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
| Catalog registry and namespace | Wire v8 requires `catalog.registry` (a DNS name such as `exchange.epistola.app` or `acme.com`, always in full) and `catalog.namespace` (a slug within that registry), as two separate members, and both on every catalog dependency. An archive at v7 or earlier names neither. | Migrating a v7 archive needs the registry and namespace the importer chooses, passed as `CatalogMigrationOptions(namespace, registry)`; without them the archive is refused with `CATALOG_REGISTRY_MISSING` or `CATALOG_NAMESPACE_MISSING`. |
| Declared catalog dependencies | Wire v8 requires every catalog the content names to be declared once in `catalogDependencies`, under an `alias` (the catalog key rule, unique in the list, never the catalog's own key; `CATALOG_DEPENDENCY_ALIAS_INVALID`, `CATALOG_DEPENDENCY_ALIAS_DUPLICATE`), by its name, and with the release it pins: its fingerprint (`releaseFingerprint`, in the form that names the algorithm) and the number it had (`releaseVersion`, never a range), the two null together or set together (`CATALOG_DEPENDENCY_CATALOG_FINGERPRINT_INVALID`). | v7 archives migrate with one declaration per other catalog their resource dependencies or content name, under its key as the alias, `releaseVersion: null` and `releaseFingerprint: null` (no release pinned), with a notice; pin a release before re-releasing. Producers write the fingerprint of the dependency release they cut with. |
| References by alias | Wire v8 content names another catalog by the alias `catalogDependencies` declares for it, never by registry or namespace (contract ADR 0003): a theme override's, stencil or image node's, font reference's and code-list binding's `catalogKey`, a template's `themeCatalogKey`, `catalog.defaultTheme.catalogKey`, a variant attribute key's qualifier, and a resource dependency's `catalogKey`. An alias content names that is not declared is `CATALOG_DEPENDENCY_CATALOG_UNDECLARED`; a `registry`, `namespace`, `themeRegistry` or `themeNamespace` in a reference is not a v8 member (`CATALOG_SCHEMA_UNKNOWN` from the migrator's wire check; an undeclared property of a stencil node). The Kotlin model drops `registry` and `namespace` from `DependencyRef`, `CatalogThemeRef`, `ThemeRefOverride`, `CodeListBindingRef` and `FontRef`, and `themeRegistry`/`themeNamespace` from `TemplateResource`, and ignores them when binding; `CatalogDependency.alias` is added, defaulting to the catalog key. A resolver (`CatalogDependencyResolver`) receives the declared catalog's name, never an alias. | A v7 reference keeps its value: the catalog it names is declared under its key as the alias. A reference qualified with a registry or namespace (the template document schema shared with v5 to v7 allowed it in a theme reference) is mapped to the alias of the catalog it names (`CATALOG_REFERENCE_ALIASED`), or is a finding when it names a catalog in another namespace (`CATALOG_REFERENCE_ALIAS_UNRESOLVED`). Documents stored under an earlier draft of wire v8 with qualified references still bind, without the qualifiers; a consumer that stored such references maps them to aliases itself. |
| Bounded lowercase keywords | Wire v7 accepts catalog keywords only as lowercase ASCII letters and digits in hyphen-separated parts, at most 30 characters, and at most 20 per catalog. A v7 archive with other keywords is invalid. | v6 archives migrate with notices: keywords are normalized, shortened, merged, or removed. Producers normalize with `CatalogKeywords.normalize` before emitting v7. |
| Canonical rich text | A text component's `content` must be a ProseMirror document object. Historical string and bare-array forms are invalid. | Open and save the content with a current editor, or transform it to `{ "type": "doc", "content": [...] }` before export. |
| A resource's address is its key | Wire v8 calls a resource's public address its `key` and an `id` only a generated identifier that is not an address. `slug` becomes `key` on the catalog, every resource entry, resource dependency, resource, variant and code-list binding; `themeId` becomes `themeKey` on a template and in a document's `ThemeRef`; a stencil node's `stencilId` becomes `stencilKey`, an image node's `assetId` `imageKey`, a `fontFamily` font reference's `slug` `key`, the presentation's `iconAssetSlug` and `imageAssetSlugs` `iconImageKey` and `imageKeys`, and an attribute assignment's `catalog` `catalogKey`. The finding codes follow: `CATALOG_RESOURCE_KEY_MISMATCH`, `CATALOG_RESOURCE_KEY_INVALID` and `CATALOG_TEMPLATE_VARIANT_KEY_DUPLICATE` replace their `SLUG` and `ID` forms. The rename leaves values unchanged (the key rule is its own row below). A v8 document using an old name is refused by the migrator's wire check (`CATALOG_SCHEMA_UNKNOWN`), and the old node props are undeclared (`TEMPLATE_NODE_PROPERTY_INVALID`). The Kotlin properties are renamed alike (`CatalogInfo.key`, `CatalogResource.key`, `ResourceEntry.key`, `DependencyRef.key`, `VariantEntry.key`, `CodeListBindingRef.key`, `TemplateResource.themeKey`, `ThemeRefOverride.themeKey`, `CatalogPresentation.iconImageKey` and `.imageKeys`, `FontRef.key`, `AttributeAssignment.catalogKey`), as are `CatalogResourceReference.key` and `CatalogKeys` (formerly `CatalogSlugs`). | v7 archives migrate silently: the value is unchanged, so like v7's rename of a variant's `id` there is nothing to report. The model still binds the old names (`@JsonAlias`), so manifests and details a consumer stored under earlier versions keep loading with plain Jackson. Producers write the new names. |
| One key rule | Wire v8 holds every resource key to one rule, `^[a-z0-9]+(-[a-z0-9]+)*$` with 3 to 50 characters, a leading digit allowed for every type (`CatalogKeys`, `CATALOG_RESOURCE_KEY_PATTERN`, `MIN_`/`MAX_CATALOG_RESOURCE_KEY_LENGTH`): the keys of templates, variants, stencils, themes, attributes, code lists, fonts, images and data examples, and every position that names one -- `themeKey` (a template's and a document's theme reference), a stencil node's `stencilKey`, an image node's `imageKey`, a `fontFamily` font reference's `key`, a code-list binding's `key`, a resource dependency's `key`, an attribute assignment's `key`, a variant's attribute keys, and the presentation's `iconImageKey` and `imageKeys`. Wire v7 bounded keys per type (1 to 64 characters, a leading letter except for images). The catalog's own key keeps its rule (a leading letter, 3 to 50). `CATALOG_RESOURCE_KEY_INVALID` reports a resource's, variant's, variant attribute's, dependency's or reference's key outside the rule; an attribute assignment's is `CATALOG_ATTRIBUTE_IDENTITY_INVALID`, a stencil node's `STENCIL_REFERENCE_INVALID`. | v7 archives migrate: a key shorter than 3 characters is padded on the right with `0` to 3 (`a` becomes `a00`, `nl` `nl0`), and one longer than 50 is cut to 50 without a trailing hyphen, each with one notice per key the catalog defines (`CATALOG_RESOURCE_KEY_PADDED`, `CATALOG_RESOURCE_KEY_TRUNCATED`; path, old key, new key). Every reference to it is rewritten the same way without a notice of its own, including references into another catalog: the rewrite (`CatalogKeys.migrate`) is a function of the key alone, so that catalog's own migration arrives at the same key, and independently migrated catalogs still agree. That is also why a collision is not resolved: when a rewritten key equals another key of its scope (a resource of the same type in the catalog, a variant or data example of the same template, an attribute of the same variant), choosing a third would depend on the catalog, and references from elsewhere would point at the wrong resource, so the archive is refused (`CATALOG_RESOURCE_KEY_PADDING_COLLISION`, `CATALOG_RESOURCE_KEY_TRUNCATION_COLLISION`). A key with characters outside the pattern is not repaired; it stays a finding. A migrated archive keeps its files where the source keys put them, which the reader and validator accept for an archive read before v8; a re-export files them under the new keys. |
| Data example keys | Wire v8 gives every template data example a required `key`, its address within the template (the one key rule above), unique there (`CATALOG_TEMPLATE_EXAMPLE_KEY_DUPLICATE`); a missing or malformed one is `CATALOG_TEMPLATE_EXAMPLE_KEY_INVALID`. `name` stays the display name, and stays unique too. `DataExampleEntry.key` is added to the Kotlin model, nullable and omitted when null, so examples stored before v8 still bind. | v7 archives migrate with one `CATALOG_DATA_EXAMPLE_KEY_ASSIGNED` notice per example: the key is derived from the name (`CatalogKeys.deriveDataExampleKey`; NFD without combining marks, lowercased, every other run of characters one hyphen, cut to 50; `example` when nothing is left; padded with `0` to 3 characters; `-2`, `-3`, ... when taken). Producers write the key. |
| Paths into the catalog | Wire v8 constrains a manifest `detailUrl`, and an image's or font face's deprecated `contentUrl`, to a relative path below the manifest's directory (`CatalogArchivePaths`, see `catalog-archive.md`). A v8 archive with another form is refused by the migrator's wire check and reported by `CatalogValidator` (`CATALOG_MANIFEST_DETAIL_URL_INVALID`, `CATALOG_ASSET_PATH_INVALID`). | None for archives written by Epistola, which already write `./resources/<type>/<key>.json` and `bin/<sha256>`. Earlier wire versions are not held to the rule and stay readable. |
| Unversioned stencils | From wire v8 a stencil carries no `version`, and a stencil node declares a valid `stencilKey`, optionally the `catalogKey` alias of another catalog, and no `version`, `draftVersion`, `namespace` or `registry` (they are reported as undeclared properties). A stencil resolves through its catalog's dependency, or within the same catalog. | v7 archives migrate with notices. Producers stop writing stencil versions; authoring consumers resolve a draft stencil through the release being edited (a draft), not a node property. |
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
- The members wire v8 adds to a template (a data example's `key`, `description`, a variant's
  `metadata`) are covered by V5 and V6 only. V1 to V4 canonicalize a resource as a v7 producer serialized
  it, without them, so a key the v7-to-v8 migration derived moves no published fingerprint.
- Existing V1 through V3 catalog fingerprints remain accepted when reading legacy v4/v5 input.
  V1 to V4 canonicalize the members wire v8 renamed under their old names, so the rename moves no
  legacy fingerprint and only V5 and V6 name a resource's address `key`.
  v6 and v7 catalogs carry V4; a v6 fingerprint over keywords that the v7 migration rewrote, a
  v7 fingerprint over stencil versions that the v8 migration removed, and a v7 fingerprint over
  keys that the v8 migration padded or cut, still verify. V1 to V4 are computed over the source
  keys, restored from the archive's raw documents, for any catalog read before wire v8. v8 catalogs use
  V6, written as `v6:<hex>` (`currentQualifiedFingerprint`); `currentFingerprint` still returns
  the bare hex. V5, which v8 used before V6 (contract ADR 0003, amendment of 2026-10-22), stays
  verifiable with exactly the values it had: a pin or a base that names `v5` is checked with V5. The
  existing `fingerprint` API retains its V1 result and the exact-version overload is additive.
- The 1.0.1 `CatalogInfo(key, name, description)` construction shape remains source-compatible
  for positional callers; the first parameter is named `key` from wire v8.
  New discovery metadata is exposed through `create` and `copyWithMetadata`. Consumers must
  recompile when upgrading the Kotlin artifact; drop-in compatibility with JVM bytecode compiled
  against an older JAR is not part of the catalog compatibility guarantee.
- Nested published-stencil composition is additive at the portable contract
  level. A consumer may expose a smaller authoring feature set while still
  recognizing the portable model.

## Authoring documents versus catalog exports

`TemplateDocument` is the shared document model. From wire v8 a stencil node
names its stencil by `stencilKey`, `catalogKey` and `namespace` only, in authoring
and in exports alike: which version is used follows from the release the
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

Wire v8 identifies a catalog by registry, namespace and key and versions catalogs as a whole. It is
not round-trip compatible with v7 -- registry and namespace are new required identity, and stencil versions are
gone -- so it is a new wire version with an explicit migration, and v7 stays valid at 7.

The Kotlin model changes with it, which is why v8 belongs to contract 2.0:

- `StencilResource.version`, `CatalogResourceReference.version` and `.draftVersion`,
  `TemplateValidationContext.allowDraftStencilReferences`, and the `version` parameter of
  `TemplateValidationContext.forStencil` are removed.
- `CatalogInfo.registry` and `.namespace`, `CatalogManifest.catalogDependencies`
  (`CatalogDependency`, with its `alias`), and `registry` and `namespace` on
  `CatalogResourceReference` (the catalog a resolver is asked about) are added.
  `CatalogMigrationOptions` gains `registry` after `namespace`, so a one-argument call still names
  the namespace. References name another catalog by alias (contract ADR 0003), so an earlier draft's
  `registry` and `namespace` on `DependencyRef`, `CodeListBindingRef`, `FontRef`,
  `ThemeRefOverride` and `CatalogThemeRef`, `TemplateResource.themeRegistry` and `.themeNamespace`,
  and `TemplateValidationContext.currentNamespace`/`currentRegistry` (and those parameters of
  `forStencil`) are gone again.

The new members that the wire requires (`CatalogInfo.registry` and `.namespace`) are nullable in
the model, as keywords were at v7: consumers rebind manifests they stored under v7 with plain
Jackson, and those name neither; `CatalogDependency.alias` defaults to the catalog key for the same
reason. The
migrator's wire check and `CatalogValidator` enforce them. `StencilResource` likewise ignores a stored `version` when binding, and so does `CatalogDependency`: an earlier draft of wire v8 gave a declaration a version selector, `version`, which a declaration no longer has (it pins an exact release).

Wire v8 also adds optional members, changed in place before v8 was released:

- `TemplateResource.description` (#97): plain text for the template's authors and readers, at most
  2000 characters, not rendered. It is the source of the template's manifest entry `description`,
  which a listing shows without opening the detail: when both are present they must be equal
  (`CATALOG_RESOURCE_DESCRIPTION_MISMATCH`). An entry may carry one while the detail does not.
- `CatalogResource.metadata`: producer-specific data the contract carries but does not define, on
  every resource type. Each member is named by a reverse-DNS namespace the producer owns, and its
  value is a JSON object. The namespace names the producer, and no other producer writes into it; a
  consumer that does not recognise a namespace keeps it, through reading, migration and writing. The
  contract does not interpret the values. A resource names one to sixteen namespaces, each at most
  100 characters (`CatalogMetadata`), and its metadata is at most 8 KiB serialized
  (`CATALOG_RESOURCE_METADATA_TOO_LARGE`, a limit the schema cannot state); other breaches are
  `CATALOG_RESOURCE_METADATA_INVALID`. Empty metadata is omitted, never written as `{}`. Metadata is
  what a producer says about the resource, not its content: no fingerprint covers it. In Kotlin it
  binds as `Map<String, Map<String, Any?>>`, the plain maps the model uses for other free-form JSON
  such as `dataModel`.

  A template variant carries no metadata of its own. An early wire-v8 archive that put metadata on
  a variant (`variants[].metadata`) has it folded into the template's when read: a variant's
  `{"<namespace>": V}` becomes the template's `{"<namespace>": {"variants": {"<variant key>": V}}}`,
  with the notice `CATALOG_VARIANT_METADATA_FOLDED`. Folding that would overwrite a value already
  there is the finding `CATALOG_VARIANT_METADATA_UNFOLDABLE`, never a choice.
- `ReleaseInfo.releaseId`: the release's id, by the API's `CatalogReleaseId` rule (lowercase
  letters, digits and single hyphens, 4 to 36 characters, `CatalogReleaseIds`;
  `CATALOG_RELEASE_ID_INVALID`). It is unique among the releases of the catalog's name and kept when
  the release moves to another installation, through an exchange, a registry or a file, so the
  release has the same id everywhere. A receiver assigns one only when the archive carries none.
- Patch release numbers (contract ADR 0003, amendment of 2026-10-22): a release number may carry a
  pre-release label naming a namespace and a sequence number, `1.3.1-gemeente-x.1`, for a release
  issued by someone who does not hold the catalog's namespace (`CatalogReleaseVersions.PATTERN`,
  `isCore`, `patchNamespace`; `CATALOG_RELEASE_VERSION_PATTERN`, with the plain rule as
  `CORE_PATTERN` and `CATALOG_RELEASE_VERSION_CORE_PATTERN`). A carried release's path accepts it
  too. A release number is at most 128 characters, so that every valid namespace (up to 63) can
  label one. The API's `ReleaseVersion` is the same rule, with the same 128-character limit.
- `ReleaseInfo.metadata`: producer-specific facts about the release, under the rules of a resource's
  `metadata` (a reverse-DNS namespace per producer, one to sixteen of them, at most 8 KiB
  serialized; `CATALOG_RELEASE_METADATA_INVALID`, `CATALOG_RELEASE_METADATA_TOO_LARGE`). Epistola
  Suite records the rendering-defaults version a release was cut with as
  `{"app.epistola.suite": {"renderingDefaultsVersion": 6}}`. A consumer keeps every namespace, one it
  does not recognise included, member for member, when it installs, migrates or re-exports the
  release. The rendering-defaults version is a property of the generator that renders, not of the
  catalog: another generator may render the same release with defaults of its own, so it stays
  release metadata outside every fingerprint (#96). An installation that lacks the defaults a release
  names refuses it (`catalog-rendering-defaults-too-new`, ADR 0008).
- `ReleaseInfo.base` (`ReleaseBase`): the release this one was made from, its draft's base, by
  identity only (ADR 0005, "The merge base travels with the release"): `version`, a release number,
  and `fingerprint`, that release's content hash, its catalog fingerprint in the qualified form (`v6:<hex>` for a base made under V6, any known
  algorithm), and nothing else. A receiver follows the lineage through it, by hash, to the closest
  release a draft and the merged release share; the merge needs that release installed and finds it
  by the fingerprint, whatever its number or name there. A receiver keeps the base
  as received. Absent for a first release, or when the producer does not know its base. Finding:
  `CATALOG_RELEASE_BASE_INVALID`, from the validator and the migrator's wire check.

  None of these is covered by the fingerprint: like the number and `releasedAt`, they are release
  metadata, and every fingerprint published before they existed stays valid.

A catalog carries its font faces as resources of its release, as it carries images: the family in
its resource detail, each face's bytes in the archive, both inside the fingerprint. That holds for a
catalog an installation supplies itself as well (Epistola Suite's `system` catalog), so a release
that pins such a catalog renders with exactly the faces it was cut with. Earlier text said bundled
system fonts were never exported; nothing in the model or the validator ever refused them.

Here, backwards compatibility applies to catalog JSON. Common Kotlin construction and property-access
patterns remain source-compatible where practical, but the Kotlin API is a recompile-on-upgrade
boundary. Epistola Suite and Exchange recompile against each upgrade; the 1.x artifact does not
promise that an already-compiled JVM application can replace the catalog JAR without recompilation.
