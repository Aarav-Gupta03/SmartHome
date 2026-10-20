# Project Rules & Boundaries

These rules apply to **any AI assistant working on this codebase** —
whether in this chat, in Claude Code, or any other IDE agent. They exist so
work stays predictable and nothing gets built or changed without the
user's explicit go-ahead.

---

## 1. Phase discipline

- Work only on the **current phase**, as marked `IN PROGRESS` in `plan.md`.
  If no phase is marked `IN PROGRESS`, ask which one to start before writing
  any code.
- Do not start a new phase without explicit user approval — even if it
  looks like the obvious next step, or even if it would be more efficient
  to "just do it while I'm in here."
- Do not do partial work across multiple phases in one sitting unless the
  user explicitly asks for that.
- When a phase is finished: stop, summarize exactly what changed (files
  touched, behavior added/changed), update its status in `plan.md`, and
  wait for confirmation before touching the next phase.

## 2. Scope discipline

- Do not implement anything from the "Explicitly out of scope" list in
  `plan.md` without a fresh, explicit discussion first.
- Do not refactor, "clean up," or "improve" code outside the current
  phase's stated scope, even if you notice something that could be better.
  Mention it instead of doing it.
- Do not add libraries, frameworks, or new services beyond what's already
  in use (see Rule 4) without asking first.

## 3. Safety-critical logic (hard rules, not style preferences)

- **Fire response must always unlock `isExitRoute: true` doors — never lock
  all doors during a fire condition.** This was a deliberate fix for a real
  safety flaw in an earlier design; do not silently revert to "lock
  everything on fire" for any reason, including simplification or
  refactoring.
- Any change to lock, transaction, or alert logic must be treated as
  high-risk: explain what changed and why before considering it "done,"
  and do not consider a phase touching this logic complete without the
  user reviewing it.

## 4. Consistency

- Keep the existing tech stack: Java 11 + Spring Boot (backend), React +
  Vite (frontend), MongoDB Atlas. Do not introduce a different framework,
  database, or major library without discussion.
- Keep the existing visual theme ("The Panel" — graphite/amber/analog-dial
  control-panel aesthetic) for any new frontend components; don't default
  to a generic dashboard look for new features.
- Reuse the existing communication pattern (REST endpoint → MongoDB write →
  change stream → WebSocket broadcast) for new features rather than
  introducing a new mechanism (e.g. polling, a second WebSocket channel,
  a message queue) unless a phase explicitly calls for it.

## 5. Communication

- Confirm understanding before any large or ambiguous change — don't guess
  silently on an unclear requirement.
- State assumptions explicitly when a small ambiguity is filled in, so the
  user can correct it easily.
- Never describe a phase as complete unless the code described would
  actually compile and behave as claimed — no optimistic status updates.
- If a request conflicts with something already decided (in `plan.md` or
  here), point out the conflict rather than silently overriding the
  earlier decision.

## 6. File/document hygiene

- `plan.md` is the single source of truth for phase status — keep it
  updated as phases progress, don't track status only in chat.
- If a plan changes (a phase is reordered, scope shifts, a new feature is
  agreed), update `plan.md` in the same session as the decision, not later.
