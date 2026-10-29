# ADR 0009: Shared schemas, included in data contracts by reference

- **Status:** Proposed
- **Date:** 2026-10-29
- **Scope:** portable catalog contract (`contracts/catalog`, wire v8: a new resource type,
  references inside JSON Schema, validation) and API contract (`contracts/api`), contract 2.0
- **Related:** [ADR 0002](0002-intermediate-releases.md) (drafts, releases, what counts as
  breaking), [ADR 0003](0003-catalog-identity-and-distribution.md) (catalog dependencies and
  aliases), [ADR 0006](0006-stencil-instances-by-reference.md) (stencils by reference, lexical
  scope)

## Context

Every template has its own data contract (`TemplateResource.dataModel`, a JSON Schema), and every
stencil its own parameter schema (`StencilResource.parameterSchema`). Templates of one catalog
mostly describe the same things: a customer, an address, an amount with a currency. Each template
writes them out again, so they drift apart, and a correction has to be made in every template.

What already exists:

- A template's data contract is its `dataModel`. Until 2.0 the API also had a `schema` member on
  the template DTOs, which no installation stored; contract 2.0 removes it, so there is one
  contract per template.
- `dataModel` is free-form JSON. The catalog validator checks that it is a valid schema, using
  JSON Schema 2020-12 as its default dialect (`PortableJsonSchema`), and checks the template's data
  examples against it. The contract itself does not name the dialect.
- The validator preloads two built-in schemas by `$id`
  (`https://epistola.app/schemas/richtext-inline-v1.json`, `…/richtext-block-v1.json`), and a
  `dataModel` can `$ref` them. A `$ref` to anything else is not described by the contract.
- A stencil's `parameterSchema` is a restricted subset (`ParameterSchemaValidator`): an object
  whose properties are primitives (`string`, `number`, `integer`, `boolean`) or arrays of
  primitives, with string formats `date` and `date-time`.
- ADR 0006 settled how a reusable resource is used: by reference, resolved in the same release or
  the dependency's pinned release, with breaks found when releasing.

Every template must keep a data contract of its own: it is what the template promises its callers,
and it is reviewed, marked ready and released with the template. Reuse must therefore not replace
the template's contract. It has to work as an inclusion inside it.

## Options

### How a contract includes a shared part

- **A. Copy on insert.** The editor offers shared snippets and copies them into the contract.
  Nothing is linked, so this removes the typing but not the drift.
- **B. An Epistola keyword** (`x-epistola-include: "address"`), expanded before validating. Every
  JSON Schema tool would see an unknown keyword and ignore it, so the schema would only mean what
  it says after Epistola has expanded it.
- **C. Standard `$ref` to a catalog resource.** The shared part is a resource of its own. A
  contract refers to it with `$ref` and a URI that names the resource. Any 2020-12 validator
  resolves it once the referenced schemas are preloaded by `$id`, the same way the richtext
  schemas already are.

### What the API returns

- **Stored.** The contract as written, references included.
- **Dereferenced.** Every reference replaced by the schema it points to. Self-contained, but
  impossible for recursive types, larger with every use, and it loses the shared names: a code
  generator makes `CustomerAddress` and `BillingAddress` where there was one `Address`.
- **Bundled.** The referenced schemas embedded once in `$defs`, and every reference rewritten to
  point there. Self-contained, each schema included once, names kept.
- **A switch** (`?resolved=true`) on the existing responses, choosing between stored and resolved.

## Decision

### 1. A shared schema is a catalog resource (option C)

A new resource type, **`schema`**, holds one reusable JSON Schema: `address`, `party`, `money`.
In wire v8, `SchemaResource` is
`{ type: "schema", key, name, description, tags, metadata, schema, dataExamples }`. `key` follows
the one resource key rule. `schema` is a JSON Schema object without `$id`; the identity comes from
where the schema sits (part 3).

`dataExamples` is optional, with the same entries and rules as a template's (`DataExampleEntry`:
a key by the resource key rule, unique keys and names). Each example is validated against the
resolved schema when the schema is checked, and an invalid one blocks the release. An example
catches a mistake before any template includes the schema, and gives the editor sample data. A
template does not inherit the examples of the schemas it includes; its own examples stay its own.

