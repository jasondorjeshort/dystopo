# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-22, at commit f02242f4, by main session (wave 45: the closing sweep red on one step of its own, T336 in flight; wave 46 on the board, not launched)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, the open wave in `docs/TASKS.md`
("## Wave 45": its paragraphs settle the choices and the order), the last closed wave's record
`docs/tasks/wave-44.md`, and the ideas (`node .claude/hooks/queue-list.js ideas`).

## Now

- **Wave 45 is open** (planned 2026-11-21 on the owner's word, after its critic: LAUNCH AFTER EDITS, answered in the wave paragraph): pass two of the Panorama view (ADR 0064) as a chain, T324 (the scene, the aim and the gestures' arithmetic in `shared`), T325 (the Horizon view's pane cut into parts two views can host; its Close button goes), T326 (the third segment, the lens plot, drag, tilt and zoom), T327 (the other layers, a row that turns the view, the edge arrow), and T328 (a launch crash keeps its stack; the start-up walker that throws).
- **All five merged and logged:** T324 (e8f17be5), T325 (74a5c2a7), T328 (cce2267e), T326 (e7690a9d) and T327 (b9873fb0); ADR 0064's second prediction held. **The closing sweep** `out/sweep-20261122-w45` (main 11a94519, 96 min, `-Prev out/sweep-20261121-w44`) was red on `features` alone: the step `panorama` wants 347 degrees at Mount Rainier and the app, agreeing with itself in both views' frames, said 343. Every other part green, no launch crash, no flake; its figures are in its `summary.json` for the close's log entry. **T336** (added at the close; Sonnet implementer launched, worktree `dystopo-wt/T336` [no-check: a sibling folder], AVD `dystopo35-T336`, console 5650, host 8996) makes the step read the bearing from the row it taps. If a session resumes with no agent alive, check `git branch -r --list 'origin/task/*'` and `E:\dystopo-avd` first.
- **Cost:** the 7-day meter read 7 at wave 45's planning and 14 when wave 46 went on the board (reset 2026-11-28 03:00 UTC); wave 45 is figured at 8.5, wave 46 at 10. The hold is 97.
- The live viewer on 8765 is wanted and was answering at the launch (`out/coordinator-pids.txt`). Rules: no force push; `-Pkotlin.daemon.jvmargs=-Xmx4g`; at most two Gradle agents and two emulators, a verifier counting as one.

## Next

1. T336: a Sonnet verifier on its branch (`check`, `python -I tools/test_android_ui.py`, the delta; the device run is step 2's), the merge sequence, the log, the cleanup chore.
2. Close wave 45: a sweep agent reruns `-Only features -RerunOf out\sweep-20261122-w45` on `main` into a second folder; green, then v0.1.41-alpha by the `release` skill, the close's log entry with the first sweep's figures, costs by `tools/agent-cost.py` (T324 to T328, T336), the allowance sentence's closing reading, and the chore that writes `docs/tasks/wave-45.md` [no-check: written at the close] and lifts the follow-ups to Later.
3. Then, without asking again (the owner, 2026-11-22: "After this release we can go ahead and build the next one."): launch wave 46 as its paragraph on the board orders it ("## Wave 46": its critic is answered there; T329, T332 and T335 first, the two chains after). The triage of 2026-11-22 is committed, one idea per commit. At T331's merge ADR 0064 takes a dated line (the offset is a heading correction, not kept between sessions); at T330's, Owner actions pending takes the real-phone check of the filter's constants.

## Waiting on user

- **The Panorama segment with a place selected** opens the view at that place, as the Horizon segment does: the coordinator took the recommendation of `docs/ideas/2026-11-21-which-way-is-it-the-panorama-facing-a-selected-place.md` (its Answer is still yours; the other answer changes one line). The wave paragraph's other choices are built and shown in T326's merge body (ADR 0031): the opening's aim, the limits, the plot's shape, the two zoom buttons, and the credit line, which now stands above the three-segment switch on an upright phone (beside it on its side) and keeps its two-line cap.
- **On the phone, v0.1.40-alpha** (ADR 0031): "View from here" on a picked peak and its two help sentences; the Horizon switch opening at a long-pressed point or a search result, the long-press card winning when both are there; the first-run card's new place. Strings and frames: the merge bodies of T320, T321 and T323, and the release's changes sheet.
- **On the phone, v0.1.39-alpha and older:** the list in `docs/TASKS.md`, Owner actions pending, and the handoff of commit 5bbd0835. Still the owner's: the two feed emails, OpenFreeMap's terms.
- **The release key:** a script started by an agent can read `tools\release.properties` (T293); closing it is a permission-settings change, yours. Left for the owner: the empty folders `dystopo-wt/T275` and `C:\c`, a stray `msg.txt` in `dystopo-wt`.

## Open findings

- `docs/findings/2026-11-15-a-drive-as-a-speed-profile.md`: open; the first routing work after wave 35.
- `docs/findings/2026-11-16-research-for-pending-ideas.md`: open; the outside research per pending idea.
- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; cause unproven.
- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: open in its file.
