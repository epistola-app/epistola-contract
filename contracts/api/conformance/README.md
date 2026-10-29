<!--
SPDX-FileCopyrightText: Epistola Nederland B.V.

SPDX-License-Identifier: EUPL-1.2
-->

# Client conformance suite

Five clients — Kotlin/Spring, Jakarta EE, .NET, Python, Node.js — talk to one server. Their
internals differ and should: each is idiomatic for its platform, and each generates its models with
a different generator. What must *not* differ is what the server sees. This suite pins that.

```bash
pnpm install                       # once
node src/run.mjs --client kotlin   # kotlin | jakarta | dotnet | python | node
node src/run.mjs --list            # what the scenarios are
node src/run.mjs --client python --scenario collect-backoff-floor
```

Or `make conformance` from the repository root, which runs all five.

## How it works

```
scenarios/*.yaml   the expectations — one file per behaviour, shared by all five clients
src/server.mjs     a scripted Epistola API that records every request it is sent (and can proxy)
src/expect.mjs     the judge: evaluates a scenario's `expect` against that record
src/run.mjs        the harness: per scenario, start the server, run a driver, judge, report
drivers/<client>/  a thin executable per client
```

For each scenario the harness starts a server on a loopback port, plays the scenario's scripted
responses back one per request, and keeps a journal of what arrived — method, path, headers, body,
arrival time. When the driver finishes, the journal is judged against the scenario's `expect`.

**The drivers assert nothing.** A driver asks the server what to do, does it with the published
client, and says when it is done. Every expectation lives in `scenarios/`, so adding one holds all
five clients to it at once — the thing five separate test suites cannot do, because they drift.

## The driver contract

A driver is two executables, `prepare.sh` (build; runs once per suite) and `run.sh <baseUrl>` (run
one scenario). `run.sh` gets only a base URL, and asks the server for the rest:

| | |
| --- | --- |
| `GET /__conformance/action` | `{scenario, action, config}` — what to do and how to configure the client |
| `POST /__conformance/report` | a flat JSON object of what the client surfaced, for scenarios that assert on it |
| `POST /__conformance/done` | `{}`, or `{"error": "…"}` if the driver could not complete |

Control-plane traffic is not journalled, so it cannot be mistaken for API traffic. Report values are
strings and numbers only — a list is joined with commas, or with `|` where its items are free text —
so that no driver needs a serializer for its own types. A boolean is reported as `true` or `false`,
and a missing value as `null`, whatever the driver's language prints for them.

The base URL addresses the server's root; a driver appends `/api` itself, because that base path is
part of the contract's `servers` entry and a client that drops it is a client with a bug. Exactly
that turned up in the .NET client on this suite's first run.

These actions cover the scenarios. A driver implements these, and nothing else:

| Action | What the driver does |
| --- | --- |
| `ping` | `POST /ping` with client metadata, through the generated API, with the credentials `config.auth` configures (none when absent) |
| `list-templates` | `GET …/templates`, `config.repeat` times, reporting the keys it parsed from the last response (`templateKeys`, comma-joined) and their descriptions (`templateDescriptions`, joined with `\|`, a missing one as `null`) |
| `collect` | build a `ResultCollector` from `config`, run it for `config.runForMs`, stop it, report what it handled, including each result's catalog and release as `releases` (`registry/namespace/catalogKey@version#releaseId:draftLabel`, a missing value as `null`) |
| `problem` | make a request the server answers with a problem, report the parsed slug and members |
| `validate-template-data` | validate `config.data` for a template through `TemplateSchemaValidator`, reporting the paths and keywords of the failures it surfaced |
| `generate-document` | `POST …/documents/generate` with a real body, through the operation that exposes the response headers; `config.registry`, `config.variantKey`, `config.versionRange` and `config.environmentKey` are sent when present, and left unset otherwise; `config.attributes`, when present, is sent as the attribute criteria, each with its `key` and `value` and whichever of `catalogKey` (an alias) and `required` it sets. Reports the `requestId`, `status`, `jobType` and `total` it parsed, and the `location` response header exactly as received (`null` when absent) |
| `routing` | poll once for a partition assignment, then report what the routing helpers compute |
| `update-consumer` | `PATCH …/consumers/{id}` with `config.name`, `config.description` and `config.clearExpiresAt` each only when present (an empty `description` sent as is), sending `config.ifMatch` as `If-Match` when present and no `If-Match` otherwise, through the operation that exposes the response headers, reporting the `id`, `name` and `lastModified` it parsed and the `etag` response header exactly as received (`null` when absent). When the server answers with a problem, report `problemTypeSlug` and `problemStatus` instead |
| `list-documents` | `GET …/documents` with `config.templateKey`, `config.registry`, `config.namespace` and `config.catalogKey` each sent only when present, reporting the `catalogs` (`registry/namespace/catalogKey`, a null value as `null`), `templateKeys` and `variantKeys` it parsed, comma-joined, in order |
| `download-document` | `GET …/documents/{id}`, reporting the SHA-256 and length of the bytes |
| `list-images` | `GET …/images?search=…`, reporting the keys, widths, heights and media types it parsed |
| `upload-image` | `POST …/images` with the file from `config` (bytes, filename, content type) and a name, reporting the returned key and name |
| `import-catalog` | `POST …/catalogs/import` with the file from `config` (bytes, filename, content type), and `config.registry` and `config.namespace` each only when present, reporting the `registry`, `namespace`, `catalogKey`, `version`, `newlyInstalled` and `total` it parsed. When the server answers with a problem, report `problemTypeSlug` and `problemStatus` instead |
| `install-catalog-release` | `POST …/catalogs/{registry}/{namespace}/{catalogKey}/available-releases/{version}/install` with `config.version` in the path, no query and no body, reporting the `registry`, `namespace`, `catalogKey`, `version`, `newlyInstalled` and `total` it parsed, and `installedDependencies` and `presentDependencies` (each release as `registry/namespace/catalogKey@version#releaseId`, comma-joined, in order, empty when absent or empty). When the server answers with a problem, 4xx or 5xx, report `problemTypeSlug`, `problemStatus` and `problemMissingDependencies` as `deploy-catalog-release` does instead |
| `list-available-catalog-releases` | `GET …/catalogs/{registry}/{namespace}/{catalogKey}/available-releases`, reporting the `registry`, `namespace` and `catalogKey` it parsed, `availableComparedTo` as `version=comparedTo` per available release (a null `comparedTo` as `null`), `availableChanges` as `version=type/key:change+…` per available release and `availableDependencies` as `version=registry/namespace/catalogKey@version:present+…` per available release (`present` as `true` or `false`), releases joined with `;` in order (empty when none). When the server answers with a problem, 4xx or 5xx, report `problemTypeSlug` and `problemStatus` instead |
| `list-release-registries` | `GET …/release-registries` (alpha), reporting `registries` as `name:kind` per registry, comma-joined, in order |
| `list-registry-releases` | `GET …/release-registries/{name}/catalogs/{registry}/{namespace}/{catalogKey}/releases` (alpha) with `config.name` and the catalog's `config.registry`, reporting the `releaseRegistry`, `registry`, `namespace` and `catalogKey` it parsed and `releases` as `version:fingerprint:releaseId:base` per release (the base as `version@fingerprint`, a missing value as `null`), comma-joined, in order, with `contentHashes` and `baseContentHashes` (each release's and its base's content hash, `null` when missing), comma-joined, in order. When the server answers with a problem, report `problemTypeSlug` and `problemStatus` instead |
| `install-registry-release` | `POST …/release-registries/{name}/catalogs/{registry}/{namespace}/{catalogKey}/releases/{version}/install` (alpha), no query and no body, reporting the `registry`, `namespace`, `catalogKey`, `version`, `newlyInstalled` and `total` it parsed. When the server answers with a problem, report `problemTypeSlug` and `problemStatus` instead |
| `publish-release-to-registry` | `POST …/catalogs/{registry}/{namespace}/{catalogKey}/releases/{release}/publish` (alpha) with `{"to": config.to}`, reporting the `status`, `releaseRegistry`, `version` and `digest` (`null` when absent) it parsed. When the server answers with a problem, report `problemTypeSlug` and `problemStatus` instead |
| `download-image` | `GET …/images/{imageKey}/content`, reporting the SHA-256 and length of the bytes |
| `delete-image` | `DELETE …/images/{imageKey}` with `force` from `config` |
| `list-catalog-dependencies` | `GET …/releases/{release}/dependencies`, reporting the `aliases`, `registries`, `namespaces`, `catalogKeys`, `versions`, `releaseIds` and `contentHashes` it parsed, in order (a null value as `null`) |
| `update-catalog-dependencies` | `PUT …/releases/{release}/dependencies` with `config.dependencies` (each entry's `namespace` and `catalogKey`, and its `alias`, `registry`, `version` and `releaseId` each only when the entry has one), sending `config.ifMatch` as `If-Match` when present and no `If-Match` otherwise, through the operation that exposes the response headers, reporting the `aliases`, `registries`, `namespaces`, `catalogKeys`, `versions`, `releaseIds` and `contentHashes`, in order (a null value as `null`), it parsed and the `etag` response header exactly as received (`null` when absent). When the server answers with a problem, report `problemTypeSlug` and `problemStatus` instead |
| `create-catalog-release` | `POST …/catalogs/{registry}/{namespace}/{catalogKey}/releases` with `config.name`, and `config.from` (a version or a `releaseId`) when present, reporting the `version`, `releaseId`, `name`, `status`, `base`, `createdFrom` and `releasedAt` it parsed, through the operation that exposes the response headers, and the `location` response header exactly as received (`null` when absent) |
| `list-catalog-releases` | `GET …/releases`, with `config.status` as the `status` filter and `config.contentHash` as the `contentHash` filter, each only when present, reporting the `versions`, `releaseIds`, `names` (joined with `\|`), `statuses`, `bases`, `createdFrom`, `behindCounts` (how many releases each is behind) and `contentHashes` (a null one as `null`) it parsed, in order |
| `get-catalog-release` | `GET …/releases/{release}`, reporting the `version`, `releaseId`, `name`, `status`, `releasedAt`, `fingerprint` and `contentHash` it parsed, its `dependencies` as `registry/namespace/catalogKey@version` and their `dependencyAliases` and `dependencyContentHashes` (each comma-joined, in order, a null value as `null`), `deployedTo`, and `behind` as `version=type/key:change+…` per release, releases joined with `;` (empty when none) |
| `update-catalog-release` | `PATCH …/releases/{release}` (`updateCatalogRelease`) with `config.name`, sending `config.ifMatch` as `If-Match` when present and no `If-Match` otherwise, through the operation that exposes the response headers, reporting the `version`, `releaseId`, `name`, `status` and `base` it parsed and the `etag` response header exactly as received (`null` when absent). When the server answers with a problem, report `problemTypeSlug` and `problemStatus` instead |
| `set-catalog-release-theme-settings` | `PATCH …/releases/{release}` (`updateCatalogRelease`) with no `name`, a `defaultTheme` of `config.defaultThemeKey` and `config.defaultThemeCatalogKey` (the theme left out when `config.defaultThemeKey` is absent, its catalog key when `config.defaultThemeCatalogKey` is), `config.clearDefaultTheme` only when present, and `config.presets` only when present, an empty array included (each entry's `key` and `name`, and its `description` only when it has one), reporting the `releaseId` and `name` it parsed, the `defaultThemeKey` and `defaultThemeCatalogKey`, and the `presetKeys` (comma-joined), `presetNames` and `presetDescriptions` (each joined with `\|`, a missing description as `null`), in order |
| `preview-catalog-draft-release` | `GET …/releases/{release}/release-preview`, with no query, reporting the `base`, `changeKind`, `reasons` (joined with `\|`, in order), `needed` and `suggested` it parsed |
| `delete-catalog-release` | `DELETE …/releases/{release}` |
| `release-catalog-draft` | `POST …/releases/{release}/release` with `config.version`, `config.notes`, `config.acknowledgedBehind` (an array) and `config.releaseDependencies` (a boolean) when present, each left out otherwise (`releaseDependencies` may go out as `false`, its default), reporting the released `version`, `status`, `releaseId` and `contentHash`, `unchangedContent`, `draftsBehind` joined with `\|` (empty when none), and `releasedDependencies` as `registry/namespace/catalogKey@version` (comma-joined, in order, empty when none). When the server answers with a problem, report `problemTypeSlug`, `problemStatus` and `problemUnreleasedDependencies` (`registry/namespace/catalogKey#releaseId=releaseName`, joined with `\|`, in order) instead |
| `preview-catalog-draft-merge` | `GET …/releases/{release}/merge-preview?version=` (alpha) with `config.version` as the query, reporting the `draft`, `base`, `release` and `alreadyMerged` it parsed, `resources` as `resourceType/resourceKey:outcome:draftChange:releaseChange` (comma-joined, in order, a null change as `null`), `resourceNames` (joined with `\|`, a null name as `null`), `resourceNeeds` as `resourceType/resourceKey=need+…` for each entry whose `needs` is not empty (joined with `;`, in order, empty when none; an empty or absent `needs` alike), `unchanged`, `token`, `missingBase` (`version@fingerprint`, `null` when absent) and `missingBaseContentHash` (`null` when absent or null) |
| `get-catalog-draft-merge-sides` | `GET …/releases/{release}/merge-sides?version=&entry=` (alpha) with `config.version` and `config.entry` as the query, reporting the `entry`, `mergeBase` (`null` when null) and `token` it parsed, each side (`base`, `draft`, `release`) as `type/key:name` from its members `type`, `key` and `name` (a missing member as `null`, a null or absent side as `null`), and `draftMembers`, the draft side's member names sorted and comma-joined (`null` when the side is null) |
| `merge-into-catalog-draft` | `POST …/releases/{release}/merge` (alpha) with `release` set to `config.version`, and `config.resolutions` (an object of `resourceType/resourceKey` to `ours` or `theirs`), `config.mergedContent` (an object of `resourceType/resourceKey` to a free-form JSON object, passed to the client as a caller holding that JSON would) and `config.expectedToken` each when present, left out otherwise, reporting the `release` it parsed and `taken`, `removed`, `kept` and `merged` (comma-joined, in order, `merged` empty when absent). When the server answers with a problem, report `problemTypeSlug`, `problemStatus`, `problemUnresolvedConflicts` (comma-joined, in order, empty when none), `problemReadOnlyReason` (`null` when absent) and `problemMissingBase` (`version@fingerprint` of a `merge-base-missing` problem, `null` otherwise) instead |
| `deploy-catalog-release` | `PUT …/environments/{environmentKey}/deployments/{registry}/{namespace}/{catalogKey}/{release}`, with `config.release` (a version or a `releaseId`) in the path and `includeDependencies` and `replace` from `config` each when present and unset otherwise, reporting the `registry`, `namespace`, `catalogKey`, `releaseId`, `status`, `version`, `releaseName`, `lastUsedAt` and `documentsRequested` it parsed (a null value as `null`). When the server answers with a problem, report `problemTypeSlug`, `problemStatus` and `problemMissingDependencies` (`registry/namespace/catalogKey@version:reason`, comma-joined, in order, empty when none) instead |
| `undeploy-catalog-release` | `DELETE …/environments/{environmentKey}/deployments/{registry}/{namespace}/{catalogKey}/{release}` with `config.release` in the path |
| `create-environment` | `POST …/environments` with `config.key`, `config.name`, and `config.production` when present, through the operation that exposes the response headers, reporting the `key`, `name` and `production` it parsed and the `location` response header exactly as received (`null` when absent) |
| `create-template` | `POST …/releases/{release}/templates` with `config.key`, `config.name`, and `config.description`, `config.dataModel` (a free-form JSON object, passed to the client as a caller holding that JSON would) and `config.dataExamples` (each entry's `key`, `name` and `data`) each only when present, through the operation that exposes the response headers, reporting what `update-template` reports from the template it parsed and the `location` response header exactly as received (`null` when absent), in place of `etag` |
| `update-environment` | `PATCH …/environments/{environmentKey}` with `config.name` and `config.production` each only when present, sending `config.ifMatch` as `If-Match` when present and no `If-Match` otherwise, through the operation that exposes the response headers, reporting the `key`, `name` and `production` it parsed and the `etag` response header exactly as received (`null` when absent). When the server answers with a problem, report `problemTypeSlug` and `problemStatus` instead |
| `update-template` | `PATCH …/releases/{release}/templates/{templateKey}` with `config.dataModel` (a free-form JSON object, passed to the client as a caller holding that JSON would) and `config.dataExamples` (each entry's `key`, `name` and `data`) each only when present, and no other member, sending `config.ifMatch` as `If-Match` when present and no `If-Match` otherwise, through the operation that exposes the response headers, reporting the `key` it parsed, `dataModelMembers` (the data model's member names, sorted and comma-joined, empty for `{}`, `null` when there is no data model), `dataModelType` (the data model's `type`, `null` when there is no data model), `dataModelRequired` (its `required`, comma-joined, in order, `null` when absent), `dataExampleKeys` (comma-joined, in order, empty when none) and the `etag` response header exactly as received (`null` when absent). When the server answers with a problem, report `problemTypeSlug` and `problemStatus` instead |
| `update-tenant` | `PATCH /tenants/{tenantKey}` with `config.name`, sending `config.ifMatch` as `If-Match` when present and no `If-Match` otherwise, through the operation that exposes the response headers, reporting the `key` and `name` it parsed and the `etag` response header exactly as received (`null` when absent). When the server answers with a problem, report `problemTypeSlug` and `problemStatus` instead |
| `update-attribute` | `PATCH …/attributes/{attributeKey}` with `config.allowedValues`, `config.codeListBinding` (its `key`, and its `catalogKey`, an alias, only when it has one) and `config.clearCodeListBinding` each only when present, and no other member, sending `config.ifMatch` as `If-Match` when present and no `If-Match` otherwise, through the operation that exposes the response headers, reporting the `key` it parsed, `allowedValues` (comma-joined, in order), `codeListBinding` (`catalogKey/key`, a null `catalogKey` as `null`, `null` when absent) and the `etag` response header exactly as received (`null` when absent). When the server answers with a problem, report `problemTypeSlug` and `problemStatus` instead |
| `update-code-list-entry-hidden` | `PATCH …/code-lists/{codeListKey}/entries/{code}/hidden` with `config.hidden`, sending `config.ifMatch` as `If-Match` when present and no `If-Match` otherwise, through the operation that exposes the response headers, reporting the entry's `code` and `hidden` it parsed and the `etag` response header exactly as received (`null` when absent). When the server answers with a problem, report `problemTypeSlug` and `problemStatus` instead |
| `update-variant-content` | `PUT …/templates/{templateKey}/variants/{variantKey}/content` with a one-node template model, sending `config.ifMatch` as `If-Match` when present and no `If-Match` otherwise, reporting the `variantKey` it parsed. When the server answers with a problem, report `problemTypeSlug` and `problemStatus` instead |
| `get-variant-content` | `GET …/templates/{templateKey}/variants/{variantKey}/content` through the operation that exposes the response headers, reporting the `variantKey`, `templateModelRoot`, `status`, `lastModified` and `readyAt` it parsed, and the `etag` response header exactly as received |
| `mark-variant-ready` | `POST …/templates/{templateKey}/variants/{variantKey}/mark-ready`, reporting the `variantKey`, `status`, `lastModified` and `readyAt` it parsed |
| `mark-template-ready` | `POST …/templates/{templateKey}/mark-ready`, reporting the `key` and `name` it parsed, and its variants' `variantKeys` and `variantStatuses`, in order |
| `get-stencil-content` | `GET …/stencils/{stencilKey}/content` through the operation that exposes the response headers, reporting the `stencilKey`, `contentRoot`, `parameterSchemaType` (the schema's `type` member), `status`, `lastModified` and `readyAt` it parsed, and the `etag` response header exactly as received |
| `update-stencil-content` | `PUT …/stencils/{stencilKey}/content` with a one-node template model as `content` and `config.parameterSchema`, sending `config.ifMatch` as `If-Match`, through the operation that exposes the response headers, reporting the `stencilKey`, `status` and `readyAt` it parsed and the `etag` response header exactly as received |
| `mark-stencil-ready` | `POST …/stencils/{stencilKey}/mark-ready`, reporting the stencil's `stencilKey`, `status`, `readyAt` and `parameterSchemaType`, `notFitting` as `templateKey/variantKey:fits` (comma-joined) and `notFittingProblems` (each instance's problems joined with `\|`, the instances with `;`) |
| `list-stencil-usage` | `GET …/stencils/{stencilKey}/usage` with `config.page` and `config.size`, reporting the `templateKeys`, `templateNames` (joined with `\|`), `variantKeys` and `fits` it parsed, `problems` (each entry's joined with `\|`, the entries with `;`), and the page's `pageNumber` and `total` |
| `get-catalog-changes` | `GET …/releases/{release}/changes`, reporting the `base`, `releasable` and `dependenciesChanged` it parsed, `changes` as `type/key:change` (comma-joined, in order), `mergedForReview` as `type/key` of each change whose `mergedForReview` is true (comma-joined, in order, empty when none; absent means false) and `catalogChanges` as `type/key:change` (comma-joined, in order) |
| `mark-catalog-ready` | `POST …/releases/{release}/mark-ready`, reporting what `get-catalog-changes` does |
| `list-environment-deployments` | `GET …/environments/{environmentKey}/deployments`, reporting the `registries`, `catalogKeys`, `releaseIds`, `statuses`, `versions`, `deployedAt`, `lastUsedAt` and `documentsRequested` it parsed (comma-joined, in order, a null value as `null`) and `releaseNames` (joined with `\|`) |
| `list-deployment-history` | `GET …/environments/{environmentKey}/deployment-history` with `config.page` and `config.size`, reporting the `catalogKeys`, `actions`, `releaseIds`, `statuses`, `versions` and `changedAt` it parsed (comma-joined), `releaseNames` and `changedBy` (joined with `\|`), in order, and the page's `total` |

Catalog-scoped actions take the catalog as `config.registry`, `config.namespace` and
`config.catalogKey`, and actions on a release of a catalog (templates, images, dependencies, and the
release operations) take the release as `config.release`: a `releaseId`, or a released
release's version. They pass each to the client exactly as given. In a path the registry is `~` (the server's default registry) or a name in full
(`acme.com`); a client may send `~` as is or percent-encoded as `%7E`, which RFC 3986 makes
equivalent, and the harness records both as `~`. In a body, a `registry` the config does not give
is left unset, never filled in: omitted means the default registry, which only the server knows.

A report that names a catalog writes `registry/namespace/catalogKey`, and a value the response
gave as null (a version, for a result rendered from a draft) as `null` in its place.

A new action also needs an entry in `ACTION_OPERATIONS` in `src/fixtures.mjs`, which names the
operation whose response schema its fixtures are checked against.

## Backends

A scenario names the backend it runs against. All three see the same drivers, because the driver
always addresses this harness — for `prism` the harness proxies, records the journal on the way
through, and turns the upstream's contract violations into failures.

| `backend:` | What it is good for | What it cannot see |
| --- | --- | --- |
| `scripted` (default) | behaviour over time — backoff, streaming, compression, acknowledgement | anything the spec says; it answers from a fixture |
| `prism` | every schema constraint on every operation, with no expectation written | timing, and conventions the spec does not model |

`prism` runs [Prism](https://stoplight.io/open-source/prism) against the bundled spec: it validates
each request and answers from the declared schemas, reporting what it disliked in an `sl-violations`
header the judge prints verbatim. **Any violation fails the scenario** — nothing needs to declare
it, which is the point. It found `GenerateDocumentRequest.attributes` being sent as `null` against a
schema that types it `array`; no hand-written scenario had thought to look, and one had explicitly
tolerated it.

Two things it does *not* check, which is why the scripted scenarios are not redundant: it accepts a
request body sent as plain `application/json`, and it knows nothing about `X-EP-Node-Id` or
`User-Agent`, which the contract deliberately does not model per operation.

Prism starts once per run and only when a scenario needs it, because parsing the spec takes several
seconds. It needs the bundled spec — run `make bundle` first, which `make conformance` does.

## Adding a scenario

Write a YAML file in `scenarios/`. Nothing else changes — the five drivers already implement the
actions, so a new scenario written once is enforced against every client immediately.

```yaml
id: collect-backoff-floor
title: An idle collector backs off, and never polls faster than its minimum interval
why: >
  Say what breaks if the clients disagree. Someone reading a failure a year from now needs to know
  whether to fix the client or the expectation.

action:
  name: collect
  config: { tenantKey: acme-corp, minIntervalMs: 150, runForMs: 1200, ... }

script:              # responses, in order; the last entry repeats
  - status: 200
    contentType: application/vnd.epistola.v2+ndjson
    ndjson: [ { sequence: 1, ... }, { _meta: true, hasMore: true } ]

expect:
  requestCountAtMost: 10
  gaps: { skipFirst: 1, minMs: 130, increasing: true }
```

**Script entries** take `status`, `contentType`, `headers`, and a body as `body` (JSON), `ndjson` (a
list of lines) or `bodyText`; `gzip: true` compresses it and sets `Content-Encoding`. The last entry
repeats, because a polling scenario runs for a wall-clock duration and how many requests it makes is
the thing under test.

**Expectations** are `requestCount` / `requestCountAtMost` / `requestCountAtLeast`, a positional
`requests` list, an `everyRequest` matcher, `gaps`, `jwt`, and `report`. Header and body values match
literally, or as `{matches: regex}`, `{contains}`, `{oneOf}`, `{absent: true}`; bodies also take
`{json: {...}}` for a deep subset, `{jsonAbsent: [keys]}` and `{jsonNullOrAbsent: [keys]}`. An array
in `json` matches element by element, at the same length, each element a deep subset. Query
strings match whole via `query` or per parameter via `queryParams`. A request's `path` is matched
with percent-encoded unreserved characters decoded (`%7E` is recorded as `~`).

A `multipart/form-data` body is matched part by part with `{multipart: {<field>: {...}}}`. Each field
takes `filename`, `contentType` and `value` (the part as text) as value matchers, and `byteLength` and
`sha256` for its content. `{absent: true}` and `{whenPresent: {...}}` work on whole fields. The parts
are split on the raw request bytes, so a binary file part is judged exactly as the client sent it.

`{whenPresent: {...}}` is worth knowing about: it applies the inner matcher only if the value was
sent. Some differences between clients are legitimate — a parameter the contract gives a default for
may be sent explicitly or omitted, and a nullable field may be `null` or absent — and forcing one
spelling would mean fighting three generators for no contract-level gain. What is never legitimate
is a value the contract does not allow, which is what these still catch.

Two more are worth knowing about:

- **`gaps`** is the only evidence of a client's polling policy. `minMs` floors every gap, `skipFirst`
  excludes the deliberate immediate poll after `hasMore`, and `increasing` requires the backoff to
  actually grow. Nothing else catches a collapsed backoff: the results still arrive, just after
  thousands of requests.
- **`jwt`** verifies the tokens rather than pattern-matching them. The harness generates a key pair
  per scenario, hands the driver the private half, and checks the signature with the public half. A
  client that signs ES256 as a DER sequence, or reuses one token across requests, fails here and
  nowhere else.

Use `skip: {<client>: "reason"}` when a scenario genuinely does not apply to a client. It reports as
skipped with the reason rather than passing quietly.

## Fixtures are contract-shaped, and checked

Scripted responses are validated against the spec's response schema when the scenario loads, before
anything is built or run. A wrong fixture does not fail honestly otherwise — it surfaces as five
different clients failing to deserialize, in five dialects, minutes into a run. That happened three
times while these scenarios were being written, and the check found twenty-seven more: every collect
fixture was missing `templateId` and `completedAt` on its result lines and `lastSequence` on its
meta line, which the clients parse leniently enough to have never noticed.

`script` entries whose response is `format: binary` are checked for status and content type only —
there is no schema to hold bytes to. Everything else, including each NDJSON line, is validated
against the schema the clients generate from.

## What it has caught

All four of these were live in released clients, and all four were invisible to those clients' own
test suites — which is the argument for the suite existing:

- **.NET dropped the API base path on result collection.** `HttpClient.BaseAddress` without a
  trailing slash resolves a relative request against the parent, so a base of `…/api` sent polls to
  `/tenants/…`. Every .NET test used `http://localhost/`, a root, where it cannot appear.
- **Python read only the first line of a batch.** urllib3 closes its response once the body is
  exhausted, and `TextIOWrapper` then raised instead of seeing EOF. The batch went unacknowledged
  and was redelivered forever. The collector's tests were all pure functions; the read loop had no
  coverage at all.
- **Kotlin sent `direction=DESC` where the contract declares `enum: [asc, desc]`.** Enum query
  parameters serialize with `toString()`, which returns the constant's name and ignores the
  `@JsonProperty` wire value — on 39 operations, by default, because the parameter defaults to the
  enum constant. Servers that parse the direction case-insensitively accepted it.
- **Python asked only for the success media type.** Its generated `select_header_accept` returns the
  first JSON entry, dropping `application/problem+json` from nearly every operation — so the client
  never asked for the problem document all four are built to parse.
- **Kotlin and .NET erased fields on every partial update.** The generated models cannot distinguish
  "not set" from "explicitly null", and both serializers wrote null for unset properties — so on the
  thirteen `PATCH` operations, where the contract documents null as "clear this", renaming a
  consumer also erased its description, contact and expiry. A 200 came back. Found by Prism
  rejecting the same habit on a field that does not accept null at all, then reproduced directly by
  the `partial-update` scenario.
- **The Jakarta client uploaded nothing, and disclosed a path doing it.** Its three multipart
  operations were generated as `@FormParam("file") File`, which a MicroProfile Rest Client
  implementation sends as `application/x-www-form-urlencoded` — the local path of the file,
  url-encoded, with none of its bytes. Every upload it offered had been broken since the client was
  published, and its own tests could not see it: they assert on what a request carries, and this
  request carried a plausible-looking form.
- **Python asked for none of what a binary download returns.** Its `Accept` override kept only the
  JSON entries an operation declares, and a binary download's only JSON entry is the problem
  document — so `downloadDocument`, `previewDocument` and the two content downloads asked for
  `application/problem+json` alone. The stock generated code picked that same entry, so the earlier
  `Accept` fix above left this half of the defect standing: the scenario that found that one looks at
  a JSON operation, and nothing looked at a binary one until `image-download`.
- **The Kotlin client's generated download methods could not run.** Every `format: binary` operation
  is generated as returning `java.io.File`, and Spring ships no converter that produces one, so
  `downloadDocument` threw `UnknownContentTypeException` — with plain wiring and with the generated
  convenience constructor alike. A hand-written `body(ByteArray::class.java)` call always worked, so
  a consumer who wrote their own download never met it. Nothing in the client's test suite mentioned
  the operation; the other three clients returned the bytes correctly.
- **Three clients refused every response from an older server.** Contract 1.3.0 declared the new
  `slug` required on eleven read models, and no server before 1.3.0 sends it. Kotlin, .NET and
  Python generate a required property as one the response must carry, so each rejected a whole
  template listing from any released Suite; Jakarta and Node.js read it. Their tests only ever saw
  responses shaped by the current spec, which is the one shape an older server never sends. The
  `older-server-response` scenario played one until 2.0.0 removed `id` and required `slug`; its
  successor, `template-listing-by-key`, holds every client to reading the address from `key`
  alone, the name 2.0.0 gives it (#84).
- **The Jakarta client could not read an `ETag`.** Contract 2.0.0 made updates conditional on the
  `ETag` a read answers with, sent back as `If-Match`. A MicroProfile Rest Client method returns its
  body alone, and the generator offers no way to return the response as well, so a caller could send
  `If-Match` but had no tag to send: optimistic locking was unusable from this client. Every scenario
  so far had only checked that `If-Match` goes out; `get-variant-content` was the first to ask for
  the tag back. The other four expose response headers on every operation (`…WithHttpInfo`, `…Raw`).