A shared schema is content like any other resource. It is fingerprinted, edited in drafts,
marked ready, and released with its catalog. It has no version of its own (ADR 0002).

A shared schema is not a data contract. It promises nothing until a contract includes it. The API
and the editor call it a *schema*, and keep *data contract* for a template's.

### 2. Every schema is JSON Schema 2020-12

A shared schema, and every `dataModel` or `parameterSchema` that includes one, is JSON Schema
2020-12. That is the dialect the validator already uses by default; the contract now says so. A
`$schema` naming another dialect there is refused. A contract without `epistola:` references keeps
the dialect it declares, so existing contracts are not tightened by this rule. 2020-12 matters
here for two reasons:

- `$ref` may have sibling keywords, so a contract can include a schema and narrow it in one place:
  `{ "$ref": "epistola:schema/party", "required": ["email"] }`. In draft-07 the siblings would be
  ignored.
- `unevaluatedProperties: false` closes an object composed with `allOf`. `additionalProperties:
  false` cannot do that, because it only sees the properties declared next to it.

### 3. A reference is `$ref` with an `epistola:` URI

| `$ref` | Refers to |
| --- | --- |
| `epistola:schema/{key}` | the shared schema `key` in the catalog of the document the reference is written in |
| `epistola:schema/{alias}/{key}` | the shared schema `key` in the catalog that the declaration `alias` names (ADR 0003) |
| `https://epistola.app/schemas/…` | a built-in schema in the contract's set (part 3a) |
| `#…` | a location in the same schema, such as its own `$defs` |

Nothing else is allowed. An `epistola:` URI is never fetched, and neither is anything else: a
reference to `http(s)` outside the built-in set is refused, so rendering never touches the network
and a release always means the same thing. A reference names a whole shared schema. A fragment
into one (`epistola:schema/party#/properties/address`) is refused. Allowing fragments later is
additive.

`$ref` can appear anywhere in a schema: as a property (`"customer": { "$ref": … }`), as array
`items`, or at the root through `allOf`. A shared schema may refer to other shared schemas, and to
built-in schemas (part 3a).

### 3a. Built-in schemas belong to the engine, not to a catalog

Some schemas describe what the renderer accepts, not anyone's content. One example is the
rich-text documents that a `richTextVariable` node renders. A catalog author cannot define these
differently. They are therefore not catalog resources, not even in the system catalog. The contract
defines them, as it defines the component and style registries in `contracts/catalog/registry/`:

- **The set belongs to the contract.** Today it is `https://epistola.app/schemas/richtext-inline-v1.json`
  and `…/richtext-block-v1.json`, from `contracts/catalog/schemas/`. They are preloaded and never
  fetched. No catalog can provide or override a schema with one of their `$id`s.
- **They are versioned in their id**, because no catalog release versions them. A changed format
  gets a new id (`-v2`), shipped with an Epistola version. Content that uses it says so in
  `compatibility.epistolaVersions`, which V6 covers. The old id keeps validating.
- **Their identity is global.** An editor or a bundler recognizes rich text by its `$id`, without
  resolving an alias.
- **The ids keep their `https:` form.** They are identifiers, not locations. Changing them would
  rewrite every contract that uses rich text, for no gain.

Shipping them as shared schemas in the system catalog was considered and rejected. A content
release would then pin what is really the renderer's input format. Whether a field is rich text
would depend on which alias names the system catalog. And an archive migration could not rewrite
existing references, because pointing them at the system catalog needs a pinned declaration of it,
which the migration cannot create without knowing a system release's fingerprint.

### 4. References resolve like stencils, by alias, lexically

A reference resolves in the release that holds the document it is written in. For another
catalog, it resolves in the release that the declaration of its alias pins. An alias belongs to
the catalog whose content uses it: when catalog A includes `epistola:schema/common/address`, and
`address` refers to `epistola:schema/geo/country`, then `geo` is the alias that `common` declares,
resolved through common's pinned release, not A's.

Aliases are release content (ADR 0003): they are declared in `catalogDependencies`, and the V6
fingerprint covers them. They change only in a draft, and changing one rewrites no content. A
reference therefore cannot point anywhere else without a new release.

Diamonds are allowed. A may pin `geo` 1.0 while `common` pins `geo` 2.0. The template's contract
then contains two different `country` schemas, and that is correct, because each part renders
with what it was released against.

