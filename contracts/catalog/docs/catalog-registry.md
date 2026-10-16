# Portable Catalog Registry

`epistola-catalog` owns the static portable catalog shared by contract
consumers and `epistola-suite`. The source of truth is JSON so non-TypeScript
consumers can load the same data without depending on Suite internals.

## Files

- `contracts/catalog/registry/component-registry.json` defines component types,
  labels, categories, slots, child rules, applicable styles, inspector metadata,
  defaults, and example fragments.
- `contracts/catalog/registry/style-registry.json` defines the supported style
  groups and style keys. It has its own `schemaVersion` so style metadata can
  evolve independently from component metadata.
- `contracts/catalog/generated/registry.ts` is generated from those JSON files and
  exports typed TypeScript values for npm consumers.
- The Gradle build packages both JSON files as
  `META-INF/epistola-catalog/*.json` for JVM consumers.

## Where a style applies

A component's `applicableStyles` is `"all"` or a list of style keys. A list entry also covers the
keys it is a prefix of, so `padding` covers `paddingTop` and `border` covers `borderRadius`.

`"all"` covers every style property except those marked `"includedInAll": false` in the style
registry. Such a property makes sense on only a few components and applies only where a component,
or a style map, lists it by key:

| Property | Type | Applies to |
| --- | --- | --- |
| `minHeight` | `unit` (`sp`, `pt`) | `container`. The container is at least this tall and grows with its content; it never clips. There is no `height`. |
| `verticalAlign` | `select`: `top`, `middle`, `bottom` | `datatable-column` (node styles), the table's `cellStyles`, the columns' `columnStyles` |

The container therefore lists its styles instead of saying `"all"`: the same keys as before, plus
`minHeight`.

### Style maps

Some parts of a node are not nodes and cannot carry `styles`: a table's cells and the columns of a
columns node. A component declares a prop that styles them under `styleMaps`, with the keys each
entry accepts:

| Component | Prop | Shape | Parts | Accepted keys |
| --- | --- | --- | --- | --- |
| `table` | `cellStyles` | `keyed`: `{ "<row>-<col>": { ... } }`, keys match `^\d+-\d+$` | Cells, 0-based, inside the `rows` × `columns` props. A merged cell takes the styles of its top-left cell. | `backgroundColor`, `padding*`, `border*` (including `borderRadius`), `verticalAlign` |
| `columns` | `columnStyles` | `indexed`: `[{ ... }, ...]`, entry *i* styles slot `column-i` | Columns, counted by the length of `columnSizes`. The array may be shorter, never longer; `{}` leaves a column unstyled. | The same |

Equal height is the renderer's job. The columns of a row are meant to be equally tall, so a column's
background, border and vertical alignment fill the height of the row, and a cell's fill the height
of its table row. Epistola Suite may not render these properties yet, nor offer them in its editor.

The npm registry facade exports `isStyleApplicable(key, applicableStyles)` and `optInStyleKeys`,
which decide applicability as the validator does.

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

Public model, theme, component, and style types are exported from the package
root. The fact that some declarations are generated is an internal build detail:

```ts
import type {
  ComponentRegistry,
  StyleRegistry,
  Theme,
} from '@epistola.app/epistola-catalog';
```

Consumers can import the registry values from their semantic facade:

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
cd contracts/catalog
pnpm generate:types
```

Validate the registries from the repository root:

```bash
node contracts/catalog/scripts/check-registry.mjs
```

The validator checks the registry shape, duplicate component/style keys, style
references, default style keys, style maps (shape, key pattern, accepted keys, and no clash with
another prop), allowed child references, and example fragment structure.
