# ADR 0003: Catalog identity, registries and mirrors

- **Status:** Accepted
- **Date:** 2026-10-16
- **Scope:** API contract (`contracts/api`) and portable catalog contract (`contracts/catalog`, wire
  v8), contract 2.0
- **Related:** [ADR 0001](0001-hotfixing-a-released-catalog.md) (hotfix releases),
  [ADR 0002](0002-intermediate-releases.md) (catalog versions)

## Context

Contract 2.0 identifies a catalog by more than its key: two publishers can each publish a catalog
called `letters`. The first attempt made a **namespace** one string in three forms: a name on the
default exchange (`acme-corp`), a name on another exchange (`exchange.example.org/acme-corp`), or a
bare domain (`acme.com`). In a URL the `/` had to be written as `~`
(`/catalogs/exchange.example.org~acme-corp/letters`), and the number of parts depended on the form.

The expected landscape:

- A **central exchange** keeps operating (`exchange.epistola.app`).
- **Municipalities will run registries of their own** and publish their catalogs there.
- A Suite installation uses catalogs from **both**.
- A municipality's registry **may mirror the central exchange**, so its installations fetch central
  catalogs locally.

Three questions follow:

1. **What is a catalog's name**, so that it means the same catalog in every installation?
2. **Where does an installation fetch a release**, including through a mirror?
3. **What name do existing catalogs get** when an installation upgrades to 2.0? They were never
   published. Putting them all under one namespace on the central exchange does not work: that
   namespace belongs to nobody there, and every installation would migrate into the same one, so two
   municipalities would each have `…/legacy/letters` with different content.

## Options

### A. The registry a catalog is published on is part of its name (Docker)

