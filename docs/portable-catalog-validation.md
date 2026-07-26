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
parameter schemas, and effective style presets. A lookup can return `UNKNOWN`
when a consumer is intentionally validating without the full catalog graph;
only an explicit `MISSING` result produces a missing-reference finding.

All findings contain a stable string code, `ERROR` or `WARNING` severity,
document-relative path, and human-readable message. Reports are deduplicated
and sorted by path, code, then message.

The validator loads component and style rules from the registries packaged at
`META-INF/epistola-catalog`; it does not maintain a second component vocabulary.
Versioned golden fixture metadata is packaged under
`META-INF/epistola-catalog/fixtures/`.

## Whole-catalog validation

`CatalogValidator.validate(input, policy)` is the complete portable validation entry point for an
archive stream. It applies `CatalogArchiveReader` safety limits and wire migration before checking
manifest/detail consistency, required files, resource references, resource-specific invariants,
data schemas and examples, release metadata, and the canonical fingerprint. I/O failures remain
exceptions; ordinary invalid content is returned as deterministic findings.

Already-decoded catalogs can use `CatalogValidator.validate(catalog, policy)`, and consumers that
need to validate one resource can use `ResourceValidator`. Cross-catalog resolution is supplied
through `CatalogDependencyResolver`; returning `UNKNOWN` deliberately suppresses existence
findings when the complete dependency graph is unavailable. No dependency version-range logic is
present because `DependencyRef` does not expose a version or range.

The validator calls `TemplateValidator` for a template's primary model, each variant model, and
stencil content. The versioned `catalog-validation-cases.json` fixture publishes one focused case
description for every stable whole-catalog finding code, alongside the wire, migration, hash, and
archive-safety fixtures.

The public model continues to use Jackson 2 annotations, while mapper
implementation types stay internal. Jackson 3 databind is an internal runtime
dependency for Boot 4/Suite-compatible parsing; its relocated `tools.jackson`
packages can coexist with Jackson 2 consumers, and the catalog build includes a
coexistence smoke test. Generated API clients remain separate artifacts and do
not depend on the catalog transitively.
