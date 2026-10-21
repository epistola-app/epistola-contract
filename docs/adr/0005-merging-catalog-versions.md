# ADR 0005: Merging catalog versions

- **Status:** Accepted (alpha), 2026-10-21. Proposed 2026-10-16; accepted as Epistola Suite
  implemented step one (Suite ADR 0032, section 5). The operations are `x-experimental` and may
  still change in a minor release.
- **Date:** 2026-10-16
- **Scope:** API contract (`contracts/api`) and portable catalog contract (`contracts/catalog`)
- **Related:** [ADR 0002](0002-intermediate-releases.md) (drafts and releases; drafts are independent
  copies), [ADR 0004](0004-resource-identity-within-a-catalog.md) (resource identity),
  [ADR 0001](0001-hotfixing-a-released-catalog.md) (hotfixing, superseded by this merge),
  Epistola Suite ADR 0032 (catalogs are drafts and releases, moved between installations and merged)

## Context

In contract 2.0 a draft is an independent copy of the release it started from (ADR 0002). A draft
is **behind** releases made from other drafts that are numbered below the number it would be
released as (above its base, or released after it started), and releasing it requires
acknowledging them. Porting a change released in `1.5.0` into another draft ("Redesign 2027") is
then the author's work: they apply it again by hand.

That is safe but laborious, and an author porting by hand can get it subtly wrong. It became
necessary rather than convenient when Epistola Suite dropped catalog types (Suite ADR 0032): a
catalog can be changed in more than one installation (test, and production where it is allowed),
and receiving a release from elsewhere must bring a draft with changes of its own along to it
instead of overwriting or ignoring them.

## Decision

### Pull, not push

A draft **pulls** a release in when its author asks: "merge `1.5.0` into Redesign 2027". Releasing
a version never changes another draft; that is ADR 0002's guarantee and stays. Pull is preferred
over pushing every release into higher drafts (rejected in ADR 0002) because:

- an author is never surprised by conflicts in a draft they are working on, caused by someone else's
  release;
- it fits the existing `behind` list: each entry gets a "merge this release" action.

In the alpha, what is merged in is a **released release** of the same catalog, named by its
version. Merging another draft is later work.

### The base stays fixed; the draft records what it merged

ADR 0002 fixes a draft's base for good, and the alpha keeps it: merging a release does not move the
base, above it or below it. Instead the draft **records each release it has merged**. A release the
draft has merged, or its base, is one it **already contains**: it drops off `behind`, a release
acknowledging behind releases no longer needs it, and merging it again is refused.

(The proposal moved the base to the merged release when it was above the old one. Keeping the base
fixed keeps "what changed against the base" and the release number it implies the same before and
after a merge, so the number a draft needs is still decided by ADR 0002's rules alone.)

### A three-way merge, per resource

The merge compares three states of each resource, identified by type and key (ADR 0004): the
draft's **base**, **theirs** (the release merged in) and **ours** (the draft). Each state is the
resource's canonical fingerprint (`CatalogCanonicalizer.perResourceFingerprints`, the per-resource
form of the release's own fingerprint), so key order, number formatting and the like never make a
difference. A resource absent from a state is not in it: added on a side that has it and the base
does not, removed on a side that does not have it and the base does.

| Ours vs base | Theirs vs base | Outcome     | Merging                                     |
| ------------ | -------------- | ----------- | ------------------------------------------- |
| same         | same           | `unchanged` | nothing                                     |
| changed      | same           | `ours`      | the draft's stays                           |
| same         | changed        | `theirs`    | taken from the release: added, written as the release has it, or removed |
| changed      | changed alike  | `same`      | nothing: both made the same change          |
| changed      | changed apart  | `conflict`  | the author chooses `ours` or `theirs`       |

"Changed" is any of added, modified (another fingerprint) or removed; the preview reports how each
side changed a resource (`draftChange`, `releaseChange`: `added`, `modified`, `removed`, or null).
The preview lists the resources whose outcome is `ours`, `theirs` or `conflict` and counts the rest
(`unchanged`).

- **A conflict is resolved per resource**, to `ours` (the draft's stays) or `theirs` (the
  release's is taken). Every conflict needs a resolution, and a resolution may only name a
  conflict. Combining both sides within one resource is later work (step two below).
- **Taken resources are marked ready**: they are released content.
- **The draft's own settings stay its own**: its default theme, presets and dependency declarations
  are not merged in the alpha. (The proposal merged declared dependencies as one more resource.)
- **A removal the draft still depends on is refused**: when the release removes a resource that
  something the draft keeps still uses, the merge is refused and changes nothing. Keep it (resolve
  to `ours`), or stop using it first.
- A merge with no conflicts and no changes of ours is a fast-forward: the draft becomes the release.

### Conflicts are shown, not guessed

- The preview (`previewCatalogDraftMerge`) changes nothing and names every conflict structurally
  (resource type and key), so a client can link to it.
- The merge (`mergeIntoCatalogDraft`) is all or nothing: a conflict without a resolution refuses it
  with `merge-conflicts-unresolved`, which lists them (`unresolvedConflicts`).

### API (alpha)

Under the draft, with the release merged in named by its version:

| Operation | Request | Notes |
| --- | --- | --- |
| `previewCatalogDraftMerge` | `GET …/releases/{release}/merge-preview?version=` | `draft`, `base`, `release`, `alreadyMerged`, `resources` (`resourceType`, `resourceKey`, `name`, `outcome`, `draftChange`, `releaseChange`), `unchanged` |
| `mergeIntoCatalogDraft` | `POST …/releases/{release}/merge` `{release, resolutions}` | `resolutions` maps `type/key` to `ours` or `theirs`; returns `release`, `taken`, `removed`, `kept`, each as `type/key` |

The release to merge is the query parameter `version` because `{release}` in the path already names
the draft; in the bodies it is `release`.

Refusals, as RFC 9457 problems:

- 409 `merge-conflicts-unresolved`: conflicts without a resolution, listed in `unresolvedConflicts`;
- 409 `merge-refused`: the release removes a resource the draft still uses;
- 422 `merge-not-applicable`: the draft already contains the release (its base, or merged before),
  or a resolution names a resource that is not a conflict;
- 409 `catalog-read-only` (`readOnlyReason`): the catalog cannot be changed in this installation;
- 409 `conflict`: the path names a released release, not a draft; 404: no such draft or release.

### Where it lives

Step one is the table above over fingerprints the catalog module already computes. When step two
comes, the merge is implemented once, in the catalog module, with published golden fixtures (base,
theirs, ours to result or conflicts), so every installation merges the same way, as with
fingerprints.

## Later: step two, within a resource

Per node of a template variant's document, per style key and preset of a theme, per code of a code
list, per attribute value. Images and fonts stay whole. It needs rules for moved and reordered
children in a slot, a node removed on one side while a child was edited on the other, and the data
model. Step two only turns some step-one conflicts into clean merges; the operations do not change,
though a resolution may gain an `edited` value (the author combined both through the normal
operations).

## Consequences

- The operations ship as `x-experimental` (an alpha) and become stable once the step-one rules have
  been used in practice.
- `CatalogReleaseDto.behind` leaves out the releases a draft has merged.
- ADR 0001's hotfix releases are not needed: a fix made in another installation comes back as a
  release that is merged.

## Open questions

- Should a release record which releases its draft merged (for provenance), or only its base?
- Should merging another draft, not only a release, be offered?
