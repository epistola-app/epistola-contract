# Portable Catalog Validation

`epistola-catalog` is the validation boundary for content that can move between
Suite, Exchange, and other consumers. Validation is product-neutral: ordinary
invalid content produces stable findings instead of exceptions, while consumer
state is accessed only through portable context interfaces.

## Suite Rule Inventory

The Suite implementations under
`modules/epistola-core/.../templates/validation` were classified as follows
before extraction:

| Suite implementation | Classification | Catalog implementation |
| --- | --- | --- |
| `TemplateDocumentGraphValidator` | Portable | Graph size, root, identity, ownership, reachability, parent, depth, and cycle findings |
| `PlaceholderValidator` | Portable | Placeholder namespace, slug, nesting, ancestry, stencil recursion, binding shape, and alias findings |
| `NodeParameterBindingValidator` | Portable | Declared/required bindings and JSONata syntax through `TemplateValidationContext` |
| `NodeParameterSchemaProvider` | Portable boundary; Suite wiring is product-specific | `TemplateValidationContext.resolveParameterSchema` |
| `ParameterSchemaValidator` | Portable | Parameter schema/name/type/default findings |
| `PageHeaderCardinalityValidator` | Portable | Page header and footer placement findings (see below); the count and root-placement limits were dropped in 1.4.0 |
| `JsonSchemaValidator` data/schema checks | Portable at whole-catalog level | Owned by `ResourceValidator`/`CatalogValidator`, not the template graph validator |
| `JsonSchemaValidator` compatibility suggestions | Suite editing/migration UX | Remains in Suite |
| `DataModelValidationException` and Suite `ValidationException` mapping | Suite presentation/API | Remains in a Suite adapter |
| `RefTypeRegistry` classpath/UI labels | Suite runtime/editor integration | Remains in Suite unless its referenced schemas become catalog wire resources |

Tenant authorization, repositories, persistence lifecycle, installed
cross-catalog dependencies, import/upsert conflict handling, and
renderer-specific capability checks remain outside this artifact.

## API

```kotlin
val report = TemplateValidator.validate(document, context)
```

`TemplateValidationContext` resolves catalog-scoped resources, dynamic
parameter schemas, and effective style presets. For stencil resource content,
`currentRegistry`, `currentNamespace`, `currentCatalogKey` and `containingStencil` identify the owning resource so
the validator can distinguish cross-catalog references from direct or
transitive self-reference. A lookup can return `UNKNOWN`
when a consumer is intentionally validating without the full catalog graph;
only an explicit `MISSING` result produces a missing-reference finding.

