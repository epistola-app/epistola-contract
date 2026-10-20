# ADR 0005: Merging catalog versions

- **Status:** Proposed — not part of contract 2.0.0; a later alpha
- **Date:** 2026-10-16
- **Scope:** API contract (`contracts/api`) and portable catalog contract (`contracts/catalog`)
- **Related:** [ADR 0002](0002-intermediate-releases.md) (drafts and releases; drafts are independent
  copies), [ADR 0004](0004-resource-identity-within-a-catalog.md) (resource identity)

## Context

In contract 2.0.0 a draft is an independent copy of the release it started from (ADR 0002). Nothing
is merged. A draft is **behind** releases made from other drafts that are numbered below the number
it would be released as (above its base, or released after it started), and releasing it requires
acknowledging them. Porting a change released in `1.5.0` into another draft ("Redesign 2027") is the
author's work: they apply it again by hand.

That is safe but laborious, and an author porting by hand can get it subtly wrong. A merge would
bring a release's changes into a draft for them. This ADR records how that should work, so that the
2.0.0 model does not make it harder later. It is not implemented, and nothing here is in 2.0.0.

## Decision (proposed)

### Pull, not push

A draft **pulls** a release in when its author asks: "update Redesign 2027 from `1.5.0`". Releasing
a version never changes another draft; that is 2.0.0 behaviour and stays.

ADR 0002 considered pushing every release into higher drafts automatically, and rejected it. Pull is
preferred because:

- it keeps 2.0.0's guarantee that a release changes no other draft;
- an author is never surprised by conflicts in a draft they are working on, caused by someone else's
  release;
- it fits the existing `behind` list: each entry gets an "update from this release" action.

After a successful update from a release above the draft's base, the draft's base becomes that
release, and the release drops off `behind`. An update from a release below the base (a fix to an
older line) leaves the base as it is and only drops that release off `behind`.

The same operation can bring in changes from any other version, not only releases, which covers
"take the finished templates from Redesign 2027 into Spring fixes":
`POST …/releases/{release}/merge` `{from, resources?}`.

### A three-way merge, first per resource

The merge compares three versions: the draft's **base**, the **incoming** version, and the **draft**.

**Step one: whole resources.** For each resource (identified by type and key, ADR 0004):

| Base → incoming | Base → draft | Result |
| --- | --- | --- |
| unchanged | anything | keep the draft's |
| changed | unchanged | take the incoming one |
| changed | changed identically | keep (no conflict) |
| changed | changed differently | **conflict** |
| added | absent | add it |
| added | added differently with the same key | **conflict** |
| removed | unchanged | remove it |
| removed | changed | **conflict** |

"Changed" and "identical" mean canonical equality, using the catalog module's canonicalizer, so key
order, number formatting and the like never cause a conflict. Declared catalog dependencies merge as
one more resource.

**Step two, later: within a resource.** Per node of a template variant's document, per style key and
preset of a theme, per code of a code list, per attribute value. Images and fonts stay whole. It
needs rules for moved and reordered children in a slot, a node removed on one side while a child was
edited on the other, and the data model. Step two only turns some step-one conflicts into clean
merges; the API does not change.

### Conflicts are shown, not guessed

- A conflict blocks releasing the draft, not editing it.
- The API returns, for each conflict, the **base, incoming and draft** content of the resource (step
  one) or node (step two), so an author never resolves without seeing what differs.
- A conflict names its place structurally (type, key, and in step two variant and node id), not as
  free text, so clients can link to it.
- Resolutions: **keep** the draft's, **take** the incoming, or **edited** (the author combined both
  through the normal operations). A resolution records who made it and when, for the release notes.

### Where it lives

The merge is implemented once, in the catalog module, with published golden fixtures (base, incoming,
draft → result or conflicts), so every installation merges the same way, as with fingerprints.

## Consequences

- 2.0.0 needs nothing from this ADR. Its `behind` list and release acknowledgement are what the merge
  later builds on.
- When implemented, it ships as `x-experimental` operations first (an alpha), and becomes stable
  only after the step-one rules have been used in practice.

## Open questions

- Should "update from a release" be allowed while a draft has unresolved conflicts from an earlier
  update, or must those be resolved first?
- Is `edited` trusted as is, or must the resource have changed since the conflict appeared?
- Does a release record which releases it was updated from (for provenance), or only its base?
