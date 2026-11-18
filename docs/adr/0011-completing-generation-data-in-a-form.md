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
4. **A member on `generateDocument` answered with the usual `202`**, the job in a new
   `awaiting-input` status and the form's URL in the response. One operation, one response type,
   one lifecycle: the session *is* the job.

## Decision

Option 4, marked alpha (`x-experimental`) until Suite has implemented it.

### Asking for a form

`GenerateDocumentRequest` gains one optional member, appended after the existing ones: `interactive`
(`InteractiveGenerationRequest`). Sending it is the opt-in; without it, nothing changes. It holds
everything about the form, so later settings are added inside it rather than to the request.

| `interactive.`     | Meaning                                                                                 |
| ------------------ | --------------------------------------------------------------------------------------- |
| `trigger`          | Required. When the user is asked: `incomplete` or `always` (below)                      |
| `expiresInMinutes` | How long the link stays usable. Omitted: the server's default                           |
| `returnUrl`        | Where the browser goes after submitting or cancelling (see "Coming back")               |
| `title`            | A heading for the form, such as the case and the document: "Objection decision, case Z-2026-0412" |
| `instructions`     | Text shown above the fields: what is asked, why, who to ask                             |
| `links`            | Up to five `{label, url}`: back to the case, to a guideline                             |
| `locale`           | The language of the form's own text (buttons, messages). Omitted: the tenant's default locale |

| `interactive.trigger` | The user is asked                                                                     |
| --------------------- | ---------------------------------------------------------------------------------------- |
| `incomplete`          | Only when the data, as sent, lacks a required field or holds an invalid value under the data contract of the release the job is bound to; otherwise the job is `pending` at once |
| `always`              | Every time, with the form pre-filled, so the user reviews what is merged                 |

Two values, because they serve different callers: an unattended process that only wants help when
stuck, and a case worker who should see every letter before it goes. They also give rendering again
its two modes (below).

The check is the analysis `validateTemplateData` runs, applied to the release the job was bound to
at acceptance rather than resolved again, and to the data exactly as sent. Where a preview or a
validation of empty data may fall back to the release's first data example, a job never does: empty
data is an empty form, not someone else's example. Absent optional fields only appear on the form;
they never cause one.

`interactive` needs no permission beyond `DOCUMENT_GENERATE`, which `generateDocument` already requires. A
caller allowed to generate but not to view templates can still be told what is missing.

`generateDocumentBatch` and `previewDocument` do not take it. A form per batch item, or a form
before a synchronous preview, is a different design.

**Expiry: a default, a caller override, a maximum.** The server has a default and a maximum, both
installation settings. A caller sets `expiresInMinutes` per job: short when the user is at the screen
now, a day or more when the form may be finished tomorrow. Above the server's maximum the request is
refused with 400 `validation-error` on `interactive.expiresInMinutes`, code `maximum`, and the message names
the maximum. Refusing rather than shortening it means a caller finds a mismatch with the installation
when it integrates, not when a user finds the link dead the next morning. Whole minutes, not an ISO
8601 duration: an integer every client reads the same way.

**Context, not identity.** `title`, `instructions` and `links` tell the user what this is about and
where it came from, which a form of bare fields cannot. They are what the caller wants to say, shown
as given: plain text, escaped, line breaks kept; no HTML or Markdown. Each `url` is `http` or
`https`, and must pass the same allowlist as `returnUrl`. A link on Epistola's page lends it
Epistola's domain, whoever wrote it. The form does not claim to know who the user is. A caller that
wants that on the page writes it into `instructions` or `title`; who completed the form is the
caller's to record, since it chose whom to give the link.

These texts often name a citizen or a case, so they are personal data under the same retention as
`data`.

### The answer

The usual `202` with `GenerationJobResponse`, which gains `interactive` (`InteractiveGenerationDto`),
appended. So does `DocumentGenerationJobDto`, so `getGenerationJobStatus` and `listGenerationJobs` carry it too.

