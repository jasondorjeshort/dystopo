# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-23, at commit e4beaa56, by the triage session (the 2026-11-22 ideas triaged and answered; wave 47 launched from the main session; v0.1.42-alpha is the latest release)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, the last closed wave's record
`docs/tasks/wave-46.md`, the board's Later in `docs/TASKS.md`, and the ideas
(`node .claude/hooks/queue-list.js ideas`).

## Now

- **Wave 46 is closed and v0.1.42-alpha is published** (2026-11-23 00:17 UTC, tag on c3778928, versionCode 2171; read from `gh release view`): the pressure unit setting (mbar by default), the Panorama's Follow phone, "Can I see it from here?", the weak-GPS note, the no-map messages. The closing sweep `out/sweep-20261122-w46` was red on `startup` alone; T338's bisection found the host slower (v0.1.41-alpha's own tag reads the same) and raised `mapview`'s ceiling to 138; the counted rerun `out/sweep-20261122-w46-rerun` was green. The log's close entry of 2026-11-22 has the figures; the record is `docs/tasks/wave-46.md`.
- **Cost:** the 7-day meter read 14 when the wave went on the board and 26 at the close: 12 points against 12 figured (the `wave` skill's figures stand); the wave's agents' fresh tokens 8.6M. Reset 2026-11-28 03:00 UTC; the hold is 97.
- **Wave 47 is launched** (the owner, 2026-11-23: "Run it."): thirteen tasks, T339 to T351, in `docs/TASKS.md`, after two surveys (written into T342 and T348) and a critic (LAUNCH AFTER EDITS, applied). First: T339 and T345 (the two Gradle slots) and T349 (no Gradle). The live viewer on 8765 is wanted (`out/coordinator-pids.txt`). Rules: no force push; `-Pkotlin.daemon.jvmargs=-Xmx4g`; at most two Gradle agents and two emulators, a verifier counting as one; ports 9012 and 9013 are another program's.
- **The ideas of 2026-11-22 are triaged and answered** (triage session, on `main` up to e4beaa56): 15 Plans, 20 Questions all answered by the owner by multiple choice (Answer: lines; the owner: "Our answers here override any decisions there", so they win over wave 47's board where the two differ), two photo duplicates folded, two ADR prediction chores filed (0040; 0064 and 0059), three brainstorm ideas recorded. New rule in `CLAUDE.md`: every question to the owner is multiple choice.

## Next

1. Reconcile wave 47's board with the triage's Answer: lines before launching more of it (sent to the main session as a message): T349 takes `core.longpaths` plus one cleanup script and may fold in the coordinator's `origin/hold/main`; T344 and T351 meet the fourth view's new Use: line; the phone's sweep timing is all three parts, at most once a week. `git log --oneline --grep "^Triage"` lists the blocks.
2. Run wave 47 by the order in its paragraph (two slots, each task after the one before it merges; T350 after T340, T341 after T343 and T350's decision). Fetch `main` before each board edit: the side session pushes there.
3. A host check when the PC is quiet (optional, a Sonnet agent): `startup` alone on v0.1.41-alpha's tag; 80 again says the slower launch was a state of the machine.

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
