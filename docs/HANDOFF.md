# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-22, at commit 80018cdb, by main session (wave 46: the closing sweep red on `startup`, T338 in flight)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, the open wave in `docs/TASKS.md`
("## Wave 46": its paragraphs settle the choices and the order), the last closed wave's record
`docs/tasks/wave-45.md`, and the ideas (`node .claude/hooks/queue-list.js ideas`).

## Now

- **Wave 45 is closed and v0.1.41-alpha is published** (2026-11-22 13:46 UTC, tag on 8edfd5ff, versionCode 2104; read from `gh release view`): the Panorama view on the phone. Its closing sweep was red on one step's own fixed bearing; T336 fixed the step and the counted rerun `out/sweep-20261122-w45-rerun` was green. The log's entry of 2026-11-22 has the figures and the cost (8 points against 8.5 figured; the `wave` skill's figures stand).
- **Wave 46 is open, all eight tasks merged and logged** (planned 2026-11-22 on the owner's word, "After this release we can go ahead and build the next one."): the Panorama's pass three, T329 (fc0e37f5), T330 (c98e5754) and T331 (c1cbc341; ADR 0064 has its dated line); T332 (79994c44, the no-map messages), T333 (558b2e58, the weak-GPS note), T334 (965c92a1, "Can I see it from here?"), T335 (aa708deb, the sweep's diff step) and T337 (c8763398, the pressure unit setting, millibar by default, the owner's highest priority, put on the board by the owner's side session).
- **The closing sweep was red on `startup` alone** (`out\sweep-20261122-w46` on 041078ea, 6298 s; every other part green, the changes sheet's diff ok, `longmire-z11` and the three scroll checks ok): `mapview` median 103 ms against its ceiling of 87, the second red (113 in T331's verifier's sweep; 80 in wave 45's). **T338** is on the board (80018cdb) and its Opus implementer is bisecting wave 46's merges on its own emulator (worktree `dystopo-wt/T338` [no-check: a sibling folder]). After its verifier and merge: a counted rerun of `startup` (`-Only startup -RerunOf out\sweep-20261122-w46`), then the release.
- **Cost:** the 7-day meter read 14 when the wave went on the board and 19 when T337 joined (reset 2026-11-28 03:00 UTC); the wave is figured at 11. The hold is 97.
- The live viewer on 8765 is wanted (`out/coordinator-pids.txt`). Rules: no force push; `-Pkotlin.daemon.jvmargs=-Xmx4g`; at most two Gradle agents and two emulators, a verifier counting as one; ports 9012 and 9013 are another program's.

## Next

1. T338's report: a cause and a fix → its verifier (Sonnet, `-PrivateLimitGB 12`, `-Out` outside the worktree), the merge, the counted `startup` rerun; green → v0.1.42-alpha by the `release` skill. No single cause → the coordinator decides on the ceiling. The next free number is T339.
2. Close wave 46 by the `wave` skill: the close's log entry with the sweep's figures, costs by `tools/agent-cost.py` (T329 to T335, T337), the allowance sentence's closing reading, the chore that writes `docs/tasks/wave-46.md` [no-check: written at the close] and lifts the follow-ups to Later (the board is over 300 lines until then; strike finished Later lines first if the recipe's check needs room).
3. The next wave is not planned and not authorised yet: the owner's go-ahead of 2026-11-22 covered one wave. For its planning: `the-tools-tests-run-with-check` first (the coordinator's recommendation), a triage of the owner's reports of 2026-11-22 and the 28 ideas recorded that day (two sets of fourteen), the reroute's amendment to ADR 0040, the routing work of `docs/findings/2026-11-16-research-for-pending-ideas.md`.

## Waiting on user

- **On the phone, wave 46's build (v0.1.42-alpha once published)** (ADR 0031): Settings > Units and display > Pressure units (mbar by default; an install on feet that read inHg now reads mbar); the Panorama's Follow phone on a real phone (steady when still, quick when turned, the drag that adjusts the compass, the button's place on the plot's middle row where a drag can begin; the debug build's `GET /panorama` figures: Owner actions pending); "Can I see it from here?" on the long-press card and its wording ("ridge" for a near slope); the weak-GPS line while recording and that it stays once shown; "No offline map covers this view". Strings and frames: the merge bodies of T330 to T334 and T337.
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
