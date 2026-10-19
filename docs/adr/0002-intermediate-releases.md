# ADR 0002: Working on several catalog versions at once

- **Status:** Accepted
- **Date:** 2026-10-15
- **Scope:** API contract (`contracts/api`), contract 2.0
- **Related:** [ADR 0001](0001-hotfixing-a-released-catalog.md) (hotfixing an older release),
  [ADR 0003](0003-catalog-identity-and-distribution.md) (catalog identity and dependencies),
  [ADR 0005](0005-merging-catalog-versions.md) (merging on request)

## Context

Before 2.0 a catalog has one working copy, and a release contains all of it. Releasing is refused
while any resource is `new` or `modified`.

That works while a team finishes one release before starting the next. It breaks when work overlaps:
the working copy holds a large change that is weeks from done, and alongside it a few finished
improvements that should ship now. The team can either wait for the large change, or undo the
unfinished work to release.

Two more things are hard to get right:

- **The number.** The number of a release follows from what changed: corrections, additions or
  breaking changes. That is clear only when the work is done. Authors in Epistola Suite never chose a
  number up front; Suite picked one at release from the kind of change.
- **Trying work before it is released.** An author wants to see unfinished work in an environment,
  with real integrations, without releasing it and without it reaching production.

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
it asks non-developers to understand branching: merges in any direction, branch lifecycle.
**Rejected.**

### C. Catalog versions: a next version and long-term work

A catalog consists of **versions**, each a draft or released. One draft is the **next version**,
where everyday work goes; other drafts are **long-term work**, kept apart until they are ready. Only
the next version is released, and it is numbered when it is released. This is the decision below.

### D. Keep "release everything"

Teams sequence their work, or keep unfinished work out of the catalog until it is done. This is
simple, but it pushes the problem onto the authors. **Rejected.**

### Considered and rejected within option C

- **Releasing flows forward into higher drafts.** Every release is merged into every higher draft
  with a node-level three-way merge, so a draft always contains every release below it. It needs a
  merge per resource type, conflicts an author did not cause, and incoming-change operations to
  resolve them. Too much for 2.0.0; a merge on request is proposed in ADR 0005.
- **A number at creation, and renumbering.** Each draft has a SemVer number from the start, is
  addressed by it, and is renumbered as the work becomes known, with warnings when a new number
  passes a release or is too low for the changes. The number is the last thing an author knows, so
  it was chosen wrongly and changed; renumbering changed addresses and needed a warning model of its
  own. Releases in any order (`1.5.0` after `2.0.0`) followed from it.
- **Pre-releases of long-term work** (`X.Y.Z-<label>.<n>`), so the work could be deployed and tried.
  A pre-release had to be deployed, kept out of every version range and out of every exchange, and
  numbered by a rule of its own, only so the work could be tried. An environment override tries the
  draft itself, with none of that.

## Decision

Adopt **option C**.

### A version is a draft or released

- A **draft** is editable. A **released** version is immutable: every write to it is refused with
  `409`.
- A draft has no number. It is identified by an opaque `draftId`. A released version is identified
  by its SemVer number, unique in its catalog.
- Only released versions are deployed, exported or published, and version selectors (`^1.4.0`)
  never match a draft. A draft is rendered only by preview and by an environment that follows it
  (see "Trying work: environment overrides").

### The next version and long-term work

- Every authored catalog has exactly **one next version**: the draft everyday work goes into. It is
  an explicit pointer on the catalog, not "the highest draft". It has no name of its own; it is
  always "Next version". A catalog that was never released has a next version too; its first release
  is its first version. A subscribed catalog has no drafts.
- **Long-term work** is any other draft: a large change that is not ready ("Redesign 2027"), kept
  apart from the next version. It is named when it is started, from the next version (unreleased
  changes included) or from a released version. Its name is unique among the catalog's drafts.
- A team that works on one thing at a time never starts long-term work, and sees one editable
  version, as before 2.0.

### A draft's base is fixed, and a draft can be behind

- **A draft's base** is the released version it started from, which its changes are measured
  against. Long-term work started from the next version takes the next version's base. The base
  does not move when other versions are released.
- **Drafts are independent copies.** Releasing one never changes another. Nothing is merged.
- **A draft is behind** every release above its base. It does not contain their changes unless an
  author applied them by hand, which the server cannot tell. `CatalogVersionDto.behind` lists them,
  each with the resources it changed against its own base.
- The next version continues on each release, so in the ordinary course it is behind nothing. Long-term
  work is behind every release published since it started.
- **Leaving a release out must be acknowledged**: releasing or promoting a draft that is behind names
  every release it is behind in `acknowledgedBehind`, or it is refused with `409`. A fix shipped in
  `1.5.0` can still be left out of later work, but never unnoticed.

### Releasing: forward, numbered from what changed

- **Only the next version is released.** Releasing long-term work is refused
  (`catalog-draft-kind`).
- A release contains the whole draft: it is refused while any resource is `new` or `modified`. Ready
  is a per-resource mark inside a draft, and the checklist for releasing it.
