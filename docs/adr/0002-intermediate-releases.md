# ADR 0002: Working on several catalog versions at once

- **Status:** Accepted
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

ADR 0001 covers the urgent case: a fix to what production runs, as a non-breaking `+hotfix.N`
release. This ADR covers the planned case: an **intermediate release** cut while other work
continues.

## Options

### A. Ready as a staging area

Marking a resource ready takes a snapshot of it. A release contains the ready snapshots plus the
previous release's content of everything else, so unfinished work no longer blocks a release.

- A resource then has three states at once: released, ready and working. Every read has to say
  which one it wants.
- One resource that mixes finished and unfinished work cannot be split.
- Nothing in the UI tells an author which release they are working towards.

**Rejected:** it is hard to explain, and it hides the question authors actually ask, "which version
am I working on?".

### B. Named branches

Several named working copies per catalog, merged into each other at will. It solves every case, but
it asks non-developers to understand branching: names unrelated to versions, merges in any
direction, branch lifecycle. **Rejected.**

### C. Catalog versions with a status

A catalog consists of **versions**. A version is created as a **draft**, edited, and **released**,
after which it is immutable. Several drafts can be open at once, each with its own version number.
A release is not a separate thing from the draft it came from. It is the same version, in a
different state, at the same address.

### D. Keep "release everything"

Teams sequence their work, or keep unfinished work out of the catalog until it is done. This is
simple, but it pushes the problem onto the authors.

## Decision (proposed)

Adopt **option C**.

### A version is a draft or released

- A catalog version is identified by its SemVer number, and has a `status`: `draft` or `released`.
- A **draft** is editable. A **released** version is immutable: every write to it is refused with
  `409`.
- **Releasing** is a state transition of the version, not a copy. The version keeps its number and its
  address.
- Only released versions are rendered, deployed, exported or published. Version selectors (`^1.4.0`,
  `latest`) never match a draft. Preview is the exception: it renders a draft.

### What a draft contains

- **A draft contains every released version below its number.** Its **base** is the highest released
  version below it. `2.0.0` opened while `1.4.0` is the latest release starts as `1.4.0`.
- **Releasing a draft flows its changes forward** into every open draft with a higher number. When
  `1.5.0` is released, `2.0.0` receives the `1.5.0` changes and its base becomes `1.5.0`.
- **Flowing forward is a three-way merge per node** (base, released, draft). Changes to different
  nodes merge without asking. When both sides changed the same node differently, the draft gets an
  **incoming conflict**. It is resolved by keeping the draft's content, taking the incoming content,
  or editing it. An unresolved conflict blocks releasing that draft, not editing it.
- The draft's declared catalog dependencies flow forward the same way.

### Creating, renumbering and discarding drafts

- **Create** a draft by giving a version number that is not in use. By default its content is its
  base.
- **Create from another version** ("create a new version from this") by also naming a source: any
  released version or open draft, below or above the new number. The draft starts as a copy of the
  source. Every released version below the new number that the source does not contain then flows
  in, exactly as on a release, so the rule "a draft contains every release below it" still holds
  and conflicts appear as incoming changes. Examples:
  - continue the `2.0.0` work as `3.0.0`: create `3.0.0` from draft `2.0.0`. When `2.0.0` is
    released, its changes flow into `3.0.0` and merge without conflict, because they are already
    there;
  - start `1.5.0` from draft `2.0.0` to ship part of it early, then undo what is not finished. A
    draft may contain more than its base; it may never contain less;
  - patch an older line: create `1.0.2` from released `1.0.1` while `2.0.0` is released.
  The draft records its source, so the UI can show "created from 2.0.0".
- **After a release, the next draft is created automatically** if no draft above it exists: releasing
  `1.4.0` opens `1.4.1`. A team that works on one thing at a time never creates a draft by hand, and
  sees one editable version, as today.
