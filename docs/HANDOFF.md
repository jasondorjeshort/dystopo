# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-25, at commit 37096ba plus this edit, by main session (wave 20 complete, sweep running)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` and `sweep` skills, `docs/TASKS.md` › Wave 20.

## Now

- Wave 20 is complete (2026-10-25, the second PC): all eight tasks merged with Status lines, log and
  changelog entries (T141 87ed16c, T147 786cc26, T144 da09785, T140 d268446, T143 fa783a8, T146 2676bdb,
  T145 51a1375, T142 24db6d2); README carries it; worktrees and task branches gone; strings per merge commit.
- The post-wave sweep runs through a Sonnet agent (`sweep` skill; `-BasePort 8980`, console 5640,
  `-AvdHome ~/Android/avd`, output under the sweep's dated folder in `out/`; Playwright installed for
  the demo parts; release-key parts from the main checkout). Green means publish (ADR 0024).
- Machine: 3 GB emulators, at most three, Gradle `--no-daemon` (`machines.md`); the live viewer on
  8765 is up (`out/coordinator-pids.txt`); the task AVDs are deleted (about 152 GB free on C:).

## Next

1. Read the sweep's `summary.json` and sheet; findings become wave 21's sweep task (`finding` skill);
   green: the `release` skill (v0.1.13-alpha, notes from the eight Status lines).
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

none
