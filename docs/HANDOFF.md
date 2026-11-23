# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-23, at commit e29e69e8, by main session (wave 47 planned on the board, not launched; v0.1.42-alpha is the latest release)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, the last closed wave's record
`docs/tasks/wave-46.md`, the board's Later in `docs/TASKS.md`, and the ideas
(`node .claude/hooks/queue-list.js ideas`).

## Now

- **Wave 46 is closed and v0.1.42-alpha is published** (2026-11-23 00:17 UTC, tag on c3778928, versionCode 2171; read from `gh release view`): the pressure unit setting (mbar by default), the Panorama's Follow phone, "Can I see it from here?", the weak-GPS note, the no-map messages. The closing sweep `out/sweep-20261122-w46` was red on `startup` alone; T338's bisection found the host slower (v0.1.41-alpha's own tag reads the same) and raised `mapview`'s ceiling to 138; the counted rerun `out/sweep-20261122-w46-rerun` was green. The log's close entry of 2026-11-22 has the figures; the record is `docs/tasks/wave-46.md`.
- **Cost:** the 7-day meter read 14 when the wave went on the board and 26 at the close: 12 points against 12 figured (the `wave` skill's figures stand); the wave's agents' fresh tokens 8.6M. Reset 2026-11-28 03:00 UTC; the hold is 97.
- **Wave 47 is planned and not launched** (the owner, 2026-11-23: "Make the list but we wont run it yet"): eleven tasks, T339 to T349, in `docs/TASKS.md`; its premises were read from the code by two read-only agents; no research agent, critic or implementer has run. Nothing runs: no task branch, no task worktree, no emulator. The live viewer on 8765 is wanted (`out/coordinator-pids.txt`). Rules: no force push; `-Pkotlin.daemon.jvmargs=-Xmx4g`; at most two Gradle agents and two emulators, a verifier counting as one; ports 9012 and 9013 are another program's.

## Next

1. Wave 47 waits on the owner's word, and on any change the owner makes to its list. Then, in the `wave` skill's order: the three surveys its wave paragraph names (R1 to R3) and the `triage` skill over the ideas of 2026-11-22 that carry no block; the critic; the launch (T339, T345 and T349 first). Left out of the wave by name, in its paragraph: the Panorama on the whole screen (joins on the owner's Answer), the reroute's amendment to ADR 0040, the routing work of `docs/findings/2026-11-16-research-for-pending-ideas.md`.
2. A host check when the PC is quiet (optional, a Sonnet agent): `startup` alone on v0.1.41-alpha's tag; 80 again says the slower launch was a state of the machine.

## Waiting on user

- **On the phone, v0.1.42-alpha** (ADR 0031): Settings > Units and display > Pressure units (mbar by default; your install on feet that read inHg now reads mbar); the Panorama's Follow phone on a real phone (steady when still, quick when turned, the drag that adjusts the compass, the button's place on the plot's middle row where a drag can begin; the debug build's `GET /panorama` figures: Owner actions pending); "Can I see it from here?" on the long-press card and its wording ("ridge" for a near slope); the weak-GPS line while recording and that it stays once shown; "No offline map covers this view". Strings and frames: the merge bodies of T330 to T334 and T337. Strings for the phone: the release notes.
- **On the phone, v0.1.41-alpha** (ADR 0031): the Panorama view, its opening aim, the tilt and zoom limits, the two zoom buttons, a row or a name turning the view, the edge arrow's line, Back's order, the credit line above the three-segment switch; the Horizon view without its Close button. Strings and frames: the merge bodies of T325, T326 and T327, the release notes and its changes sheet. **With a place selected the Panorama opens there**, as the Horizon view does: the coordinator took the recommendation of `docs/ideas/2026-11-21-which-way-is-it-the-panorama-facing-a-selected-place.md`, whose Answer is still yours.
- **In Hatchery (ADR 0065, the brainstorm practice):** land `carry-back/triage-brainstorm-and-rest` at 982e4a5, decline `carry-back/triage-brainstorm-step` at 4d00b15 as superseded, and file the rule as an idea there (draft text: the ADR's Decision); Hatchery's tree needs its unpushed proposal-0025 commit and two untracked items settled first. Deleting the old branch is yours.
- **On the phone, v0.1.40-alpha** (ADR 0031): "View from here" on a picked peak and its two help sentences; the Horizon switch opening at a long-pressed point or a search result, the long-press card winning when both are there; the first-run card's new place. Strings and frames: the merge bodies of T320, T321 and T323, and the release's changes sheet.
- **On the phone, v0.1.39-alpha and older:** the list in `docs/TASKS.md`, Owner actions pending, and the handoff of commit 5bbd0835. Still the owner's: the two feed emails, OpenFreeMap's terms.
- **The release key:** a script started by an agent can read `tools\release.properties` (T293); closing it is a permission-settings change, yours. Left for the owner: the empty folders `dystopo-wt/T275` and `C:\c`, a stray `msg.txt` in `dystopo-wt`.

## Open findings

- `docs/findings/2026-11-15-a-drive-as-a-speed-profile.md`: open; the first routing work after wave 35.
- `docs/findings/2026-11-16-research-for-pending-ideas.md`: open; the outside research per pending idea.
- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; cause unproven.
- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: open in its file.