Stencil composition is part of the portable specification. From wire v8 a
stencil node names its stencil by `stencilKey` (`stencilId` until wire v8), optionally with the
`catalogKey` alias of another catalog the owning catalog depends on (contract ADR 0003; omitted, or
the owning catalog's own key, means the owning catalog), and carries no version: catalogs are
versioned as a whole, so the stencil reached is the one in the release the catalog dependency pins,
or the one in the same catalog. The context resolver receives that identity as a
`CatalogResourceReference`. A node's `version`, `draftVersion`, `namespace` or `registry` is
reported as an undeclared property (`TEMPLATE_NODE_PROPERTY_INVALID`), as is a leftover `isDraft`.

Standalone validation counts the containing stencil as nesting level one and
rejects a repeated ancestor identity or a chain deeper than five. Consumers
may expose a smaller authoring feature set while still accepting and
round-tripping conforming catalog content.

All findings contain a stable string code, `ERROR` or `WARNING` severity,
document-relative path, and human-readable message. Reports are deduplicated
and sorted by path, code, then message.

## Styles

A style key that has no effect is a `WARNING`, never an error (#97), so a typo such as
`font-weight` is reported without making the document invalid. Two codes cover it, wherever styles
appear:

- `TEMPLATE_STYLE_UNKNOWN`: the style registry does not define the key.
- `TEMPLATE_STYLE_NOT_APPLICABLE`: the registry defines it, but the place it is set does not accept
  it.

| Where | Path | Accepted keys |
| --- | --- | --- |
| A node's `styles` | `nodes.<id>.styles.<key>` | The component's `applicableStyles` |
| A component's style map: a table's `cellStyles`, a columns node's `columnStyles` | `nodes.<id>.props.cellStyles.<row>-<col>.<key>`, `nodes.<id>.props.columnStyles[<i>].<key>` | The style map's `applicableStyles` |
| `documentStylesOverride` | `documentStylesOverride.<key>` | Any registry key (only unknown keys are reported) |
| A theme's `documentStyles` | `documentStyles.<key>` | Any registry key (only unknown keys are reported) |
| A theme's `defaultBlockStyles` | `defaultBlockStyles.<key>` | Margin keys only (#59) |
| A theme's block style preset | `blockStylePresets.<name>.styles.<key>` | Keys at least one component in the preset's `applicableTo` accepts, or any component when that is empty |

Until #97 the first row was an error under the same two codes; nothing else checked style keys, and
renderers ignored the keys. Theme styles are checked by
`TemplateValidator.validateThemeStyles(documentStyles, blockStylePresets, defaultBlockStyles)`, which
`CatalogValidator` runs for every theme resource. `TemplateValidationCodes.WARNINGS` lists the codes
that are only ever warnings. A report's `valid` counts errors only, for templates and whole
catalogs alike.

The shape of a style map is an error, `TEMPLATE_NODE_PROPERTY_INVALID`:

- `cellStyles` that is not an object, a key that does not match `^\d+-\d+$`, or an entry that is
  not an object.
- A cell outside the table. Keys are `<row>-<col>`, 0-based, counted over the table's `rows` and
  `columns` props. A table without numeric `rows` and `columns` is not range-checked.
- `columnStyles` that is not an array, or an entry that is not an object (use `{}` for a column
  without styles).
- More entries than columns. Columns are counted by the length of `columnSizes`. The array may be
  shorter.

Style values are not checked, for these properties as for the others.

## Page headers and footers

A template may contain any number of `pageheader` and `pagefooter` nodes,
anywhere in the flow: inside stencils, conditionals and loops. Where one sits
decides which pages it applies to: a header applies to what comes after it, a
footer from the page it lands on. The renderer owns that schedule; the
validator reports one shape, `PAGEBAND_NESTED` (error): a header or footer
inside another header or footer.

## Whole-catalog validation

`CatalogValidator.validate(input, policy)` is the complete portable validation entry point for an
archive stream. It applies `CatalogArchiveReader` safety limits and wire migration before checking
manifest/detail consistency, required files, resource references, resource-specific invariants,
data schemas and examples, release metadata, and the canonical fingerprint. I/O failures remain
exceptions; ordinary invalid content is returned as deterministic findings.

A document that declares the current wire version (v8) is first held to the published schemas
(`catalog-manifest-v8`, `resource-detail-v8` and the frozen `template-document-v8`,
`template-shared-v8` and `theme-v8` they reference), each resource to the definition of the type
it declares. A member the schema does not define, a missing required member, or a value of the
wrong type or out of bounds is `CATALOG_SCHEMA_UNKNOWN` at that member, and the document does not
bind. An explicit `null` counts as leaving the member out. A document migrated from an older wire
version is held to what its own version allowed instead, and to the migration's repairs.

For catalog discovery metadata (wire v6 and later), catalog attributes use an explicit `catalogKey`
(`catalog` until wire v8), `key`, and `value`. The catalog key follows the catalog key syntax and the key the resource key rule below, and a catalog may
assign each qualified attribute at most once. Attribute values remain generic: Suite and Exchange
validate them against the referenced attribute definition. From wire v7, keywords are lowercase
ASCII letters and digits in hyphen-separated parts, at most 30 characters, unique, and at most 20
per catalog (`CATALOG_KEYWORD_INVALID`, `CATALOG_KEYWORD_TOO_LONG`, `CATALOG_KEYWORD_DUPLICATE`,
`CATALOG_KEYWORD_LIMIT_EXCEEDED`). The migrator's wire check applies the same rules before binding.
The catalog icon (`iconImageKey`) and the ordered gallery keys (`imageKeys`) resolve only against image resources in the same catalog;
missing resources, non-assets, non-image media types, and duplicate gallery entries have separate
stable finding codes. The icon may also appear in the gallery.

From wire v8 (ADR 0007) a catalog may name a `defaultTheme` and declare `presets`. The default theme
is a theme reference like a template's theme: it resolves against the catalog's own theme resources
(`CATALOG_RESOURCE_REFERENCE_MISSING`) or, when it names another catalog's alias, through the
dependency resolver, and that alias must be declared in `catalogDependencies`
(`CATALOG_DEPENDENCY_CATALOG_UNDECLARED`). Its key follows the resource key rule, its `catalogKey` the
alias rule. A declared preset's key is a leading
lowercase letter, then lowercase letters and digits in hyphen-separated parts, at most 50 characters
(`CATALOG_PRESET_KEY_INVALID`), unique within the catalog (`CATALOG_PRESET_DUPLICATE`); its name is
nonblank and at most 100 characters (`CATALOG_PRESET_NAME_INVALID`), its description at most 500
(`CATALOG_PRESET_DESCRIPTION_INVALID`). The migrator's wire check applies the preset rules before
binding. An archive from wire v7 or earlier has neither member and reads as "no default theme" and
"no declared presets".

From wire v8 a catalog names its `registry`, in full, and its `namespace`, and so does every
`catalogDependencies` entry (`CATALOG_REGISTRY_INVALID`, `CATALOG_NAMESPACE_INVALID`, also raised by
the migrator's wire check). `catalogDependencies` declares each catalog at most once
(`CATALOG_DEPENDENCY_CATALOG_DUPLICATE`), never the catalog itself
(`CATALOG_DEPENDENCY_CATALOG_SELF`, which also applies to a resource dependency naming the
catalog's own key), under an `alias` that follows the catalog key rule and is not the catalog's own
key (`CATALOG_DEPENDENCY_ALIAS_INVALID`, also raised by the migrator's wire check for a missing or
malformed one) and is unique in the list (`CATALOG_DEPENDENCY_ALIAS_DUPLICATE`), pinning, unless
migrated from wire v7, an exact `releaseVersion`, never a range
(`CATALOG_DEPENDENCY_CATALOG_VERSION_INVALID`).
From wire v8 every release number, `release.version`, a pinned `releaseVersion` and
`release.base.version`, is `MAJOR.MINOR.PATCH` without leading zeros,
or a patch release `MAJOR.MINOR.PATCH-<namespace>.<N>` labelled with a namespace and a sequence
number from 1 (`1.3.1-gemeente-x.1`), and no other pre-release or build part, at most 128
characters (`CatalogReleaseVersions`, the API's `ReleaseVersion`; `CATALOG_RELEASE_VERSION_INVALID` and
`CATALOG_DEPENDENCY_CATALOG_VERSION_INVALID`, also raised by the migrator's wire check). Who may
issue which number (the holder of the namespace the plain ones, anyone else only a patch release
labelled with a namespace they hold) is not checked: the format cannot know. Earlier wire versions
accepted full SemVer; migrating a release numbered `1.0.0-beta.1` or `1.0.0+5` to v8 is a finding
(`CATALOG_RELEASE_VERSION_INVALID`), never a silent strip and never a patch release.
A release's `releaseId`, when it carries one, is the API's `CatalogReleaseId` (`CatalogReleaseIds`;
`CATALOG_RELEASE_ID_INVALID`, also raised by the migrator's wire check), and its `metadata` follows
the rules of a resource's (`CATALOG_RELEASE_METADATA_INVALID`, `CATALOG_RELEASE_METADATA_TOO_LARGE`).
Its `base`, when it carries one (`ReleaseBase`), names a release number and a qualified
fingerprint of a known algorithm (`CATALOG_RELEASE_BASE_INVALID`, also for a missing member; the
migrator's wire check raises it too). Whether the receiver has that release, and whether it is below
this one, is not checked: the base is kept as given.
A recorded `releaseVersion` comes with the `releaseFingerprint` of that release, the pin, naming a
known algorithm (`v6:<lowercase SHA-256 hex>` for a release made under V6), and an unrecorded one
with a null fingerprint
(`CATALOG_DEPENDENCY_CATALOG_FINGERPRINT_INVALID`, also raised by the migrator's wire check).
Every alias a reference names must be declared there (`CATALOG_DEPENDENCY_CATALOG_UNDECLARED`, at
the reference): a resource dependency's, the default theme's, and every reference inside content
-- a template's `themeCatalogKey`, a theme override's, a stencil or image node's, a `fontFamily`
font reference's (in theme, preset, document or node styles) or a code-list binding's `catalogKey`,
and the qualifier of a variant's attribute key (`system.locale`). A reference that names no alias,
or the catalog's own key, is to the catalog itself; a declaration nothing uses is allowed. A
reference never names a registry or namespace (contract ADR 0003): those are the declaration's, and
the migrator's wire check refuses them in a v8 document.

From wire v8 every resource key follows one rule, `^[a-z0-9]+(-[a-z0-9]+)*$` with 3 to 50
characters (`CatalogKeys.RESOURCE`): a resource's key, a variant's key and attribute keys, a resource
dependency's key, and the key a template's theme, a font reference or a code-list binding names are
`CATALOG_RESOURCE_KEY_INVALID` outside it, an attribute assignment's key
`CATALOG_ATTRIBUTE_IDENTITY_INVALID`, and a stencil node's `stencilKey` `STENCIL_REFERENCE_INVALID`.
The catalog's own key keeps its rule (a leading letter, 3 to 50). A catalog migrated from wire v7 has
had keys outside the bounds padded or cut, with the references to them (see
`catalog-compatibility.md`); its detail files keep their source names, which the validator accepts
for a catalog read before v8.

From wire v8 a template's data examples each have a `key`, by the same rule and unique within the template (`CATALOG_TEMPLATE_EXAMPLE_KEY_INVALID`
when missing or malformed, `CATALOG_TEMPLATE_EXAMPLE_KEY_DUPLICATE`), alongside the unchanged rule
that names are unique (`CATALOG_TEMPLATE_EXAMPLE_NAME_DUPLICATE`). A template's `description` is at
most 2000 characters (`CATALOG_TEMPLATE_DESCRIPTION_INVALID`) and, when its manifest entry also has
one, equal to it (`CATALOG_RESOURCE_DESCRIPTION_MISMATCH`): the template's own is the source the
entry copies. A resource's `metadata`, of any type, names one to sixteen reverse-DNS namespaces of
at most 100 characters (`CATALOG_RESOURCE_METADATA_INVALID`) and is at most 8 KiB as compact UTF-8
JSON (`CATALOG_RESOURCE_METADATA_TOO_LARGE`); what a namespace holds is its producer's business and
is not checked. A variant carries no metadata of its own; the migrator folds an early wire-v8
variant's into its template's (`CATALOG_VARIANT_METADATA_FOLDED`, or `CATALOG_VARIANT_METADATA_UNFOLDABLE`
when that would overwrite a value). A `detailUrl` that is not a relative path below the manifest's directory is
`CATALOG_MANIFEST_DETAIL_URL_INVALID`, and a `contentUrl` `CATALOG_ASSET_PATH_INVALID`, for a catalog
read at v8 (see `catalog-archive.md`, "Paths Into the Catalog"); a well-formed `detailUrl` that does
not name the resource's canonical file is `CATALOG_MANIFEST_DETAIL_PATH_INVALID` at any version.

Optional catalog-wide license metadata requires a trimmed, nonblank display name. SPDX expressions
and copyright text, when supplied, must also be trimmed and nonblank; the contract preserves but
does not resolve or legally interpret SPDX expressions. License URLs must be absolute HTTP or HTTPS
URLs. Resource-level license overrides are not part of the wire format.

Already-decoded catalogs can use `CatalogValidator.validate(catalog, policy)`, and consumers that
need to validate one resource can use `ResourceValidator`. Cross-catalog resolution is supplied
through `CatalogDependencyResolver`; returning `UNKNOWN` deliberately suppresses existence
findings when the complete dependency graph is unavailable. A reference into another catalog
reaches the resolver as the catalog its alias is declared for: that declaration's catalog key,
namespace and registry, never the alias. An alias the dependency list does not declare is reported
and never reaches the resolver. A `catalogDependencies` entry pins an exact release; whether the
consumer has that release is for the consumer to check.

The validator calls `TemplateValidator` for a template's primary model, each variant model, and
stencil content. It rejects unsupported template `modelVersion` values, validates the parameter
schema published by every stencil resource, resolves a template resource's declared theme, checks
that same-catalog stencil references resolve to a stencil resource in the catalog, and rejects direct
or transitive cycles across stencil resources.
The versioned `conformance/catalog-cases.json` index publishes a small executable conformance
suite. Every entry identifies a directory containing the actual `catalog.json`, actual resource
files, and an exact `expected-report.json`. The set covers a minimal valid catalog, a
missing detail document, a missing catalog-scoped reference, and a schema-valid template that
breaks the wire-v8 rules the schema cannot state (a description that differs from its manifest
entry, a repeated data example key, and resource metadata over 8 KiB). These files are language-neutral:
another JVM implementation, a Python validator, or a browser client can load the same inputs and
compare its complete ordered report with the expected result.

`carried-releases/carried-release-cases.json` is a second executable suite, for archives that carry
the releases they pin (wire v8): a thin archive, one carrying flat transitive dependencies, and one
case per refusal, each built from the catalogs under `carried-releases/catalogs/` as the index says,
with the expected validity, the code and path of every finding, and the reader's install order. It
also publishes valid and invalid carried-release paths and the path pattern, which the Kotlin and npm
libraries are both held to.

`catalog-validation-cases.json` and `archive-validation-cases.json` are finding-code registries,
not executable catalog inputs. They ensure stable public codes stay inventoried, while focused
Kotlin unit tests provide detailed implementation coverage. The smaller conformance suite is
deliberately made of genuine portable inputs and can grow when a behavior needs to be locked
across languages. `stencil-composition-validation.json` separately publishes the authoritative
valid and invalid outcomes for nested stencil definitions.

The public model continues to use Jackson 2 annotations, while mapper
implementation types stay internal. Jackson 3 databind is an internal runtime
dependency for Boot 4/Suite-compatible parsing; its relocated `tools.jackson`
packages can coexist with Jackson 2 consumers, and the catalog build includes a
coexistence smoke test. Generated API clients remain separate artifacts and do
not depend on the catalog transitively.
