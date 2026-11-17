# ADR 0011: Completing generation data in a form

- **Status:** Proposed
- **Date:** 2026-11-17
- **Scope:** API contract (`contracts/api`), contract 2.x (additive, alpha)
- **Related:** Epistola Bridge ADRs 0019 (complete the document on the wizard page), 0020 (a
  caller's handle never leaves the Bridge), 0021 (saving a half-filled wizard form, rejected)

## Context

A caller asks for a document with the data it has. Often that is not all the template needs: the
case knows the citizen and the address, but not the paragraph of motivation, the deadline the case
worker agreed on the phone, or the reference the other party used. Today the caller has two
choices, and both are poor:

- **Submit anyway.** `generateDocument` does not check data when it accepts a request; the item
  fails when it renders, and the person who could have supplied the value is long gone.
- **Build a form itself.** `validateTemplateData` already reports `missingFields` and
  `invalidFields`, each with a JSON Pointer and the field's JSON Schema, so a caller with its own
  UI can render exactly those fields and submit again. Many callers have no UI suited to it, and
  every one that builds it builds the same thing.

The request we keep hearing is the second, done once: the caller submits, and when data is missing
it gets a URL to send the user's browser to, where Epistola asks for what is missing.

Epistola Bridge already does this for one protocol. Its SmartDocuments adapter must hand the user's
browser to a page it controls, and Bridge ADR 0019 made that page a form derived from the template's
schema, pre-filled from the case, with generation deferred until the form is submitted. What it
learned is the starting point here: the URL is a bearer capability, user input is personal data
that needs a retention rule, every field is editable because the user is accountable for the
document, and a draft that cannot be listed by the caller is worse than none (Bridge ADR 0021).

The caller that sent the request also wants what the user entered back, not only the PDF, so it
can keep it in its own records, and it wants to render again later without asking the user twice.

## Options

1. **Leave it to callers.** Document the `validateTemplateData` pattern and stop. Costs nothing in
   the contract, and solves nothing for callers without a UI.
2. **A separate resource**, `POST …/generation/sessions`, with its own lifecycle (awaiting input,
   submitted, cancelled, expired) that becomes a generation job on submit. Clean, but a second
   lifecycle next to the job's, a second set of operations in five clients, and a second thing to
   track and correlate for every caller.
3. **A flag on `generateDocument` answered with `302 Found`** and the form's URL in `Location`.
   Small, but the wrong mechanism:
   - The caller is a backend holding an API key, not the browser. The URL is data the backend
     forwards to its own frontend; a redirect tells the HTTP client to repeat the request.
   - The five clients react differently. None configures redirects, so each uses its library's
     default: some follow the redirect and try to parse the form's HTML as JSON, others surface a
     3xx, and the generators treat 3xx as an error, so success would arrive as an exception.
   - A 302 has no body: no `requestId`, no expiry, no way to cancel, and the caller never learns
     that the user closed the tab.
4. **A flag on `generateDocument` answered with the usual `202`**, the job in a new
   `awaiting-input` status and the form's URL as a member. One operation, one response type, one
   lifecycle: the session *is* the job.

## Decision

Option 4, marked alpha (`x-experimental`) until Suite has implemented it.

### Asking for a form

`GenerateDocumentRequest` gains an optional member, appended after the existing ones: `prompt`,
whether the user is asked to complete the data before rendering.

| `prompt`          | Meaning                                                                                  |
| ----------------- | ---------------------------------------------------------------------------------------- |
| `never` (default) | Today's behaviour: the job is `pending` at once                                          |
| `missing`         | A form only when the data, as sent, lacks a required field or holds an invalid value under the data contract of the release the job is bound to; otherwise `pending` at once |
| `always`          | A form every time, pre-filled, so the user reviews what is merged                        |

The check is the analysis `validateTemplateData` runs, applied to the release the job was bound to
at acceptance rather than resolved again, and to the data exactly as sent. Where a preview or a
validation of empty data may fall back to the release's first data example, a job never does: empty
data is an empty form, not someone else's example. Absent optional fields only appear on the form;
they never cause one.

`prompt` needs no permission beyond `DOCUMENT_GENERATE`, which `generateDocument` already requires.
A caller allowed to generate but not to view templates can still be told what is missing.

An enum rather than a boolean, because `missing` and `always` serve different callers: an
unattended process that only wants help when stuck, and a case worker who should see every letter
before it goes. It also gives re-rendering its two modes (below).

`generateDocumentBatch` and `previewDocument` do not take it. A form per batch item, or a form
before a synchronous preview, is a different design.

### The answer

The usual `202` with `GenerationJobResponse`. When the user must act, the job's `status` is
`awaiting-input`, and three members appended to `GenerationJobResponse` are set:

| Member           | Meaning                                                                                |
| ---------------- | -------------------------------------------------------------------------------------- |
| `inputUrl`       | Where to send the user's browser. A bearer capability (below)                          |
| `inputExpiresAt` | When the URL stops working and the job is cancelled                                    |
| `missingFields`  | What the form will ask for, as `validateTemplateData` reports it (`MissingDataField`) |

When nothing is asked, they are null and the job is `pending`. A caller only checks whether
`inputUrl` is set.

The response also echoes `prompt`. A server that predates this member ignores it: Suite binds request
bodies with Jackson's defaults, which skip unknown members, and answers an ordinary `pending` job. A
caller that sent `prompt` and gets none back knows the server did not honour it, rather than learning
when the item fails to render. The echo is the only signal it gets, so it is required whenever the
server honoured the member, `never` included.

This becomes a general rule in "Compatibility within 2.x": *a server may ignore a request member it
does not know; a member whose effect the caller must rely on is echoed in the response.* It
describes what servers do without promising it, so a later server may still refuse unknown members,
and it gives every future member of this kind the same answer.

`GenerationJobStatus` and `GenerationItemStatus` gain `awaiting-input`, before `pending`. Contract
2.x allows response enums to gain values, and every client reads an unknown value without failing.
Only callers who sent `prompt` create such jobs; others see the value only in `listGenerationJobs`.

### The form

Epistola Suite serves the form. It already has what a good one needs: the release's data contract,
the renderer (so the form can preview), editors for rich text and arrays, and the tenant. The
contract fixes what the form must do, not how it looks:

- **Derived from the data contract** of the release the job is bound to, pre-filled from `data`,
  and validated against that contract on the server when submitted. The browser may help; it is not
  trusted.
- **Every field editable**, including values the caller supplied. The user is accountable for the
  document; a caller that needs read-only pre-fill asks for it in a later revision.
- **Nothing renders until the user submits.** Submitting moves the job to `pending`; cancelling on
  the form, `cancelGenerationJob`, or reaching `inputExpiresAt` moves it to `cancelled`.
  `cancelGenerationJob` therefore accepts an `awaiting-input` job.
- **A page of its own.** The caller opens it as a top-level page or in a new tab. An installation
  that does not allow embedding refuses to be framed, so a caller cannot count on an iframe.

### Why a job was cancelled

A caller acts differently on "the user declined", "nobody came" and "we cancelled it ourselves": it
drops the first, reminds someone of the second, and already knows the third. One status with a
reason says all three without a status per cause. `GenerationJobResponse`, `DocumentGenerationJobDto`,
`DocumentGenerationItemDto` and `GenerationResult` gain `cancelReason`, appended, set whenever the
status is `cancelled` and null otherwise:

| `cancelReason` | The job was cancelled                                          |
| -------------- | -------------------------------------------------------------- |
| `caller`       | by `cancelGenerationJob`, prompted or not                      |
| `user`         | by the user, on the form                                       |
| `expired`      | because nobody submitted the form before `inputExpiresAt`      |

A response enum, so it may gain values in a minor version like any other.

### The URL is a capability

`inputUrl` carries an unguessable token and nothing else: no tenant credential, no caller identity,
no data. Whoever holds it can complete this one job, and nothing more: not read other jobs, not
choose another template, not look anything up. It works until `inputExpiresAt` (an installation
setting, of the order of an hour) or until the form is submitted or the job cancelled, whichever is
first, and it is never reissued for the same job. The caller should hand it to the authenticated
user it was minted for, and not store it.

### Coming back

The request may name a `returnUrl`, appended to `GenerateDocumentRequest`. After submitting or
cancelling, the form sends the browser there with the job's `requestId` and `status` as query
parameters, and nothing else: user input in a URL ends up in browser history, proxy logs and
`Referer` headers. The caller's backend reads the outcome with its own credential.

A `returnUrl` must match an origin the server allows; others are refused with 400 `validation-error`,
the field `returnUrl`, and a new domain validation code `return-url-not-allowed`. Without that, a
form any token-holder can open is an open redirect. Where the list is kept is the server's, and the
contract adds no member for it: Suite keeps its other host allowlists as installation settings, which
is enough to start. A list per consumer, the calling system that sends the job, can be appended to
the consumer resource later without breaking anything.

### What the user entered goes back to the caller

`DocumentGenerationItemDto` and the collect stream's `GenerationResult` gain two members, appended
after the existing ones, set only for jobs created with a `prompt`:

| Member         | Meaning                                                                                          |
| -------------- | ------------------------------------------------------------------------------------------------ |
| `data`         | The complete data the item renders from: what the caller sent, as amended on the form, before the data contract's defaults are applied |
| `editedFields` | JSON Pointers where `data` differs from what the caller sent, including fields the user cleared |

The whole object, not only the gaps: the caller may not have kept its request, and members the data
contract does not declare are passed through unchanged. `editedFields` lets the caller tell a value
the user supplied from a value the user overwrote, and decide per field whether to write it back to
its own records. `version` and `releaseId` on the same item say which data contract `data` conforms
to.

They are readable from the moment the user submits (`pending`), through `listGenerationJobItems`,
so a caller can store the input before the PDF exists. A `failed` result still carries them: a
template defect must not cost the user their input.

The collect stream delivers terminal results, today `completed` or `failed`. A caller that only
collects would never learn that the user abandoned the form, so `GenerationResult.status` gains
`cancelled`, delivered only for jobs created with a `prompt`, with its `cancelReason` and `data`
null.

### Rendering again

No new mechanism. Because `data` is complete, and holds only what the caller and the user supplied
(defaults are applied again at every render, never stored into it), it is valid input to
`generateDocument` for the same template, and the contract says so:

| The caller wants                                   | It sends                                              |
| -------------------------------------------------- | ----------------------------------------------------- |
| The same document again                            | `data` from the earlier item, no `prompt`             |
| The user to correct something                      | that `data` with `prompt: always`: the form opens with their earlier answers |
| A render against a newer release                   | that `data` with `prompt: missing`: asked only what the new release adds |

`versionRange` decides as always: pinned to the earlier `version` for an identical document, open to
pick up a template fix. A caller whose own records changed meanwhile overlays the earlier `data` at
`editedFields` onto its fresh data, so the user's work survives and the caller's records win where
the user did not touch them.

Resubmitting by reference (a `fromRequestId` member) is deliberately not offered. It would make
Epistola the store of user input, with a retention that outlives the job, and turn a request id into
a key to a person's data. Bridge ADR 0021 reached the same conclusion for drafts: a record that a
document was started belongs to the caller. It can be appended later if callers that keep nothing
turn out to matter.

### Retention

`data` and `editedFields` are personal data entered by a user. They stay readable for as long as the
job does, and go with it; on a collect line, for as long as the result is kept. Input on a form that
is never submitted is discarded when the job is cancelled.

## Fit with Suite

Checked against Epistola Suite's generation code on 2026-11-17, to make sure the contract asks for
something Suite can build without bending it. Nothing here binds Suite; it records why the shape
above is the one chosen.

- **The new status costs a migration, not a redesign.** A job is one row with its status in a
  checked column. Workers claim only `PENDING` rows (`FOR UPDATE SKIP LOCKED`), and the stale-job
  recovery touches only `IN_PROGRESS`, so an `awaiting-input` row is ignored until the form moves it
  to `PENDING`, after which it is picked up like any other. Suite maps its statuses to the contract's
  by name, so the contract value has to exist first.
- **The release is bound at acceptance**, which is what lets the form be derived from the release the
  job will actually render, and why `prompt: missing` is defined against that binding.
- **The analysis exists and is reusable.** `validateTemplateData`'s analysis is a component that takes
  a contract and data, and Suite's own documentation expects generation to use it. Its endpoint
  resolves the release again, requires `TEMPLATE_VIEW`, and substitutes a data example for empty data;
  the decision above avoids all three for jobs.
- **Suite already has the form.** The data contract editor's example form renders a JSON Schema as
  inputs, including arrays and rich text, with errors per path: more than the Bridge's wizard, which
  leaves arrays out. It is bundled in the editor today and would need its own entry for a page
  outside the editor.
- **The page is Suite's first public one.** No page or endpoint outside the API and the authenticated
  UI is reachable today, so the form needs its own route with token authentication. Suite's hashed
  API keys and its hashed, expiring OAuth state already show the pattern for storing the token.
  Its content security policy (no inline scripts, no framing unless embedding is enabled) is why the
  form is specified as a page of its own.
- **Request data is already kept per job and never cleared after rendering**, and contract defaults
  are applied in memory at render time. `data` is therefore the stored data, and the "before defaults"
  rule above is what Suite has. `editedFields` needs the data as the caller sent it, kept beside the
  amended data when the form is submitted.
- **Collect only knows `completed` and `failed`**, constrained in the database, and cancelling a job
  writes nothing to it today. A `cancelled` line for prompted jobs means widening that constraint and
  emitting a result on cancellation and on expiry. `cancelReason` is a column on the job, written by
  whichever of the three cancels it. Results are written as NDJSON from a hand-built
  map, so `data` and `editedFields` are easy to add there.
- **Nothing expires a job yet.** Suite sets an expiry on finished jobs but nothing acts on it;
  `inputExpiresAt` needs a sweep that cancels forms nobody completed. Retention otherwise drops whole
  monthly partitions, whatever a job's status, so a job cannot outlive it waiting for input.
- **Unknown members are ignored**, hence the echoed `prompt`.
- **Return hosts** have no home yet. Suite's other allowlists are installation settings, and its
  calling systems (`applications`, the contract's consumers) carry no URLs.

## Consequences

- One flag gives callers without a UI a way to finish incomplete requests, and the callers with one
  keep the `validateTemplateData` route.
- Additive within 2.x: optional request members, optional response members, enum values in
  responses. `make append-only` holds; `make breaking` reports the enum additions, which the 2.x
  rules allow.
- Every client changes: the generated types, each README's generation example, and a conformance
  scenario covering `prompt` and its echo, the `awaiting-input` job with `inputUrl`, `data` and
  `editedFields` on a collect line, a `failed` line that still carries `data`, a `cancelled` line
  with its `cancelReason`, and `data` sent back unchanged.
- Suite gains an end-user surface reachable without a session, holding only a token. That is the
  surface to keep small and to review hardest.
- A job can now wait on a person for up to `inputExpiresAt`. Job counts, dashboards and timeouts
  that assumed a job is always on its way to a worker must allow for `awaiting-input`.
- The Bridge's SmartDocuments wizard can later create a job with `prompt: always` and redirect to
  Suite's form, instead of keeping a form of its own.

## Settled while drafting

- **`prompt`, not `interactive`.** It reads with its values (`prompt: missing`), and an
  `interactive` member suggests a boolean, which cannot say `missing` from `always`. OpenID
  Connect's `prompt` parameter likewise decides whether a user is asked.
- **`data`, not `submittedData` or `renderedData`.** Rendering again is then `item.data` into
  `request.data`. `renderedData` would be slightly untrue, since defaults are applied after it.
- **Expiry is a `cancelReason`, not a status.** A separate `expired` status would tell expiry apart
  but not the user's cancel from the caller's; a reason tells all three apart with one status.
- **Return hosts are a server setting.** The contract fixes only the refusal; a per-consumer list
  can be appended later.
- **Unknown request members** get a sentence in "Compatibility within 2.x" that describes rather
  than promises, with the echo as the general rule.

## Open questions

- **The user's identity.** The form knows only the token. A caller that must record who completed it
  supplies that itself; whether the request should carry a display name for the form to show is
  open.
