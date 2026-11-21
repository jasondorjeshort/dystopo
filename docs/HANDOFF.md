# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-21, by main session (wave 43 closed, v0.1.39-alpha published)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, the last closed wave's record
`docs/tasks/wave-43.md`, Later on the board in `docs/TASKS.md`, and the ideas
(`node .claude/hooks/queue-list.js ideas`).

## Now

- **Wave 43 is closed and v0.1.39-alpha is published** (2026-11-21). It ran T315 to T317 and the sweep fix T318, which rebaselined the a11y findings from the longer long-press card. Its record is `docs/tasks/wave-43.md`. Branches, worktrees and task AVDs are cleaned up. No wave is open.
- **For the owner:** the emulator `emulator-5554` (default AVD `dystopo35`) is still running from the v0.1.38 draft check. Stop it without `-Delete` if wanted.
- **Cost:** the 7-day meter read 23 at the close (21 at planning; shared with other work) and resets 2026-11-27 03:00 UTC. The figures were revised at this close to 1.0 a task, 1 a wave and 0.5 a release (the `wave` skill, step 3). The hold is 97.
- The live viewer on 8765 is wanted (`out/coordinator-pids.txt`). Rules: no force push; `-Pkotlin.daemon.jvmargs=-Xmx4g`; at most two Gradle agents and two emulators.

## Next

1. Small follow-ups from wave 43 (Later): the debug `flights` part's `slow` step writes no frame for the sweep's `-First`; devtool `aircraft` prints a null `groundM`; android-a11y.py has no screen for the Horizon view at a chosen point; the stale `settings-data:1.0` baseline line. `DivergenceLayer` binds without unbinding (T314's finding).
2. The Panorama view (the owner: "soon but after the airplane tracks"), then the window-seat flip of the Horizon view; "View from here" on a peak's card and "View from map centre" (Plan in `docs/ideas/2026-11-20-horizon-view-from-another-place-and-saying-where-it-is.md`).
3. The reroute: an amendment to ADR 0040 for drives, with a short research pass; then one drive task.
4. The routing work of `docs/findings/2026-11-16-research-for-pending-ideas.md`; the cheap phone fixes ready as Plans.

## Waiting on user

- **On the phone, v0.1.39-alpha** (ADR 0031): the Map | Horizon switch's new bottom-left place in both views; the credit line cut short beside it held upright; the Horizon plot held sideways (about 300 dp instead of 363); "Sky from here" on the long-press card, the amber "At <name>, <distance> <direction> of you" line and Back to here; the refresh choices 2 min and 3 min with the line saying how long requests last; the card's "N ft above the ground". At the largest text the first-run card covers the activity chip and readout. Strings: the merge bodies of T315, T316 and T317.
- **Answers in `docs/ideas/`**: ten new Questions of 2026-11-21 on flights (crossing the sun or moon, home site and feed emails, airspace for drone flyers, firefighting aircraft, contrail forecast, own ADS-B feeder, camera to name a plane, a pocket receiver, overhead alerts, the view from that plane); the next Horizon layer; the sharp-bend warning; clock time and sun along a route; a Shortest drive; energy on a charge; the pace from your tracks; pack facts.
- **On the phone, v0.1.38-alpha:** the dotted estimate and Route lines (Selected flight or All flights); whether more airline flights show routes; the ±300 ft/min climbing threshold, the 18,000 ft flight level, Sky range's Overhead 45° and Near 50 km.
- **Older phone checks** (v0.1.35 to v0.1.29): the peak names and their weights; Check my GPS's thresholds; "Follow the phone" held sideways; Report a problem's Share and Open GitHub issue; Plan drives for and the fuel line; the activity chip and Trip details; OpenFreeMap's terms (the owner emails them). **The release key:** a script started by an agent can read `tools\release.properties` (T293); closing it is a permission-settings change, yours. Left for the owner: the empty folders `dystopo-wt/T275` and `C:\c`, a stray `msg.txt` in `dystopo-wt`.

## Open findings

- `docs/findings/2026-11-15-a-drive-as-a-speed-profile.md`: open; the first routing work after wave 35.
- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; cause unproven.
- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: open in its file.