### 5. Every reference is recorded as a resource dependency

A template or stencil that includes a shared schema depends on it, and so does a shared schema
that includes another. The manifest's resource index records these dependencies. A reference
into another catalog is a `CatalogDependencyRef` with type `schema`. This lets the usage listing
and the release check see the references without parsing every schema again. Because they are
part of the index, V6 covers them.

### 6. Releasing checks the resolved contracts

A draft is not releasable while any schema, in a contract, a parameter schema or a shared schema,
has:

- a reference to a schema that does not exist, or through an alias that no declaration gives
  (`CATALOG_DEPENDENCY_CATALOG_UNDECLARED`, as for every other reference);
- a fragment, or a reference outside part 3;
- a cycle of references, whether a schema includes itself directly or through others. Recursive
  types are refused for now; allowing them later is additive;
- a resolved contract that is not a valid schema.

Each finding names the resource and a JSON Pointer to the reference
(`template/invoice dataModel /properties/billingAddress/$ref`). A reference inside a schema is
much harder to spot than a `themeCatalogKey`, especially after an alias was renamed.

The other checks work on the **resolved** contract:

- **Data examples** are validated against it. A change to a shared schema can therefore make
  another template's examples invalid, which blocks the release and names that template.
- **Defaults.** Rendering fills an absent property from its schema `default` before it validates
  the data, and the validator does the same for examples (`withDefaults`). Both follow `$ref`
  (and its siblings) to find the defaults. Composition (`allOf`, `oneOf`, `anyOf`) stays
  unexpanded, as it is now.
- **`readsTemplateData`** (ADR 0006, part 5) checks the paths a stencil reads against the
  resolved contract of each template that uses it.
- **Compatibility** (ADR 0002). Whether a change is `additive` or `breaking` is judged on each
  template's resolved contract and each stencil's resolved parameter schema. Narrowing `address`
  is a breaking change to every template that includes it. The release's reasons name the
  template and the shared schema the change came from.

Marking a shared schema ready marks only that schema. Changing it does not clear the ready mark
of the templates that include it. This is the same rule as for stencils (ADR 0006): the release's
change list names the templates the change reaches.

### 7. Stencil parameters can include shared schemas, within the parameter subset

A stencil's `parameterSchema` may `$ref` a shared schema as the schema of a parameter. The
validator checks the parameter subset on the **resolved** schema. A shared schema that is a
primitive or an array of primitives can therefore be a parameter (`currency` as a string enum,
`iban` as a pattern), and an object such as `address` cannot.

Object parameters are a separate decision: how a template binds an object, and how the editor
shows it. Once they are allowed, shared object schemas work as parameters without another change
here, and a stencil parameter and a template field that include the same schema can be bound
without comparing their structure.

### 8. The API returns what is stored; a separate operation returns the bundled contract

**Authoring returns the stored form.** `TemplateDto.dataModel`, the stencil DTOs'
`parameterSchema` and a shared schema's `schema` come back exactly as written, references
included. Everything else would break round-tripping: an editor that received an inlined contract
would write the copy back, and the reuse would be gone without anyone noticing. `ETag` and
`If-Match` cover the stored form.

**Callers get the bundled form from an operation of its own**, `getTemplateDataContract`, at
`…/releases/{release}/templates/{templateKey}/data-contract`. It returns one self-contained
JSON Schema 2020-12 document, the contract resolved in that release, as `application/schema+json`.
That is a deliberate exception to the versioned vendor media type that every other JSON response
uses: the response is meant to go straight into validators and code generators, which recognise
the registered type. The document's shape is JSON Schema's, not the API's, so the API version has
nothing to say about it:

- each shared schema the contract reaches, directly or transitively, is placed once in `$defs`,
  and each reference to it is rewritten to `#/$defs/{name}`. No `$id` is embedded, so tools that
  handle only local references can read it (quicktype, openapi-generator, older validators). A
  built-in schema that the contract reaches (part 3a) is embedded the same way, named by its id's
  file name without `.json` (`richtext-block-v1`), so the document needs nothing it does not
  contain;
- `{name}` is the schema's key for the template's own catalog, and `{catalogKey}.{key}` for
  another catalog, using that catalog's **real** key, never the alias. Aliases are internal
  wiring. If names came from aliases, renaming one in the next release would rename the types
  every caller generates, without any structural change;
