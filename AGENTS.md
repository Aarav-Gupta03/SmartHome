# Agent Instructions — Smart Home IoT Data Engine

Before doing anything in this project, **read `plan.md` and `rules.md` in
this same root folder first.** They are not optional background reading —
they are the actual constraints for how work happens here.

- `plan.md` — the phased implementation roadmap. Only work on the phase
  currently marked `IN PROGRESS`. If no phase is marked `IN PROGRESS`, stop
  and ask which one to start rather than picking one yourself.
- `rules.md` — hard boundaries: phase discipline, scope discipline, a
  safety-critical rule about fire/lock logic, tech-stack consistency, and
  communication expectations. Follow these exactly, including when they
  say to stop and wait for confirmation rather than continuing on your own.

## Quick summary (full detail is in the two files above)

- One phase at a time. Do not start the next phase without explicit
  approval, even if it seems efficient to continue.
- Do not touch code outside the current phase's stated scope.
- Do not implement anything from the "Explicitly out of scope" list in
  `plan.md` without a fresh discussion.
- Fire response must always unlock `isExitRoute: true` doors and never
  lock all doors — this is a fixed safety rule, not a style choice.
- Keep the existing stack (Java 11 + Spring Boot backend, React + Vite
  frontend, MongoDB Atlas) and the existing visual theme ("The Panel").
- When a phase is finished, summarize exactly what changed and stop —
  don't keep going into the next phase automatically.

## Project structure

```
backend/    Spring Boot app (Java 11, Maven)
frontend/   React + Vite dashboard
plan.md     Phase-by-phase roadmap and current status
rules.md    Full boundary rules
README.md   Setup and run instructions
```
