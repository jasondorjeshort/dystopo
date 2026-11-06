# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-06, by main session (v0.1.21-alpha published; wave 29 waits on the owner's phone trace)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` (wave 29),
`docs/tasks/wave-28.md`, ADR 0056, ADR 0057.

## Now

- **Wave 29 is open: trace the startup freezes.** Every task but T229 is merged: T227, T228 (the startup trace),
  T231 (the emulator guard), T230 and T232 (airport gates, drawn from z15), T233 (the sweep under the guard).
- **v0.1.21-alpha is published** (2026-11-06, tag on 03c086af, versionCode 1226) to bring T228's trace to the
  owner's phone. Next: the owner's Startup freeze report, then plan T229 (the launch's disk work off the main thread)
  from it, past a critic. Inventory: `out/wave29-research/` [no-check: gitignored]. The merge reports and notes of
  this session: `out/wave29-reports/` [no-check: gitignored].
- No task branch or worktree is open. Leftover folders in the worktrees directory (`T223`, `X`, `.scratch`, logs of
  2 to 5 November) are not worktrees; clear them when convenient.
- **Emulators:** `tools/android-emu.ps1` runs a RAM guard per emulator (8 GB, 4 GB floor; the sweep 12 GB with reboots
  past 6 GB). Briefs need no RAM step of their own; a boot can be refused for free RAM.
- **Rule, ADR 0057:** one Gradle-using agent at a time unless the owner says wider.
- The airport gate research report is untracked in the main checkout's `reports/` and `research_notes/`
  [no-check: untracked]; move it under `out/` or commit it, the owner to say.
- The live viewer on 8765 was up at the last check.

## Next

1. The owner's trace, then T229, and close wave 29 (record in `docs/tasks/`).
2. The next wave's candidates: one rule for state made off the main thread (T226), the layout authority's second half
   with ADR 0053's build step 2 and the top-row regroup (T224's merge commit: three tasks), ADR 0056's build tasks
   (B1a, B1b, B3, B2), the two ready Plans; T230, T231 and T233's follow-ups on the board.
3. A Hatchery-rooted session: the pending carry-backs plus T190's two, T201's, the sentinel rule, ADR 0057's rule,
   T231's guard.

## Waiting on user

- A Startup freeze report from the phone on v0.1.21-alpha; on the first open after an update only, or other opens too?
- ADR 0056: should "pack" also leave the code, routes and JSON keys, or only what people read (assumed)? The location
  permission: at launch or on the tap (T220). "Keep north up": rename or keep (T176). The top row: a Menu or not.
- Proposals 0054 and 0055's choices; ADR 0053's Category column; the rest of the board's Owner actions.

## Open findings

- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded by T231 and T233; the cause open.
- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: T189 ships the graph; the phone next.
