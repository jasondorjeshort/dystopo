# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-27, at commit 758fc49 plus this edit, by main session (wave 21: seven of nine merged, T154 in flight)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` › Wave 21.

## Now

- Wave 21 is on the board past the critic: nine tasks, launch order T149, T150, T152, T151, T156, T153, T157, T154,
  T155 (T154 and T155 may take any slot). **One agent at a time** (owner, 2026-10-27): implementer, verifier, merge,
  next task. Merged and logged, branches and worktrees cleaned: T149 (dbe6e69), T150 (98c9a9d), T152 (3f0af48),
  T151 (213bb38), T156 (5412c7b), T153 (2d94d75), T155 (ad31b31). T154's implementer (Opus) was launched 2026-10-27
  on worktree `~/Documents/src/dystopo-wt/T154` (host 8750, console 5630; its sweep on the sweep's own ports and
  AVD, with the signing files copied into its gitignored `tools/`); its sweep is the wave's post-wave sweep.
  Merge drafts: `%TEMP%/dystopo-wave21/`. Research and the critic review: the gitignored `out/wave21-research/`.
- Usage gate: the weekly hold is 100 (`policy.json`); the 7-day meter read 95% at T155's launch and resets
  2026-10-30 about 04:00 UTC. The tail was reordered for it: T155, then T154 (its sweep closes the wave for a
  release), then T157 only if the meter holds; otherwise T157 opens wave 22 unchanged.
- Machine: 3 GB emulators, at most three, Gradle `--no-daemon`; the Rainier DEM pack is the wide one
  (`machines.md`); the live viewer on 8765 (`out/coordinator-pids.txt`). The owner's phone is attached over
  wireless adb: agents address only their emulator by serial. No `devtool.local.json` on this PC.

## Next

1. When T154 reports: a Sonnet verifier (host 8790, console 5660; it repeats the z8 check and views the sweep's
   sheet, not a second full sweep), the merge sequence, the log entry, a Haiku cleanup chore; then, with the
   sweep green, the README status paragraph and a release (`release` skill, ADR 0024); then T157 if the meter holds.
2. After the last merge the budget allows: README status, the sweep (T154 runs it as its own verification),
   a release (ADR 0024), the handoff.
3. A Hatchery-rooted session (carry-backs, the ownership idea, ADR 0021's follow-ups, the second base update; the ADR for T144's reference counting); ADR 0040's and 0042's build tasks after the owner.

## Waiting on user

- The owner's list is `docs/TASKS.md` › Owner actions pending (rewritten 2026-10-27): the phone checks after the
  next alpha (CPU after HOME, no new memory reports, issues 23 to 29), the wave 18 to 21 strings, the decisions
  (auto-resume toggle, ADR 0040 and 0042, S Pen, Play), the git housekeeping, the `RELEASE_*` secrets, the first
  PC's pack recipe. Carried from older waves: T100, T104.

## Open findings

- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: T149, T150 merged; the phone next.
- `docs/findings/2026-10-26-z8-tiles-check-misses-the-start-up-tile.md`: T154.
