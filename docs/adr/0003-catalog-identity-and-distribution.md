# ADR 0003: Catalog identity, and where releases come from

- **Status:** Proposed
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

Three questions came up that this did not answer:

1. **Existing catalogs need a namespace.** A Suite installation upgrading to 2.0 holds catalogs that
   were never published. Putting them all under one namespace on the exchange does not work: that
   namespace belongs to nobody there, and every installation would migrate into the same one, so
   two municipalities would each have `…/legacy/letters` with different content.
2. **A catalog may be published in more than one place**: the public exchange, a municipal
   exchange, an internal mirror. If the place is part of the catalog's identity, each place makes it
   a different catalog.
3. **Installing a catalog installs what it depends on.** Each dependency has to be found somewhere,
   and verified once found.

## Options

### A. The place of publication is the identity

The registry a catalog is published on is part of its name. Publishing it on a second exchange
creates a second catalog with the same content; dependents pick one. Moving a catalog, including
publishing a migrated catalog for the first time, changes its identity, so every reference,
deployment and generation request naming it has to follow.

**Rejected:** a catalog's name would not survive publishing or mirroring.

### B. The identity names who grants the name; places are configuration

A catalog's identity names the **authority** that grants it: the party that guarantees no one else
publishes under that name. Where releases are fetched from is separate, and may be several places.
This is how Go modules work: `github.com/acme/letters` keeps its name whether it is fetched from
GitHub, `proxy.golang.org` or a company mirror.

### C. Global namespaces without an authority

Namespaces are unique on their own, granted by one central service. That makes the central service
a requirement for every installation, including ones that never publish.

**Rejected.**

## Decision (proposed)

Adopt **B**.

### Identity: authority, namespace, key

A catalog is identified by three parts, always together:

- The **authority**: a DNS name, in normal order (`exchange.epistola.app`, `gemeente-x.nl`). It
  grants namespaces, so control of the name is what makes them unique. An exchange is an authority
  for the namespaces it hands out to its accounts. An organisation's own domain is an authority for
  itself. A Suite installation is one too, by its host name.
- The **namespace**: a slug within the authority (`acme-corp`). The same namespace under two
  authorities is two namespaces. There are no bare domain namespaces: `acme.com` becomes authority
  `acme.com` plus a namespace such as `acme`.
- The **catalog key**: a slug within the namespace (`letters`).

In a URL each part is one path segment: `/catalogs/{authority}/{namespace}/{catalogKey}/…`. `~` as
the authority means the installation's **default authority**, which is configuration. The default
can also be written by its name; both address the same catalog. In a body the three parts are
separate members. A request may leave the authority out; a response always names it in full, never
`~`. The API description's "Catalog identity" section has the rules for omitted members.

The identity does not change when a catalog is published, mirrored or moved to another exchange.
Publishing under a different authority or namespace is a **fork**: a new catalog, on purpose.

### Registries: where releases come from

A **registry** is a place that serves releases: an exchange, a mirror, or the authority itself. A
registry may serve catalogs of any authority. An installation configures an ordered list of
registries to fetch from. That list is configuration, not part of the contract or of a catalog's
identity.

A release is immutable and carries a fingerprint that covers its content and its identity (the V5
canonical fingerprint), so a release fetched from any registry can be checked.

### Dependencies name the identity, and point to a location

A catalog declares each catalog it depends on once (wire v8 `catalogDependencies`):

```json
{
  "authority": "gemeente-x.nl", "namespace": "huisstijl", "catalogKey": "branding",
  "version": "^1.4.0",
  "releaseVersion": "1.4.2",
  "releaseFingerprint": "<V5 fingerprint of branding 1.4.2>",
  "location": "https://exchange.example.org"
}
```

- `version`: the selector. `releaseVersion`: the release this release was cut with.
- `releaseFingerprint` (new): that release's fingerprint, so an installation can check a copy it
  fetched from any registry. Null only for archives migrated from v7.
- `location` (new, optional): a registry the publisher fetched the dependency release from. It is
  a hint, so registries and catalogs can point to each other. It is not identity and is not covered
  by the fingerprint: where a release was found does not change what it renders.

### Installing resolves dependencies

Installing (subscribing to) a release of a catalog also installs what it depends on, recursively,
as subscribed catalogs:

1. For each declared dependency, a release already installed in the tenant that satisfies the
   selector is used.
2. Otherwise the installation fetches the declared `releaseVersion`, not the highest match: it is
   the release the publisher tested with, so the result is reproducible. Newer releases arrive
   through an upgrade. It looks in its configured registries, in order, then at the declaration's
   `location` if its configuration allows that. The release is checked against
   `releaseFingerprint`.