| `interactive.`  | Meaning                                                                                 |
| --------------- | --------------------------------------------------------------------------------------- |
| `trigger`       | The request's `trigger`, echoed                                                         |
| `url`           | Where to send the user's browser, while the job is `awaiting-input`; null otherwise. A bearer capability (below) |
| `expiresAt`     | When the link stops working and the job is cancelled; null when nothing was asked       |
| `missingFields` | What the form asks for, as `validateTemplateData` reports it (`MissingDataField`); empty when nothing was asked |

When the user must act, the job's `status` is `awaiting-input` and `interactive.url` is set. When nothing
is asked, the job is `pending` and `interactive.url` is null. A caller only checks `interactive.url`.

**`interactive` in the response is the echo.** A server that predates it ignores the request's `interactive`:
Suite binds request bodies with Jackson's defaults, which skip unknown members, and answers an
ordinary `pending` job. A caller that sent `interactive` and gets none back knows the server did not honour
it, rather than learning when the item fails to render. So a server that honours it always returns
it, even when nothing was asked.

This becomes a general rule in "Compatibility within 2.x": *a server may ignore a request member it
does not know; a member whose effect the caller must rely on is echoed in the response.* It
describes what servers do without promising it, so a later server may still refuse unknown members,
and it gives every future member of this kind the same answer.

`GenerationJobStatus` and `GenerationItemStatus` gain `awaiting-input`, appended last. Contract
2.x allows response enums to gain values, and every client reads an unknown value without failing.
Only callers who sent `interactive` create such jobs; others see the value only in `listGenerationJobs`.

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
  the form, `cancelGenerationJob`, or reaching `interactive.expiresAt` moves it to `cancelled`.
  `cancelGenerationJob` therefore accepts an `awaiting-input` job.
- **Progress may be kept, and is not a submission.** The form may save what the user has entered
  and restore it when the link is opened again, until it is submitted, cancelled or expired. How is
  the server's. Only submitting moves the job to `pending`, and nothing saved before that reaches
  the caller.
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
| `caller`       | by `cancelGenerationJob`, with a form or without               |
| `user`         | by the user, on the form                                       |
| `expired`      | because nobody submitted the form before `interactive.expiresAt`      |

A response enum, so it may gain values in a minor version like any other.

### The URL is a capability

Epistola never authenticates the person on the form, and that person needs no Epistola account.
Epistola authenticates the caller, which needs `DOCUMENT_GENERATE` to create the job and could
already render any document with any data. The caller authenticates its own user and decides who may
complete the document, as it decides today who may have one generated. The link delegates the
caller's authority over this one job to that user, and nothing beyond it: not other jobs, not another
template, no lookups.

`interactive.url` carries an unguessable token and nothing else: no tenant credential, no caller identity,
no data. Its rules:

- **Usable until submitted or expired.** It can be opened any number of times until the form is
  submitted, the job cancelled, or `interactive.expiresAt` reached, whichever is first. Reloading the page,
  closing the tab and returning, or a dropped connection costs the user nothing.
- **Submitted once.** The first submission moves the job from `awaiting-input` to `pending`, and
  every later one, from another tab or a replay, is refused with the outcome shown. Changing the data
  afterwards is a new job (see "Rendering again").
- **One link per job, never reissued.** Its lifetime is the job's wait for input.
- **Fetched, not stored.** The caller need not keep the link: `getGenerationJobStatus` returns it
  for as long as the job is `awaiting-input`. A "continue" button asks for it when it is pressed, so
  the link lives in Epistola and in the browser of the user it was handed to, not in the caller's
  database or a message.
- **The expiry bounds a leak.** Whoever holds the link sees the pre-filled data and the preview until
  it expires. The caller hands it only to the authenticated user it is meant for, and does not
  forward it. The page sends no `Referer`, so the token does not travel to links on it.

