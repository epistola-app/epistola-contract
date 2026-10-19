# ADR 0004: Resource identity within a catalog

- **Status:** Accepted
- **Date:** 2026-10-16
- **Scope:** API contract (`contracts/api`) and portable catalog contract (`contracts/catalog`, wire
  v8), contract 2.0
- **Related:** [ADR 0002](0002-intermediate-releases.md) (catalog versions),
  [ADR 0003](0003-catalog-identity-and-distribution.md) (catalog identity), issue #84 (a resource's
  address is its `key`)

## Context

Contract 2.0 names every resource's address `key` (#84) and identifies a catalog by registry,
namespace and key (ADR 0003). A resource has no version of its own; it is versioned with its catalog
(ADR 0002). That leaves one question: is a resource's `key` unique within its type, or within the
whole catalog?

Today it is unique per type. `CatalogValidator` checks duplicates on `type/key`, so one catalog can
hold a template `invoice`, a theme `invoice` and a stencil `invoice`. None of the catalogs we could
check (Suite's system, demo and fixture catalogs, the contract fixtures) has a key shared across
types, but those are small, and customer catalogs were not available.

A single written name for a resource would help in logs, messages, documentation and search. With
keys unique per type, that name needs the type in it.

## Options

### A. Keys unique per type, and a canonical name that includes the type

`exchange.epistola.app/epistola/letters@1.4.0/stencils/letter-shell`. Authors keep ordinary names
(a template, its theme and a stencil all called `invoice`; an attribute `locale` bound to a code
list `locale`). Nothing existing has to be renamed.

### B. Keys unique across the whole catalog

`exchange.epistola.app/epistola/letters@1.4.0/letter-shell`. One segment shorter, and a reference
could name a resource without knowing its type. But:

- authors lose the ordinary names above, and must invent a second name for one of each pair;
- a catalog with such a pair cannot be migrated to v8 without renaming a resource and rewriting
  every reference to it, inside and outside the catalog, and Suite would need the same migration
  over its database;
- references would not get simpler: content already knows the type of what it references (a stencil
  node carries `stencilKey`, an image node `imageKey`, a `ThemeRef` `themeKey`), and REST paths are
  per type (`/stencils/{stencilKey}`).

## Decision

Adopt **A**.

### Uniqueness

A resource's `key` is unique among the resources **of its type** in one catalog version. A variant's
`key` is unique within its template.

### The canonical name

A resource is written as one string:

```
{registry}/{namespace}/{catalogKey}@{catalogVersion}/{collection}/{key}
exchange.epistola.app/epistola/letters@1.4.0/stencils/letter-shell
exchange.epistola.app/epistola/letters@1.4.0/templates/invoice/variants/english
```

- `{collection}` is the resource's REST collection name: `templates`, `stencils`, `themes`,
  `images`, `fonts`, `code-lists`, `attributes`. A variant follows its template, as in the API.
- The registry is always written in full, never `~`: the name must mean the same resource in every
  installation (ADR 0003).
- `@{catalogVersion}` may be left out to name the resource in the catalog without a version, as a
  reference inside content does; which release (or, in a draft, which draft) it means is then
  decided by the catalog dependency that declares it.
- The name is unambiguous without escaping: a registry is a DNS name, a namespace and a key are
  lowercase letters, digits and hyphens, and a version is a SemVer number or a draft id
  (ADR 0002), none of which contains `/` or `@`.
- The tenant is not part of the name. It is local to an installation; the REST path puts
  `/tenants/{tenantKey}` in front.

The canonical name is the REST path's catalog-relative part with `/catalogs/` and `/versions/`
dropped and the version joined with `@`, so either converts to the other mechanically.

Lists that mix resource types keep writing `type/key` (`theme/corporate`) within one catalog
version; that is the tail of the canonical name.

## Consequences

- No resource has to be renamed, and no migration changes for this decision.
- Messages and logs can name a resource precisely in one string. The contract does not require any
  producer to do so yet, and no operation accepts the canonical name as input.
- Two resources of different types can share a key. Anything that names a resource without its type
  (a free-text search, a mixed list) must show the type next to the key.

## Later

- An operation that resolves a canonical name to the resource, if a consumer needs one.
- Writing the canonical name into problem details and validation findings, so a message about a
  resource in another catalog names it fully.
