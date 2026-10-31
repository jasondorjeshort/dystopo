# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-31, at commit 26dc895 plus this edit, by main session (wave 24 planned, not launched)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` (the Wave 24 section and its paragraph: slots, ports, launch order, the sentinel rule; the record `docs/tasks/wave-23.md` for the previous wave).

## Now

- **Wave 24 is planned and waiting for the owner's go** ("we'll wait to implement", 2026-10-31): nine tasks, T190 to
  T198, on the board. T190 the emulator locks (owner's priority, cap 2), T191 the sweep's timeouts and the overlay
  screenshot, T192 the reviewer's defects, T193 the terrain-sunset deadline, T194 shade along the route, T195 the
  route's exposure, T196 the way down after a storm warning, T197 the summit calibration, T198 the trip report page.
  Nothing launched; no research agent, no critic yet.
- **Wave 23 is closed** and **v0.1.16-alpha is published** (tag bc62eee; the record `docs/tasks/wave-23.md`).
- Three stale Later lines struck (T159, T162 and T185 had done them). Usage at this edit: 7-day 55 (resets
  2026-11-06 04:00 UTC); the wave's allowance sentence assumes 32 points against the hold of 97.

## Next

1. On the owner's word: launch day by the `wave` skill, Plan and launch steps 1 to 4 in this order: the five
   research agents named in the wave paragraph (reports to the gitignored research folder the paragraph names), their answers written into T193,
   T194, T195, T197 and T198; the critic (most capable model, read-only) over the section with the wave 23 record and
   the two findings of 2026-10-31 in its brief; then T190, T191, T192 and the pure halves of T193, T195, T197, T198.
   Re-read both meters first. The two coordinator chores (ADR 0049's and 0050's Outcomes) go with the first launches.
2. A Hatchery-rooted session: five pending carry-backs; the triage skill's carry-back step runs in project trees
   and classifies against the wrong `main`; the wave skill's AVD line and the sentinel rule once adopted.
3. ADR 0040's and 0042's build tasks after the owner; ADR 0049's C after the z10 answer; the terrain-fix live
   source after the owner's hike logs.

## Waiting on user

- The go for wave 24. `docs/TASKS.md` › Owner actions pending: install v0.1.16-alpha and the hike checks; the
  `Question:` blocks of 2026-10-30 and 2026-10-31 (twelve, none blocks wave 24); ADR 0051's two answers; the paging
  file (system-managed, or 32 GB or more); the git housekeeping row.

## Open findings

- `docs/findings/2026-10-31-sweep-hung-when-the-paging-file-ran-out.md` (T191 resolves it) and
  `2026-10-31-appended-blocks-drop-their-closers-on-rebase.md` (the wave 24 paragraph adopts its sentinel rule).
- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: T189 ships the graph; the phone next.
