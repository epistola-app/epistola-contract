# ADR 0001: Hotfixing a subscribed catalog release that production runs

- **Status:** Superseded (2026-10-21) by Epistola Suite's ADR 0032 ("Catalogs are drafts and
  releases, moved between installations and merged", `docs/adr/0032-catalog-sources-releases-and-merging.md`
  in epistola-suite) and, in this contract, by [ADR 0005](0005-merging-catalog-versions.md)
  (merging, alpha). Never implemented.
- **Date:** 2026-10-19
- **Scope:** API contract (`contracts/api`) and catalog contract (`contracts/catalog`), after 2.0.0
- **Related:** [ADR 0002](0002-intermediate-releases.md) (working on several releases of a catalog at once),
  [ADR 0003](0003-catalog-identity-and-distribution.md) (catalog identity and dependencies)

## Superseded

This ADR answered "how does an installation that does not author a catalog fix a release it
runs?" with a `+hotfix.N` release that only that installation could cut and never publish. The
question no longer arises, because the distinction it rests on is gone:

- **There are no catalog types.** Every catalog has releases and drafts, wherever its releases come
  from. Whether a catalog can be changed is a property of the installation (a run-only switch) and of
  whether the server itself supplies the catalog, reported as `CatalogDto.editable` and
  `readOnlyReason`. An installation that allows editing fixes any catalog the ordinary way: a draft
  from the release production runs, released as the next patch (`1.0.2`), as ADR 0002 already does
  for an authored catalog.
- **Changes made in more than one installation are merged back.** The authoring installation
  receives that release (by file, a registry or the exchange) and its draft is then behind it;
  merging the release into the draft (ADR 0005, alpha) takes the fix per resource, and a conflict is
  resolved per resource to ours or theirs. Nothing is lost silently, and there is no second kind of
  release.
- **Numbers are checked where releases meet**, not prevented by a suffix. One number names one
  content everywhere: an import refuses a known number with other content, an exchange refuses a
  number published before, and a registry with immutable tags does too. Two installations that
  release the same number independently are detected when the releases meet; the later one comes
  back as a merge and a new number.

What this ADR needed and that is therefore not added: the `+hotfix.N` version grammar, its range
semantics, the `+` encoding rule in paths, the non-breaking check for hotfixes, the dropped-fix guard
on deploy, a hotfix permission, and the "exportable, never published" rule. The text below is kept
as the record of the option that was considered.

## Context

In contract 2.0 a catalog is versioned as a whole (ADR 0002). An authoring installation has any
number of drafts of its catalog, each started from a release and released on its own, numbered from
what it changed against that release. A catalog is identified by registry, namespace and key,
published to an exchange, and installed and deployed elsewhere. Environments can serve several
releases of one catalog. A render request picks the highest release that satisfies its version
range (`versionRange`), among the releases its environment serves.

**Fixing an older line where the catalog is authored is solved by ADR 0002.** Say production runs
`1.0.1`, a spelling error in it has to be fixed now, and the authoring installation already holds
work towards `2.0.0`, or has released it. The team starts a draft from `1.0.1`, fixes the error, and
releases it as `1.0.2`, whatever was released since; the `2.0.0` work is a separate draft and is
untouched, and is told it is behind `1.0.2`. `1.0.2` is published and installed on acceptance and
production like any release.

**The gap is an installation that does not author the catalog.** A subscribed catalog has no drafts:
it is installed from its publisher. When production runs a release of a catalog it subscribes to,
for example another publisher's shared stencil library, and an error in it cannot wait for the
publisher to fix and release it, production has no way to change it. This ADR is about that case
only.

The team needs to:

- take the content of the installed release, `1.0.1`;
- change one small thing;
- have production render the fix;
- not take over the publisher's namespace, or diverge from the publisher's next release.

## Options

### A. Fix it where it is authored

Ask the publisher, or the team's own authoring installation when it is their catalog, to release
`1.0.2` from a draft based on `1.0.1` (ADR 0002), and install it.

- It keeps production a pure consumer of published releases, and is the normal path.
- It does not help when the publisher cannot release in time, or the installation cannot reach the
  publisher. **Kept as the normal path; not sufficient on its own.**

### B. Author a release of the subscribed catalog in production and publish it from there

This gives the namespace two authors. The publisher does not know about the new release, and its
next release can collide with or silently revert it. **Rejected.**

### C. Unversioned override in production

