# ADR 0001: Hotfixing a catalog release that production runs

- **Status:** Proposed
- **Date:** 2026-10-15
- **Scope:** API contract (`contracts/api`) and catalog contract (`contracts/catalog`), contract 2.0

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
copy already holds weeks of unreleased work towards `2.0.0`. That work must not reach production. The
team needs to:

- take the content of `1.4.0`;
- change one small thing;
- release that as `1.4.1` and take it through acceptance to production;
- not lose, block or contaminate the `2.0.0` work in test;
- end up with the fix in `2.0.0` too, so the next release does not reintroduce the error.

Going through test and acceptance is not the problem; it is desirable. The problem is that
**the working copy is the only place to author, and it is already ahead of production**.

The last point is the hard one in every option: whatever path the fix takes to production, it has to
end up in the working copy as well.

## Options

### A. Hotfix working copy in the authoring installation

The authoring installation can open a second, short-lived working copy for a catalog, the **hotfix
working copy**:

- It always starts from an existing release (the **base**, e.g. `1.4.0`), never from the main working
  copy.
- A catalog has at most one hotfix working copy at a time.
- It can only produce a **patch** release on the base's line (`1.4.0` → `1.4.1`), never a minor or
  major one.
- It is discarded once released. There is no long-lived branch and no history to merge.

When the hotfix is released, its changes are **carried forward** into the main working copy by a
three-way merge per resource, between base (`1.4.0`), hotfix (`1.4.1`) and the working copy:

- The diff from base to hotfix is the fix. Only that diff is applied, so the `2.0.0` work stays intact.
- The merge works below the resource level:
  - template, variant and stencil documents per node (nodes have stable ids);
  - themes per style key;
  - code lists per entry;
  - the data contract per schema property.

  A spelling fix changes one text node, so it carries forward automatically even into a template that
  was redesigned around it, as long as `2.0.0` did not edit that same node.
- Each resource the hotfix touched ends up in one of three states:
  - **carried forward**: it becomes `modified` in the working copy and is marked ready with the rest;
  - **no longer relevant**: the working copy already removed the node or resource; reported, nothing to do;
  - **conflict**: both sides changed the same node, key, entry or property. The author resolves it by
    keeping their version, taking the hotfix, or editing by hand.
- **The next release is refused while a conflict is open**, the same way held-back stencil instances
  block a release today. A fix cannot silently disappear.

The merge is a rule that every installation must apply identically. It belongs in the catalog
module, implemented once and exported to Kotlin and TypeScript, with golden fixtures per merge case,
next to canonicalization and fingerprints.

What it costs:

- **API:**
  - start, inspect and discard a hotfix
    (`POST …/catalogs/{namespace}/{catalogKey}/hotfix` with `{ fromRelease }`);
  - the working-copy operations need to say which copy they address. One optional parameter
    (`workingCopy=hotfix`) is cheaper than duplicating every path;
  - `getCatalogChanges` lists the open carry-forwards and their state, and `releasable` requires none
    to be in conflict;
  - an operation to resolve a conflict.
- **Version rule:** releases are currently required to be strictly increasing. That becomes: unique,
  and higher than the base within its patch line. `1.4.1` is valid even when `1.5.0` already exists.
- **Catalog module:** the three-way merge, per resource type.

### B. Stash the working copy

Set the working copy aside, reset it to `1.4.0`, fix, release, then restore the stash.

This is option A with worse properties. Nobody can work on `2.0.0` while the stash is out, and the
restored working copy still lacks the fix, so the carry-forward problem is unsolved. **Rejected.**

### C. Cherry-pick a patch release

Make the fix in the main working copy, then release only selected resources on top of `1.4.0`.

This avoids a second working copy, but the selection is per resource. It fails in the common case,
where the template with the error has also changed for `2.0.0`: half a template cannot be released.
Making it finer-grained means selecting individual node changes from a mixed working copy, which is
harder than option A's merge and less predictable for the author. **Rejected.**

