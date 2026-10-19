# ADR 0001: Hotfixing a catalog release that production runs

- **Status:** Proposed
- **Date:** 2026-10-15
- **Scope:** API contract (`contracts/api`) and catalog contract (`contracts/catalog`), contract 2.0
- **Related:** [ADR 0002](0002-intermediate-releases.md) (working on several catalog versions at once),
  [ADR 0003](0003-catalog-identity-and-distribution.md) (catalog identity and dependencies)

## Context

In contract 2.0 a catalog is versioned as a whole (ADR 0002). Everyday work goes into the catalog's
**next version**, a draft whose resources are marked ready and released together as an immutable
SemVer release; a large change is kept apart as **long-term work** until it is promoted to the next
version. A catalog is identified by registry, namespace and key, published to an exchange, and
installed and deployed elsewhere. Environments can serve several releases of one catalog. A render
request picks the highest release that satisfies its version selector, among the releases its
environment serves.

**Releases only go forward.** A release number must be above every release of the catalog, and
only the next version is released. So once `2.0.0` is released there is no way to ship `1.4.1`: a
fix to an older line, which production may still run, has nowhere to be made. This ADR is about
filling that gap.

The editorial flow this ADR is about:

1. A team authors its catalog on one installation, typically **test**.
2. When a release is ready, they publish it and install it on **acceptance**, then on **production**.

Say production runs `1.0.1`, and a spelling error in it has to be fixed now. Meanwhile test already
holds weeks of unreleased work towards `2.0.0`, in its next version or as long-term work, or `2.0.0`
is already released and production has not moved to it yet. Either way the work towards `2.0.0`
must not reach production. The team needs to:

- take the content of `1.0.1`;
- change one small thing;
- have production render the fix;
- not lose, block or contaminate the `2.0.0` work in test.

The same need arises for catalogs the team does **not** own: a subscribed catalog from another
publisher (for example a shared stencil library) with an error that production cannot wait for the
publisher to fix.

## Options

### A. Maintenance draft in the authoring installation

The authoring installation starts a draft from an older release and releases it as a patch of that
line (`1.0.2` while `2.0.0` exists).

- It keeps production a pure consumer of published releases.
- It costs:
  - a third kind of draft next to the next version and long-term work (ADR 0002), released below the
    latest release, which loosens ADR 0002's rule that releases only go forward;
  - the fix reaches the next version only when an author applies it again by hand (ADR 0002's
    `behind`), until a merge exists (ADR 0005).
- It only works for catalogs the team authors. A subscribed catalog has no drafts, and its namespace
  is not the team's to release in.

### B. Stash the next version

Set the next version aside, reset it to `1.0.1`, fix, release, then restore the stash. Nobody can work
on `2.0.0` while the stash is out, and the restored next version still lacks the fix. **Rejected.**

### C. Cherry-pick a patch release

Make the fix in the next version and release only selected changes on top of `1.0.1`. This fails when
the template with the error has also changed for `2.0.0`, because half a template cannot be released.
**Rejected.** [ADR 0002](0002-intermediate-releases.md) covers the related, broader need to keep
unfinished work out of a release, with long-term work.

### D. Author a normal release in production and publish it from there

This gives the namespace two authors. The authoring installation does not know about the new release,
and its next release can collide with or silently revert it. **Rejected.**

### E. Unversioned override in production

