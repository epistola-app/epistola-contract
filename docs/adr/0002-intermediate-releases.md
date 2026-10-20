# ADR 0002: Working on several catalog versions at once

- **Status:** Accepted
- **Date:** 2026-10-19
- **Scope:** API contract (`contracts/api`), contract 2.0
- **Related:** [ADR 0001](0001-hotfixing-a-released-catalog.md) (hotfixing a subscribed release),
  [ADR 0003](0003-catalog-identity-and-distribution.md) (catalog identity and dependencies),
  [ADR 0005](0005-merging-catalog-versions.md) (merging on request)

## Context

Before 2.0 a catalog has one working copy, and a release contains all of it. Releasing is refused
while any resource is `new` or `modified`.

That works while a team finishes one release before starting the next. It breaks when work overlaps:
the working copy holds a large change that is weeks from done, and alongside it a few finished
improvements that should ship now, or a fix to a release that is already out. The team can either
wait for the large change, or undo the unfinished work to release.

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

### C. Drafts, each released on its own

A catalog consists of **releases**, each a draft or released. A catalog has any number of drafts,
all alike: each named, each started from a release it is measured against, and each released on its
own, given its version when it is released, from what it changed. Nothing is merged. This is the decision
below.

### D. Keep "release everything"

Teams sequence their work, or keep unfinished work out of the catalog until it is done. This is
simple, but it pushes the problem onto the authors. **Rejected.**

### Considered and rejected within option C

- **A next version and long-term work, with promotion.** One draft is the "next version", the only
  one released; other drafts are long-term work that must be promoted to the next version before
  release. **Rejected:** it made a UI convention a rule of the contract, and needed a special draft,
  a promote operation and a problem type of its own, while a fix to an older line was still not
  possible. Epistola Suite may still show one draft as "Next version"; the contract does not know
  it.
- **Releasing flows forward into higher drafts.** Every release is merged into every higher draft
  with a node-level three-way merge, so a draft always contains every release below it. It needs a
  merge per resource type, conflicts an author did not cause, and incoming-change operations to
  resolve them. Too much for 2.0.0; a merge on request is proposed in ADR 0005.
- **A number at creation, and renumbering.** Each draft has a SemVer number from the start, is
  addressed by it, and is renumbered as the work becomes known. The number is the last thing an
  author knows, so it was chosen wrongly and changed; renumbering changed addresses and needed a
  warning model of its own.
- **Pre-releases of drafts** (`X.Y.Z-<label>.<n>`), so the work could be deployed and tried. A
  pre-release had to be deployed, kept out of every version range and out of every exchange, and
  numbered by a rule of its own, only so the work could be tried. Deploying the draft itself tries
  it, with none of that.

## Decision

Adopt **option C**.

### A release is a draft or released

- A catalog's releases are one object, in status **draft** (a draft release, or draft: editable)
  or **released** (immutable: every write to it, its name included, is refused with `409`).
- Every release has an opaque **`releaseId`**, assigned by the server when it is started as a draft
  and kept when it is released, as is its name; a release installed by import or from its source
  gets one when it is installed, and has no name. A draft has no version yet, so it is identified by
  its `releaseId` alone. A released release is identified by its `releaseId` or by its **version**,
  `MAJOR.MINOR.PATCH`, unique in its catalog, interchangeably: both address the same release.
  "Version" only ever means that number.
