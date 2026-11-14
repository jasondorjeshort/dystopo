# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-14 07:40 UTC, at commit 6eeb5358, by main session (v0.1.29-alpha published; the wave's record chore next)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, `docs/TASKS.md` (wave 32 until its record is
moved to `docs/tasks/`, then the board's Later).

## Now

- **v0.1.29-alpha is published** (2026-11-14 07:10 UTC, tag on 6eeb5358, versionCode 1593): the closing release of
  wave 32, nine tasks over v0.1.28-alpha (T267, T278, T263, T266, T268, T273, T279, T270, T280). Its sweep is
  `out/sweep-20261114-0027` (green, no rerun; the next release's `-Prev`). The log has the entries.
- **Wave 32 is closed but for its record**: a Haiku chore moves it to `docs/tasks/` by that folder's recipe and
  lifts the Status lines' follow-ups into Later; the coordinator reads the list, commits and pushes.
- **The sheet attached to the release was rebuilt** without four frames that show a map outside `areas.json` (the
  a11y part's first-run screens and `bothunits`' second phone); the cause is unknown (the log's publish entry).
- The wave skill's cost figure is 1.0 a task from this close (16 points for eleven tasks; the meter read 17).
- The live viewer is wanted (`out/coordinator-pids.txt`). Rules: no force push; a false `:devtool:compileTestKotlin` failure or an out-of-memory Kotlin compile clears with `:devtool:clean check "-Pkotlin.daemon.jvmargs=-Xmx4g"`; a worktree folder with long paths goes by the `\\?\` delete.

## Next

1. The record chore's list read, the board, the record and the index committed together and pushed.
2. The next wave planned from the board's Later and a triage of `docs/ideas/` (the `wave` skill, Plan and launch).
3. First in it: what opens a fix-less emulator's map outside the public areas, and a check in `sweep-diff.py`.

## Waiting on user

- **The sheets already published with v0.1.27-alpha and v0.1.28-alpha** probably hold the second phone's two frames
  of that same place (they are in those sweeps' changed lists). The privacy scan knows no private term or box
  there; if the place is yours, delete the two `-changes.png` assets (or say so and an agent replaces them).
- On the phone, v0.1.29-alpha: the update from 0.1.28 moving the kept maps (T267's check on a real phone), the
  activity chip, Trip details. The strings of T263 and T278 to read (ADR 0031) are in their merge bodies.
- Task agents are refused the release key, so only the sweep and the draft check ran `upgrade`; say if they may read it.
- The owner closes whatever window sits in the empty folders `dystopo-wt/T275` and `dystopo-wt/T267-verify`, then
  removes them; and removes the empty `C:\c` folder. OpenFreeMap's terms (Later's first entry; the owner emails them).

## Open findings

- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; T273 measured it (the sweep keeps rebooting), cause unproven.
- `docs/findings/2026-11-10-range-cache-keeps-blocks-across-a-republish.md`: 56 stale cells deleted; the validator is T269.
