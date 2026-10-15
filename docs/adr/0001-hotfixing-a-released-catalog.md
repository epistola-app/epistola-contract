# ADR 0001: Hotfixing a catalog release that production runs

- **Status:** Proposed
- **Date:** 2026-10-15
- **Scope:** API contract (`contracts/api`) and catalog contract (`contracts/catalog`), contract 2.0
- **Related:** [ADR 0002](0002-intermediate-releases.md) (releasing part of the working copy)

## Context

In contract 2.0 a catalog is versioned as a whole. Its resources are edited in one **working copy**,
marked ready, and released together as an immutable SemVer release. A catalog is identified by
namespace and key, published to an exchange, and installed and deployed elsewhere. Environments can
serve several releases of one catalog. A render request picks the highest release that satisfies its
version selector, among the releases its environment serves.

The editorial flow this ADR is about:

1. A team authors its catalog on one installation, typically **test**.
2. When a release is ready, they publish it and install it on **acceptance**, then on **production**.

Say production runs `1.4.0`, and a spelling error in it has to be fixed now. Meanwhile, test's working
copy already holds weeks of unreleased work towards `2.0.0`, which must not reach production. The
team needs to:

- take the content of `1.4.0`;
- change one small thing;
- have production render the fix;
- not lose, block or contaminate the `2.0.0` work in test.

The same need arises for catalogs the team does **not** own: a subscribed catalog from another
publisher (for example a shared stencil library) with an error that production cannot wait for the
publisher to fix.

## Options

### A. Hotfix working copy in the authoring installation

The authoring installation opens a second, short-lived working copy from a release, releases it as a
patch (`1.4.1`), and carries the change forward into the main working copy with a node-level
three-way merge. A merge conflict blocks the next release.

- It keeps production a pure consumer of published releases.
- It costs a second working copy in the API, a `workingCopy` selector on every working-copy
  operation, a three-way merge per resource type in the catalog module, and a looser release
  ordering rule.
- It only works for catalogs the team authors. A subscribed catalog has no working copy to branch
  from, and its namespace is not the team's to release in.
- An urgent fix still has to go through test and acceptance first.

**Rejected:** option E solves the same problem with less machinery, and also covers catalogs the team
does not own.

### B. Stash the working copy

Set the working copy aside, reset it to `1.4.0`, fix, release, then restore the stash. Nobody can work
on `2.0.0` while the stash is out, and the restored working copy still lacks the fix. **Rejected.**

### C. Cherry-pick a patch release

Make the fix in the working copy and release only selected changes on top of `1.4.0`. This fails when
the template with the error has also changed for `2.0.0`, because half a template cannot be released.
**Rejected.** [ADR 0002](0002-intermediate-releases.md) covers the related, broader need to release
only part of the working copy.

### D. Author the fix in production and publish it from there

This gives the namespace two authors. The authoring installation does not know about the new release,
and its next release can collide with or silently revert it. **Rejected.**

### E. Production override: the same version, patched where it runs

An environment can hold an **override** of a release it serves: a local layer of changes on top of
that release. A hotfix is not a new version. It is the same version (`1.4.0`), overridden in that
environment.

- **Scope:** an override belongs to one release of one catalog in one environment (prod,
  `letters@1.4.0`). Every render in that environment that uses that release applies it. That
  includes:
  - requests pinned to exactly `1.4.0`;
  - other catalogs that depend on `letters@1.4.0`.
- **Any catalog:** it works for authored and subscribed catalogs alike, including catalogs from
  other publishers. No release is minted in a namespace the environment's owner does not control.
- **Never published, never exported.** The override exists only in that environment.
- **Replaced, not merged.** Deploying a different release does not carry the override with it.
  Requests that move to `1.4.1` render `1.4.1` as published. When `1.4.0` is undeployed, its
  override goes with it.

What it gets right:

- It is the fastest path in an emergency, and it needs nothing from the authoring installation.
- It fixes what production actually runs, including dependencies the team does not own.
- Version selection is untouched. An override is not a release, so it never competes with real
  releases for "highest".

What it costs, and how this ADR deals with it:

- **The version no longer identifies the content alone.** `1.4.0` in production can differ from
  `1.4.0` elsewhere. To compensate:
  - an override has a **revision**, which increases with every change to it, and a fingerprint of
    its own;
  - every rendered document and generation result records the override revision next to
    `releaseVersion`, or null when none applied;
  - the environment's deployments show which releases have an override.
- **The fix can be lost.** If the authoring team never makes the same fix, the next release brings
  the error back. The guard below catches this; it does not prevent it.
- **Production needs a way to edit content.** Subscribed catalogs are read-only today. Editing an
  override is a separate permission from authoring, and every change to it is audited.

## Decision (proposed)

1. **Adopt option E: a hotfix is an override of the same version in the environment that runs it.**
2. **Guard the replacement.** Deploying a release of a catalog compares it with any override on
   another release of that catalog in the same environment. For each node the override changed, it
   checks whether the incoming release still has the overridden (wrong) content. If any do, the
   deploy reports them and requires confirmation that those fixes are dropped. For a catalog the team
   does not own, this also shows whether the publisher has fixed the error yet.
3. **Offer a way back, but do not require it.** An override's changes can be read as a diff against
   its release, so the authoring team can apply the same fix in its working copy. Applying it stays
   a manual step.
4. **Reject options A to D.**

## Consequences

- **API:**
  - Override lifecycle per deployment: read it, edit its resources, discard it. These are addressed
    under `…/environments/{environmentId}/deployments/{namespace}/{catalogKey}/{releaseVersion}/override`,
    with the same resource shapes as the working copy.
  - `DeploymentDto` reports the override revision.
  - Generated documents, generation items and collected results record `overrideRevision` next to
    `releaseVersion`.
  - `deployRelease` reports dropped override fixes and needs a confirmation flag to proceed.
  - A permission for editing overrides, separate from authoring and from deploying.
- **Catalog module:**
  - Applying an override layer to a release, for rendering.
  - The node-level comparison behind the guard: per node the override changed, does the incoming
    release still have the base content?
  - The override fingerprint.
- **Rendering:** a release is resolved first, by version selector and environment, unchanged. The
  environment's override of that release, if any, is applied after.
- **Not needed:** a second working copy, a three-way merge, or a change to release ordering.

## Open questions

- Can an override add or remove resources, or only change existing ones? Changing only is enough for
  hotfixes, and keeps the guard simple.
- Does editing an override in production need a review step (four eyes) before it applies?
- Should an override be copyable to another environment, so acceptance can check it first, or is
  production-only the point?
