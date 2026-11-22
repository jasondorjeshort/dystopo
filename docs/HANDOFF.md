# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-21, by main session (wave 44 closed, v0.1.40-alpha published; no wave open)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, the last closed wave's record
`docs/tasks/wave-44.md`, Later on the board in `docs/TASKS.md`, and the ideas
(`node .claude/hooks/queue-list.js ideas`).

## Now

- **Wave 44 is closed and v0.1.40-alpha is published** (2026-11-22 01:27 UTC, read back from the remote). It ran T319 (the sweep's two checks), T320 and its fix-up T323 (the first-run card in the layout rule), T321 (a peak's "View from here"; the switch opens the Horizon view at the map's selected place) and T322 (the Panorama's pass one, ADR 0064, whose first prediction held). Its record is `docs/tasks/wave-44.md`; the close is the last entry of the log in `docs/PLANNING.md`. Its branches, worktrees and task AVDs are cleaned up. Nothing is in flight and no wave is planned.
- The closing sweep was red on `a11y` alone with the launch crash of `docs/ideas/2026-11-19-launch-crash-rootdir-after-the-guards-reboot.md` (the third time, no stack yet); the part's rerun was green. Its Plan belongs in the next wave.
- The default AVD `dystopo35` was stopped on the owner's word on 2026-11-21 and kept; both emulator slots are free.
- **Cost:** the 7-day meter read 7 at the close (1 at planning, 8 figured; reset 2026-11-28 03:00 UTC); 4.0M fresh tokens over 22 agents, the sweep's Sonnet-class agent 1.0M of it. The figures of the `wave` skill's step 3 stand. The hold is 97.
- The live viewer on 8765 is wanted (`out/coordinator-pids.txt`). Rules: no force push; `-Pkotlin.daemon.jvmargs=-Xmx4g`; at most two Gradle agents and two emulators.

## Next

1. **The Panorama view's pass two** (the phone view with drag steering): the Plan in `docs/ideas/2026-11-20-a-panorama-view-a-scrolling-ribbon-of-the-horizon.md`, no further survey. It takes T322's proposals (default field of view 60 degrees, the level line set low), calls `ViewSwitch.openAtSelection`, builds no Close button, and waits on the owner's answer in `docs/ideas/2026-11-21-which-way-is-it-the-panorama-facing-a-selected-place.md`. T322's follow-ups in Later are its inputs. Then pass three (Follow the phone).
2. **A small wave of ready Plans** that can run beside it or first: the Horizon view's Close button goes (`docs/ideas/2026-11-21-the-horizon-views-close-button-goes.md`); the launch crash above (save logcat on a launch crash, find the walker); T321's "View from here" button about 34 dp tall in landscape; the first-run card on its side at font 2.0.
3. Triage (the `triage` skill): the ideas of 2026-11-21 still untriaged (the nine of the close's list were recorded that day on the owner's word, five as files and four as dated lines in older ideas), and the 20 answered Questions into Plans (to build: the overhead banner, the fire-tanker card line, the weak-GPS note, your places in the Horizon view, the pack-facts proposal).
4. The reroute: an amendment to ADR 0040 for drives, with a short research pass; then one drive task.
5. The routing work of `docs/findings/2026-11-16-research-for-pending-ideas.md`; the sweep agent's cost (a cheaper wait than a Sonnet-class agent polling for 86 minutes).

## Waiting on user

- **On the phone, v0.1.40-alpha** (ADR 0031): "View from here" on a picked peak and its two help sentences; the Horizon switch opening at a long-pressed point or a search result, the long-press card winning when both are there (the implementer's order, to confirm or change); the first-run card's new place under the readout and the scale bar. Strings and frames: the merge bodies of T320, T321 and T323, and the release's changes sheet.
- **A choice before Panorama pass two:** the Panorama segment opens at the selected place, or at your location facing it (the which-way idea above; the coordinator recommends the selected place, with "Which way is it?" as its own action). Also T322's proposals above, shown in its merge body.
- **On the phone, v0.1.39-alpha and older:** the switch's bottom-left place; "Sky from here", the amber place line and Back to here; the 2 and 3 minute flight refresh; the dotted estimate and Route lines; the climbing threshold, flight level and Sky range figures; the peak names and their weights; Check my GPS's thresholds; "Follow the phone" held sideways. The camera is an option of the Panorama view, parked until its measurement pass. Still the owner's: the two feed emails, OpenFreeMap's terms.
- **The release key:** a script started by an agent can read `tools\release.properties` (T293); closing it is a permission-settings change, yours. Left for the owner: the empty folders `dystopo-wt/T275` and `C:\c`, a stray `msg.txt` in `dystopo-wt`.

## Open findings

- `docs/findings/2026-11-15-a-drive-as-a-speed-profile.md`: open; the first routing work after wave 35.
- `docs/findings/2026-11-16-research-for-pending-ideas.md`: open; the outside research per pending idea.
- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; cause unproven.
- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: open in its file.
