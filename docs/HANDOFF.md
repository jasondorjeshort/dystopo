# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-06, by main session (T228 merged; T231 at check; T230 next)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` (wave 29),
`docs/tasks/wave-28.md`, ADR 0056, ADR 0057.

## Now

- **Wave 29 is open: trace the startup freezes.** T227 merged (readable stacks, ADR 0058; a freeze's true length). T228 merged 2026-11-06 (a trace of every start, through Report a problem). The owner's freeze is the screen stuck on the splash during the opening. On the emulator no unexplained hold appeared, so the cause awaits a trace from the owner's phone: release next (after a sweep), then plan T229 (the launch's disk work off the main thread) from it. Inventory: `out/wave29-research/` [no-check: gitignored]. `git branch -r --list 'origin/task/*'` shows what is pushed.
- **T231 (the emulator guard in `tools/android-emu.ps1`)**: built and live-tested, its `check` running before its push;
  the implementer's report is in `out/wave29-reports/` [no-check: gitignored]; then its verifier and merge.
- **T230 (airport gate labels, a map option, gate search) launches after T231 merges, highest priority** (owner,
  2026-11-06). Its research report is untracked in the main checkout's `reports/` [no-check: untracked]; give the brief its path.
- The T228 cleanup chore is not yet run (worktrees `T228`, `T228-verify`, branch, AVD `dystopo35-T228v`).
- Wave 28 is closed and v0.1.20-alpha is published (T217 to T226).
- **New rule, ADR 0057:** one Gradle-using agent at a time unless the owner says wider. The `wave` skill's per-task
  figure is now 1.25 points (wave 28's close read 5.5 under its figure).
- The live viewer on 8765 was up at the last check. The T224b design note is in the body of T224's merge commit.

## Next

1. The next wave's candidates: one rule for state made off the main thread (T226), the layout authority's second half
   with ADR 0053's build step 2 and the top-row regroup (T224's merge commit: three tasks), ADR 0056's build tasks
   (B1a, B1b, B3, B2), the two ready Plans. One at a time, so size it to the owner's patience as well as the meter.
2. A Hatchery-rooted session: the pending carry-backs plus T190's two, T201's, the sentinel rule, ADR 0057's rule.

## Waiting on user

- The startup freeze: on the first open after an update only, or on other opens too?
- ADR 0056: should "pack" also leave the code, routes and JSON keys, or only what people read (assumed)? The location
  permission: at launch or on the tap (T220; the board's list). The Stop chip hiding on a phone on its side: the layout's second half.
- "Keep north up": rename or keep (T176). The map screen's top row: regroup behind a Menu or not.
- v0.1.20-alpha on the phone (`GET /identity`, the board's phone checks); proposals 0054 and 0055's choices;
  ADR 0053's Category column; the rest of the board's Owner actions.

## Open findings

- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: at most six cold launches a boot until T231's guard merges.
- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: T189 ships the graph; the phone next.
