# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-24, at commit f7576c2a, by the main session (wave 47 closed, v0.1.43-alpha published)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, the last closed wave's record
`docs/tasks/wave-47.md`, the board's Later in `docs/TASKS.md`, and the ideas
(`node .claude/hooks/queue-list.js ideas`).

## Now

- **Wave 47 is closed and v0.1.43-alpha is published** (2026-11-24 06:43 UTC, tag on 34c7b91c, versionCode 2340; read from `gh release view`). Fifteen tasks, T339 to T353: the List view (fourth segment), the full-window Panorama, one terrain read for Horizon and Panorama, maps switched off without deleting, the Download card's "Already on this phone", "More detail from online" (ADR 0066's first slice), the zoom label, the stale-recording fix. The log's close entry of 2026-11-24 has the sweep and draft figures.
- **The sweep** now has `features` and `features-sky` (split by T353 on the owner's ruling, both 2100 s). No changes sheet this release: run the next closing sweep with `-Prev out/sweep-20261124-w47-rerun2` or the w46 folder.
- **The hold on `main`** may now be deleted by the coordinator (`hold/main` in policy.json branchDelete, owner 2026-11-23); none stands. No task branches remain.
- **Cost:** the 7-day meter 26 at planning, 38 at the close, 12 points against 16.5 by the skill's figures (they stand); about 15.5M fresh tokens. Reset 2026-11-28 03:00 UTC; the hold is 97.

## Next

1. Offer the owner the brainstorm (ADR 0065, `wave` skill Close step 6), then plan wave 48 from the board's Later and the ideas: ADR 0066's remaining build tasks (T350's proposal), ADR 0067's step two (the Horizon pane into the List).
2. Triage (the scan): the earliest open ADR prediction is due 2026-12-01; ADR 0059's per-release prediction chore (`docs/ideas/2026-11-22-adr-0064-and-0059-predictions-due.md`).
3. Carry-backs to Hatchery: the policy.json hold change and the hook tests' Linux skip (T345's follow-up, ADR 0038 departure row).

## Waiting on user

- **On the phone, v0.1.43-alpha** (ADR 0031): the List view and its button on the Panorama; the Panorama filling the window and Layers; turning a map off in Offline maps; the Download card's "Already on this phone"; Settings > Data and storage > Sources > More detail from online (on by default) and the map at country scale (online fills in 10 to 15 s after the overview); the zoom label by the scale bar. Strings and frames: the merge bodies of T341 to T344, T346, T348, T351, T352 and the release notes. For T352, a capture from the phone (`GET /packs`, `/firstrun`, `/horizon`, `/panorama`, `adb logcat -s Dystopo DystopoAssets`).
- **On the phone, v0.1.42-alpha and older:** the handoff of commit e4beaa56 and the board's Owner actions pending; the Panorama's opening at a selected place is still your Answer to give.
- **In Hatchery (ADR 0065):** land `carry-back/triage-brainstorm-and-rest` at 982e4a5, decline `carry-back/triage-brainstorm-step` at 4d00b15; deleting the old branch is yours.
- **The release key:** an agent-started script can read `tools\release.properties` (T293); closing it is a permission-settings change, yours. Left for you: the empty folders `dystopo-wt/T275` and `C:\c`, a stray `msg.txt` in `dystopo-wt`.

## Open findings

- `docs/findings/2026-11-22-research-for-the-clock-the-moving-horizon-and-the-full-screen-panorama.md`: open; outside research.
- `docs/findings/2026-11-20-aircraft-layer-sources-and-display.md`: open; outside research.
- `docs/findings/2026-11-18-github-rate-limit-reddened-two-sweep-parts.md`: open.
- `docs/findings/2026-11-16-research-for-pending-ideas.md`: open; the outside research per pending idea.
- `docs/findings/2026-11-15-a-drive-as-a-speed-profile.md`: open; the first routing work after wave 35.
- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: open (guarded); cause unproven.
- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: open.
