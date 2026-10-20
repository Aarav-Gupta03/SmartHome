# Smart Home IoT Data Engine — Implementation Plan

## Status of this document
This plan reflects the redesigned system (dual fire/intrusion logic,
exit-aware locking, and 4 additional agreed features) that was designed in
planning conversations but **not yet implemented in code**. The existing
`smart-home-fullstack` codebase currently reflects an older, simpler design
(single "lockdown" scenario that locks all doors on fire — a known safety
flaw being fixed in Phase 2 below).

## Ground rules for working through this plan
- Work happens **one phase at a time**, in order, unless explicitly told to skip ahead.
- A phase is not "started" until the user explicitly approves starting it.
- A phase is not "done" until the user has reviewed and confirmed it.
- Do not begin a later phase's code while "finishing up" an earlier one.
- After finishing a phase: stop, summarize exactly what changed, and wait
  for confirmation before touching the next phase.
- See `rules.md` for the full boundary rules that apply throughout all phases.

---

## Phase 0 — Baseline (already complete)

MongoDB time-series collection, schema validation, compound + TTL indexing,
`$setWindowFields` + `$merge` aggregation, change streams, single ACID
lockdown transaction (old "lock everything" version), REST + WebSocket
layer, React dashboard ("The Panel" theme), replica-set topology check,
sharding-attempt endpoint.

**Status: DONE** — exists in the current `smart-home-fullstack` zip.

---

## Phase 1 — Data model upgrades

- Add `isExitRoute: boolean` field to `SMART_LOCK` documents; update the
  `$jsonSchema` validator to include it.
- Add a `home_status` collection/document: `{ mode: "HOME" | "AWAY" }`.
- Add `batteryLevel: number` field to all device documents; update the
  validator accordingly.
- Seed realistic demo lock data: e.g. `front_door` / `back_door`
  (`isExitRoute: true`), `garage_internal_door` / `wine_cellar_door`
  (`isExitRoute: false`).

**Deliverable:** updated `DatabaseInitializer` + a small seed routine.
**Status: IMPLEMENTED — awaiting your review before Phase 2 starts**

---

## Phase 2 — Fire vs. Intrusion logic split (safety fix)

- Replace the single `LockdownService.triggerLockdown()` with two distinct handlers:
  - `handleFireEvent()` — unlock all `isExitRoute: true` doors, force-lock all
    `isExitRoute: false` doors, insert an audit log with `type: "FIRE_EVACUATION"`
    recording which doors were unlocked/secured.
  - `handleIntrusionEvent()` — lock every door regardless of `isExitRoute`,
    insert an audit log with `type: "INTRUSION_LOCKDOWN"`.
- Update `ChangeStreamListener` to route based on `deviceType` and condition:
  - `TEMPERATURE_SENSOR` + reading over threshold → fire handler.
  - `MOTION_SENSOR` + `home_status.mode == "AWAY"` → intrusion handler.
- Both handlers keep the existing `WriteConcern.MAJORITY` / `ReadConcern.SNAPSHOT` transaction setup.
- WebSocket messages carry a `type` field: `"FIRE"`, `"INTRUSION"`, or `"NORMAL_READING"`.

**Status: DONE** — this is the core safety fix; treat as highest priority after Phase 1.

---

## Phase 3 — Home/Away toggle

- REST endpoint to set `home_status.mode`.
- Dashboard toggle switch (HOME/AWAY).
- Instant propagation to all connected clients via the existing WebSocket channel.
- Motion-event handling checks `home_status.mode` before deciding intrusion vs. normal.

**Status: DONE**

---

## Phase 4 — Autonomous scheduled generator

- Spring `@Scheduled` task generating realistic readings automatically **by
  default** (not gated behind an opt-in "Auto Mode" toggle like the current
  baseline).
- Manual per-device controls remain available for live demo purposes.

**Status: SKIPPED**

---

## Phase 5 — Battery / device health tracking

- `batteryLevel` drains gradually over time via the scheduled task.
- Devices below a threshold are flagged `offline` and excluded from
  fire/intrusion decision-making.
- A device that stops sending *any* reading at all (not even a "no motion"
  heartbeat) for an unusually long time is also flagged as possibly offline
  — silence is not treated as "all clear."

**Status: DONE**

---

## Phase 6 — Historical event timeline view

- Dashboard page/component listing `audit_logs` sorted by time.
- REST endpoint with basic pagination.

**Status: DONE**

---

## Phase 7 — Text index search on audit logs

- Create a text index on `audit_logs`.
- Search box in the timeline view + a REST endpoint to query it.

**Status: DONE**

---

## Phase 8 — TTL-based rate limiting / suspicious sensor detection

- Short-TTL collection tracking recent write events per sensor (sliding window).
- Flag a sensor as suspicious/malfunctioning if it writes abnormally fast.

