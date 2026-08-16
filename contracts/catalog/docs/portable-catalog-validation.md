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
| `PageHeaderCardinalityValidator` | Portable | Page-header count and root-placement findings |
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
`currentCatalogKey` and `containingStencil` identify the owning resource so
the validator can distinguish cross-catalog references from direct or
transitive self-reference. A lookup can return `UNKNOWN`
when a consumer is intentionally validating without the full catalog graph;
only an explicit `MISSING` result produces a missing-reference finding.

Stencil composition is part of the portable specification. Standalone
template validation understands both authored draft references and references
to exact published stencil versions; the context resolver receives the
exact `draftVersion` identity. Whole-catalog validation is the publication boundary and
therefore rejects draft references. It requires portable references to declare
omit `draftVersion` and pin an exact published `version`.

Standalone validation counts the containing stencil as nesting level one and
rejects a repeated ancestor identity or a chain deeper than five. Consumers
may expose a smaller authoring feature set while still accepting and
round-tripping conforming catalog content.

All findings contain a stable string code, `ERROR` or `WARNING` severity,
document-relative path, and human-readable message. Reports are deduplicated
and sorted by path, code, then message.

The validator loads component and style rules from the registries packaged at
`META-INF/epistola-catalog`; it does not maintain a second component vocabulary.
Versioned fixture data is packaged under
`META-INF/epistola-catalog/fixtures/` in Maven and
`@epistola.app/epistola-catalog/fixtures/` in npm.

## Whole-catalog validation

`CatalogValidator.validate(input, policy)` is the complete portable validation entry point for an
archive stream. It applies `CatalogArchiveReader` safety limits and wire migration before checking
manifest/detail consistency, required files, resource references, resource-specific invariants,
data schemas and examples, release metadata, and the canonical fingerprint. I/O failures remain
exceptions; ordinary invalid content is returned as deterministic findings.

For wire v6 discovery metadata, `defaultLanguage` must be a trimmed, structurally valid BCP 47 tag.
Keywords preserve authored case and text, must be trimmed and nonblank, and may not contain exact
duplicates. Catalog icon and ordered gallery slugs resolve only against image assets in the same
catalog; missing resources, non-assets, non-image media types, and duplicate gallery entries have
separate stable finding codes. The icon may also appear in the gallery.

Already-decoded catalogs can use `CatalogValidator.validate(catalog, policy)`, and consumers that
need to validate one resource can use `ResourceValidator`. Cross-catalog resolution is supplied
through `CatalogDependencyResolver`; returning `UNKNOWN` deliberately suppresses existence
findings when the complete dependency graph is unavailable. No dependency version-range logic is
present because `DependencyRef` does not expose a version or range.

The validator calls `TemplateValidator` for a template's primary model, each variant model, and
stencil content. It rejects unsupported template `modelVersion` values, validates the parameter
schema published by every stencil resource, resolves a template resource's declared theme, checks
same-catalog stencil references against the exact exported version, and rejects direct or
transitive cycles across stencil resources.
The versioned `conformance/catalog-cases.json` index publishes a small executable conformance
suite. Every entry identifies a directory containing the actual `catalog.json`, actual resource
files, and an exact `expected-report.json`. The initial set covers a minimal valid catalog, a
missing detail document, and a missing catalog-scoped reference. These files are language-neutral:
another JVM implementation, a Python validator, or a browser client can load the same inputs and
compare its complete ordered report with the expected result.

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
