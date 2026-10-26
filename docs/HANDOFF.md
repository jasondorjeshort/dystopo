# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-26, at commit 43cf7f6 plus this edit, by main session (v0.1.13-alpha published)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` › Wave 20 (closed; wave 21 unplanned).

## Now

- Wave 20 is closed and v0.1.13-alpha published (2026-10-26, the second PC; log entries of 2026-10-26):
  the sweep (`out/sweep-w20`) green on the app, its two reds explained (a throttle that did not take; the
  z8 `tiles` check against this PC's narrow pack, the open finding below); the release built locally at
  the tag (secrets still absent) and verified from the draft. No task branch is in flight.
- Machine: 3 GB emulators, at most three, Gradle `--no-daemon`; the Rainier DEM pack is the wide one
  (`machines.md`); a live viewer on 8765 is wanted (`out/coordinator-pids.txt` has the PID when up).

## Next

1. Plan wave 21 (`wave` skill, critic first): the sweep task from the open finding (the tour's `tiles`
   check, the first PC's pack recipe recorded) plus the Status lines' follow-ups: leases for readers
   outside the loopback server, the `onMain` waits and `Pack.metadata` priming (T144); StrictMode;
   T138's DEM recovery; precomputed country sizes, `POST /map/longpress/around`, the "0.0 MB of your
   2 GB" wording and the picker's other follow-ups (T142); `--tiles` to `tileItems` (T141); the template
   chooser and `Scrub.Known` against short pack names (T145); `drawCheck` between drives (T146).
2. A Hatchery-rooted session (carry-backs, the ownership idea, ADR 0021's follow-ups, the second base
   update); ADR 0040's and 0042's build tasks after the owner.

## Waiting on user

- Whether the repository goes public before testers hear of Report a problem (a private one takes no
  outside issues, T145); `git push origin --delete task/T138`, `git branch -D carry-back/ownership-project-list`,
  `git branch -D task/T143` and `task/T146` (pre-rebase copies; the rebased branches merged); the
  `RELEASE_*` secrets; the wave 18 to 20 strings (ADR 0031); ADR 0042's confirmations, ADR 0040's
  positions; real-phone runs (`android-battery.py`, heap numbers, v0.1.13-alpha, the first watchdog dump).
- Carried: the S Pen question; the T122 folder, `triage-saved` and `refs/original/*`; the two older
  Claude-authored commits; T122's and the wave 14 to 17 strings; Play registration; T100, T104.

## Open findings

- `docs/findings/2026-10-26-z8-tiles-check-misses-the-start-up-tile.md`: wave 21's sweep task.