3. The install is all or nothing. A release that no allowed registry serves stops it, with the list
   of what is missing.

Two installed catalogs that need different releases of the same catalog get both: a catalog's
releases already stay installed side by side, and each catalog renders with the release its own
declaration resolves to. There is no single winning version and therefore no conflict to resolve.

Upgrading a subscribed catalog (`upgradeCatalog`) follows the same steps for the new release's
dependencies, and its preview lists the releases it would install. A subscription no longer has one
"source" address: the identity says what it is, the registries say where to get it.

### Deploying carries dependencies along

A release renders only if the releases its dependencies resolve to are served in the same
environment. Deploying a release also deploys the dependency releases it resolves to that the
environment does not serve yet, and the response says which.

### Migrating existing catalogs

An installation upgrading to 2.0 puts its existing catalogs under its **own authority**, its host
name (`epistola.gemeente-x.nl`), with one namespace per tenant, the tenant's slug by default. The
contract's migrator does not choose: it takes the authority and namespace from the importer
(`CatalogMigrationOptions`) and refuses an archive from before v8 without them.

This is unique without the exchange (the host name already belongs to its owner) and truthful (the
catalogs were written in that installation). Publishing them later keeps their identity, so nothing
that names them has to change.

An installation therefore has two configured authorities: its **own**, for catalogs it authors, and
the **default** that `~` stands for. They may be the same.

### Verifying an authority

Two levels, of which only the first is needed for 2.0:

1. **The registry checks once, when an account claims a name.** Before an exchange accepts releases
   under authority `gemeente-x.nl`, the account proves it controls the domain: a DNS record
   (`_epistola.gemeente-x.nl TXT "epistola-verification=<code>"`) or a file at
   `https://gemeente-x.nl/.well-known/epistola`, as Maven Central and ACME do. A mirror repeats the
   check or trusts the registry it mirrors. Installations trust their registries. An exchange is
   the authority for the names it grants and needs no check for those.
2. **Later: releases are signed.** The authority publishes its keys, and the registries that serve
   its catalogs, in a well-known document on its own domain. A release carries a detached signature
   over its fingerprint. An installation checks the signature itself, whichever registry the release
   came from, so registries become untrusted carriers. A Suite installation controls its host and
   can publish that document itself; one not reachable from the internet registers its key with the
   exchange account instead.

Signing is additive: an extra file in the archive and a well-known document, no change to
`catalog.json`. It gets its own ADR.

### Hotfix releases

A hotfix (ADR 0001) can be cut by any installation, for any catalog, and is never published. It is
local to that installation and carries no guarantee from the catalog's authority. A release whose
dependencies resolve to a hotfix cannot be published either: no registry serves that hotfix, so no
other installation could install the dependency. ADR 0001 does not say this yet.

## Contract changes

- **API:** the identity members and path segment are called `authority`, `namespace` and
  `catalogKey`. `~` is the default authority. `upgradeCatalog` and `previewCatalogUpgrade` describe
  dependency installation and lose the single "source". `deployRelease` describes deploying
  dependency releases along, and reports them.
- **Wire v8** (unreleased, changed in place): `catalog.authority` and `catalog.namespace`; the same
  two members on catalog dependencies, resource dependencies and content references;
  `releaseFingerprint` and `location` on catalog dependencies; the V5 fingerprint covers the
  authority and namespace and each dependency's `releaseFingerprint`, but not `location`.
- **Migration options:** `CatalogMigrationOptions(authority, namespace)`.

## Consequences

- A catalog keeps one name from authoring to every installation that uses it, whatever path it took.
- Installations need configuration they did not have: their own authority, the default authority,
  and an ordered list of registries. Suite may not have these yet.
- Exchanges take on authority verification for names they did not grant. Exchange may not do this
  yet.
- A release can be verified wherever it came from, through its fingerprint and, later, a
  signature.
- An authority must be a DNS name with a dot. A local test setup uses something like
  `exchange.localhost`, not `localhost`.

## Open questions

- Should `location` be consulted by default, or only for registries an administrator allows? A
  hint written by a publisher sends an installation to a server it did not choose. The fingerprint
  protects the content, but not the fact of the request.
- Deploying with dependencies: deploy them along (proposed), or refuse with 409 and the list?
- Is a "fork" (copy a catalog to a new identity) an API operation in 2.0, or later?
- The format of the well-known document and of signatures (later ADR).
- Publishing a release that depends on a hotfix is refused (proposed above); ADR 0001 should say so.
