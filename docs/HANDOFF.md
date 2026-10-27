# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-26, at commit 77198b2 plus this edit, by main session (T148 merged, no release)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` › Wave 20 and "One-off fixes after wave 20" (wave 21 unplanned).

## Now

- v0.1.13-alpha is the published release. Since then: T148 merged (01d05eb, the issue link copies only what the
  body lacks; no release, owner's word); the owner's phone filed five problem reports (issues 23 to 27, four
  events, all memory pressure; finding below). No task branch is in flight; worktrees cleaned.
- Machine: 3 GB emulators, at most three, Gradle `--no-daemon`; the Rainier DEM pack is the wide one
  (`machines.md`); the live viewer on 8765 (`out/coordinator-pids.txt`). No `devtool.local.json` on this PC, so
  the privacy scans check coordinates only.

## Next

1. Plan wave 21 (`wave` skill, critic first): the map opens on the last known position, owner's high priority
   (`docs/ideas/2026-10-26-open-the-map-on-the-last-known-position.md`, needs triage); the routing-graph OOM (finding below); the two 2026-10-26 download
   ideas the owner asked for (they share a cursor and `AreaDownload.kt`: one owner or two waves); the z8 sweep
   task; the Status follow-ups of wave 20 and T148; `tools/merge-log.py` failing on the last task of the board.
2. A Hatchery-rooted session (carry-backs, the ownership idea, ADR 0021's follow-ups, the second base update);
   ADR 0040's and 0042's build tasks after the owner.

## Waiting on user

- T148's strings (ADR 0031): "The issue is complete; press Submit.", "The rest of the stack trace is on your
  clipboard; paste it below.", "(from frame N)"; the wave 18 to 20 strings.
- `git push origin --delete task/T138`, `git branch -D carry-back/ownership-project-list`, `git branch -D task/T143`
  and `task/T146` (pre-rebase copies); the `RELEASE_*` secrets; ADR 0042's confirmations, ADR 0040's positions;
  real-phone runs (`android-battery.py`, heap numbers, the first watchdog dump).
- Carried: the S Pen question; the T122 folder, `triage-saved` and `refs/original/*`; the two older
  Claude-authored commits; T122's and the wave 14 to 17 strings; Play registration; T100, T104.

## Open findings

- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: wave 21, first.
- `docs/findings/2026-10-26-z8-tiles-check-misses-the-start-up-tile.md`: wave 21's sweep task.
