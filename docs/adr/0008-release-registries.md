# ADR 0008: Release registries

- **Status:** Accepted (alpha), 2026-10-21. The operations are `x-experimental` and may still
  change in a minor release. Amended 2026-10-22 (see "Amendment: release registries for
  content-addressed releases").
- **Date:** 2026-10-21
- **Scope:** API contract (`contracts/api`)
- **Related:** [ADR 0003](0003-catalog-identity-and-distribution.md) (catalog identity, registries
  and mirrors), [ADR 0005](0005-merging-catalog-versions.md) (merging, the merge base),
  Epistola Suite ADR 0032 (catalogs are drafts and releases, moved between installations and merged),
  Epistola Suite ADR 0034 (releases are content-addressed; names point at them)

## Context

A catalog's releases move between installations: from test to acceptance to production, or from a
publisher to its users. Contract 2.0 had three ways to receive one, and none to send one:

- importing an archive (`importCatalog`), a file carried by hand;
- installing from a catalog's source (`installCatalogRelease`), the registry its name says it is
  published on (ADR 0003), through mirrors;
- the Epistola Exchange, whose publishing (a publication outbox, namespaces the Exchange grants an
  installation) and installing Epistola Suite implements outside this contract.

Epistola Suite also carries releases through a container registry (OCI distribution) an
organisation already runs: production usually pulls its images from one, so no new network path
is needed, and installations never contact each other. That had no REST surface either.

## Decision

### One way to move releases: a release registry

A **release registry** is a place releases are published to and installed from. The Exchange is
one kind (`exchange`), a container registry another (`container`). The API offers one set of
operations for all of them, so a client moves a release the same way whatever carries it:

| Operation | Request | Notes |
| --- | --- | --- |
| `listReleaseRegistries` | `GET /tenants/{tenantKey}/release-registries` | `{items: [{name, kind}]}`: the registries this installation has configured or is connected to; no credentials, no addresses |
| `listRegistryReleases` | `GET …/release-registries/{name}/catalogs/{registry}/{namespace}/{catalogKey}/releases` (amended 2026-10-22: `{registry}` added) | `[{version, fingerprint, releaseId?, base?}]`, highest first, best effort |
| `installRegistryRelease` | `POST …/release-registries/{name}/catalogs/{registry}/{namespace}/{catalogKey}/releases/{version}/install` | as `importCatalog`, with its response (`ImportCatalogResponse`) |
| `publishReleaseToRegistry` | `POST /tenants/{tenantKey}/catalogs/{registry}/{namespace}/{catalogKey}/releases/{release}/publish` `{to}` | `{status, releaseRegistry, version, digest?}` (amended 2026-10-22: was `registry`) |

Permissions are those of the matching catalog operations: `CATALOG_VIEW` to list,
`CATALOG_MANAGE` to install (as importing), `CATALOG_PUBLISH` to publish (as sending a release to
the Exchange: the release leaves the installation, which being trusted to release it does not
cover).

### A release registry is not a catalog's registry

The `{registry}` of a catalog's path is part of the catalog's name (ADR 0003): the registry that
grants its namespace. A release registry is named by the installation (`acme`, `exchange`;
`ReleaseRegistryName`) and is configuration, never part of a name. A container registry grants no
names: it carries releases, as a mirror carries copies, and a release installed from it keeps the
name its archive gives it. The Exchange is both: it grants namespaces, so it is the `{registry}` of
the catalogs published on it, and it is a release registry to the installations connected to it.

So the release-registry paths address a catalog by namespace and key only, inside the registry
named by `{name}`. A consequence: one container registry holds one catalog per namespace and key,
so two catalogs that differ only in the registry part of their names cannot share one. (Superseded
2026-10-22: the paths name `{registry}` too, and a repository includes the authority; see the
amendment.)

### Installing is importing

`installRegistryRelease` installs the release exactly as `importCatalog` installs an archive's,
with the same checks and refusals: the fingerprint recomputed from the content (what a registry
says of a release is a label, never trusted), one number and one id name one release (409
`conflict`; amended 2026-10-22: 409 `release-number-taken`, as publishing refuses a number taken,
for every way of receiving a release), missing dependencies (409 `release-dependencies-missing`), an invalid archive or not
the catalog asked for (400 `bad-request`), rendering defaults this installation lacks (400
`catalog-rendering-defaults-too-new`). A catalog the tenant does not have is created with the
release registry as its source; one that is another catalog under the same key, or receives its
releases elsewhere, is refused before anything is fetched. Nothing is merged.

### Publishing: one number names one release

`publishReleaseToRegistry` publishes a released release exactly as it was released. The registry
is asked first: the same version with the same fingerprint is `already-published` and nothing is
sent; other content is refused with 409 `release-number-taken`. Between the look and the push
another installation could publish the same version; only a registry that refuses to overwrite one
(immutable tags) closes that.

The `status` says what happened, because the kinds publish differently: `published` when a
container registry holds it now (it publishes while the request waits, and gives its manifest
`digest`), `queued` when the Exchange's publication outbox has it and the Exchange decides later,
also when it was queued already, so a retry gets the same answer. A draft is refused with 409
`conflict`: only a released release is published.

New problem types: 404 `release-registry-not-found` (no such release registry here), 409
`release-number-taken`, 409 `namespace-not-granted` (the Exchange does not grant this installation
the catalog's namespace). A registry that cannot be used is `catalog-source-unavailable`, 503 or
502, as a catalog's source is.

### What stays Exchange-specific

Searching catalogs, browsing publishers, managing namespaces and the connection to an Exchange,
and withdrawing a queued publication are the Exchange's own, and not part of these operations.

## Consequences

- One API moves releases through any release registry; a new kind (another artifact store) is a
  new `ReleaseRegistryKind` value, which clients keep as a string they may not know.
- A release registry's listing is best effort: what the registry can say. A container registry
  records a release's fingerprint (as an annotation) and may record nothing else.
- Epistola Suite's MCP tools and UI already do this for container registries; its Exchange
  publication becomes the `exchange` kind of the same operations.

## Amendment: release registries for content-addressed releases (2026-10-22)

ADR 0003's amendment of the same day (Epistola Suite ADR 0034) separates four questions: which
release is this (its **content hash**, the V6 fingerprint), what it is called (a name, authority /
namespace / key, and a number), who may call it that (the holder of the namespace, by a
**binding**), and where it is kept (a release registry, or a file). This ADR answered only the
last, and assumed the others were one: a name and number that were the release. They are not any
more, and four things follow for release registries.

**The authority is part of a container repository path.** A container registry keeps a release at
`<prefix>/<authority>/<namespace>/<key>:<version>`: the configured prefix, the catalog's full name
(the authority is the `registry` of the name, a domain, ADR 0003) and the number as the tag. The
earlier rule, a repository per namespace and key, is superseded, and with it the consequence that
"two catalogs that differ only in the registry part of their names cannot share one": they can, as
`<prefix>/acme.com/acme/letters` and `<prefix>/exchange.epistola.app/acme/letters`. The tag is the
binding's number, "this name at this number"; it is not the release's identity.

**A release resolves by digest and content hash.** The installation resolves a tag to a digest
once and fetches the release by that digest, so what it verified is what it installs; the release
then verifies itself by its content hash, recomputed from its content (what a registry says of a
release stays a label). A registry records the release's content hash beside its fingerprint
(`RegistryReleaseDto.contentHash`, and the base's, `base.contentHash`: lineage is by hash), and the
API names a release by it wherever it describes one (`CatalogReleaseDto.contentHash`, found with
`listCatalogReleases?contentHash=`). Receiving is decided by content hash: a release the tenant has
with the same content hash is the release it has, whatever number or registry it came from; one
whose number the tenant has with another content hash is refused (`release-number-taken`), since one
number names one release in a catalog. A tag moved to other content is a new binding of that number,
refused the same way.

**Mirroring copies releases and their bindings unchanged.** A mirror is a release registry holding
copies: releases and their bindings, the same content, name and number, at the same repository
path under its own prefix. It adds nothing under a name, so it needs no namespace: the content hash
proves the content, and the signature over a binding (a later phase) who named it. Publishing a
release this installation received rather than released (`publishReleaseToRegistry`) is mirroring
it: the release goes out exactly as it came in, and to a container registry it needs no namespace.
The Exchange hosts names rather than copies, so publishing to it stays the holder's
(`namespace-not-granted`). A patch release (`1.3.1-gemeente-x.1`, ADR 0003) is published under the
name it patches, never under the holder's own numbers, and only its issuer publishes it as a new
binding; anyone may mirror it.

**A catalog's source is per tenant and changeable.** `CatalogDto.source` is where this tenant
receives the catalog's new releases from: another tenant of the same installation may receive the
same catalog from elsewhere (the Exchange in one, the organisation's own Harbor in another), and a
tenant may change it ("from now on, from our Harbor"). Changing it changes where new releases come
from, never which releases are installed or what they are: a release is its content hash, the same
from any registry. A catalog installed from a release registry gets it as its source, as before;
installing from a registry that is not its source is still refused, so receiving from a mirror is
changing the source to it first.

What does not change: the operations, the permissions, and installing as importing.

**Follow-up, the same day: the layout is the contract's, and the API names the authority.** The
container layout is defined once in the catalog library (`CatalogContainerLayout`;
`catalogContainerRepository` and `CATALOG_CONTAINER_*` in npm, documented in "In a Container
Registry" of `catalog-archive.md`): the repository above, the release number as the tag (every valid
release number is a valid OCI tag), the media types and the manifest annotations Epistola Suite
already wrote, plus `app.epistola.catalog.contentHash`. The release-registry API paths name the
catalog in full, `…/release-registries/{name}/catalogs/{registry}/{namespace}/{catalogKey}/releases`
(and `…/{version}/install`), because addressing by namespace and key could not tell two catalogs of
one registry apart that differ only in their authority, and adding the segment after 2.0 would be a
breaking change. In those operations and in publishing, `registry` always means the authority: the
release registry's name is `releaseRegistry` (`RegistryReleaseListResponse`,
`ReleasePublicationDto`), and the listing names the catalog's authority in `registry`.

## Open questions

- Should installing take a content hash instead of a number (`…/releases/by-hash/{contentHash}`),
  for a registry that keeps releases by digest without a tag?
- How a binding travels in a container registry: a signed statement beside the release (as cosign
  does), or in its manifest (ADR 0003, open as there).
- Changing a catalog's source has no operation in this contract yet (Epistola Suite ADR 0034 makes
  it changeable in its phase 6). Should the API offer one?
- Should installing fetch the dependency releases a release pins from the same release registry,
  as `installCatalogRelease` does from their sources?
- Should a release registry's `queued` publication be readable (its state, its failure) through
  this API, or stay with the Exchange?