`registry.gemeente-x.nl/huisstijl/branding`, like `ghcr.io/acme/app`. The name says where the
catalog is published, and that registry grants the namespace, so no one has to prove ownership of a
name to anyone else. A mirror is installation configuration ("fetch `exchange.epistola.app/*`
through `registry.gemeente-x.nl` first"); the name does not change, and the content fingerprint
proves the mirrored copy is the same.

A catalog is published in one place. Publishing it somewhere else is a different catalog, and moving
it renames it.

### B. The first part names who grants the name, not where it is (Go modules)

`gemeente-x.nl/huisstijl/branding` keeps its name whichever registry serves it, as
`github.com/acme/letters` does on any Go proxy. A catalog can be published as an original on several
registries. Every registry that serves a name it did not grant has to verify the claim (DNS record or
well-known file), and installations need a search order across registries plus a location hint per
dependency.

### C. Namespace and key only, registry from configuration (npm scopes)

`acme-corp/letters`, with each installation mapping a namespace to a registry. Short names, but two
registries can each hand out `acme-corp`, and an installation can then reach only one of them.
Migrated local namespaces can collide with registry ones.

### D. A generated ID, or a publisher key, as the identity

An opaque ID per catalog survives renames but is unreadable and can be claimed by anyone unless it is
signed. A publisher's public key as the identity (Bluesky, Debian) verifies strongly but makes key
management a requirement for every municipality, and a lost key loses the identity.

## Decision

Adopt **A**, with mirrors.

B only adds publishing one catalog as an original in several places. The expected landscape does
not need that: a catalog that should be available in more places is mirrored, not republished. B's
cost (verification of names by every registry, search order, location hints) buys nothing here. C
breaks as soon as there are several registries, which is the expected case. D is heavier than the
problem.

### Identity: registry, namespace, key

A catalog is identified by three parts, always together:

- The **registry**: the DNS name, in normal order, of the registry the catalog is published on and
  that grants its namespace (`exchange.epistola.app`, `registry.gemeente-x.nl`).
- The **namespace**: a slug within the registry (`acme-corp`). The same namespace in two registries
  is two namespaces. There are no bare domain namespaces: a municipality publishes under a namespace
  on its registry.
- The **catalog key**: a slug within the namespace (`letters`).

In a URL each part is one path segment: `/catalogs/{registry}/{namespace}/{catalogKey}/…`. `~` as the
registry means the installation's **default registry**, which is configuration. The default can also
be written by its name; both address the same catalog. In a body the three parts are separate
members. A request may leave the registry out; a response always names it in full, never `~`. The
API description's "Catalog identity" section has the rules for omitted members.

### Mirrors are installation configuration

An installation configures, outside this contract:

- its **default registry**, which `~` stands for;
- optionally, **mirrors per registry**: "fetch `exchange.epistola.app` catalogs through
  `registry.gemeente-x.nl` first, then from `exchange.epistola.app` itself".

A mirror serves copies and grants nothing. A release is immutable and carries a fingerprint that
covers its content and its identity (the V5 canonical fingerprint), so a copy from a mirror is
checked against the fingerprint the release was published with.

### A fingerprint names its algorithm

Wire v8 writes every fingerprint as the algorithm that computed it, a colon and the hex:
`v5:58f4…`. Before, a fingerprint was bare hex and the archive's wire version implied the
algorithm. That breaks for a fingerprint copied into another archive: a dependency's
`releaseFingerprint` stays when the current algorithm moves on, and a later reader could not tell
which algorithm to check it with. Trying every algorithm is not a fix either: an older one covers
less (V4 covers no identity and no dependencies), so a copy differing only there would pass. A
verifier computes exactly the algorithm the value names, and a release must name the current one.

The API reports the same value. A fingerprint recorded before contract 2.0 may still be bare hex in
an API response, meaning its algorithm was not recorded.

### Dependencies name the release and its fingerprint

A catalog declares each catalog it depends on once (wire v8 `catalogDependencies`):

```json
{
  "registry": "registry.gemeente-x.nl", "namespace": "huisstijl", "catalogKey": "branding",
  "version": "^1.4.0",
  "releaseVersion": "1.4.2",
  "releaseFingerprint": "v5:<fingerprint of branding 1.4.2>"
}
```

- `version`: the selector. `releaseVersion`: the release this release was cut with.
- `releaseFingerprint`: that release's fingerprint, copied from its own `release.fingerprint`, so an
  installation can check a copy it fetched through any mirror. Null exactly when `releaseVersion` is, which is only for archives migrated from
  v7.

The name says where to fetch; there is no location field.

### Installing resolves dependencies

The behaviour below is the direction for installations. The contract fixes the names and the
fingerprint it relies on; Suite may not implement this yet.

Installing (subscribing to) a release of a catalog also installs what it depends on, recursively, as
subscribed catalogs:

1. For each declared dependency, a release already installed in the tenant that satisfies the
   selector is used.
2. Otherwise the installation fetches the declared `releaseVersion`, not the highest match: it is the
   release the publisher tested with, so the result is reproducible. Newer releases arrive through
   an upgrade. It fetches from the dependency's registry, through that registry's mirrors when
   configured, and checks the copy against `releaseFingerprint`.
3. The install is all or nothing. A release that cannot be fetched stops it, with the list of what
   is missing.

Two installed catalogs that need different releases of the same catalog get both: a catalog's
releases already stay installed side by side, and each catalog renders with the release its own
declaration resolves to. There is no single winning version and therefore no conflict to resolve.

Upgrading a subscribed catalog follows the same steps for the new release's dependencies.

### Deploying uses dependencies implicitly; deploying them too is a choice

A release renders with the dependency releases it was cut with, direct and transitive: it is pinned
to them, and renders them from the tenant's copy whatever the environment serves. So deploying a
release (`deployRelease`) makes the environment use its dependency releases **implicitly**: they are
not deployed, and nothing else the environment serves changes. Deploying one of them as well, so
that the environment also serves that catalog's own templates, is an explicit deployment of its own,
or the flag `includeDependencies`, default false:

- **Always**, a dependency release must be available to render from. One the tenant does not have
  is installed first, as described above. When one cannot be fetched or does not match its
  fingerprint (`UNAVAILABLE`), the deployment is refused with `release-dependencies-missing` (409), whose `missingDependencies`
  lists those. Nothing is deployed.
- **With `includeDependencies`**, the dependency releases the environment does not serve are also
  deployed explicitly, in the same transaction. It is all or nothing.

Implicit is the default because deploying a dependency release explicitly can change what other
catalogs in that environment render, when they select the same catalog with a range: the caller
opts into that. `NOT_DEPLOYED` and `NOT_INSTALLED` remain in the reason enum for clients written
against the earlier draft; a deployment is no longer refused for them.

### Migrating existing catalogs

An installation upgrading to 2.0 gives its existing catalogs a registry and one namespace per tenant
(the tenant's slug by default). The contract's migrator does not choose: it takes both from the
importer (`CatalogMigrationOptions`) and refuses an archive from before v8 without them.

The registry should be the one the municipality will publish from:

- If it knows its registry's host (`registry.gemeente-x.nl`), it migrates straight to that, and
  publishing later changes nothing.
- Otherwise it uses the installation's own host. That is correct as long as the installation serves
  its own releases. Publishing from a different registry later is a move: the catalog gets a new
  identity, and what names it has to follow.

## Consequences

- A catalog's name says who published it and where. Mirrors make it available elsewhere without
  renaming it.
- No registry has to verify names it did not grant, and installations need no search order: the name
  says where, the mirror configuration says through what.
- Installations need configuration they may not have yet: the default registry and mirrors.
- Moving a catalog to another registry renames it. A "move" operation that rewrites references and
  redirects the old name is needed eventually, not in 2.0.
- A registry must be a DNS name with a dot. A local test setup uses something like
  `exchange.localhost`, not `localhost`.
- **Wire v8** (unreleased, changed in place): `catalog.registry` and `catalog.namespace`; the same two
  members on catalog dependencies, resource dependencies and content references; `releaseFingerprint`
  on catalog dependencies; the V5 fingerprint covers all of them. Every fingerprint names its
  algorithm (`v5:<hex>`).
- Deploying can fail on dependencies it did not fail on before: callers either deploy dependencies
  first or pass `includeDependencies=true`.

## Hotfix releases

A hotfix (ADR 0001) can be cut by any installation, for any catalog, and is never published. A
release whose dependencies resolve to a hotfix cannot be published either: no registry serves that
hotfix, so no other installation could install the dependency. ADR 0001 does not say this yet.

## Later

- **Signed releases.** A registry or publisher publishes its keys; a release carries a detached
  signature over its fingerprint, so an installation can verify it without trusting the mirror it
  came from. Additive: an extra archive file, no change to `catalog.json`. Its own ADR.
- **Publishing one catalog as an original in several registries** (option B), if the landscape ever
  needs it.
- **Bundling dependency releases into an export**, for installations without network access to any
  registry.
- **Moving a catalog** to another registry.

## Open questions

- Undeploying a release that another release the environment serves renders with. Refusing it, as
  deploying refuses, would be symmetrical; this ADR does not decide it yet.