- **Renumber** a draft by changing its version, as long as it is a draft, up or down. Typically the
  automatic `1.4.1` becomes `1.5.0` or `2.0.0` once the work is known. Renumbering follows the same
  rules as creating a version from another one, so nothing is lost and the containment rule holds:
  - **Up past a released version** (`1.5.0` → `1.7.0` with `1.6.0` released): the releases the
    draft now sits above flow in, exactly as on a release, and may open incoming conflicts.
  - **Down past a released version** (`2.0.0` → `1.5.0` with `1.6.0` released): the draft keeps its
    content, so it now contains changes from releases above it. That is allowed, as for "create
    from", but it means `1.5.0` will ship what `1.6.0` shipped.
  - **Past another open draft** (`1.5.0` → `2.1.0` with `2.0.0` open): what either draft contains does
    not change, but the direction changes: `2.0.0` no longer receives this draft's changes on
    release, and receives them instead when it is released first.
  - **To a lower kind of version than the changes need** (a patch number while the draft has
    breaking changes against its base, by the rule ADR 0001 defines for hotfixes).

  None of these is refused. Each is a **warning**, listed by a renumber preview. A renumber with
  warnings is refused with `409` until it is repeated with `acknowledgeWarnings: true`, the same
  confirmation pattern as deploying over a hotfix (ADR 0001). The UI shows them before the author
  confirms. A renumber that only moves within the same gap (`1.5.0` → `1.5.3`) has no warnings.

  Refused outright: a number already in use, renumbering a released version, and renumbering a
  `+hotfix` draft. The response carries the new address; links to the old number stop working,
  which is acceptable for a draft.
- **Discard** a draft by deleting it, as long as it is a draft. Changes that already flowed forward
  from it are released content elsewhere and stay.
- A version number is unique in its catalog, across drafts and released versions.

### Ordering

- A draft can be released when no draft below it is blocked on it. Versions do **not** have to be
  released in order: `1.5.0` can be released after `2.0.0`, as a maintenance release of the `1.x`
  line, and flows forward into drafts above it (none, in that example, once `2.0.0` is released).
- Ready stays what it is today: a per-resource mark inside a draft, and the checklist for releasing
  it. A draft is releasable when every changed resource is ready and no conflict is open.

### Hotfixes

- A hotfix of an owned catalog is a draft of the next patch, for example `1.4.1` next to `2.0.0`.
- A `+hotfix.N` release (ADR 0001) is a draft numbered `1.0.1+hotfix.N` that the installation creates
  from an installed release. Its number is assigned when it is created, the next free `N`, and it
  cannot be renumbered. It contains its base, `1.0.1`, never later releases, and does not flow
  forward.

## API

Addresses use the version number, the same for drafts and released versions. The path parameter is
`catalogVersion`:

```
/v1/tenants/{tenantId}/catalogs/{namespace}/{catalogKey}/versions
/v1/tenants/{tenantId}/catalogs/{namespace}/{catalogKey}/versions/{catalogVersion}
/v1/tenants/{tenantId}/catalogs/{namespace}/{catalogKey}/versions/{catalogVersion}/templates/{templateId}
/v1/tenants/{tenantId}/catalogs/{namespace}/{catalogKey}/versions/{catalogVersion}/stencils/{stencilId}
…
```

| Operation | Request | Notes |
| --- | --- | --- |
| `listCatalogVersions` | `GET …/versions?status=` | Drafts and released versions, highest first |
| `createCatalogVersion` | `POST …/versions` `{version, from?}` | Opens a draft from its base, or from version `from` |
| `getCatalogVersion` | `GET …/versions/{catalogVersion}` | Status, base, source, open conflicts; release data once released |
| `previewCatalogVersionRenumber` | `GET …/versions/{catalogVersion}/renumber-preview?to=` | The renumber's warnings, without applying it |
| `renumberCatalogVersion` | `PATCH …/versions/{catalogVersion}` `{version, acknowledgeWarnings?}` | Drafts only; `409` while warnings are not acknowledged; returns the new `Location` |
| `deleteCatalogVersion` | `DELETE …/versions/{catalogVersion}` | Drafts only |
| `releaseCatalogVersion` | `POST …/versions/{catalogVersion}/release` `{notes?}` | Draft → released; reports the drafts the changes flowed into, and the draft it opened |
| `listIncomingChanges` | `GET …/versions/{catalogVersion}/incoming?state=` | Merged, conflicting and resolved changes from lower releases |
| `resolveIncomingChange` | `POST …/versions/{catalogVersion}/incoming/{changeId}/resolve` `{resolution}` | `keep`, `take` or `edited` |

