# Quality profiles

> **Status:** alpha, wire v8 (contract ADR 0010). May change in a minor release.

A quality profile records how an organisation writes: rules about its documents, independent of the
systems that check them. A catalog carries profiles as resources and says which apply through a
`quality` binding, on the catalog and on a template.

Three places hold quality content:

| Where                                 | What                                               |
| ------------------------------------- | -------------------------------------------------- |
| `resources/qualityProfile/<key>.json` | the profile: its rules                             |
| `catalog.json`, `catalog.quality`     | the profiles every template in the catalog uses    |
| a template resource, `quality`        | the profiles that template adds, and its overrides |

## A profile

`resources/qualityProfile/clear-language.json`:

```json
{
  "schemaVersion": 8,
  "resource": {
    "type": "qualityProfile",
    "key": "clear-language",
    "name": "Clear language",
    "description": "Plain, short writing for letters to citizens.",
    "rules": [
      {
        "id": "sentence-length",
        "title": "Short sentences",
        "intent": "Keep sentences to at most 20 words: citizens read at language level B1.",
        "severity": "warning",
        "blocking": true,
        "scope": { "roles": ["prose"], "languages": ["nl"] },
        "examples": {
          "good": ["Wij sturen u de stukken."],
          "bad": [
            "Middels deze brief, die u heden ontvangt, delen wij u mede dat de stukken zijn verzonden."
          ]
        },
        "extras": {
          "builtin": { "type": "max-sentence-words", "limit": 20 },
          "llm": { "instruction": "Count words as a reader would; a placeholder is one word." }
        }
      },
      {
        "id": "plain-words",
        "title": "Plain words",
        "intent": "Prefer everyday words to official ones.",
        "severity": "info",
        "extras": {
          "builtin": { "type": "word-list", "entries": { "nl": { "middels": ["met", "via"] } } },
          "languagetool": { "categories": ["STYLE"] }
        }
      },
      {
        "id": "tone",
        "title": "Friendly tone",
        "intent": "Address the reader as 'u', warmly; never threaten, even about deadlines.",
        "severity": "info",
        "extras": {
          "llm": { "instruction": "Judge the tone of the whole letter, not single words." }
        }
      }
    ],
    "acceptedWords": ["Epistola", "DigiD"]
  }
}
```

The manifest lists it like any resource:

```json
{
  "type": "qualityProfile",
  "key": "clear-language",
  "name": "Clear language",
  "detailUrl": "./resources/qualityProfile/clear-language.json"
}
```

`acceptedWords` are words the organisation uses on purpose (names, product terms) that a spelling
check must not report.

### A rule

Every rule has a core that every reader understands, and `extras` that tell a particular kind of
checking system how to apply it.

| Member             | Required | Meaning                                                                                                                                |
| ------------------ | -------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| `id`               | yes      | Unique within the profile, in the resource-key shape (no dots). A consumer may show it qualified, as `clear-language.sentence-length`. |
| `title`            | yes      | A short name.                                                                                                                          |
| `intent`           | yes      | The rule in plain language, what and why. Shown to authors as the explanation of every finding, and what a language model applies.     |
| `severity`         | yes      | `info`, `warning` or `error`.                                                                                                          |
| `blocking`         | no       | `true`: an open finding stops a variant being marked ready and a release being cut. Omitted when false.                                |
| `scope`            | no       | What the rule reads. Each list narrows; an absent scope means every prose field.                                                       |
| `scope.roles`      | no       | Field roles: `prose`, `identifier`, `expression`, `value`, `reference`. Absent means `prose`.                                          |
| `scope.components` | no       | Component types, such as `text` or `image`.                                                                                            |
| `scope.languages`  | no       | BCP 47 language tags, such as `nl` or `en-GB`.                                                                                         |
| `examples`         | no       | `good` and `bad` texts, for authors and as examples for a language model.                                                              |
| `extras`           | no       | An object per kind of checking system. Only `builtin` has a shape the contract fixes.                                                  |

A rule needs no extra for the systems an installation has. The `tone` rule above has only an `llm`
extra; a consumer without a language model keeps the rule and reports it as not checked.

### The `builtin` extra

| `type`                     | Members                                                    | Example                                                                   |
| -------------------------- | ---------------------------------------------------------- | ------------------------------------------------------------------------- |
| `repeated-word`            | `allowed`: per language, words that may repeat             | `{"type": "repeated-word", "allowed": {"nl": ["dat"], "en": ["had"]}}`    |
| `max-sentence-words`       | `limit`, a positive whole number (required)                | `{"type": "max-sentence-words", "limit": 20}`                             |
| `double-space`             | none                                                       | `{"type": "double-space"}`                                                |
| `space-before-punctuation` | `exceptLanguages`: languages that put a space there        | `{"type": "space-before-punctuation", "exceptLanguages": ["fr"]}`         |
| `word-list`                | `entries` (required): per language, phrase to alternatives | `{"type": "word-list", "entries": {"nl": {"ten behoeve van": ["voor"]}}}` |

A `builtin` extra with another type, a missing required member, or a member its type does not take
is invalid. Every other kind (`languagetool`, `llm`, `vale`, …) is an open object: the contract does
not check it, and every consumer keeps it as written.

