# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-22, at commit a7ecb69e, by main session (wave 46: T335 and T329 merged, T330 and T332 in flight)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, the open wave in `docs/TASKS.md`
("## Wave 46": its paragraphs settle the choices and the order), the last closed wave's record
`docs/tasks/wave-45.md`, and the ideas (`node .claude/hooks/queue-list.js ideas`).

## Now

- **Wave 45 is closed and v0.1.41-alpha is published** (2026-11-22 13:46 UTC, tag on 8edfd5ff, versionCode 2104; read from `gh release view`): the Panorama view on the phone. Its closing sweep was red on one step's own fixed bearing; T336 fixed the step and the counted rerun `out/sweep-20261122-w45-rerun` was green. The log's entry of 2026-11-22 has the figures and the cost (8 points against 8.5 figured; the `wave` skill's figures stand).
- **Wave 46 is open** (planned 2026-11-22 on the owner's word, "After this release we can go ahead and build the next one."; critic: LAUNCH AFTER EDITS, answered in the wave paragraph): pass three of the Panorama as a chain, T329 (the lens rolls and takes the phone's attitude, the filter, the heading correction, in `shared` and devtool), T330 (the sensor, the steering setting, the Follow phone button), T331 (the correction's line, the weak-compass line, the arrow as a sign, the sweep's steps); a second chain, T332 (the no-map messages, reproduced first), T333 (a weak-GPS note while recording), T334 ("Can I see it from here?"); and T335 (the sweep's diff step, the launch check's `since`; no Gradle).
- **Merged and logged:** T335 (aa708deb, MERGE WITH NOTES, no Gradle run by either agent) and T329 (fc0e37f5, one fix round: Drag's edge arrow keeps the bearing difference at every tilt; a zero-length rotation vector is skipped). **In flight:** implementers (Opus) of T332 (launched about 14:00 UTC; worktree `dystopo-wt/T332` [no-check: a sibling folder], AVD `dystopo35-T332`, console 5652, host 8997, base ports from 9050) and T330 (launched about 16:10 UTC; AVD `dystopo35-T330`, console 5658, host 8993, base ports from 9060; its brief quotes T329's API). If a session resumes with neither alive, check `git branch -r --list 'origin/task/*'`, the worktrees and `E:\dystopo-avd` first; a pushed branch with no report gets a verifier, an unpushed worktree a fresh implementer told what is there.
- **Cost:** the 7-day meter read 15 at wave 46's launch (reset 2026-11-28 03:00 UTC), the 5-hour meter 6; the wave is figured at 10. The hold is 97.
- The live viewer on 8765 is wanted (`out/coordinator-pids.txt`). Rules: no force push; `-Pkotlin.daemon.jvmargs=-Xmx4g`; at most two Gradle agents and two emulators, a verifier counting as one; ports 9012 and 9013 are another program's.

## Next

1. As each implementer reports: a verifier (Sonnet), the merge sequence, the log by `tools/merge-log.py`, the cleanup chore. T331 launches when T330 is merged; T333 when T332 is merged, T334 after T333. T330 and T332 both add lines to `tools/a11y-baseline.json` and blocks to `tools/android-a11y.py`: if the second merge conflicts, its implementer runs `git merge origin/main` (no rebase of a pushed branch).
2. At T330's merge, Owner actions pending takes the real-phone check of the filter's constants (the debug build's `GET /panorama` figures); at T331's, ADR 0064 takes a dated line (the offset is a heading correction along the level line, not kept between sessions).
3. Close wave 46 by the `wave` skill: the README status, the sweep with `-Prev out\sweep-20261122-w45` and `-First` on the frames the tasks name (`sweep-diff.py --check-first` first), v0.1.42-alpha, costs, the record `docs/tasks/wave-46.md` [no-check: written at the close]. A release step a task adds to a sweep part is run as the whole part before hand-over (T336's lesson).

## Waiting on user

- **On the phone, v0.1.41-alpha** (ADR 0031): the Panorama view, its opening aim, the tilt and zoom limits, the two zoom buttons, a row or a name turning the view, the edge arrow's line, Back's order, the credit line above the three-segment switch; the Horizon view without its Close button. Strings and frames: the merge bodies of T325, T326 and T327, the release notes and its changes sheet. **With a place selected the Panorama opens there**, as the Horizon view does: the coordinator took the recommendation of `docs/ideas/2026-11-21-which-way-is-it-the-panorama-facing-a-selected-place.md`, whose Answer is still yours.
- **The triage of 2026-11-22** left Questions with blank Answers in the ideas of 2026-11-21 (the list: `node .claude/hooks/queue-list.js ideas`); each is one choice of yours.
- **On the phone, v0.1.40-alpha** (ADR 0031): "View from here" on a picked peak and its two help sentences; the Horizon switch opening at a long-pressed point or a search result, the long-press card winning when both are there; the first-run card's new place. Strings and frames: the merge bodies of T320, T321 and T323, and the release's changes sheet.
- **On the phone, v0.1.39-alpha and older:** the list in `docs/TASKS.md`, Owner actions pending, and the handoff of commit 5bbd0835. Still the owner's: the two feed emails, OpenFreeMap's terms.
- **The release key:** a script started by an agent can read `tools\release.properties` (T293); closing it is a permission-settings change, yours. Left for the owner: the empty folders `dystopo-wt/T275` and `C:\c`, a stray `msg.txt` in `dystopo-wt`.

## Open findings

- `docs/findings/2026-11-15-a-drive-as-a-speed-profile.md`: open; the first routing work after wave 35.
- `docs/findings/2026-11-16-research-for-pending-ideas.md`: open; the outside research per pending idea.
- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; cause unproven.
- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: open in its file.
