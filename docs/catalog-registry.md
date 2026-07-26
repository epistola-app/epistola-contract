# Portable Catalog Registry

`epistola-catalog` owns the static portable catalog shared by contract
consumers and `epistola-suite`. The source of truth is JSON so non-TypeScript
consumers can load the same data without depending on Suite internals.

## Files

- `epistola-catalog/registry/component-registry.json` defines component types,
  labels, categories, slots, child rules, applicable styles, inspector metadata,
  defaults, and example fragments.
- `epistola-catalog/registry/style-registry.json` defines the supported style
  groups and style keys. It has its own `schemaVersion` so style metadata can
  evolve independently from component metadata.
- `epistola-catalog/generated/registry.ts` is generated from those JSON files and
  exports typed TypeScript values for npm consumers.
- The Gradle build packages both JSON files as
  `META-INF/epistola-catalog/*.json` for JVM consumers.

## Suite Integration

`epistola-suite` still owns the editor implementation: Lit components,
inspectors, renderers, command handlers, dynamic slot builders, and behavior
callbacks remain there. The suite imports the contract registry and overlays
that static metadata onto its runtime registry entries.

This split keeps the portable contract data in `epistola-catalog` while allowing
suite-specific code to keep using functions and classes that cannot live in a
JSON contract.

Component parameter support is explicit:

- omitted `parameters` means the component has no parameter support
- `parameters: { "kind": "dynamic" }` means the schema is resolved per node
- `parameters: { "kind": "static", "schema": { ... } }` means every node of
  the component type uses the same JSON Schema

## TypeScript Consumers

Consumers can import the generated facade:

```ts
import {
  componentRegistry,
  componentTypes,
  styleRegistry,
  styleKeys,
  type ComponentType,
  type StyleKey,
} from '@epistola.app/epistola-catalog/registry';
```

Raw JSON remains available through package exports such as
`@epistola.app/epistola-catalog/registry/component-registry.json`.

## JVM Consumers

JVM consumers should load the packaged resources from the catalog artifact:

```text
classpath:META-INF/epistola-catalog/component-registry.json
classpath:META-INF/epistola-catalog/style-registry.json
```

The suite MCP module uses this path so its component-type endpoint is backed by
the same contract registry as the editor package.

## Regeneration And Validation

Run catalog type generation after editing schemas or registry JSON:

```bash
cd epistola-catalog
pnpm generate:types
```

Validate the registries from the repository root:

```bash
node scripts/check-catalog-registry.mjs
```

The validator checks the registry shape, duplicate component/style keys, style
references, default style keys, allowed child references, and example fragment
structure.