- only a genuine clash qualifies a name further: two different schemas that would get the same
  name, from a diamond (part 4) or from two dependencies with the same key. The name then becomes
  `{namespace}.{catalogKey}.{key}`, and then `…@{version}`. The template's own `$defs` keep their
  names; a shared schema that collides with one is qualified in the same way;
- it is read-only. Nothing stops a client from writing it back as a contract, but that stores a
  copy, and the editor never does it.

`validateTemplateData` validates against the resolved contract, so its behaviour for callers does
not change. The field schemas its result carries (`invalidFields[].schema`, `missingFields[].schema`)
already have local `$ref`s inlined and keep rich-text references as their id. `epistola:`
references are inlined like local ones, because a caller cannot resolve them; built-in references
keep their id (part 3a).

A caller that validates in-process (the clients' `TemplateDataValidator` extension point) needs a
self-contained schema too. It should take it from `getTemplateDataContract`, not from
`TemplateDto.dataModel`, and the clients' documentation says so.

The **switch** option is rejected. One field would then mean two things depending on the request,
generated clients would type it the same either way, and a resolved form would be one `PUT` away
from replacing the stored one.

The **catalog archive** always stores references. It carries the shared schemas as resources,
and the schemas of dependencies come with the dependency's own release.

### 9. Wire v8 changes in place

The latest published contract is `v2.0.0-alpha.3`, and wire v8 has not shipped in a stable
release. Like ADR 0006, this changes v8 in place instead of adding v9. Wire v7 has no shared
schemas, so the migration has nothing to convert.

One rule does tighten existing content: a reference outside part 3, such as a `dataModel` that
refers to a schema on the web. The v7 → v8 migration reports such a reference as a finding and
does not drop it. It costs little, because such a contract does not work today either. The catalog
validator (networknt 3.0.7, as configured in `PortableJsonSchema`) does not fetch it: an `http`
reference to a local server that would have answered received no request. Instead the reference
goes unnoticed when the template has no data examples, and when it has one, `CatalogValidator.validate`
throws a `SchemaException` instead of reporting a finding. Part 6 turns both into findings.

## Consequences

- **Wire v8:** `SchemaResource` in `resource-detail-v8`, `schema` as a resource type in the
  manifest's index and in `CatalogDependencyRef.type`, the generated TypeScript types, and the
  conformance and golden fixtures. The dialect rule of part 2 applies to `dataModel` and
  `parameterSchema`.
- **Catalog validation:** reference parsing and resolution (part 3 and 4), the checks of part 6
  with new codes for an unknown schema, a fragment, an external reference and a cycle, the
  parameter subset on resolved schemas (part 7), and `withDefaults` following references. The
  resolution and bundling live in the catalog module, exported to Kotlin and TypeScript, so Suite,
  Exchange and the editor resolve a contract the same way. The module also exports the built-in
  set of part 3a, instead of keeping it as a list inside the validator.
- **API:** schema operations within a release (`listSchemas`, `getSchema`, `createSchema`,
  `updateSchema`, `deleteSchema`, `markSchemaReady`, `listSchemaUsage`), following the stencil
  operations, `ETag` and `If-Match` included. A `schemas` consumer permission. A release's changes
  list shared schemas. `getTemplateDataContract`, whose `application/schema+json` response the
  clients' `Accept` handling (`ContractMediaTypes`, the Node client's `CONTRACT_OPERATIONS`) must
  carry as the one exception. Every client, its README, and a conformance driver action and
  scenario for each new operation, including one that checks the media type.
- **Deleting a shared schema** that is still referenced is allowed in a draft. It blocks the
  release (part 6), as deleting a used stencil does.
- **A change to a shared schema reaches every template that includes it, at once.** The release's
  change list, and the planned release preview (ADR 0006), name those templates.
- **Implementations:** Suite and Exchange resolve references when they validate, render and
  compare releases. The contract describes the behaviour; Suite may not implement it yet.

## Open questions

- **Selecting a release by range or environment.** A caller can address a release by version or
  id. A variant that selects the release the way `validateTemplateData` does (`versionRange`,
  `environmentKey`) can be added if callers need it.