- Only released releases are exported or published to a registry, and version ranges (`^1.4.0`) never match a
  draft. A draft is rendered only by preview and by an environment it is deployed to (see "Trying
  work: deploying a draft").

### Drafts

- An authored catalog has **any number of drafts**, none special. A subscribed catalog has none.
- A draft has a `releaseId` (its address), a **name** unique among the catalog's drafts, kept when
  it is released, and a fixed
  **base**: the release it started from, which its changes are measured against and its number
  follows from. The base is null when the catalog had no release yet.
- A draft is started (`createCatalogRelease {name, from?}`) as a copy of a **release**, which becomes
  its base, or of **another draft**, unreleased changes included, on that draft's base. Without
  `from` it starts from the catalog's highest release, or empty when there is none.
- Any draft can be renamed and discarded. Discarding ends its deployments.
- **Drafts are independent copies.** Releasing one never changes another. Nothing is merged.
- Which draft an author calls the next version, and which long-term work, is a convention of the
  UI, not of the contract.

### Releasing any draft, numbered from what changed against its base

- **Any draft can be released.** A release contains the whole draft: it is refused while any
  resource is `new` or `modified`. Ready is a per-resource mark inside a draft, and the checklist for
  releasing it.
- **The number follows from the kind of change against the draft's base**: corrections raise the
  patch, additions the minor, breaking changes the major, by the rules `markStencilReady` and the
  release impact already use. A draft without a base needs `1.0.0`.
- The number must be **above the base** and **not already taken**. When the number the changes need
  is taken, the release gets the next free number of the same kind. The author may give a higher
  number (to mark a major on purpose), never one lower than the changes need. The release preview
  (`previewCatalogDraftRelease`) shows the number the changes need (`needed`), the number the draft
  gets (`suggested`), and why.
- Examples, from base `1.4.0` while `1.5.0` and `2.0.0` exist: a fix becomes `1.4.1`; a breaking
  redesign needs a major, `2.0.0` is taken, so it becomes `3.0.0`, or a higher number the author
  picks.
- **A fix to an older line is an ordinary release** of a draft started from a release of that line:
  `1.4.1` after `2.0.0`. Releases are not in order of time.
- Only what others rely on counts, so a catalog's dependencies do not make a release breaking by
  themselves: a dependency added is an addition (an installation brings it along), one removed is no
  change, and moving to another release of a dependency is breaking only when that release breaks a
  resource this catalog uses (see "Dependencies are exact"), whatever the step in the dependency's
  number.
- **The draft becomes released** at that version, immutable. Its `releaseId` and its name stay,
  and the id addresses it as the version does: a write through either is refused with
  `409`, never `404`. Where the draft was deployed, the environment now serves the release.

### A draft can be behind

A draft does not contain the changes of releases made from other drafts, unless an author applied
them by hand, which the server cannot tell. Relative to the number it would be released as, a draft
is **behind** every release that is:

- (a) numbered above its base and below that number, or
- (b) released after the draft was started and numbered below that number (other than its base).

Examples:

- A draft on `2.0.0` is behind a fix `1.4.1` released after it was started (b).
- "Redesign", started from `1.4.0` and released as `2.0.0`, is behind `1.5.0` (a).
- A fix `1.4.1` from `1.4.0` is behind nothing: `1.5.0` and `2.0.0` are above its number.

`CatalogReleaseDto.behind` lists them against the number the release preview gives, each with the
resources it changed against its own base. **Leaving a release out must be acknowledged**: releasing
a draft that is behind names every one of those releases in `acknowledgedBehind`, recomputed for the
number it is actually released as, or it is refused with `409`. A fix shipped in `1.5.0` can still
be left out of `2.0.0`, but never unnoticed. The release response names the catalog's drafts the new
release puts behind (`draftsBehind`).

### Trying work: deploying a draft

A draft is tried where it is authored, by deploying it to an environment, not by releasing it
(`deployCatalogRelease`, addressed by its `releaseId`; ADR 0003, "Deploying"):

- The environment renders the draft live, with no release. It serves at most one draft per
  catalog; deploying another replaces it. Deploying is a deployment change: the same permission,
  recorded in the deployment history.
- A generation or preview request through that environment that names **no version** gets the
  draft. A request naming a **range or an exact release** gets the highest served release that
  satisfies it: a draft never matches a version, so an integration pinned to a release or a range
  is not moved.
- An environment marked **production** serves only releases: deploying a draft there is refused,
  and an environment that serves a draft cannot be marked production
  (`environment-deployment-refused`).
- A document rendered from a draft records the draft's id and its name at render time, and its PDF
  carries a preview watermark.
- The deployment follows the draft: releasing it keeps the `releaseId`, so the environment then
  serves it as released (`released` in the history); discarding it ends the deployment (`undeployed`).

### Releasing takes along the drafts it uses

A release pins exact releases of the catalogs it uses (ADR 0003). While authoring, a draft may build
on another catalog's work that is not released yet:

- In a **draft**, a dependency declaration names either a release (`version`) or a draft of
  the other catalog (`releaseId`), exactly one. The draft then renders with that draft, live.
- A **release never pins a draft**, so the catalog archive and exchanges only ever carry releases.
- Releasing a draft whose declarations name drafts is refused with `409`
  (`release-dependencies-unreleased`), listing each such draft, directly or through those drafts'
  own declarations, unless the request asks to release them along (`releaseDependencies`).
- With `releaseDependencies`, those drafts are released first, recursively, each numbered by its
  own rules, in the same transaction, **all or nothing**: when one of them is refused (behind
  without acknowledgement, not releasable), nothing is released. The new release pins the releases
  they became, and the response lists them (`releasedDependencies`).
- A draft released on its own becomes a release, and declarations that named it name that release.
  A draft that another catalog's draft declares cannot be discarded.

### Dependencies are exact; compatibility is checked per use

- A release pins **exact** releases of the catalogs it uses (ADR 0003), and renders with exactly
  those wherever it is installed. A dependency declaration names an exact release, not a range.
  Ranges in declarations (such as `^1.0.0`, any non-breaking 1.x) may come in a later 2.x, as an
  addition. Version ranges (`versionRange`) remain only where a request picks a release to render or check:
  generation, preview and validation.
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
- **An export can carry what a release uses**: a catalog archive may contain the releases it pins,
  transitively, each an ordinary catalog archive at
  `dependencies/{registry}/{namespace}/{catalogKey}/{releaseVersion}.zip`
  ([catalog archive](../../contracts/catalog/docs/catalog-archive.md)). Importing it installs the
  dependencies first. It is how a catalog moves to an installation that does not reach an exchange.

## API

A release of a catalog, draft or released, is addressed by one path segment, `{release}`:

```
/tenants/{tenantKey}/catalogs/{registry}/{namespace}/{catalogKey}/releases
/tenants/{tenantKey}/catalogs/{registry}/{namespace}/{catalogKey}/releases/{release}
/tenants/{tenantKey}/catalogs/{registry}/{namespace}/{catalogKey}/releases/{release}/templates/{templateKey}
/tenants/{tenantKey}/catalogs/{registry}/{namespace}/{catalogKey}/releases/{release}/stencils/{stencilKey}
…
```

`{release}` is a `releaseId` (a draft, or a released release) or an exact version (a released
release); a released release is read-only whichever way it is addressed. A `releaseId`
never contains a dot and a SemVer number always does, so they never collide: drafts and releases
share one segment and every resource path stays where it is. A `releaseId` is lowercase letters,
digits and single hyphens, 4 to 36 characters (`CatalogReleaseId`); a UUID fits, but a short random
id such as `k7m2x9qa` is recommended, because people read and type it.

| Operation | Request | Notes |
| --- | --- | --- |
| `listCatalogReleases` | `GET …/releases?status=` | Drafts (oldest first), then released releases (highest version first) |
| `createCatalogRelease` | `POST …/releases` `{name, from?}` | Always a draft; `from` a version or a `releaseId`; default the highest release |
| `getCatalogRelease` | `GET …/releases/{release}` | Status, base, name, what a draft is behind; for a released release its dependencies; the environments that serve it |
| `updateCatalogRelease` | `PATCH …/releases/{release}` `{name?, defaultTheme?, presets?}` | Any draft; a released release, its name included, is refused with `409` |
| `deleteCatalogRelease` | `DELETE …/releases/{release}` | Any draft |
| `previewCatalogDraftRelease` | `GET …/releases/{release}/release-preview` | `base`, `changeKind`, `reasons`, `needed`, `suggested` |
| `releaseCatalogDraft` | `POST …/releases/{release}/release` `{version?, notes?, acknowledgedBehind?, releaseDependencies?}` | Any draft. Returns the release, `draftsBehind` and `releasedDependencies`; refused with `release-dependencies-unreleased` when declarations name drafts and `releaseDependencies` is not set |

Deployments (ADR 0003, "Deploying"):

| Operation | Request | Notes |
| --- | --- | --- |
| `deployCatalogRelease` | `PUT …/environments/{environmentKey}/deployments/{registry}/{namespace}/{catalogKey}/{release}` | A version or a `releaseId`; at most one draft per catalog; a draft on a production environment is refused (`environment-deployment-refused`) |
| `undeployCatalogRelease` | `DELETE …/environments/{environmentKey}/deployments/{registry}/{namespace}/{catalogKey}/{release}` | Only an explicit deployment |

- **Every resource path of the catalog's content is under `…/releases/{release}/`,** and so
  are `getCatalogChanges`, `markCatalogReady` and the dependency operations. Reads work on any
  release. Writes and ready marks work on drafts only, and are refused with `409` on a released
  release.
- `CatalogDto.drafts` lists the drafts (`CatalogDraftReleaseSummary`: `releaseId`, name, base).
  `CatalogReleaseDto` carries `releaseId`, `status` (`draft` or `released`) and `name` for every
  release, and `version` once released. A release keeps its `releaseId` and its name when it is
  released; a release installed by import or from its source has no name.
- `CatalogDependencyRequirement` and `CatalogDependencyDto` carry `version` or, in a draft,
  `releaseId`.
- `EnvironmentDto.production` marks a production environment.
- Generation and results address released releases by version, and every member named `version`
  is one. A deployment names a release by `releaseId` or version, with its `status` (`draft` or
  `released`), its `releaseName`, and a null `version` for a draft. A document rendered from a draft
  carries `releaseId` and `draftLabel` with a null `version`. `validateTemplateData` stays on the catalog, because
  it selects a release by version range and environment, as generation does.
- Preview renders a draft named by its `releaseId`.
- `importCatalog` never touches a draft. It installs the release the archive carries, at its version,
  next to the catalog's other releases (ADR 0003): already present with the same fingerprint, nothing
  changes; the same number with a different fingerprint is refused with 409. A catalog the import
  creates holds that release and no draft; an author starts one from the release
  (`createCatalogRelease` with `from`).

## Consequences

- **Contract change:** the working-copy operations gain a release segment, and a catalog's drafts
  and released releases become one object, the release. Every client follows, and the conformance
  suite has scenarios for the release operations, including a draft that is behind, an acknowledged release,
  and a release that takes its dependency drafts along.
- **No merge in 2.0.0.** Porting a change from one draft to another is the author's work, made
  visible by `behind` and by the acknowledgement. ADR 0005 proposes a merge on request.
- **No choice of number up front, and no renumbering.** An address of a draft never changes; the
  number is decided once, at release.
- **Older lines are fixed by ordinary releases.** ADR 0001 is narrowed to an installation that does
  not author the catalog.
- **Archives:** unchanged by drafts. Only released releases are exported, a release pins only
  releases, and importing an archive installs its release: an archive never becomes a draft.
- **Not in the API yet:** the per-use check when moving to a newer dependency release.

### Suite

Suite may not implement this yet. The contract does not prescribe a UI, but the model is designed for
this one:

- **The author picks catalog and draft once**, in the top navigation next to the tenant: "Letters ·
  Spring fixes". Suite may show one draft as "Next version", a convention of its own. A subscribed
  catalog shows its installed release, read-only.
- **The authoring pages follow that choice.** Templates, stencils and themes list the resources of
  the chosen version; a create dialog no longer asks for a catalog. Resources from dependencies show
  read-only, with their version, or with the draft they follow.
- **The page address carries the choice**, catalog first, mirroring the API. The last choice is
  remembered per user.
- **A version bar** under the navigation shows the base, the number of changes and ready resources,
  the releases the draft is behind (with what each changed), and the release button. The editor
  header names the catalog and draft.
- **Releasing** shows the number the changes need and the number the draft gets, lets the author
  raise it, asks for notes and the optional exchange publication, asks the author to confirm the
  releases the draft is behind, offers to release the drafts of other catalogs it uses along with
  it, and afterwards lists the drafts now behind it.
- **Trying work** is "deploy to environment…" on a draft.
- **Deployments** become a catalog-level page: environments × releases, released ones and the
  draft each environment serves.

## Open questions

- Should a released release be withdrawable (still installed, never selected), or is undeploying
  enough?