- **The number follows from the kind of change against the latest release**: corrections raise the
  patch, additions the minor, breaking changes the major, by the rules `markStencilReady` and the
  release impact already use. The author may give a higher number (to mark a major on purpose), never
  one lower than the changes need, and it must be above every release of the catalog. The release
  preview shows the number and why.
- Only what others rely on counts, so a catalog's dependencies do not make a release breaking by
  themselves: a dependency added is an addition (an installation brings it along), one removed or its
  range changed is no change, and moving to another release of a dependency is breaking only when
  that release breaks a resource this catalog uses (see "Dependencies are exact"), whatever the step
  in the dependency's number.
- **After it is released, the next version continues**: it goes on as a draft on the new release.
  The catalog's history only moves forward. Long-term work is untouched and now behind the release,
  which the release response names (`draftsBehind`).

### Promotion: long-term work becomes the next version

- Long-term work becomes the next version when it is ready ("promote"). It must first contain every
  release published since it started, or acknowledge those it leaves out. It then continues on the
  latest release, behind nothing.
- The draft that was the next version is discarded with its changes, or kept as long-term work under
  a name: the author chooses.
- After promotion it is released like any next version.
- Discarding the next version starts it again on the latest release (empty when there is none), so
  everyday work always has somewhere to go. Discarding long-term work removes it.

### Trying work: environment overrides

Long-term work and the next version are tried where they are authored, by pointing an environment at
them, not by releasing them:

- An environment can **override** which version of a catalog it uses as its latest: one of the
  releases it serves (a rollback, without undeploying anything), or a **draft** of the catalog, the
  next version or long-term work, rendered live with no release. There is at most one override per
  catalog per environment. Setting one is a deployment change: the same permission, recorded in the
  deployment history.
- A generation or preview request through that environment that names **no version, or a version
  range**, gets the override. A request naming an **exact release** still gets that release, so an
  integration pinned to a release is not moved.
- An environment marked **production** uses only published versions: an override there may name a
  release, never a draft, and an environment that follows a draft cannot be marked production.
- A document rendered from a draft records the draft's id and its name at render time ("Next version"
  for the next version), and its PDF carries a preview watermark.
- An override ends with what it names: undeploying the release it names ends it, and so does
  discarding the draft it follows. Promoting long-term work moves environments following the next
  version to the new next version; an environment following the promoted work keeps following it.

### Releasing takes along what it uses

A release pins exact releases of the catalogs it uses (ADR 0003). A draft that uses changes only in
another catalog's **next version** cannot be released alone: releasing it releases that catalog's next
version too, in the same transaction, all or nothing, and pins the new release. A draft that uses
another catalog's long-term work cannot be released until that work is promoted, or the use removed.

### Dependencies are exact; compatibility is checked per use

- A release pins **exact** releases of the catalogs it uses (ADR 0003), and renders with exactly
  those wherever it is installed. Nobody chooses a version range: a declaration's range defaults to
  `*`, and stays only for clients that set one over the API.
- Moving to a newer release of a catalog it uses is decided while authoring, and **checked per use**,
  not by the version number. The newer release is compared with the pinned one resource by resource,
  by the rules that classify a release's changes (a resource removed, a stencil placeholder removed
  or a parameter now required, a code removed, …), and only the breaking changes to resources this
  catalog **uses** count:
  - none: the newer release **fits**, and the next release moves to it;
  - any: it does not fit; the catalog stays on the pinned release, and the author is told what does
    not fit and why, until the templates are adapted.

  A major release that breaks only what is not used fits; a patch that breaks something used does
  not.
- Version numbers still say, for people, what kind of change a release is. They do not decide
  compatibility.
- Installing a release requires the releases it pins to be present; installing from an exchange
  installs them first. Deploying a release uses the releases it pins implicitly, without deploying
  them (ADR 0003).

### Exchanges and exports

- **Work in progress is never published to an exchange.** An exchange carries what other
  installations build on, which is releases.
- **An export can carry what a release uses** ([catalog bundle](../catalog-bundle.md)): one file
  with the release and the releases it pins, transitively, installed dependencies first. It is how a
  catalog moves to an installation that does not reach an exchange.

### Older lines

Releases only go forward: a release number must be above every release of the catalog, and only the
next version is released. A fix to an older line once a newer major is out (`1.4.1` after `2.0.0`)
is therefore not possible through this model. ADR 0001 proposes how to fill that gap.

## API

A catalog version is addressed by one path segment, `{catalogVersion}`:

```
/tenants/{tenantKey}/catalogs/{registry}/{namespace}/{catalogKey}/versions
/tenants/{tenantKey}/catalogs/{registry}/{namespace}/{catalogKey}/versions/{catalogVersion}
/tenants/{tenantKey}/catalogs/{registry}/{namespace}/{catalogKey}/versions/{catalogVersion}/templates/{templateKey}
/tenants/{tenantKey}/catalogs/{registry}/{namespace}/{catalogKey}/versions/{catalogVersion}/stencils/{stencilKey}
…
```

