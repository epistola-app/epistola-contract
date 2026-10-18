# ADR 0002: Working on several catalog versions at once

- **Status:** Accepted, amended 2026-10-16: drafts do not merge (see "Amendment"); amendment 2 proposed
  2026-10-18: a draft gets its number when it is released (see "Amendment 2")
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

## Amendment (2026-10-16): drafts are independent copies

The first version of this ADR flowed every release forward into higher drafts with a node-level
three-way merge, and kept the rule "a draft contains every released version below its number". The
merge is not part of contract 2.0.0, so neither is that rule. Instead:

- **A draft is an independent copy.** Releasing one never changes another. Nothing is merged.
- **A draft's base is fixed**: the released version it started from. It does not move when other
  versions are released or the draft is renumbered.
- **A draft can be behind.** The released versions numbered between its base and its own number are
  ones it does not contain, unless an author applied their changes by hand, which the server cannot
  tell. `CatalogVersionDto.behind` lists them, each with the resources it changed against its own
  base, and a release lists the higher drafts now behind it (`draftsBehind`).
- **Releasing a draft that is behind must be acknowledged**: `acknowledgedBehind` names every
  release it is behind, or the release is refused with `409`. A fix shipped in `1.5.0` can still be
  left out of `2.0.0`, but never unnoticed.
- `listIncomingChanges`, `resolveIncomingChange`, incoming conflicts, `flowedInto` and
  `openConflicts` are removed. Bringing a release's changes into a draft on request, with a merge, is
  proposed separately in [ADR 0005](0005-merging-catalog-versions.md), outside 2.0.0.

The sections below are updated to this model.

## Amendment 2 (2026-10-18, proposed): a draft is numbered when it is released

The model below numbers a draft when it is created (`2.0.0`), renumbers it as the work becomes
known, and addresses it by that number. In practice the number is the last thing an author knows:
it follows from what changed, which is clear only when the work is done. Authors in Epistola Suite
never chose a number at creation; Suite already picked one at release from the kind of change,
renumbering under the hood. This amendment makes that the model. **Hotfixes are out of scope** here
and stay as ADR 0001 describes; they will be fitted to this model separately.

### A draft has a purpose and a name, not a number

- A **draft** is identified by an opaque `draftId`, and has a `name` people see. It has no version
  number until it is released.
- Every authored catalog has exactly **one next version**: the draft everyday work goes into. It is
  an explicit pointer on the catalog, not "the highest draft". A catalog with no released version
  has a next version too; its first release is its first version.
- **Long-term work** is any other draft: a large change that is not ready ("Redesign 2027"), kept
  apart from the next version. It is named when it is created, from the next version or from a
  released version.
- A draft's **base** stays what it is today: the released version it started from, fixed.

### Releasing: forward, numbered from what changed

- **Releasing the next version** numbers it from the kind of change against the **latest release**
  (the highest that is not a pre-release): corrections raise the patch, additions the minor,
  breaking changes the major, by the rules `markStencilReady` and the release impact already use. The
  author may raise the number (to mark a major on purpose), never lower it below what the changes
  need, and it must be above every release of the catalog.
- **After it is released, the next version continues**: a new next version opens on the release, as
  today. The catalog's history only moves forward.
- **Long-term work is released only as a pre-release**: `X.Y.Z-<label>.<n>`, where `X.Y.Z` is the
  number its changes would need against the latest release and `<label>` derives from its name
  (`2.0.0-redesign-2027.1`). SemVer ranges such as `^1.4.0` never match a pre-release, and the
  eventual `2.0.0` sorts above it, so a pre-release can be deployed to a test environment without
  any request selecting it by accident. A pre-release pins its dependencies like any release.
- **Long-term work becomes the next version** when it is ready ("promote"). It must first contain
  every release published since its base, or acknowledge those it leaves out, as a behind draft does
  today. The draft that was the next version is then discarded, or kept as long-term work: the author
  chooses. After promotion it is released like any next version.

### Releasing takes along what it uses

A release pins exact releases of the catalogs it uses (ADR 0003). A draft that uses changes only in
another catalog's **draft** cannot be released alone: releasing it releases that catalog's next
version too, in the same transaction, all or nothing, and pins the new release. A draft that uses
another catalog's long-term work cannot be released until that work is promoted, or the use removed.

### Installing and deploying (unchanged, restated)