## A binding

A `quality` binding names the profiles that apply (`profiles`) and changes their rules at this level
(`overrides`). It has the same shape on the catalog and on a template.

### On the catalog

In `catalog.json`, applying to every template of the catalog. One profile is the catalog's own, one
comes from a shared catalog:

```json
{
  "schemaVersion": 8,
  "catalog": {
    "key": "letters",
    "name": "Letters",
    "quality": {
      "profiles": [{ "key": "clear-language" }, { "key": "house-style", "catalogKey": "house" }],
      "overrides": [
        { "profile": { "key": "clear-language" }, "ruleId": "plain-words", "severity": "warning" }
      ]
    }
  },
  "dependencies": [{ "type": "qualityProfile", "catalogKey": "house", "key": "house-style" }],
  "catalogDependencies": [
    {
      "alias": "house",
      "registry": "exchange.epistola.app",
      "namespace": "acme",
      "catalogKey": "house",
      "releaseVersion": "1.2.0",
      "releaseFingerprint": "v6:…"
    }
  ]
}
```

A profile without `catalogKey` is in this catalog. One with `catalogKey` is in the catalog declared
under that alias, exactly like a theme reference, and is listed in `dependencies` as
`qualityProfile`.

An override names a profile and a `ruleId`, with `enabled`, `severity` or both. A member it leaves
out stays as the profile says.

### On a template

On the template resource, adding to the catalog's binding:

```json
{
  "schemaVersion": 8,
  "resource": {
    "type": "template",
    "key": "legal-notice",
    "name": "Legal notice",
    "quality": {
      "profiles": [{ "key": "legal-terms" }],
      "overrides": [
        { "profile": { "key": "clear-language" }, "ruleId": "sentence-length", "enabled": false }
      ]
    }
  }
}
```

## How bindings stack

All the profiles of the catalog's binding and the template's apply together. A rule is known by its
profile and id, so two profiles never compete for a rule. For the same rule, the template's override
replaces the catalog's.

With the two bindings above, the rules for `legal-notice` are:

| Rule                             | From                          | Applies as                        |
| -------------------------------- | ----------------------------- | --------------------------------- |
| `clear-language.sentence-length` | the catalog                   | off: the template switched it off |
| `clear-language.plain-words`     | the catalog                   | `warning`: the catalog raised it  |
| `clear-language.tone`            | the catalog                   | `info`, as the profile says       |
| `house-style.*`                  | the catalog, a shared catalog | as the profile says               |
| `legal-terms.*`                  | the template                  | as the profile says               |

Neither list in a binding is ordered: the position of a profile or an override decides nothing.
Should precedence among the profiles of one binding ever be needed, it becomes an explicit member (an
`order`), never the position in the list.

## Validation

| Code                                    | When                                                                          |
| --------------------------------------- | ----------------------------------------------------------------------------- |
| `CATALOG_QUALITY_RULE_ID_INVALID`       | a rule id is not in the resource-key shape                                    |
| `CATALOG_QUALITY_RULE_ID_DUPLICATE`     | a profile uses a rule id twice                                                |
| `CATALOG_QUALITY_SEVERITY_INVALID`      | a rule's or an override's severity is not `info`, `warning` or `error`        |
| `CATALOG_QUALITY_RULE_SCOPE_INVALID`    | a scope names an unknown field role                                           |
| `CATALOG_QUALITY_RULE_EXTRA_INVALID`    | a `builtin` extra has an unknown type, or a member its type needs or refuses  |
| `CATALOG_QUALITY_OVERRIDE_RULE_UNKNOWN` | an override names a rule its profile, held in the same catalog, does not have |
| `CATALOG_QUALITY_BINDING_DUPLICATE`     | a binding names a profile twice, or overrides the same rule twice             |
| `CATALOG_DEPENDENCY_CATALOG_UNDECLARED` | a profile is named by an alias the catalog does not declare                   |

A profile in another catalog is not read by the validator, so an override of one of its rules is the
consumer's to check.

## Fingerprints

- A catalog without profiles or bindings keeps the fingerprint it had before quality profiles
  existed: V5 and V6 cover a binding only when present, and V1 to V4 leave a template's out.
- A binding is fingerprinted sorted (`QualityBinding.canonical`: profiles by catalog and key,
  overrides by profile and rule), so writing it in another order leaves the release's identity
  alone.
- A profile is a resource like any other: changing a rule changes its fingerprint and the
  catalog's.

## In the REST API

The REST API (alpha, `x-experimental`) manages a release's profiles like its themes, under
`…/releases/{release}/quality-profiles`: `listQualityProfiles`, `createQualityProfile`,
`getQualityProfile`, `updateQualityProfile` and `deleteQualityProfile`. A profile and a rule are
written there as above. A binding is the `quality` member of a release (`CatalogReleaseDto`,
`updateCatalogRelease`) and of a template (`TemplateDto`, `createTemplate`, `updateTemplate`); on an
update it replaces the binding as a whole, and an empty one removes it. A server that does not
support quality profiles yet answers 501 `operation-not-implemented`.
