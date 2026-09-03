<!--
SPDX-FileCopyrightText: Epistola Nederland B.V.

SPDX-License-Identifier: EUPL-1.2
-->

# Client conformance suite

Four clients — Kotlin/Spring, Jakarta EE, .NET, Python — talk to one server. Their internals differ
and should: each is idiomatic for its platform, and each generates its models with a different
generator. What must *not* differ is what the server sees. This suite pins that.

```bash
pnpm install                       # once
node src/run.mjs --client kotlin   # kotlin | jakarta | dotnet | python
node src/run.mjs --list            # what the scenarios are
node src/run.mjs --client python --scenario collect-backoff-floor
```

Or `make conformance` from the repository root, which runs all four.

## How it works

```
scenarios/*.yaml   the expectations — one file per behaviour, shared by all four clients
src/server.mjs     a scripted Epistola API that records every request it is sent
src/expect.mjs     the judge: evaluates a scenario's `expect` against that record
src/run.mjs        the harness: per scenario, start the server, run a driver, judge, report
drivers/<client>/  a thin executable per client
```

For each scenario the harness starts a server on a loopback port, plays the scenario's scripted
responses back one per request, and keeps a journal of what arrived — method, path, headers, body,
arrival time. When the driver finishes, the journal is judged against the scenario's `expect`.

**The drivers assert nothing.** A driver asks the server what to do, does it with the published
client, and says when it is done. Every expectation lives in `scenarios/`, so adding one holds all
four clients to it at once — the thing four separate test suites cannot do, because they drift.

## The driver contract

A driver is two executables, `prepare.sh` (build; runs once per suite) and `run.sh <baseUrl>` (run
one scenario). `run.sh` gets only a base URL, and asks the server for the rest:

| | |
| --- | --- |
| `GET /__conformance/action` | `{scenario, action, config}` — what to do and how to configure the client |
| `POST /__conformance/report` | a flat JSON object of what the client surfaced, for scenarios that assert on it |
| `POST /__conformance/done` | `{}`, or `{"error": "…"}` if the driver could not complete |

Control-plane traffic is not journalled, so it cannot be mistaken for API traffic. Report values are
strings and numbers only — a list is joined with commas — so that no driver needs a serializer for
its own types.

The base URL addresses the server's root; a driver appends `/api` itself, because that base path is
part of the contract's `servers` entry and a client that drops it is a client with a bug. Exactly
that turned up in the .NET client on this suite's first run.

Four actions cover the scenarios. A driver implements these, and nothing else:

| Action | What the driver does |
| --- | --- |
| `ping` | `POST /ping` with client metadata, through the generated API |
| `list-templates` | `GET …/templates`, `config.repeat` times |
| `collect` | build a `ResultCollector` from `config`, run it for `config.runForMs`, stop it, report what it handled |
| `problem` | make a request the server answers with a problem, report the parsed slug and members |

## Adding a scenario

Write a YAML file in `scenarios/`. Nothing else changes — the four drivers already implement the
actions, so a new scenario written once is enforced against every client immediately.

```yaml
id: collect-backoff-floor
title: An idle collector backs off, and never polls faster than its minimum interval
why: >
  Say what breaks if the clients disagree. Someone reading a failure a year from now needs to know
  whether to fix the client or the expectation.

action:
  name: collect
  config: { tenantId: acme-corp, minIntervalMs: 150, runForMs: 1200, ... }

script:              # responses, in order; the last entry repeats
  - status: 200
    contentType: application/vnd.epistola.v1+ndjson
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
`{json: {...}}` for a deep subset and `{jsonAbsent: [keys]}`.

Two are worth knowing about:

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

## Fixtures are contract-shaped

Scripted responses must be valid against the spec, not merely parseable. The clients type their
models from the same schemas, and they are not equally forgiving: `requestId` is `format: uuid`, so
the Kotlin client (which types it as `String`) accepted `req-501` while the Jakarta client (which
types it as `UUID`) did not. If a fixture only works on some clients, the fixture is wrong.

## What it has caught

Both of these were live in released clients, and both were invisible to the clients' own test
suites — which is the argument for the suite existing:

- **.NET dropped the API base path on result collection.** `HttpClient.BaseAddress` without a
  trailing slash resolves a relative request against the parent, so a base of `…/api` sent polls to
  `/tenants/…`. Every .NET test used `http://localhost/`, a root, where it cannot appear.
- **Python read only the first line of a batch.** urllib3 closes its response once the body is
  exhausted, and `TextIOWrapper` then raised instead of seeing EOF. The batch went unacknowledged
  and was redelivered forever. The collector's tests were all pure functions; the read loop had no
  coverage at all.