The environment holds a local layer of changes on top of the release it serves, identified by a
revision rather than a version. (This is not deploying a draft (ADR 0003, "Deploying"), which
serves one of the catalog's existing versions and changes no content.)

- It fixes what production runs, for any catalog, without minting anything in another namespace.
- But an override is not a release:
  - it cannot be exported, so acceptance cannot check it and another installation cannot receive it;
  - it needs its own identity (a revision and fingerprint) next to the version in every document;
  - it needs its own lifecycle in the API, separate from releases and deployments.

**Rejected** in favour of option D, which keeps the same behaviour but makes the hotfix a release.

### D. Hotfix release: the same version, with a `+hotfix` build suffix

A hotfix is a release of its own, labelled with SemVer **build metadata** on the release it fixes:
`1.0.1+hotfix.1`, `1.0.1+hotfix.2`.

SemVer ignores build metadata when ordering versions, and npm ranges ignore it when matching. A
hotfix therefore *is* `1.0.1` for every version range:

- **Selection:**
  - A range that matches `1.0.1` (`1.0.1`, `^1.0.1`, `>=1.0.0 <2.0.0`) matches all of its
    hotfixes.
  - Among releases that differ only in the hotfix suffix, the **highest hotfix number wins**.
  - A range that names a hotfix in full (`1.0.1+hotfix.2`) selects exactly that one.
  - `1.0.1+hotfix.0` selects the release without hotfixes.
- **Deployment:** a hotfix is deployed like any release, next to the release it fixes.
  - Requests the environment routes to `1.0.1` now render the hotfix.
  - **Rollback is undeploying it:** routing falls back to `1.0.1`.
  - **The next release replaces it:** `1.0.2`, `1.1.0` and `2.0.0` all sort above every
    `1.0.1+hotfix.N`.
- **Dependents get the fix.** A catalog that pins `1.0.1` renders with its latest hotfix the
  environment serves, so a fix in a shared stencil reaches every catalog that uses it.
- **Traceable.** Generated documents and results record the full `version`, for example
  `1.0.1+hotfix.1`. No separate override identity is needed.

#### An installation that does not author the catalog cuts it

- A hotfix is created from a release of a **subscribed** catalog the installation has installed,
  including another publisher's. An authoring installation releases a normal patch instead
  (ADR 0002).
- It is a hotfix draft (ADR 0002) on that release, in a catalog that otherwise has no drafts. It is
  numbered `1.0.1+hotfix.N`, the next free `N`.
- It contains its base, `1.0.1`, never later releases.
- Releasing it is the same state transition as for any draft. It sorts as the release it fixes.

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

1. **Keep option A as the normal path**, and **adopt option D for what it cannot cover: a hotfix is a
   non-breaking `+hotfix.N` release of the version it fixes**, cut by an installation for a catalog
   it subscribes to. It can be exported, but never published to an exchange.
2. **Guard the replacement.** Deploying a release of a catalog compares it with the hotfixes of that
   catalog that the environment serves. For each node a hotfix changed, it checks whether the incoming
   release still has the base content, that is, the error. If any do, the deploy reports them and
   requires confirmation that those fixes are dropped. For a catalog the team does not own, this also
   shows whether the publisher has fixed the error yet.
3. **Offer a way back, but do not require it.** A hotfix can be read as a diff against its base, and
   installed in the authoring installation next to its releases (an import installs a release, never
   a draft), so the publisher can apply the same fix in a draft and release it. Applying it stays a
   manual step.
4. **Reject options B and C.**

## Consequences

- **Version grammar:** release versions accept the build suffix `+hotfix.N`, and only that one.
  Contract 2.0.0's `ReleaseVersion` and wire v8 accept `MAJOR.MINOR.PATCH` only, so this widens
  both, which clients that validate a release number must be told about.
  `ReleaseCatalogDraftRequest.version` keeps plain `MAJOR.MINOR.PATCH`; hotfix labels are assigned
  by the server.
- **Version ranges:** "highest" breaks ties between hotfixes of one version by hotfix number. A
  range without a suffix matches every hotfix of the versions it matches.
- **`+` in URL paths.** `getCatalogRelease`, `deployCatalogRelease` and `undeployCatalogRelease`
  carry the version in the path. In a path, `+` is a literal character, but some stacks decode it
  as a space, as in a query string. The contract states that `+` is sent as `%2B` or literally and means `+`, and a
  conformance scenario holds every client to it.
- **API:**
  - Hotfix lifecycle: the release operations of ADR 0002, with a way to start a hotfix
    draft from an installed release of a subscribed catalog, which has no drafts today.
  - Validation of the hotfix on release that reports every breaking change it finds.
  - `deployCatalogRelease` reports dropped hotfix fixes and needs a confirmation flag to proceed.
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
