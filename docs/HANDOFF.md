# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-27, at commit 0d980dd plus this edit, by main session (wave 21: T149 and T150 merged, T152 in flight)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` › Wave 21.

## Now

- Wave 21 is on the board past the critic: nine tasks, launch order T149, T150, T152, T151, T156, T153, T157, T154,
  T155 (T154 and T155 may take any slot). **One agent at a time** (owner, 2026-10-27): implementer, verifier, merge,
  next task. T149 (dbe6e69) and T150 (98c9a9d) are merged and logged, their branches and worktrees cleaned.
  T152's implementer (Opus) was launched 2026-10-27 on worktree `~/Documents/src/dystopo-wt/T152`, AVD
  `dystopo35-T152`, console 5630, host 8750; nothing else is in flight. Merge drafts: `%TEMP%/dystopo-wave21/`.
- Research reports and the critic review: the coordinator's gitignored `out/wave21-research/`. Usage gate: the
  weekly hold is 100 while this project is the priority (`policy.json`); the 5-hour meter's 50 binds.
- Machine: 3 GB emulators, at most three, Gradle `--no-daemon`; the Rainier DEM pack is the wide one
  (`machines.md`); the live viewer on 8765 (`out/coordinator-pids.txt`). The owner's phone is attached over
  wireless adb: agents address only their emulator by serial. No `devtool.local.json` on this PC.

## Next

1. When T152 reports: a Sonnet verifier (host 8790, console 5660), the merge sequence (`wave` skill), the log
   entry, a Haiku cleanup chore, then T151's implementer (the cursor over T152's rows), and so on down the order.
2. After the last merge the budget allows: README status, the sweep (T154 runs it as its own verification),
   a release (ADR 0024), the handoff.
3. A Hatchery-rooted session (carry-backs, the ownership idea, ADR 0021's follow-ups, the second base update; the ADR for T144's reference counting); ADR 0040's and 0042's build tasks after the owner.

## Waiting on user

- The wave 18 to 21 strings (ADR 0031; T148's and T149's are in their merge commits).
- `git push origin --delete task/T138`, `git branch -D carry-back/ownership-project-list`, `git branch -D task/T143`
  and `task/T146` (pre-rebase copies); the `RELEASE_*` secrets; ADR 0042's confirmations, ADR 0040's positions;
  real-phone runs (`android-battery.py`, heap numbers, the first watchdog dump); the first PC's Rainier pack recipe.
- Carried: the S Pen question; the T122 folder, `triage-saved` and `refs/original/*`; the two older
  Claude-authored commits; T122's and the wave 14 to 17 strings; Play registration; T100, T104.

## Open findings

- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: T149, T150 merged; the phone next.
- `docs/findings/2026-10-26-z8-tiles-check-misses-the-start-up-tile.md`: T154.