- Installing a release requires the releases it pins to be present. Installing from an exchange
  installs them first (ADR 0003).
- Deploying a release uses the releases it pins implicitly, without deploying them (ADR 0003).

### What goes

- Choosing a number when creating a draft, and **renumbering** (`previewCatalogVersionRenumber`,
  `renumberCatalogVersion`, the renumber warnings). A draft has no number to change.
- "A version number is unique across drafts and releases": only releases have numbers.
- The automatic patch draft after a release is replaced by "the next version continues".
- `behind` stays, but only long-term work can be behind; the next version never is, since only it
  releases forward.

### API (proposed)

| Operation | Request | Notes |
| --- | --- | --- |
| `listCatalogDrafts` | `GET …/drafts` | The next version first, then long-term work |
| `createCatalogDraft` | `POST …/drafts` `{name, from?}` | Long-term work, from the next version (default) or a released version |
| `getCatalogDraft` | `GET …/drafts/{draftId}` | Name, base, whether it is the next version, the number it would be released as, what it is behind |
| `renameCatalogDraft` | `PATCH …/drafts/{draftId}` `{name}` | |
| `deleteCatalogDraft` | `DELETE …/drafts/{draftId}` | Not the next version, unless another is promoted first |
| `promoteCatalogDraft` | `POST …/drafts/{draftId}/promote` `{previous: discard \| keep, acknowledgedBehind?}` | Makes it the next version |
| `releaseCatalogDraft` | `POST …/drafts/{draftId}/release` `{notes?, version?, alsoRelease?, acknowledgedBehind?}` | The next version releases a version, long-term work a pre-release; returns the release |

- `next` is accepted wherever a `draftId` is: `…/drafts/next/templates/invoice` is the next
  version's Invoice. Scripts and the UI rarely need another id.
- Draft resources live under `…/drafts/{draftId}/…`; released versions stay under
  `…/versions/{version}/…`, read-only. `getCatalogChanges`, `markCatalogReady` and the dependency
  operations move under the draft.
- Preview names a draft by `draftId` in place of `draftVersion`.

### Open questions

- Does the next version's name show anywhere, or is it always "Next version"?
- Should a pre-release be publishable to an exchange, or local only?

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

- **A draft's base** is the released version it started from. `2.0.0` opened while `1.4.0` is the
  latest release starts as a copy of `1.4.0`, and `1.4.0` stays its base.
- **Releasing a draft changes no other draft.** When `1.5.0` is released while `2.0.0` is open,
  `2.0.0` is **behind** `1.5.0`: it holds the `1.5.0` changes only if an author applied them by hand.
  The draft lists what it is behind, and releasing it requires acknowledging that list.

### Creating, renumbering and discarding drafts

- **Create** a draft by giving a version number that is not in use. By default its content is its
  base.
- **Create from another version** ("create a new version from this") by also naming a source: any
  released version or open draft, below or above the new number. The draft starts as a copy of the
  source; its base is the source when that is released, and the source's base when it is a draft.
  Nothing flows in: released versions between the base and the new number make the draft behind
  them. Examples:
  - continue the `2.0.0` work as `3.0.0`: create `3.0.0` from draft `2.0.0`. Later edits to
    `2.0.0` do not reach `3.0.0`; once `2.0.0` is released, `3.0.0` is behind it;
  - start `1.5.0` from draft `2.0.0` to ship part of it early, then undo what is not finished. A
    draft may contain more than its base; it may never contain less;
  - patch an older line: create `1.0.2` from released `1.0.1` while `2.0.0` is released.
  The draft records its source, so the UI can show "created from 2.0.0".
- **After a release, the next draft is created automatically** if no draft above it exists: releasing
  `1.4.0` opens `1.4.1`. A team that works on one thing at a time never creates a draft by hand, and
  sees one editable version, as today.