The environment holds a local layer of changes on top of the release it serves, identified by a
revision rather than a version. (This is not ADR 0002's environment override, which chooses one of
the catalog's existing versions and changes no content.)

- It fixes what production runs, for any catalog, without minting anything in another namespace.
- But an override is not a release:
  - it cannot be exported, so acceptance cannot check it and another installation cannot receive it;
  - it needs its own identity (a revision and fingerprint) next to the version in every document;
  - it needs its own lifecycle in the API, separate from releases and deployments.

**Rejected** in favour of option F, which keeps the same behaviour but makes the hotfix a release.

### F. Hotfix release: the same version, with a `+hotfix` build suffix

A hotfix is a release of its own, labelled with SemVer **build metadata** on the release it fixes:
`1.0.1+hotfix.1`, `1.0.1+hotfix.2`.

SemVer ignores build metadata when ordering versions, and npm ranges ignore it when matching. A
hotfix therefore *is* `1.0.1` for every selector:

- **Selection:**
  - A selector that matches `1.0.1` (`1.0.1`, `^1.0.1`, `>=1.0.0 <2.0.0`) matches all of its
    hotfixes.
  - Among releases that differ only in the hotfix suffix, the **highest hotfix number wins**.
  - A selector that names a hotfix in full (`1.0.1+hotfix.2`) selects exactly that one.
  - `1.0.1+hotfix.0` selects the release without hotfixes.
- **Deployment:** a hotfix is deployed like any release, next to the release it fixes.
  - Requests the environment routes to `1.0.1` now render the hotfix.
  - **Rollback is undeploying it:** routing falls back to `1.0.1`.
  - **The next release replaces it:** `1.0.2`, `1.1.0` and `2.0.0` all sort above every
    `1.0.1+hotfix.N`.
- **Dependents get the fix.** A catalog that pins `1.0.1` renders with its latest hotfix the
  environment serves, so a fix in a shared stencil reaches every catalog that uses it.
- **Traceable.** Generated documents and results record the full `releaseVersion`, for example
  `1.0.1+hotfix.1`. No separate override identity is needed.

#### Any installation can cut a hotfix, for any catalog

- A hotfix is created from a release the installation has installed: one it authored or one it
  subscribes to, including another publisher's.
- It is a draft (ADR 0002) of its own kind, next to the next version and any long-term work, so the
  `2.0.0` work in an authoring installation is untouched. It is numbered `1.0.1+hotfix.N`, the next
  free `N`.
- It contains its base, `1.0.1`, never later releases. Nothing is merged into other drafts.
- Releasing it is the same state transition as for any draft. It is the one release that is not
  above every release of the catalog: it sorts as the release it fixes.

#### A hotfix may not break anything

Binding to `1.0.1` silently moves a caller to the latest hotfix. That is only safe if no hotfix can
break a caller who never chose it. **A hotfix release is refused if it is breaking relative to its
base:**

- The template data contract must accept every input the base accepted.
- No template, variant, stencil or other resource may be removed, and no resource added.
- A stencil's interface (placeholders and parameters) may stay the same or accept more, by the same
  rule `markStencilReady` uses to decide which instances a change breaks.
- Attribute definitions, code-list entries and the catalog's declared dependencies are unchanged.

Anything else needs a real release (`1.0.2`, `1.1.0`, `2.0.0`) through the normal path.

#### Exportable, never published

- A hotfix can be exported and imported as an archive, so acceptance can check it before production,
  and the authoring team can receive it.
- It is **never published to an exchange**. The namespace is not necessarily the hotfixer's, and the
  publisher's next release is the real fix.
- Two installations can each cut `1.0.1+hotfix.1` with different content. An import whose label
  matches an installed release but whose fingerprint differs is refused.
- A release whose dependencies pin a hotfix cannot be published either: no registry serves
  that hotfix, so no other installation could install the dependency (ADR 0003).

## Decision (proposed)

1. **Adopt option F: a hotfix is a non-breaking `+hotfix.N` release of the version it fixes.** It can
   be cut by any installation, for any installed catalog. It can be exported, but never published to
   an exchange.
2. **Guard the replacement.** Deploying a release of a catalog compares it with the hotfixes of that
   catalog that the environment serves. For each node a hotfix changed, it checks whether the incoming
   release still has the base content, that is, the error. If any do, the deploy reports them and
   requires confirmation that those fixes are dropped. For a catalog the team does not own, this also
   shows whether the publisher has fixed the error yet.
3. **Offer a way back, but do not require it.** A hotfix can be read as a diff against its base, and
   imported into the authoring installation, so the team can apply the same fix in its next version
   or long-term work. Applying it stays a manual step.
4. **Reject options A to E.**

## Consequences

- **Version grammar:** release versions accept the build suffix `+hotfix.N`, and only that one.
  `ReleaseCatalogDraftRequest.version` keeps plain `MAJOR.MINOR.PATCH`; hotfix labels are assigned
  by the server.
- **Version selectors:** "highest" breaks ties between hotfixes of one version by hotfix number. A
  selector without a suffix matches every hotfix of the versions it matches.
- **`+` in URL paths.** `getCatalogVersion`, `deployRelease` and `undeployRelease` carry the version
  in the path. In a path, `+` is a literal character, but some stacks decode it as a space, as in a
  query string. The contract states that `+` is sent as `%2B` or literally and means `+`, and a
  conformance scenario holds every client to it.
- **API:**
  - Hotfix lifecycle: the catalog version operations of ADR 0002, with a way to start a hotfix
    draft from an installed release, also in a subscribed catalog, which has no drafts today.
  - Validation of the hotfix on release that reports every breaking change it finds.
  - `deployRelease` reports dropped hotfix fixes and needs a confirmation flag to proceed.
  - Export of a single release, and refusal of a conflicting import.
  - A permission for cutting hotfixes, separate from authoring and from deploying.
- **Catalog module:**
  - The non-breaking check per resource type. It largely exists for stencils and data contracts
    already, and becomes one rule that every installation applies identically.
  - The node-level comparison behind the guard.
  - Archive support for hotfix labels.
- **Not needed:** a separate override identity, or a hotfix-specific editing API.

## Open questions

- Can a hotfix be cut from any installed release, or only from one an environment serves?
- Does cutting or deploying a hotfix in production need a review step (four eyes)?
- When a hotfix is imported into the authoring installation, should it be offered as a diff to apply
  to a draft, or only be available to read?
