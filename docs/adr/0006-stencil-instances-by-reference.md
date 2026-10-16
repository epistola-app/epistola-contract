# ADR 0006: Stencil instances by reference, with lexical scope

- **Status:** Proposed — parts 1–4 decided (16 October 2026); part 5 (isolation) proposed
- **Date:** 2026-10-16
- **Scope:** portable catalog contract (`contracts/catalog`, wire v8: template model, validation,
  rendering semantics) and API contract (`contracts/api`), contract 2.0
- **Related:** [ADR 0002](0002-intermediate-releases.md) (catalog versions),
  [ADR 0003](0003-catalog-identity-and-distribution.md) (catalog dependencies),
  [ADR 0004](0004-resource-identity-within-a-catalog.md) (resource keys)

## Context

A stencil is a reusable piece of template content — a letterhead, a signature block — with
**parameters** (inputs the using template binds) and **placeholders** (spots the using template
fills).

Until 2.0 a template held a **copy** of the stencil version it was inserted with, pinned by
`version`. Contract 2.0 removes stencil versions: a stencil is versioned with its catalog (ADR 0002),
and wire v8 strips the pins. That leaves the copy without a version to say what it is a copy of, and
raises the question this ADR answers: when a stencil changes, how do the templates that use it
follow?

The 2.0 API as first drafted kept the copy: marking a stencil ready moved every instance its change
does not break, and **held back** the others (`heldBack`), which blocked the release until resolved.
That needs a per-instance compatibility check, digests to know whether an instance is behind, a
moving step, and an answer to what a template author sees when someone else's change moves their
template.

A second question came with it: what an expression inside a stencil instance can see. Until 2.0 a
stencil's content saw the template's data plus its own `params`, and a template's **fill** — the
content it puts into a placeholder — also saw the stencil's `params`, because it renders inside the
stencil. With stencils inside stencils, every stencil calls its scope `params`, so an inner stencil
hides the outer one's.

## Options

### Instances

- **A. Copy, moved on ready, breaking ones held back** (the first 2.0 draft). Each template keeps
  rendering what it was last moved to; a change reaches templates in a separate step. Costs the
  compatibility check, digests, held-back state and its UX, and duplicate content in every template.
- **B. Copy, moved only on request.** Simpler than A but leaves stale copies in every release.
- **C. Reference.** The template names the stencil and holds only what it supplies; the stencil is
  read when the template is shown or rendered. A change reaches every template at once; there is
  nothing to move. Breaks are found when releasing, like compile errors.

### Scope

- **Dynamic.** An expression sees what is in scope where it renders: a fill sees the stencil's
  `params`. Pre-2.0 behaviour; needs a distinct alias per stencil on every nesting path, and an
  alias hides a data field with the same name.
- **Lexical.** An expression sees what is in scope where it was written: a fill sees the scope of
  the template (or outer stencil) that wrote it; `params` always means the stencil you are writing
  in.

## Decision

### 1. A template stores a stencil instance by reference (option C)

A `stencil` node holds:

- `stencilKey`, and `catalogKey` (with `namespace`/`registry`, ADR 0003) when the stencil is not in
  the referencing document's own catalog;
- `parameterBindings`, and optionally `paramsAlias`;
- in its `children` slot, **one `placeholder` node per placeholder it fills**, with `name` and a
  `fill` slot holding the fill. A placeholder it does not fill is absent. Nothing of the stencil's
  own content is stored.

The stored form is canonical: the placeholder nodes and their slots get IDs derived from the
instance and the placeholder name (`{instanceId}:fill:{name}`, slot `{instanceId}:fill:{name}:fill`,
children slot `{instanceId}:children`), so storing an unchanged instance always produces the same
document. No `version`, `draftVersion` or `parameterSchemaSnapshot` is stored.

### 2. The stencil is resolved in the release, or the dependency's pinned release

An instance reads its stencil from the same catalog version as the referencing document, or — for a
stencil in another catalog — from the release the catalog dependency pins (ADR 0003). A reference
without a catalog means the catalog of the document it is written in: a template's own catalog, or,
inside a stencil, the stencil's catalog.

Showing and rendering **expand** the instance: the stencil's content is inlined below the instance
with IDs derived from the instance ID and the stencil's own (`{instanceId}:{nodeId}`), and each fill
goes into the stencil's placeholder of the same name. Expanding the same document twice gives the
same IDs; fill content keeps its own IDs.

### 3. Releasing checks every instance against its stencil

