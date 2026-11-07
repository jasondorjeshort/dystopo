# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-07, by main session (wave 30 planned and launched; T229 running)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` (wave 30),
`docs/tasks/wave-29.md`, ADR 0056, ADR 0057, `out/wave30-research/critic-wave30.md` [no-check: gitignored].

## Now

- **Wave 30 is open, nine tasks in series (ADR 0057), in the board's order:** T229 (running: the launch's first frame,
  Opus, worktree `T229`, AVD `dystopo35-T229`, console 5582, host 8702), then T234, T235, T236, T237 (ADR 0056's build),
  T238, T241, T239 (the layout's second half), T240 (small, last). Each: implementer, verifier (Sonnet), merge, then
  the next launch; briefs name what merged ahead of a task (the wave paragraph's hand-over list).
- **A release follows T229's merge** (sweep, then an alpha; ADR 0024), so the owner's phone carries the fix beside
  T228's trace.
- **RAM is watched** (the owner's word): read free RAM before each launch and after each merge into the log entry;
  every report quotes the guard log's lowest free and peak private; a guard stop or under 6 GB holds the next launch.
  Readings so far: 17.5 GB free at planning, 18.9 GB at T229's launch.
- Wave 29 is closed and recorded (`docs/tasks/wave-29.md`); its follow-ups are in Later, four struck as taken.
- The live server on 8765 is up from the last session (PID in `out/coordinator-pids.txt`).
- The gate research is untracked in `reports/` and `research_notes/` [no-check: untracked]; the owner to place it.

## Next

1. T229's report: launch its verifier, merge, log, then the sweep and the release; then T234.
2. The owner's Startup freeze report, when it arrives: the fix it names becomes the next launch, ahead of the queue.
3. After the wave: Layout C (T224's note, steps 6 to 9, the owner's Menu choice), the two ready Plans, a chore for
   T227's push-pack daemon and T231's lock gap, and a Hatchery-rooted session for the pending carry-backs.

## Waiting on user

- A Startup freeze report from the phone on v0.1.21-alpha (and the next alpha, with T229).
- ADR 0056's build is under way on the assumption that "pack" stays in code, routes and JSON keys.
- The top row: a Menu or not (Layout C). The location permission at launch or on the tap (T220). "Keep north up" (T176).
- Proposals 0054 and 0055's choices; ADR 0053's Category column; the rest of the board's Owner actions.

## Open findings

- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded by T231 and T233; the cause open.
- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: T189 ships the graph; the phone next.
