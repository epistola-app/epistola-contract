# ADR 0010: Quality profiles as a catalog resource

- **Status:** Proposed
- **Date:** 2026-10-31
- **Scope:** portable catalog contract (`contracts/catalog`, wire v8: a new resource type and a
  binding on the catalog and on a template), contract 2.0
- **Related:** [ADR 0003](0003-catalog-identity-and-distribution.md) (catalog dependencies and
  aliases), [ADR 0007](0007-themes-per-catalog.md) (a catalog's default theme); Epistola Suite
  ADRs 0044 to 0047 (quality checks: results, locations, profiles, providers)

## Context

Organisations write to a standard: a reading level, a tone, sentence length, words to avoid and
words to use instead, their own rules and exceptions. Epistola Suite checks templates against such
a standard with several systems: a built-in engine, a LanguageTool server, a language model. The
standard has to be recorded once, travel with the catalog it belongs to, be shared through Exchange,
and stay independent of the systems that check it, which differ per installation.

The same rule is checked differently by different systems: "at most 20 words a sentence" is a count
for a built-in engine and an instruction for a model. Not every installation has every system, and
a profile written on one must still be valid, and keep everything it says, on another.

## Decision

### A resource type, `qualityProfile`

A profile is a catalog resource, so it is versioned, released and fingerprinted with its catalog,
and moves between installations as catalogs do. It has a `key`, `name`, optional `description`,
`rules`, `acceptedWords` (names and terms a spelling check must not report), `tags` and `metadata`.

A **rule** is a generic core every system and every author reads:

| Member     | Meaning                                                                                       |
| ---------- | --------------------------------------------------------------------------------------------- |
| `id`       | unique within the profile, a key; findings and overrides name it                              |
| `title`    | a short name                                                                                  |
| `intent`   | the rule in plain language, what and why: the explanation of every finding, and what a model applies |
| `severity` | `info`, `warning` or `error`                                                                  |
| `blocking` | an open finding stops a variant being marked ready and a release being cut; left out when false |
| `scope`    | field `roles` (`prose`, `identifier`, `expression`, `value`, `reference`), `components`, `languages` |
| `examples` | `good` and `bad` text                                                                         |
| `extras`   | per kind of checking system, an object                                                        |

Only the `builtin` kind's extra has a shape the contract fixes, because Epistola's own engine reads
it and a profile must mean the same everywhere: `type` one of `repeated-word` (`allowed` per
language), `max-sentence-words` (`limit`), `double-space`, `space-before-punctuation`
(`exceptLanguages`), `word-list` (`entries`: per language, phrase to alternatives). Every other kind
(`languagetool`, `llm`, `vale`, …) is an open object the consumer that has such a system reads, and
every consumer keeps.

### A binding, stacked

`QualityBinding` names `profiles` (each a `QualityProfileRef`: a key and, for another catalog's, the
dependency alias, exactly like a theme reference) and `overrides` (a profile reference, a rule id, and
`enabled` or `severity`). A catalog carries one as `catalog.quality`, which applies to every
template; a template carries its own as `quality`, which adds to it. All the profiles' rules apply
together; an override at the template replaces one at the catalog for the same rule. A profile from
another catalog is listed in the manifest's `dependencies` as `qualityProfile`.

Neither list is ordered. Every profile applies, and an override is known by its profile and rule, so
the position of an entry decides nothing. Fingerprints therefore cover a binding sorted
(`QualityBinding.canonical`: profiles by catalog and key, overrides by profile and rule), and writing
the same binding in another order leaves the release's identity alone. Within one binding a profile
bound twice, or a rule overridden twice, is an error rather than settled by which comes last. Should
precedence among the profiles of one binding ever be needed, it becomes an explicit member (an
`order`), never the position in the list.

### Wire v8, additive

2.0, whose format v8 is, has had only alpha prereleases, so the type joins v8 rather than a v9. The
members are optional and new: a catalog without them keeps its fingerprints. V5 and V6 cover a
binding only when present; V1 to V4 leave a template's out (`CatalogV8Additions`). A consumer built
before this refuses an archive holding a profile, as it refuses any unknown resource type.

### Validation

The validator, not the model, holds a profile to its rules: rule ids are keys and unique, severities
and roles known, a `builtin` extra well formed (`CATALOG_QUALITY_*`). A binding's profiles resolve
like any reference, an alias must be declared, an override of a profile in the same catalog must
name a rule it has, and no profile or overridden rule appears twice in one binding. A profile in another catalog is not read, so its rule ids are the consumer's to
check.

## Consequences

- A standard is recorded once, travels with its catalog, and is checked as far as each installation
  can, with the rest reported as not checked rather than lost.
- Epistola Suite gets a resource type to store, edit and resolve, and a binding to apply; Exchange
  carries profiles like any resource.
- The `builtin` shapes are now part of the contract: a new check type, or a new parameter, is a
  contract change.

## Open questions

- Whether a target level and tone become profile members of their own rather than rules.
- Whether a variant gets a binding of its own; languages are handled inside a profile for now.
