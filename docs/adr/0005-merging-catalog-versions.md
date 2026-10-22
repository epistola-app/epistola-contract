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
**merge base** (the closest release the draft and the merged release both descend from, see "The
merge base travels with the release"; amended 2026-10-21, it said the draft's base, which it need
not be), **theirs** (the release merged in) and **ours** (the draft). Each state is the
resource's canonical fingerprint (`CatalogCanonicalizer.completePerResourceFingerprints`, the
per-resource form of V5, the current catalog algorithm; amended twice on 2026-10-21: it named
`perResourceFingerprints`, the V1 form, then `currentPerResourceFingerprints`, the V3 form, both of
which leave out what wire v8 added to a resource, a template's `description`, a variant's
`metadata` and a data example's `key`, so a change to only those looked like no change), so key
order, number formatting and the like never make a difference. A resource absent from a state is not in it: added on a side that has it and the base
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

- **A conflict is resolved per resource**, to `ours` (the draft's stays), `theirs` (the release's
  is taken) or **merged content** (amended 2026-10-22): the entry as the draft is to hold it,
  combined from the sides by a person, an assistant or a tool. Every conflict needs one resolution,
  and a resolution may only name a conflict. The server does not combine sides itself; that is
  step two below.
- **Merged content is read from the sides and goes back whole** (2026-10-22).
  `getCatalogDraftMergeSides` returns one listed entry's content in the merge base, the draft and the
  release, each in the portable catalog format (a resource as a resource detail carries it; a
  setting's or a dependency's value), null when a side does not have it, with the preview's `token`.
  The merged result goes back in `mergeIntoCatalogDraft`'s `mergedContent` (`type/key` to that
  content), beside `resolutions`, which stays a map of plain `ours`/`theirs` strings: a `oneOf` of
  a string and an object in `resolutions` generates no usable type in the Kotlin and Jakarta clients
  (both reduced it to an object with only the content), and would have changed the type of a member
  existing clients use. The content is validated as an import validates it (the portable format and
  the server's rules; 400 `validation-error`) and the merge's result is checked as every merge's is
  (`merge-refused`). It lands in the draft as given and is not marked ready: it is reviewed like
  any edit before the draft is released. The response lists such entries in `merged`.
- **Taken resources are marked ready**: they are released content.
- **A release's catalog settings are merged like resources** (amended 2026-10-21; it said the
  draft's settings stay its own): its default theme and its presets, one entry per setting, of type
  `catalog` and keyed by the setting (`catalog/defaultTheme`, `catalog/presets`). Each is classified
  by the table above, from a fingerprint of its value in a fixed shape and order, and a conflict on
  one is resolved like a resource's. Its keys are not resource keys, so `resourceKey` does not
  follow the resource-key rule. The catalog's name, description and other catalog-wide details are
  not a release's, and are not merged.
- **Each catalog a release uses is its own entry** (amended 2026-10-22; the declarations were one
  entry, `catalog/dependencies`, so a release adding one dependency and a draft re-pinning another
  conflicted): type `catalogDependency`, keyed by the catalog's key (`catalogDependency/shared`) and
  valued by its declaration, the release it pins (with its fingerprint) or the draft it names.
  Registry and namespace are part of the declaration, not of the key: a catalog key is unique in a
  tenant, and a full name does not fit the 50 characters of `resourceKey`. Adding, removing or
  re-pinning a dependency is a change of its own, and only both sides changing the same catalog's
  declaration differently is a conflict. A dependency nothing refers to any more after the merge is
  kept, as editing keeps one; one the release no longer declares, and the draft did not change, is
  removed (`removed`).
- **Every reference in the result resolves** (amended 2026-10-22; it said only that a removal the
  draft still depends on is refused): when the result of the merge would hold a reference that does
  not resolve, the merge is refused and changes nothing (`merge-refused`). Within the catalog, the
  resource referred to must be in the result: a release that removes a resource something the draft
  keeps still uses, or a template taken from the release that uses a stencil the result does not
  have, is refused. Into another
  catalog, the reference resolves only through the result's declaration of that catalog, against
  the release it pins (never that catalog's newest release) or the draft it names; a catalog the
  result does not declare resolves nothing. The catalog's default theme is checked the same way.
  Only what the merge breaks is refused: a reference that did not resolve in the draft before is the
  draft's own, for releasing to refuse. The problem's `detail` names what is missing and what to
  choose ("the default theme 'brand' of 'shared' needs 'shared' as a dependency: take v1.0.1's
  dependency on 'shared' too, or keep your default theme").
- **The preview says what goes together** (2026-10-22): each entry lists the other entries of the
  preview its value refers to (`needs`: the theme or stencil a template uses, the dependency entry a
  reference into another catalog resolves through), so the author chooses them with each other in
  view and avoids a refused merge.
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
| `previewCatalogDraftMerge` | `GET …/releases/{release}/merge-preview?version=` | `draft`, `base` (the merge base), `release`, `alreadyMerged`, `resources` (`resourceType`, `resourceKey`, `name`, `outcome`, `draftChange`, `releaseChange`, `needs`), `unchanged`, `token`, `missingBase` |
| `getCatalogDraftMergeSides` | `GET …/releases/{release}/merge-sides?version=&entry=` | `entry`, `mergeBase`, `base`, `draft`, `release` (each the entry's content in the portable catalog format, or null), `token` |
| `mergeIntoCatalogDraft` | `POST …/releases/{release}/merge` `{release, resolutions, mergedContent, expectedToken}` | `resolutions` maps `type/key` (a setting as `catalog/<setting>`, a catalog used as `catalogDependency/<catalogKey>`) to `ours` or `theirs`, `mergedContent` maps `type/key` to merged content; returns `release`, `taken`, `removed`, `kept`, `merged`, each as `type/key` |

The release to merge is the query parameter `version` because `{release}` in the path already names
the draft; in the bodies it is `release`.

Refusals, as RFC 9457 problems:

- 400 `validation-error`: merged content is not valid for its entry (each failure's `field` names
  `mergedContent.<type/key>` and the path within it), or a conflict is resolved twice;
- 409 `merge-conflicts-unresolved`: conflicts without a resolution, listed in `unresolvedConflicts`;
- 409 `merge-refused`: the result would hold a reference that does not resolve, within the catalog
  (a resource the release removes that the draft still uses included) or into another catalog
  through a declared dependency, checked against the release it pins (the default theme included);
  the `detail` names what is missing and what to choose;
- 409 `merge-preview-outdated`: the draft or the release changed since the preview whose `token`
  the request sent as `expectedToken` (2026-10-21). The token is opaque and identifies the draft,
  the release and the merge base the preview was computed from, so resolutions chosen from a
  preview are never applied to states the author did not see;
- 409 `merge-base-missing` (`version`, `fingerprint`): this installation does not have the merge
  base (see "The merge base travels with the release");
- 422 `merge-not-applicable`: the draft already contains the release (its base, or merged before),
  or a resolution or merged content names an entry that is not a conflict;
- 409 `catalog-read-only` (`readOnlyReason`): the catalog cannot be changed in this installation;
- 409 `conflict`: the path names a released release, not a draft; 404: no such draft or release.

### Where it lives

Step one is the table above over fingerprints the catalog module already computes. When step two
comes, the merge is implemented once, in the catalog module, with published golden fixtures (base,
theirs, ours to result or conflicts), so every installation merges the same way, as with
fingerprints.

### The merge base travels with the release (2026-10-21)

The base of a three-way merge is the **merge base**: the closest release both the draft and the
merged release descend from. Before this, an archive did not say what a release was made from:
production that got `1.1.0` and now receives a fix `1.0.1` could not tell that `1.0.1` was made
from `1.0.0`.

So every release names the release it was made from, its draft's base, in wire v8:
`release.base`, that release's number (`version`) and its catalog `fingerprint`, and nothing more.
A receiver follows these links through the releases it knows to the closest release both lines
share. The base is not covered by any fingerprint, and a receiver keeps it as received.

- **The merge base must be installed.** The merge compares against that release's own resources,
  so the receiving installation needs it, and checks by its fingerprint that the release it has
  under that number is the same release.
- **When it is not installed**, the merge is refused and says which release to get first: 409
  `merge-base-missing`, whose `version` and `fingerprint` name that release. The preview does not
  fail: it answers with `missingBase` (`{version, fingerprint}`) naming the same release and
  classifies nothing, so a client can offer to install it (`importCatalog`,
  `installCatalogRelease`) and preview again.
- **When no common release is known** (archives that name no base, from before `release.base`, or
  from a producer that did not know it), the merge has no base: every resource that differs is a
  choice for the author, never a guess from the release numbers.

## Later: step two, within a resource

Per node of a template variant's document, per style key and preset of a theme, per code of a code
list, per attribute value. Images and fonts stay whole. It needs rules for moved and reordered
children in a slot, a node removed on one side while a child was edited on the other, and the data
model. Step two only turns some step-one conflicts into clean merges; the operations do not change.
Until then, merged content (`mergedContent`) is how both sides of a conflict are combined.

## Consequences

- The operations ship as `x-experimental` (an alpha) and become stable once the step-one rules have
  been used in practice.
- `CatalogReleaseDto.behind` leaves out the releases a draft has merged.
- ADR 0001's hotfix releases are not needed: a fix made in another installation comes back as a
  release that is merged.

## Open questions

- Should a release record which releases its draft merged (for provenance), or only its base? (It
  now records its base, `release.base`, for merging; provenance of merges stays open.)
- Should merging another draft, not only a release, be offered?
