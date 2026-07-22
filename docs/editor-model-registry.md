# Editor Model Registry

`epistola-model` owns the static editor model shared by contract consumers and
`epistola-suite`. The source of truth is JSON so non-TypeScript consumers can
load the same data without depending on suite internals.

## Files

- `epistola-model/registry/component-registry.json` defines component types,
  labels, categories, slots, child rules, applicable styles, inspector metadata,
  defaults, and example fragments.
- `epistola-model/registry/style-registry.json` defines the supported style
  groups and style keys.
- `epistola-model/generated/registry.ts` is generated from those JSON files and
  exports typed TypeScript values for npm consumers.
- The Gradle build packages both JSON files as
  `META-INF/epistola-model/*.json` for JVM consumers.

## Suite Integration

`epistola-suite` still owns the editor implementation: Lit components,
inspectors, renderers, command handlers, dynamic slot builders, and behavior
callbacks remain there. The suite imports the contract registry and overlays
that static metadata onto its runtime registry entries.

This split keeps the portable contract data in `epistola-model` while allowing
suite-specific code to keep using functions and classes that cannot live in a
JSON contract.

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
} from '@epistola.app/epistola-model/registry';
```

Raw JSON remains available through package exports such as
`@epistola.app/epistola-model/registry/component-registry.json`.

## JVM Consumers

JVM consumers should load the packaged resources from the model artifact:

```text
classpath:META-INF/epistola-model/component-registry.json
classpath:META-INF/epistola-model/style-registry.json
```

The suite MCP module uses this path so its component-type endpoint is backed by
the same contract registry as the editor package.

## Regeneration And Validation

Run model type generation after editing schemas or registry JSON:

```bash
cd epistola-model
pnpm generate:types
```

Validate the registries from the repository root:

```bash
node scripts/check-model-registry.mjs
```

The validator checks the registry shape, duplicate component/style keys, style
references, allowed child references, and example fragment structure.
