# ADR 0008: Release registries

- **Status:** Accepted (alpha), 2026-10-21. The operations are `x-experimental` and may still
  change in a minor release.
- **Date:** 2026-10-21
- **Scope:** API contract (`contracts/api`)
- **Related:** [ADR 0003](0003-catalog-identity-and-distribution.md) (catalog identity, registries
  and mirrors), [ADR 0005](0005-merging-catalog-versions.md) (merging, the merge base),
  Epistola Suite ADR 0032 (catalogs are drafts and releases, moved between installations and merged)

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
| `listRegistryReleases` | `GET …/release-registries/{name}/catalogs/{namespace}/{catalogKey}/releases` | `[{version, fingerprint, releaseId?, base?}]`, highest first, best effort |
| `installRegistryRelease` | `POST …/release-registries/{name}/catalogs/{namespace}/{catalogKey}/releases/{version}/install` | as `importCatalog`, with its response (`ImportCatalogResponse`) |
| `publishReleaseToRegistry` | `POST /tenants/{tenantKey}/catalogs/{registry}/{namespace}/{catalogKey}/releases/{release}/publish` `{to}` | `{status, registry, version, digest?}` |

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
so two catalogs that differ only in the registry part of their names cannot share one.

### Installing is importing

`installRegistryRelease` installs the release exactly as `importCatalog` installs an archive's,
with the same checks and refusals: the fingerprint recomputed from the content (what a registry
says of a release is a label, never trusted), one number and one id name one release (409
`conflict`), missing dependencies (409 `release-dependencies-missing`), an invalid archive or not
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

## Open questions

- Should installing fetch the dependency releases a release pins from the same release registry,
  as `installCatalogRelease` does from their sources?
- Should a release registry's `queued` publication be readable (its state, its failure) through
  this API, or stay with the Exchange?