`interactive.expiresAt` follows from `interactive.expiresInMinutes` or the server's default. Resuming the next day,
with the progress the form kept, is a matter of asking for a long enough expiry, within the maximum
the installation allows.

Fresh links on request were considered: an operation on the job that mints a short-lived link and
revokes the previous one, so a job can wait for days while each link lives for minutes. It is
deferred. It complicates what every caller has to do, and a capped, per-job expiry covers the cases
in sight. It can be appended without breaking anything if callers turn out to need forms that wait
longer than an installation lets a link live.

### Coming back

The request may name `interactive.returnUrl`. After submitting or cancelling, the form sends the browser
there with the job's `requestId` and `status` as query parameters, and nothing else: user input in a
URL ends up in browser history, proxy logs and `Referer` headers. The caller's backend reads the
outcome with its own credential. Without a `returnUrl` the form ends on a page saying the document is
on its way, or was cancelled, and the user closes the tab.

A `returnUrl`, like every `interactive.links` url, must match an origin the server allows; others are
refused with 400 `validation-error` on the field, with a new domain validation code
`url-not-allowed`. Only an authenticated caller sets them, but a page on Epistola's domain that
forwards anywhere a leaked or misused API key chooses is still an open redirect. Where the list is
kept is the server's, and the contract adds no member for it: Suite keeps its other host allowlists
as installation settings, which is enough to start. A list per consumer, the calling system that
sends the job, can be appended to the consumer resource later without breaking anything.

### What the user entered goes back to the caller

`DocumentGenerationItemDto` and the collect stream's `GenerationResult` gain two members, appended
after the existing ones, set only for jobs created with a `interactive`:

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
so a caller can store the input before the PDF exists. While the job is `awaiting-input` they are
null, even when the form has kept the user's progress: a caller must never store input the user did
not confirm as if it were final. A `failed` result still carries them: a template defect must not
cost the user their input.

The collect stream delivers terminal results, today `completed` or `failed`. A caller that only
collects would never learn that the user abandoned the form, so `GenerationResult.status` gains
`cancelled`, delivered only for jobs created with a `interactive`, with its `cancelReason` and `data` null.

### Rendering again

No new mechanism. Because `data` is complete, and holds only what the caller and the user supplied
(defaults are applied again at every render, never stored into it), it is valid input to
`generateDocument` for the same template, and the contract says so:

| The caller wants                   | It sends                                                                      |
| ---------------------------------- | ----------------------------------------------------------------------------- |
| The same document again            | `data` and `variantKey` from the earlier item, no `interactive`                      |
| The user to correct something      | the same with `interactive.trigger: always`: the form opens with their earlier answers  |
| A render against a newer release   | the same with `interactive.trigger: incomplete`: asked only what the new release adds      |

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

`data`, `editedFields` and the texts in `interactive` are personal data. They stay readable for as long as
the job does, and go with it; on a collect line, for as long as the result is kept. Input on a form
that is never submitted, including progress the form kept, is discarded when the job is cancelled or
its link expires.

### Later: choices on the form

The first version asks only for data. The next thing a user will want to decide is the variant: the
case worker knows the citizen wants the letter in English, the caller does not. Bridge ADR 0019 puts
variant selection on its wizard for the same reason. It is not designed here, but the contract above
keeps room for it, and any later choice should follow the same rules:

- **Pre-selected at acceptance, never left open.** `DocumentGenerationItemDto.variantKey` is
  required and not nullable, and making it nullable would break clients that read every job. The
  variant is still resolved when the job is accepted, from `variantKey`, `attributes` or the
  template's default variant, and the user may change it, never empty it. A job is complete at
  every moment; the form only amends it. Suite already resolves an omitted variant this way, to the
  template's default variant in the bound release; the contract does not say so yet, and the change
  that implements this ADR documents it on `GenerateDocumentRequest`.
- **Reported in members the result already has.** The chosen variant is the item's `variantKey`,
  and rendering again sends it back. A choice that needs a new result member is a sign it is the
  wrong kind of choice.