A catalog version is not releasable while any instance, in a template or in a stencil, has:

- a fill for a placeholder its stencil does not have;
- a required parameter without binding and without default;
- a stencil that does not exist, contains itself, or nests deeper than the nesting limit.

A binding for a parameter the stencil does not declare is a warning; it is ignored when rendering.
A finding about an instance inside a stencil is reported against that stencil, once, not against
every template that uses it.

`heldBack` and moving instances are dropped: `markStencilReady` marks the stencil reviewed and
nothing else; `listStencilUsage` lists the template variants (and stencils) that use the stencil and
whether each still fits.

### 4. Stencils may contain stencils, and scope is lexical

A stencil's content may contain stencil instances, resolved as in part 2. An outer stencil fills an
inner one's placeholders, and can offer one of its own placeholders inside such a fill, which the
template then fills.

Expressions are scoped **lexically**:

| Content | Sees |
| --- | --- |
| A stencil's own content, including a placeholder's default | the stencil's parameters (`params`, or its `paramsAlias`) |
| A fill written in a template | the template's data and loop variables |
| A fill written in a stencil | that stencil's parameters |

Only what expressions see follows this rule. A fill still renders where it sits: styles, fonts and
page state come from its position inside the stencil.

### 5. A stencil sees only its parameters (proposed)

A stencil's own content does **not** see the using template's data or loop variables, only its
parameters and the system scope (`sys`). Everything a stencil needs comes in through a parameter.

This makes a stencil a function: it works in every template that binds its parameters, whatever
that template's data model, and part 3 can verify that completely. Without it, a letterhead reading
`supplier.name` directly renders empty in a template whose data calls it `org.name`, and no check
can tell.

## Migration from 1.x

The upgrade must not change what renders, except where named below. The v7 → v8 catalog migration
and the server's own data migration apply the same rules, so an archive and a database migrate to
the same result:

| 1.x instance | 2.0 |
| --- | --- |
| Copy of the latest version of its stencil, unchanged except for fills | Reference + fills (part 1) |
| Copy of an older version | Reference + fills; renders the latest stencil. What no longer fits (part 3) is reported, not prevented |
| No stencil reference, or a copy that differs from its version beyond fills | Plain content (the stencil node is replaced by its copied content), reported |
| Fill reading the stencil's alias (`params.x`) | `params.x` replaced by the instance's binding for `x`, parenthesised, or by its default as a literal |
| Stencil content reading the template's data or loop variables (part 5) | Each path read becomes a new parameter of the stencil; its expressions read `params.<new>`, and every instance binds the new parameter to the original path |

Where an expression cannot be rewritten safely — it reads the alias as a whole or with a dynamic key,
reads an optional parameter that has no binding and no default (absent, not null), would capture a
loop variable, or does not parse — the instance is marked `legacyFillScope: true` (for fills) and
keeps the pre-2.0 scope; such instances are reported, and a release warns about each until the
author rewrites it. Rewriting uses a JSONata parser, never text replacement.

## Consequences

- **Wire v8 (unreleased, changed in place):** the `stencil` node gains the stored form of part 1; a
  `placeholder` in a template appears only as a fill carrier inside a `stencil` node's `children`
  slot (no `default` slot). `legacyFillScope` is a recognised prop. The v7 → v8 migration implements
  the table above.
- **Validation:** the catalog module checks part 3 against the stencils of the same catalog version
  and of pinned dependency releases, and part 4's nesting rules, identically in every installation.
- **API:** `heldBack` and `heldBackInstances` are removed (`MarkStencilReadyResponse`,
  `StencilUsageDto`, the catalog version's changes); a version's `releasable` no longer mentions held
  back instances but reports instances that do not fit.
- **Rendering:** a stencil node records the scope it is entered from; a placeholder renders its fill
  in that scope and its default in the stencil's. With part 5, entering a stencil also replaces the
  data scope with the stencil's parameters and `sys`.
- **A stencil change reaches every template in its catalog at once.** A structurally compatible
  change can still change layout (a taller letterhead moves content to the next page). A release
  preview of the templates a stencil change affects is planned; until then the release's list of
  changes names them.
- **Not possible any more:** a stencil handing values it computes (such as the current item of its
  own loop) to a fill. If that is needed, it returns as an explicit, declared exposure on the
  placeholder, additively.

## Open questions

- Part 5: confirm isolation, including that a stencil does not see the template's loop variables.
- Whether `paramsAlias` stays: with lexical scope it is no longer needed for nesting.
