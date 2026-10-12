# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-12 (evening), by main session

## Now

- Wave 12 second half, batch A in flight since 2026-10-12 (owner's second account on the development
  machine; this checkout was set up fresh that day, see the second-half paragraph in `docs/TASKS.md`):
  T85 (T80 follow-ups), T73, T74, T75, one Opus implementer each in `..\dystopo-wt\<task>`, plus a chore
  agent building the Rainier packs and starting the live server on 8765. Batch B (T77, T78, T84) waits
  for their merges. A read-only agent is comparing the coordinator rules with Hatchery (the coordinator
  ran setup by hand today; the owner wants that rule explicit).
- Hatchery (the process base) has proposal 0003, four findings and a Windows reference from this
  project; its critic pass waits for the weekly reset.

## Next

1. Verify and merge batch A PRs as they arrive (Sonnet verifier per PR), then launch batch B: T77,
   T78, T84 (Hatchery alignment, last because it restructures CLAUDE.md). Critic pass on Hatchery
   proposal 0003 in parallel.
2. Sweep, then `v0.1.5-alpha` (the first update the 0.1.4 updater will offer).
3. Make `tools/server-soak.ps1` print the reason for a non-zero exit (it exited 1 on a passing run).

## Waiting on user

- Google developer registration (publisher verification) before many people install; see
  `docs/TASKS.md` owner actions.
- A real walk with GPS on and the screen off on mobile data, on the owner's phone.

## Open findings

- Hatchery proposal 0003 (wave workflow) awaits a critic pass; see `../hatchery/docs/HANDOFF.md`.