`{catalogVersion}` is `next` (the next version), a `draftId` (long-term work) or an exact SemVer
number (a released version, read-only). A UUID, `next` and a SemVer number never collide, so drafts
and releases share one segment and every resource path stays where it is: `…/versions/next/templates/invoice`
is the next version's Invoice.

| Operation | Request | Notes |
| --- | --- | --- |
| `listCatalogVersions` | `GET …/versions?status=` | The next version, then long-term work (oldest first), then released versions (highest first) |
| `createCatalogDraft` | `POST …/versions` `{name, from?}` | Starts long-term work, from the next version (default) or a released version |
| `getCatalogVersion` | `GET …/versions/{catalogVersion}` | Status, base, `isNext`, name, what a draft is behind; for a release its dependencies and the environments that serve it |
| `renameCatalogDraft` | `PATCH …/versions/{catalogVersion}` `{name}` | Long-term work only |
| `deleteCatalogDraft` | `DELETE …/versions/{catalogVersion}` | Drafts only; the next version starts again on the latest release |
| `previewCatalogDraftRelease` | `GET …/versions/{catalogVersion}/release-preview` | The number the draft would be released as, and why |
| `releaseCatalogDraft` | `POST …/versions/{catalogVersion}/release` `{version?, notes?, acknowledgedBehind?}` | The next version only; long-term work is refused (`catalog-draft-kind`). Returns the release and `draftsBehind` |
| `promoteCatalogDraft` | `POST …/versions/{catalogVersion}/promote` `{keepPreviousAs?, acknowledgedBehind?}` | Makes long-term work the next version |

Environment overrides:

| Operation | Request | Notes |
| --- | --- | --- |
| `listEnvironmentOverrides` | `GET …/environments/{environmentKey}/overrides` | At most one per catalog |
| `setEnvironmentOverride` | `PUT …/environments/{environmentKey}/overrides/{registry}/{namespace}/{catalogKey}` `{releaseVersion? \| catalogVersion?}` | Exactly one; a draft on a production environment is refused (`environment-override-refused`) |
| `clearEnvironmentOverride` | `DELETE …/environments/{environmentKey}/overrides/{registry}/{namespace}/{catalogKey}` | Idempotent |

- **Every resource path of the catalog's content is under `…/versions/{catalogVersion}/`,** and so
  are `getCatalogChanges`, `markCatalogReady` and the dependency operations. Reads work on any
  version. Writes and ready marks work on drafts only, and are refused with `409` on a released
  version.
- `CatalogDto.drafts` lists the open drafts (`CatalogDraftSummary`), the next version first.
- `EnvironmentDto.production` marks a production environment.
- Deployments, generation and results address released versions by number, and keep the name
  `releaseVersion` where they mean one. A document rendered from a draft carries `draftId` and
  `draftLabel` with a null `releaseVersion`. `validateTemplateData` stays on the catalog, because
  it selects a release by version selector and environment, as generation does.
- Preview renders a draft named by `next` or its `draftId`.
- `importCatalog` takes the draft of an authored catalog to import into as `catalogVersion`
  (`next` or a draft id; default the next version).

## Consequences

- **Contract change:** the working-copy operations gain a version segment, and the release
  operations become version operations. Every client follows, and the conformance suite has
  scenarios for the version operations, including a draft that is behind and an acknowledged
  release.
- **No merge in 2.0.0.** Porting a change from one version to another is the author's work, made
  visible by `behind` and by the acknowledgement. ADR 0005 proposes a merge on request.
- **No choice of number up front, and no renumbering.** An address of a draft never changes; the
  number is decided once, at release.
- **No fix to an older line** without ADR 0001 or a successor.
- **Archives:** unchanged. Only released versions are exported.
- **Not in the API yet:** releasing another catalog's next version along with a release, and the
  per-use check when moving to a newer dependency release. `releaseCatalogDraft` and the dependency
  operations do not describe either yet.

### Suite

Suite may not implement this yet. The contract does not prescribe a UI, but the model is designed for
this one:

- **The author picks catalog and version once**, in the top navigation next to the tenant:
  "Letters · Next version", or the name of long-term work. A catalog with only a next version shows no
  version choice. A subscribed catalog shows its installed release, read-only.
- **The authoring pages follow that choice.** Templates, stencils and themes list the resources of
  the chosen version; a create dialog no longer asks for a catalog. Resources from dependencies show
  read-only, with their version.
- **The page address carries the choice**, catalog first, mirroring the API:
  `/tenants/acme-corp/catalogs/acme-corp/letters/versions/next/templates/invoice`. The last choice
  is remembered per user.
- **A version bar** under the navigation shows the base, the number of changes and ready resources,
  the releases the draft is behind (with what each changed), and the release or promote button. The
  editor header names the catalog and version.
- **Releasing** shows the number the changes need, lets the author raise it, asks for notes and the
  optional exchange publication, asks the author to confirm the releases the draft is behind, and
  afterwards lists the long-term work now behind it.
- **Trying work** is "use in environment…" on a draft, which sets an environment override.
- **Deployments** become a catalog-level page: environments × released versions, with each
  environment's override.

## Open questions

- Should a released version be withdrawable (still installed, never selected), or is undeploying
  enough?