### D. Author the fix in production and publish it from there

Production edits the catalog and publishes `1.4.1` itself.

This gives the namespace two authors. The authoring installation does not know `1.4.1` exists, and its
next release can collide with or silently revert it. **Rejected.**

### E. Production override: edit in production, never publish, replaced by the next release

Production gets a local, **unpublishable** override of the release it serves:

- An operator edits the deployed content in production directly. The result is a local release that
  production serves, but that is never exported, published or installed elsewhere.
- When the next real release is installed and deployed, it **replaces** the override.

The override can be given a version that makes the replacement automatic. For example, as a
pre-release of the next patch, `1.4.1-override.1`:

- it sorts above `1.4.0`, so an environment serving both renders the override;
- it sorts below `1.4.1`, so the real fix supersedes it the moment it is deployed.

Requests pinned to exactly `1.4.0` keep rendering `1.4.0`.

What it gets right:

- It is the fastest path in an emergency. There is no detour through test and acceptance, and no
  second working copy in the authoring installation.
- Nothing in the authoring installation changes.

What it costs:

- **The fix still has to reach the working copy.** "Simply override" means the next release replaces
  the override. If the team forgets to make the same fix in test, that release reintroduces the error
  in production, and nothing notices. This is the carry-forward problem again, with the work
  done by hand and nothing checking it.
- **Production is no longer reproducible from published releases.** What production renders exists
  only there. Acceptance never saw it, and exporting or restoring production loses or must
  special-case it.
- **Production needs authoring permissions and an authoring UI** for subscribed catalogs, which are
  read-only by design today. Edit rights in production are a risk in their own right.
- **Dependents do not see it.** Catalogs that depend on `1.4.0` keep rendering `1.4.0`.
- It contradicts the rule that documents are always rendered from a published release, unless an
  override is formally a release of its own (as above), with an audit trail.

Two additions would make it safe enough as a **break-glass** path, but not as the normal one:

1. **A guard on replacement:** installing or deploying a release over an override checks, per node the
   override changed, whether the incoming release contains that change. If not, the operator must
   confirm that the fix is being dropped.
2. **A way back:** the override's diff against its base can be exported and imported into the
   authoring installation as a hotfix (option A), so the same three-way merge brings it into the
   working copy.

With both, option E becomes a fast front end to option A, rather than an alternative to it.

## Decision (proposed)

1. **Adopt option A as the standard hotfix path:** a single, short-lived hotfix working copy from a
   release, producing a patch release, with node-level carry-forward. A conflict blocks the next
   release.
2. **Do not adopt option E for now.** Revisit it as a break-glass path only together with the
   replacement guard and the way back described above, because "simply override" on its own
   eventually reintroduces the error it fixed.
3. **Reject options B, C and D.**

This mirrors how this repository ships itself: one trunk, plus short-lived `release/X.Y` branches for
hotfixes.

## Consequences

- The catalog module gains a three-way merge per resource type, the largest piece of work in this
  decision. It needs golden fixtures for at least:
  - an untouched node;
  - a node moved in the working copy;
  - a node removed in the working copy;
  - a node changed on both sides (conflict);
  - a resource added or removed by the hotfix.
- The release ordering rule changes from "strictly increasing" to "unique, and on the base's patch
  line".
- The API gains the hotfix lifecycle, a `workingCopy` selector on working-copy operations, and
  carry-forward state in `getCatalogChanges`. These are contract changes, so every client and the
  conformance suite follow.
- Environments that serve several releases need nothing new: deploying `1.4.1` next to `1.4.0` moves
  unpinned requests to it, and undeploying it moves them back.

## Open questions

- Should starting a hotfix be allowed from any release, or only from one that an environment serves?
- Does a hotfix of a catalog that other catalogs depend on also need patch releases of those
  dependents? Or is that left to their own version selectors once ranges are supported?
- Who may start and release a hotfix: the same permission as releasing (`CATALOG_RELEASE`), or a
  separate one?
