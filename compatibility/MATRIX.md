# Epistola compatibility matrix

<!-- Generated from `compatibility/aggregate.json` by `compatibility/render.sh`. Do not edit by hand. -->

Anchor: **epistola-contract** (the wire contract every artifact speaks). Each artifact publishes its own `compatibility.json` feed; this matrix joins those feeds with [`compatibility-log.json`](../compatibility-log.json) (which contract releases broke which operations).

A pairing is judged **operation-level** when possible — incompatible only if a breaking contract release between the client's target and the server's contract touches an operation the client uses — and falls back to the **range** rule (`floor <= target <= serverContract`) otherwise. The _Judged by_ column says which rule decided each row.

_No rows: no feeds could be read. See `compatibility/feeds.txt`._

### Legend

- **Judged by** — `operations`: verdict from the breaking-change log joined with the client's declared operations. `range`: the coarse fallback rule (the client declared no operations, or the log does not cover the whole version window).
- **Floor** — the newest breaking contract release at or below the server's contract version, derived from the log (never hand-maintained here).
- Verification that a published suite image really serves what it declares lives in `epistola-suite` (`compatibility/smoke.sh`); this matrix is the judged view over declarations.
