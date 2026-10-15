# ADR 0002: Releasing part of the working copy

- **Status:** Proposed
- **Date:** 2026-10-15
- **Scope:** API contract (`contracts/api`), contract 2.0
- **Related:** [ADR 0001](0001-hotfixing-a-released-catalog.md) (hotfixing a release that production runs)

## Context

A catalog has one working copy, and a release contains all of it. `releaseCatalog` is refused while
any resource is `new` or `modified`.

That works while a team finishes one release before starting the next. It breaks when work overlaps:
the working copy holds a large change towards `2.0.0` that is weeks from done, and alongside it a
few finished improvements that should ship now as `1.5.0`. Today the team can either wait for
`2.0.0`, or undo the unfinished work to release.

ADR 0001 covers the urgent case, a fix to what production runs, with a non-breaking
`+hotfix.N` release cut where it is needed. This ADR covers the planned case: an **intermediate release** cut from the authoring installation while
other work continues.

## Options

### A. Branches

Several named working copies per catalog, each releasable on its own line, merged into each other
when work comes together.

- It solves every case, including a single resource that holds both finished and unfinished changes.
- But it needs a merge for every resource type, merge conflicts with a UI to resolve them, branch
  lifecycle and permissions, and a `branch` selector on every working-copy operation.
- Authors who are not developers have to understand branching.

**Rejected:** the cost is out of proportion to the case it solves that option B does not.

### B. Ready as a staging area

Marking a resource ready **takes a snapshot** of it. A release contains:

- the ready snapshot of every resource that has one;
- the previously released content of every other resource;
- no unreleased resource that has no ready snapshot.

Work in progress is not released, so it no longer blocks a release.

- **Editing continues after ready.** The snapshot stays the release candidate while the working copy
  moves on. A resource then has up to three states: released, ready (the snapshot), and working.
  Marking it ready again replaces the snapshot. `getCatalogChanges` shows both "ready" and
  "modified since ready".
- **The release must be consistent.** Validation runs on the set the release would contain, not on
  the working copy. If a ready template uses a stencil whose change is not ready, or references a
  catalog dependency that is not declared in the ready state, the release is refused and says what is
  missing.
- **Stencil instances follow the snapshot.** Instances held back by a stencil change are judged
  against the ready snapshot of the stencil.
- **Dependencies are part of the snapshot.** Declared catalog dependencies are marked ready like a
  resource, so a dependency bump for `2.0.0` does not leak into `1.5.0`.

The limitation: one resource that already mixes finished and unfinished changes cannot be split. The
author has to mark it ready *before* starting the unfinished work, or undo part of it. In practice
this means marking ready at the end of each finished piece of work, which is what "ready" means
anyway.

### C. Keep "release everything"

Status quo. Teams sequence their work, or keep unfinished work out of the catalog until it is done.
This is simple, but it pushes the problem onto the authors.

## Decision (proposed)

1. **Adopt option B: ready is a snapshot, and a release contains the ready snapshots plus the
   previous release's content.**
2. **Reject branches (option A).** Revisit only if teams regularly need to split a single resource's
   changes across releases.
3. A release no longer requires that nothing is `new` or `modified`. It requires that something is
   ready, and that the set it would contain is consistent.

## Consequences

- **Release semantics change:**
  - `releaseCatalog` releases the ready snapshots;
  - `releasable` means "something is ready, and the ready set is consistent";
  - the release response lists what was left out.
- **Working-copy reads need to tell the states apart:**
  - resource status gains "modified since ready";
  - variant, stencil and template content can be read as working or as ready.
- **Dependencies:** `listCatalogDependencies` shows the working and the ready declaration;
  `updateCatalogDependencies` edits the working one, and marking ready snapshots it.
- **Contract change:** every client follows, and the conformance suite gains scenarios.
- **Combined with ADR 0001:** urgent fixes go through a hotfix release, planned intermediate
  releases through ready snapshots. Neither needs a second working copy of the catalog.

## Open questions

- Should marking ready be possible per variant, or only per template (all its variants)?
- Should discarding a ready snapshot be possible, returning a resource to "modified" without losing
  the working edits?