**Status: DONE**

---
## Phase 9 — Polish & documentation

- Update `README.md` and the DBMS abstract PDF to match the final,
  actually-implemented system.
- Final end-to-end review pass.

**Status: DONE**

### Documentation Talking Points (DO NOT SKIP THESE)

#### 1. MongoDB Text Index & Tokenizer Behaviour
- We implemented a wildcard `$**` text index on `audit_logs` (Phase 7).
- MongoDB's text tokenizer treats underscores (`_`) as word characters, not separators.
  - `INTRUSION_LOCKDOWN` is stored as a single token, not two words.
  - Searching `intrusion_lockdown` works instantly; searching `intrusion` alone returns nothing.
- This is a critical difference vs. a regex (`$regex`) scan, which does a full collection scan with no index — O(N) vs. O(log N).
- Great presentation soundbite: *"We deliberately chose a Text Index over regex to demonstrate database-aware design. A regex scan on millions of audit rows is a classic IoT anti-pattern."*

#### 2. Rate Limiter — Architecture & Sliding Window
Include the full architecture flowchart from the Phase 8 implementation plan showing:
- Sensor write → `RateLimiterService.checkAndRecord()` → TTL collection count check → allow/block.
- Safety override path (fire/intrusion always allowed even when sensor is quarantined).
- Auto-release path when TTL expires.

Include the **sliding window timeline** illustration:
```
T=+0s  Event #1 → count=1 ✅
T=+1s  Event #2 → count=2 ✅
T=+5s  Event #5 → count=5 ⚠️ AT LIMIT
T=+6s  Event #6 → count=6 🚨 QUARANTINED
T=+10s MongoDB auto-deletes Event #1 → count=5
T=+11s count=4 → auto-release fires ✅
```

#### 3. Rate Limiter — MongoDB TTL Index as Sliding Window
- Key insight to highlight in the abstract: **"The sliding window is implemented entirely as a MongoDB TTL index. No scheduled jobs, no manual cleanup, no cron. The database self-manages the event window."**
- This is a strong demonstration of advanced MongoDB indexing beyond just query performance.
- TTL index runs a background thread every ~60 seconds to expire documents.

#### 4. Rate Limiter — Safety Override (Critical Design Decision)
- Even a quarantined sensor is allowed to emit fire-temperature readings and intrusion events.
- This is a hard safety rule: blocking a smoke detector because it writes too fast could be lethal.
- The system records `RATE_LIMIT_SAFETY_OVERRIDE` events in `audit_logs` for traceability.

#### 5. Rate Limiter — Audit Trail Integration (Phases 7 + 8 working together)
- `RATE_LIMIT_QUARANTINE` events are written to the same `audit_logs` collection as fire and intrusion events.
- Because of the Phase 7 text index, you can search for `rate_limit` in the Timeline modal and instantly retrieve all past quarantine events — two phases complementing each other.

#### 6. Quarantine UI Design
- Amber pulsing card glow (distinct from: grey=offline, red=fire alarm).
- `⚡ RATE LIMITED` badge, `QUARANTINED` readout, all controls blocked.
- System-level `AlertBanner` at the top of the page.
- `✓ Release Sensor` button with a tooltip explaining why it was blocked.
- Stats pill: "Quarantined N times" from historical `audit_logs` count.

#### 7. Overall DBMS Features Used (summary for abstract)
| Feature | Where Used |
|---|---|
| Time-Series Collection | `sensor_data` — all temperature, lock, motion readings |
| TTL Index | `sensor_data` (7-day expiry) + `sensor_rate_events` (10-second sliding window) |
| Compound Index | `sensor_data`, `sensor_rate_events`, `sensor_averages_daily` |
| Text Index | `audit_logs` — full-text search across event history |
| Change Streams | `ChangeStreamListener` — live WebSocket push to dashboard |
| ACID Transactions | Fire evacuation + intrusion lockdown (WriteConcern.MAJORITY) |
| `$setWindowFields` + `$merge` | Rolling average aggregation into `sensor_averages_daily` |
| Schema Validation (`$jsonSchema`) | `sensor_data` collection |
| Singleton Document Pattern | `home_status` — armed/away mode state |
| Replica Set Topology Check | Startup check for change stream + transaction support |

**Status: NOT STARTED**


---

## Explicitly out of scope (do not implement without a new discussion)

- Role-based access control (owner/guest roles)
- Multi-zone logic
- Geospatial "nearest exit" indexing
- Anomaly detection via aggregation deviation from rolling average
- Replica set failover live demo
- Wellness-check-style extended-stillness detection

These were deliberately rejected in planning as too high-effort or
out-of-scope for this project's bar. If the user later asks for one of
these, treat it as new scope requiring its own plan update — do not add it
opportunistically while working on something else.
