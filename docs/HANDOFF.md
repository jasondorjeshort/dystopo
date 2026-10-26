# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-26, at commit 8fde29e plus this edit, by main session (wave 20 sweep read; v0.1.13-alpha in progress)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` and `sweep` skills, `docs/TASKS.md` › Wave 20.

## Now

- Wave 20 is complete (2026-10-25, the second PC): all eight tasks merged with Status lines, log and
  changelog entries (T141 87ed16c, T147 786cc26, T144 da09785, T140 d268446, T143 fa783a8, T146 2676bdb,
  T145 51a1375, T142 24db6d2); README carries it; worktrees and task branches gone; strings per merge commit.
- The sweep (`out/sweep-w20`, log entry of 2026-10-26) is green on the app: `network`'s red was a throttle
  that did not take (rerun green); the z8 `tiles` red is the check and this PC's narrow pack (the open
  finding). v0.1.13-alpha is being published by the `release` skill's local path (secrets still absent).
- Machine: 3 GB emulators, at most three, Gradle `--no-daemon`; the Rainier DEM pack is now the wide
  one (`machines.md`); the live viewer on 8765 is up (`out/coordinator-pids.txt`).

## Next

1. Finish v0.1.13-alpha (verify from the draft, publish, the log entry and the README line), then
   rewrite this file.
2. Plan wave 21 from the Status lines' follow-ups: leases for readers outside the loopback server, the
   `onMain` waits and `Pack.metadata` priming (T144); StrictMode; T138's DEM recovery; precomputed country
   sizes and the picker's follow-ups (T142); `--tiles` to `tileItems` (T141); the template chooser (T145).
3. A Hatchery-rooted session (carry-backs, the ownership idea, ADR 0021's follow-ups, the second base
   update); ADR 0040's and 0042's build tasks after the owner.

## Waiting on user

- Whether the repository goes public before testers hear of Report a problem (a private one takes no
  outside issues, T145); `git push origin --delete task/T138`, `git branch -D carry-back/ownership-project-list`,
  `git branch -D task/T143` and `task/T146` (pre-rebase copies; the rebased branches merged); the
  `RELEASE_*` secrets; the wave 18 to 20 strings (ADR 0031); ADR 0042's confirmations, ADR 0040's
  positions; real-phone runs (`android-battery.py`, heap numbers, v0.1.12-alpha, the first watchdog dump).
- Carried: the S Pen question; the T122 folder, `triage-saved` and `refs/original/*`; the two older
  Claude-authored commits; T122's and the wave 14 to 17 strings; Play registration; T100, T104.

## Open findings

- `docs/findings/2026-10-26-z8-tiles-check-misses-the-start-up-tile.md`: wave 21's sweep task.