- **Within the bound release.** The user chooses among what the job's release holds, never another
  version.
- **No effect on whether a form appears.** A pre-selected choice is never missing, so `trigger:
  incomplete` still asks only for data; a caller that wants the user to choose sends `always`.
- **Declared in `interactive` and echoed.** Something like `interactive.variantChoice`, appended inside the
  request's `interactive`, and echoed in the response's `interactive`, so a caller can tell a server that
  predates it.

**Granted, not implied by an omitted variant.** Omitting `variantKey` and `attributes` already means
"the default variant", and callers omit them because a template has one variant or because they do
not mind. Reading the omission as "the user decides" would put a choice in front of users that those
callers never meant to offer. The choice is granted explicitly, and the request's own `variantKey` or
`attributes`, or their absence, decide only what is pre-selected.

**Attributes as facets, not free input.** The natural grant is by attribute: the user may vary
`language`, and the request's `attributes` fix the rest (`brand: acme`). Changing attributes freely
can match no variant or several, which `generateDocument` answers with 404 or 409 and a form could
only show as an error. So the form offers the attribute values as facets, limited to combinations a
variant of the bound release has, and every choice resolves to exactly one variant. Granting "any
variant of the template" is the same picker with nothing fixed. A granted attribute is named as
`VariantSelectionAttribute` names one, by `key` and, for an attribute of a dependency catalog,
`catalogKey`, not by a bare string.

For example, a caller that knows the brand and guesses the language sends, as today,
`attributes: [{key: brand, value: gemeente-x}, {key: language, value: nl}]`, which selects the Dutch
variant at acceptance, and grants `interactive.variantChoice: {attributes: [{key: language}]}`. The form
offers the languages that variant's brand has, with Dutch selected; the brand is not offered.
Without the grant the Dutch variant renders and the form shows no choice.

**A member of `interactive`, not a flag on the attribute.** `{key: language, value: nl, userMayChange:
true}` would keep the pre-selection and the grant together, but `VariantSelectionAttribute` is shared
with batch items and preview, where such a flag means nothing, and a caller that names an explicit
`variantKey` could not grant a choice at all.

The data contract belongs to the template, not the variant, so switching variant keeps the form's
fields. It changes the preview, and the optional fields the variant's template reads.

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
  job will actually render, and why `trigger: incomplete` is defined against that binding. So is the
  variant, which is what lets a later variant choice start from a pre-selection.
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
  Because the link stays usable until it is submitted, the page needs no session: the token is
  looked up on every request and posted back with the form, which also keeps it working in an iframe
  where third-party cookies are blocked. Its content security policy (no inline scripts, no framing
  unless embedding is enabled) is why the form is specified as a page of its own.
- **Returning the link from `getGenerationJobStatus`** means the token cannot be kept only as a
  hash: Suite keeps it encrypted, or derives it, so it can hand it out again until the job leaves
  `awaiting-input`.
- **Request data is already kept per job and never cleared after rendering**, and contract defaults
  are applied in memory at render time. `data` is therefore the stored data, and the "before defaults"
  rule above is what Suite has. `editedFields` needs the data as the caller sent it, kept beside the
  amended data when the form is submitted.
- **Collect only knows `completed` and `failed`**, constrained in the database, and cancelling a job
  writes nothing to it today. A `cancelled` line for jobs with a form means widening that constraint
  and emitting a result on cancellation and on expiry. `cancelReason` is a column on the job, written
  by whichever of the three cancels it. Results are written as NDJSON from a hand-built map, so
  `data` and `editedFields` are easy to add there.
- **Nothing expires a job yet.** Suite sets an expiry on finished jobs but nothing acts on it;
  `interactive.expiresAt` needs a sweep that cancels forms nobody completed. Retention otherwise drops whole
  monthly partitions, whatever a job's status, so a job cannot outlive it waiting for input.