- **Renumber** a draft by changing its version, as long as it is a draft, up or down. Typically the
  automatic `1.4.1` becomes `1.5.0` or `2.0.0` once the work is known. Only the number changes; the
  content and the base stay:
  - **Up past a released version** (`1.5.0` → `1.7.0` with `1.6.0` released): the draft becomes
    behind `1.6.0` (warning `falls-behind`).
  - **Down past a released version** (`2.0.0` → `1.5.0` with `1.6.0` released): the draft keeps its
    content, so it now contains changes from releases above it. That is allowed, as for "create
    from", but it means `1.5.0` will ship what `1.6.0` shipped.
  - **To a lower kind of version than the changes need** (a patch number while the draft has
    breaking changes against its base, by the rule ADR 0001 defines for hotfixes).

  None of these is refused. Each is a **warning**, listed by a renumber preview. A renumber with
  warnings is refused with `409` until it is repeated with `acknowledgeWarnings: true`, the same
  confirmation pattern as deploying over a hotfix (ADR 0001). The UI shows them before the author
  confirms. A renumber that only moves within the same gap (`1.5.0` → `1.5.3`) has no warnings.

  Refused outright: a number already in use, renumbering a released version, and renumbering a
  `+hotfix` draft. The response carries the new address; links to the old number stop working,
  which is acceptable for a draft.
- **Discard** a draft by deleting it, as long as it is a draft.
- A version number is unique in its catalog, across drafts and released versions.

### Ordering

- Versions do **not** have to be released in order: `1.5.0` can be released after `2.0.0`, as a
  maintenance release of the `1.x` line. It changes no other draft.
- Ready stays what it is today: a per-resource mark inside a draft, and the checklist for releasing
  it. A draft is releasable when every changed resource is ready, and, when it is behind, the
  release acknowledges the releases it is behind.

### Hotfixes

- A hotfix of an owned catalog is a draft of the next patch, for example `1.4.1` next to `2.0.0`.
- A `+hotfix.N` release (ADR 0001) is a draft numbered `1.0.1+hotfix.N` that the installation creates
  from an installed release. Its number is assigned when it is created, the next free `N`, and it
  cannot be renumbered. It contains its base, `1.0.1`, never later releases.

## API

Addresses use the version number, the same for drafts and released versions. The path parameter is
`catalogVersion`:

```
/v1/tenants/{tenantId}/catalogs/{registry}/{namespace}/{catalogKey}/versions
/v1/tenants/{tenantId}/catalogs/{registry}/{namespace}/{catalogKey}/versions/{catalogVersion}
/v1/tenants/{tenantId}/catalogs/{registry}/{namespace}/{catalogKey}/versions/{catalogVersion}/templates/{templateId}
/v1/tenants/{tenantId}/catalogs/{registry}/{namespace}/{catalogKey}/versions/{catalogVersion}/stencils/{stencilId}
…
```

| Operation | Request | Notes |
| --- | --- | --- |
| `listCatalogVersions` | `GET …/versions?status=` | Drafts and released versions, highest first |
| `createCatalogVersion` | `POST …/versions` `{version, from?}` | Opens a draft from its base, or from version `from` |
| `getCatalogVersion` | `GET …/versions/{catalogVersion}` | Status, base, source, the releases a draft is behind; release data once released |
| `previewCatalogVersionRenumber` | `GET …/versions/{catalogVersion}/renumber-preview?to=` | The renumber's warnings, without applying it |
| `renumberCatalogVersion` | `PATCH …/versions/{catalogVersion}` `{version, acknowledgeWarnings?}` | Drafts only; `409` while warnings are not acknowledged; returns the new `Location` |
| `deleteCatalogVersion` | `DELETE …/versions/{catalogVersion}` | Drafts only |
| `releaseCatalogVersion` | `POST …/versions/{catalogVersion}/release` `{notes?, acknowledgedBehind?}` | Draft → released; `409` while the draft is behind a release it does not acknowledge; reports the higher drafts now behind it, and the draft it opened |

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
  not know, a draft that is behind, and an acknowledged release.
- **No merge in 2.0.0.** Porting a change from one version to another is the author's work, made
  visible by `behind` and by the release acknowledgement. ADR 0005 proposes the merge.
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
  the releases the draft is behind (with what each changed), and the release button. The editor header names the catalog and version.
- **Creating a draft** reuses the patch / minor / major choice of today's release dialog, to pick its
  number. Every version, draft or released, offers "create a new version from this". Releasing a
  draft asks for notes and the optional exchange publication, asks the author to confirm the
  releases the draft is behind, and afterwards lists the higher drafts now behind it.
- **Deployments** become a catalog-level page: environments × released versions.

## Later: merging on request

See [ADR 0005](0005-merging-catalog-versions.md).

## Open questions

- Should a released version be withdrawable (still installed, never selected), or is undeploying
  enough?