- **Every resource path of the working copy moves under `…/versions/{catalogVersion}/`,** and so do
  `getCatalogChanges`, `markCatalogReady` and the dependency operations. Reads work on any version.
  Writes and ready marks work on drafts only, and are refused with `409` on a released version.
- `releaseCatalog`, `listCatalogReleases` and `getCatalogRelease` are replaced by the version
  operations, and `CatalogReleaseDto` by `CatalogVersionDto`. `CatalogDto` lists `draftVersions`.
- **Unchanged:** deployments, generation and results address released versions by number, and keep
  the name `releaseVersion` where they mean one. `validateTemplateData` stays on the catalog,
  because it selects a release by version selector and environment, as generation does.
- Preview renders a draft named by `draftVersion`, in place of the boolean `workingCopy`.
- `importCatalog` takes the draft of an authored catalog to import into as `catalogVersion`
  (required when several are open), and numbers a new catalog's first draft with it (default
  `1.0.0`).
- Renumber warnings are read from the preview rather than carried by the `409`, so no new problem
  type is needed. Their `kind` is an open set of strings, not an enum, so a client built today shows
  a kind added later.
- A version containing `+` (a hotfix) is sent in a path as `%2B` or literally, as ADR 0001 states.
  The contract does not accept hotfix numbers yet.

## Consequences

- **Contract change:** the working-copy operations move and gain a version segment; the release
  operations merge into the version operations. Every client follows, and the conformance suite
  gains scenarios for every version operation, including a renumber warning of a kind the client does
  not know and an unresolved incoming change.
- **Catalog module:** the node-level three-way merge per resource type, applied identically by every
  installation. ADR 0001 rejected a merge as too expensive for hotfixes alone. Here it carries
  intermediate releases, and hotfixes of owned catalogs come with it.
- **Archives:** unchanged. Only released versions are exported.

### Suite

Suite may not implement this yet. The contract does not prescribe a UI, but the model is designed for
this one:

- **The author picks catalog and version once**, in the top navigation next to the tenant:
  "Letters · 2.0.0 (draft)". A catalog with one draft shows no version choice. A subscribed catalog
  shows its installed release, read-only.
- **The authoring pages follow that choice.** Templates, stencils and themes list the resources of
  the chosen version; a create dialog no longer asks for a catalog. Resources from dependencies show
  read-only, with their version.
- **The page address carries the choice**, catalog first, mirroring the API:
  `/tenants/acme-corp/catalogs/acme-corp/letters/versions/2.0.0/templates/invoice`. The last choice
  is remembered per user.
- **A version bar** under the navigation shows the base, the number of changes and ready resources,
  open incoming conflicts, and the release button. The editor header names the catalog and version.
- **Creating a draft** reuses the patch / minor / major choice of today's release dialog, to pick its
  number. Every version, draft or released, offers "create a new version from this". Releasing a
  draft asks only for notes and the optional exchange publication, and lists the
  drafts the changes flow into.
- **Deployments** become a catalog-level page: environments × released versions.

## Later: merging on request

This ADR does not add a manual merge, but leaves room for one. Forward flow on release already needs
the node-level three-way merge and the incoming-changes API with conflict resolution. A later
`mergeIntoCatalogVersion` (`POST …/versions/{version}/merge` `{from, resources?}`) can reuse both:
it brings another version's changes, or those of selected resources, into a draft as incoming
changes. That would replace "create `1.5.0` from `2.0.0`, then undo what is not finished" with
"merge the finished templates from `2.0.0` into `1.5.0`".

## Open questions

- Should resolving a conflict record who resolved it, for the release notes?
- Should a released version be withdrawable (still installed, never selected), or is undeploying
  enough?
