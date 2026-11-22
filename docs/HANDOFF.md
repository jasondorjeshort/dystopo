# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-22, at commit 8d0b7d05, by main session (wave 45 open: T324 and T325 merged, T326 and T328 in flight)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, the open wave in `docs/TASKS.md`
("## Wave 45": its paragraphs settle the choices and the order), the last closed wave's record
`docs/tasks/wave-44.md`, and the ideas (`node .claude/hooks/queue-list.js ideas`).

## Now

- **Wave 45 is open** (planned 2026-11-21 on the owner's word, after its critic: LAUNCH AFTER EDITS, answered in the wave paragraph): pass two of the Panorama view (ADR 0064) as a chain, T324 (the scene, the aim and the gestures' arithmetic in `shared`), T325 (the Horizon view's pane cut into parts two views can host; its Close button goes), T326 (the third segment, the lens plot, drag, tilt and zoom), T327 (the other layers, a row that turns the view, the edge arrow), and T328 (a launch crash keeps its stack; the start-up walker that throws).
- **Merged:** T324 (e8f17be5) and T325 (74a5c2a7), each MERGE with no fix round, logged and cleaned up. **In flight:** the implementers of T326 and T328 (Opus-class); no branch pushed yet (`git branch -r --list 'origin/task/*'` tells). T327 is launched only after T326 is merged. If a session resumes with no agent alive, a pushed `task/T326` or `task/T328` goes to a verifier with the branch's own commit messages as the report; an unpushed one is relaunched from the task text and the two merge bodies its brief names (T324's and T325's).
- **Cost:** the 7-day meter read 7 at planning (reset 2026-11-28 03:00 UTC), the 5-hour meter 4; the wave is figured at 8.5. The hold is 97.
- The live viewer on 8765 is wanted and was answering at the launch (`out/coordinator-pids.txt`). Rules: no force push; `-Pkotlin.daemon.jvmargs=-Xmx4g`; at most two Gradle agents and two emulators, a verifier counting as one.

## Next

1. As T326 reports: a Sonnet-class verifier (`brief` skill, `verifier.md`) that also runs the comparison with devtool that T324's Interacts with line gives, then the merge sequence of the `wave` skill.
2. T327 (Opus-class) after T326's merge; its verifier checks the GNSS hold and the poller's owner (T326's Interacts with line); record ADR 0064's second prediction at its merge.
3. T328 in the free slots; its verifier is Sonnet-class (an emulator and a stack to read).
4. Close the wave: the README status, the sweep with `-First` on the Panorama frames (`sweep-diff.py --check-first` on the exact names first), v0.1.41-alpha by the `release` skill, costs by `tools/agent-cost.py`, the record `docs/tasks/wave-45.md` [no-check: written at the close].
5. Then: Panorama pass three (Follow the phone, the steering setting, the named offset); triage of the ideas of 2026-11-21 still untriaged and the 20 answered Questions into Plans; the reroute's amendment to ADR 0040; the routing work of `docs/findings/2026-11-16-research-for-pending-ideas.md`.

## Waiting on user

- **The Panorama segment with a place selected** opens the view at that place, as the Horizon segment does: the coordinator took the recommendation of `docs/ideas/2026-11-21-which-way-is-it-the-panorama-facing-a-selected-place.md` (its Answer is still yours; the other answer changes one line). The wave paragraph's other choices (the opening's aim, the limits, the plot's shape, the credit line above a switch too wide for it, the two zoom buttons) are built and shown in the reports (ADR 0031).
- **On the phone, v0.1.40-alpha** (ADR 0031): "View from here" on a picked peak and its two help sentences; the Horizon switch opening at a long-pressed point or a search result, the long-press card winning when both are there; the first-run card's new place. Strings and frames: the merge bodies of T320, T321 and T323, and the release's changes sheet.
- **On the phone, v0.1.39-alpha and older:** the list in `docs/TASKS.md`, Owner actions pending, and the handoff of commit 5bbd0835. Still the owner's: the two feed emails, OpenFreeMap's terms.
- **The release key:** a script started by an agent can read `tools\release.properties` (T293); closing it is a permission-settings change, yours. Left for the owner: the empty folders `dystopo-wt/T275` and `C:\c`, a stray `msg.txt` in `dystopo-wt`.

## Open findings

- `docs/findings/2026-11-15-a-drive-as-a-speed-profile.md`: open; the first routing work after wave 35.
- `docs/findings/2026-11-16-research-for-pending-ideas.md`: open; the outside research per pending idea.
- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; cause unproven.
- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: open in its file.