- **Unknown members are ignored**, hence `interactive` echoed in the response.
- **Return hosts** have no home yet. Suite's other allowlists are installation settings, and its
  calling systems (`applications`, the contract's consumers) carry no URLs. The expiry's default and
  maximum are installation settings beside them.
- **The tenant already has a default locale**, which is what `interactive.locale` falls back to.

## Consequences

- One member gives callers without a UI a way to finish incomplete requests, and the callers with
  one keep the `validateTemplateData` route.
- Additive within 2.x: optional request members, optional response members, enum values in
  responses. `make append-only` holds; `make breaking` reports the enum additions, which the 2.x
  rules allow.
- Every client changes: the generated types, each README's generation example, and a conformance
  scenario covering `interactive` sent as given and echoed, the `awaiting-input` job with `interactive.url`, the
  link returned again by `getGenerationJobStatus`, `data` and `editedFields` on a collect line, a
  `failed` line that still carries `data`, a `cancelled` line with its `cancelReason`, and `data`
  sent back unchanged.
- The clients generate both modes from one shape: one operation, one request type with an optional
  `interactive`, one response type with nullable members, and no `oneOf` per mode, which the
  generators handle poorly. A caller that never sends `interactive` changes nothing but the
  trailing optional argument a rebuild brings.
- `data` in a result is declared exactly as `GenerateDocumentRequest.data` is (an inline
  `type: object`, nullable), not as a named schema or with `additionalProperties`. The generators
  type the request's `data` as `Any`, `Object` and `object`, but a map-shaped schema as
  `Map<String, Any>`; declared alike, rendering again passes `item.data` into `request.data`
  without a conversion, and the conformance scenario proves the round trip.
- The collect stream is read by hand-written collectors in the Kotlin, Jakarta, .NET and Python
  clients, with their own result types; only the Node.js collector uses the generated one. Those
  four gain `data`, `editedFields` and `cancelReason` by hand, typed as each client types the
  request's `data`, and their documentation stops calling a result "completed or failed". Their
  status is already a string, so a `cancelled` line passes through.
- Suite gains an end-user surface reachable without a session, holding only a token. That is the
  surface to keep small and to review hardest.
- A job can now wait on a person for up to `interactive.expiresAt`. Job counts, dashboards and timeouts
  that assumed a job is always on its way to a worker must allow for `awaiting-input`.
- The Bridge's SmartDocuments wizard can later create a job with `interactive.trigger: always` and redirect to
  Suite's form, instead of keeping a form of its own.
- Suite can use the feature itself. An author can create a job with a form from the editor against a
  draft deployed to a test environment, and see the form a case worker will see before any caller
  integrates. It is the same job path, so the contract needs nothing more for it.

## Settled while drafting

- **One member, not a flag beside a settings object.** A first draft had `prompt` (`never`,
  `missing`, `always`) next to a `form` object whose settings meant nothing without it, a sign they
  are one thing. Sending `interactive` is the opt-in, so the `never` value goes, and the response's
  `interactive` mirrors it and is the echo.
- **`interactive`**, the usual term for documents a person completes as opposed to batch generation.
  As an object it no longer reads as a boolean. `interactiveMode` was considered, but the object
  carries the link, the expiry and the texts, not only a mode. `form` named the page rather than the
  behaviour, and `userInput`, `attended` and `dataEntry` were the other candidates.
- **`trigger: incomplete | always`**: what causes the form. `show` read oddly, `when` is a Kotlin
  keyword the generated client would have to escape, and `missing` became `incomplete`, which also
  covers invalid values.
- **`data`, not `submittedData` or `renderedData`.** Rendering again is then `item.data` into
  `request.data`. `renderedData` would be slightly untrue, since defaults are applied after it.
- **Expiry is a `cancelReason`, not a status.** A separate `expired` status would tell expiry apart
  but not the user's cancel from the caller's; a reason tells all three apart with one status.
- **Allowed origins are a server setting.** The contract fixes only the refusal (`url-not-allowed`,
  for `returnUrl` and every link); a per-consumer list can be appended later.
- **Unknown request members** get a sentence in "Compatibility within 2.x" that describes rather
  than promises, with the echo as the general rule.
- **The link is submitted once and lives until its expiry**, rather than opening once. Reopening it
  is what users do after a reload or a dropped connection, and losing the form to that would be
  worse than the exposure the expiry already bounds.
- **The link is fetched, not stored.** `getGenerationJobStatus` returns it while the job waits, so a
  "continue" button never needs a stored copy.
- **Resuming overnight is a long enough expiry.** The caller sets it per job up to the server's
  maximum, and the form restores the progress it kept. Unlike the Bridge's case (Bridge ADR 0021),
  the caller can offer "continue": it holds the `requestId` and can list its jobs `awaiting-input`.
- **Context instead of identity.** The form shows what the caller says the document is about
  (`title`, `instructions`, `links`), not who the user is. Who completed it is the caller's to record.

## Open questions

None at the time of writing.

## Appendix: a full example

Gemeente X uses a case system. A case worker prepares an objection decision for case Z-2026-0412.
The case system knows the citizen and the address, not the motivation or the decision date.
`interactive.variantChoice` is the later addition, not part of the first version.

**1. The caller submits** (`POST /api/tenants/gemeente-x/documents/generate`, with its API key):

```json
{
  "namespace": "gemeente-x",
  "catalogKey": "letters",
  "templateKey": "objection-decision",
  "attributes": [
    { "key": "brand",    "value": "gemeente-x" },
    { "key": "language", "value": "nl" }
  ],
  "versionRange": "^1.4",
  "environmentKey": "production",
  "data": {
    "caseNumber": "Z-2026-0412",
    "citizen": {
      "name": "J. Jansen",
      "address": { "street": "Kerkstraat 1", "postalCode": "1234 AB", "city": "Sittard" }
    },
    "objectionReceivedOn": "2026-10-02"
  },
  "filename": "besluit-bezwaar-Z-2026-0412.pdf",
  "correlationId": "Z-2026-0412",
  "routingKey": "Z-2026-0412",
  "interactive": {
    "trigger": "incomplete",
    "expiresInMinutes": 1440,
    "returnUrl": "https://zac.gemeente-x.nl/zaken/Z-2026-0412",
    "title": "Besluit op bezwaar, zaak Z-2026-0412",
    "instructions": "Vul de motivering en de besluitdatum in.\nControleer het adres van de bezwaarmaker.",
    "links": [
      { "label": "Open zaak", "url": "https://zac.gemeente-x.nl/zaken/Z-2026-0412" },
      { "label": "Schrijfwijzer bezwaarbesluiten", "url": "https://intranet.gemeente-x.nl/schrijfwijzer" }
    ],
    "locale": "nl-NL",
    "variantChoice": { "attributes": [{ "key": "language" }] }
  }
}
```

Suite binds release `1.4.2` and the Dutch variant, finds `/motivation` and `/decisionDate` missing,
and checks both URLs against the allowed origins.

**2. The answer** (`202 Accepted`, `Location: /api/tenants/gemeente-x/documents/jobs/550e8400-…`):

```json
{
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "awaiting-input",
  "jobType": "single",
  "total": 1,
  "createdAt": "2026-11-18T09:12:00Z",
  "cancelReason": null,
  "interactive": {
    "trigger": "incomplete",
    "url": "https://suite.gemeente-x.nl/forms/k3J9fQ2xV7mN0pL8sR4tY6wZ1aB5cD3e",
    "expiresAt": "2026-11-19T09:12:00Z",
    "missingFields": [
      { "path": "/motivation",         "required": true,  "schema": { "type": "string", "title": "Motivering" } },
      { "path": "/decisionDate",       "required": true,  "schema": { "type": "string", "format": "date" } },
      { "path": "/citizen/salutation", "required": false, "schema": { "type": "string", "enum": ["Geachte heer", "Geachte mevrouw", "Geachte heer/mevrouw"] } }
    ]
  }
}
```

The case system keeps the `requestId`, shows "letter waiting for input" with a **Continue** button,
and opens `interactive.url` in a new tab. **Continue**, pressed the next morning, fetches the same link with
`getGenerationJobStatus`; the form still holds what the user typed.

**3. The user submits.** They fill in the motivation and the date, pick a salutation, correct the
street to "Kerkstraat 11", and (later) switch the language to English. Suite validates the input,
moves the job to `pending`, and sends the browser to
`https://zac.gemeente-x.nl/zaken/Z-2026-0412?requestId=550e8400-…&status=pending`.

**4. The result**, on the collect stream as today (shown here formatted, one NDJSON line on the
wire):

```json
{
  "sequence": 1763456000001,
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "completed",
  "documentId": "770e8400-e29b-41d4-a716-446655440002",
  "correlationId": "Z-2026-0412",
  "routingKey": "Z-2026-0412",
  "registry": "epistola.gemeente-x.nl",
  "namespace": "gemeente-x",
  "catalogKey": "letters",
  "templateKey": "objection-decision",
  "variantKey": "objection-decision-en-gx",
  "version": "1.4.2",
  "filename": "besluit-bezwaar-Z-2026-0412.pdf",
  "contentType": "application/pdf",
  "sizeBytes": 91234,
  "error": null,
  "completedAt": "2026-11-19T08:40:07Z",
  "cancelReason": null,
  "data": {
    "caseNumber": "Z-2026-0412",
    "citizen": {
      "name": "J. Jansen",
      "salutation": "Dear Sir or Madam",
      "address": { "street": "Kerkstraat 11", "postalCode": "1234 AB", "city": "Sittard" }
    },
    "objectionReceivedOn": "2026-10-02",
    "motivation": "The objection is well-founded because …",
    "decisionDate": "2026-11-19"
  },
  "editedFields": ["/citizen/salutation", "/citizen/address/street", "/motivation", "/decisionDate"]
}
```

The same `data` and `editedFields` are on the item from `listGenerationJobItems` as soon as the job
is `pending`.

**When it does not go through.** Nobody submits before `interactive.expiresAt`: the job is `cancelled`
with `cancelReason: expired`, the saved input is discarded, and collect delivers a `cancelled` line
with `data` null. The user presses cancel: `user`, and the browser returns with `status=cancelled`.
The caller cancels: `caller`. Rendering fails after submission: `failed`, with `data` still present.

**A refused request:**

```json
{
  "type": "https://epistola.app/errors/validation-error",
  "title": "Validation failed",
  "status": 400,
  "errors": [
    { "field": "interactive.returnUrl", "code": "url-not-allowed", "message": "https://zac.example.com is not an allowed origin" },
    { "field": "interactive.expiresInMinutes", "code": "maximum", "message": "at most 1440 minutes" }
  ]
}
```

**A server that predates this** answers `"status": "pending"` and no `interactive`; the caller falls back.

**5. A correction next week**, from the stored result:

```json
{
  "namespace": "gemeente-x",
  "catalogKey": "letters",
  "templateKey": "objection-decision",
  "variantKey": "objection-decision-en-gx",
  "versionRange": "1.4.2",
  "environmentKey": "production",
  "data": { "…": "the data from the earlier item, unchanged" },
  "correlationId": "Z-2026-0412-v2",
  "interactive": {
    "trigger": "always",
    "expiresInMinutes": 60,
    "returnUrl": "https://zac.gemeente-x.nl/zaken/Z-2026-0412",
    "title": "Correctie besluit op bezwaar, zaak Z-2026-0412",
    "instructions": "Corrigeer de besluitdatum."
  }
}
```

The form opens with every earlier answer, in English, against the same release. Without `interactive` the
same request renders the identical letter at once.
