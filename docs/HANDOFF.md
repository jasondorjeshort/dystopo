# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-17 03:20 UTC, at the commit after 8837fcfe, by main session (wave 35: all eleven merged, the closing sweep running)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, the last closed wave's record
`docs/tasks/wave-34.md`, the board's Later in `docs/TASKS.md`, the finding named under Now, and the ideas
(`node .claude/hooks/queue-list.js ideas`).

## Now

- **Wave 35: all eleven merged** (T292 to T302; log entries and Status lines written; the last, T300, at f78f8218). **The closing sweep is running** from the main checkout (`-Prev out\sweep-20261115-2123`, started about 03:15 UTC 2026-11-17; a Sonnet agent reads its summary and both sheets). A cleanup chore for T293, T295, T299, T300 (branches, worktrees) is running beside it. The meter at the last merge: 5-hour 49 % (resets 04:30 UTC), 7-day 73.
- The sweep's `-Prev` has no sidecars, so its sides read `unknown` and are kept on the before-and-after sheet (expected; T295). Known flakes to watch in it: `features`' Settings > Sources failing to reach the LAN server; the device tour's offline world view at z3 not drawn (drawn in T295's rerun).
- **For the owner:** T293's permission test was not refused: a script run by an agent can read `tools\release.properties` in the main checkout (the deny rule covers the Read tool only). Nothing read a value; closing it is a permission-settings change, the owner's.
- Still owed by the coordinator at the close: the README status; the meter at the close in the wave paragraph's allowance sentence (planned 61 to 63.5) with the per-task figure revised if more than five points off; per-task costs by `python -I tools/agent-cost.py <Tnn>`; the wave moved to `docs/tasks/wave-35.md` (Haiku chore). v0.1.31-alpha is the last release; the live viewer on 8765 is wanted (`out/coordinator-pids.txt`). Rules: no force push; `-Pkotlin.daemon.jvmargs=-Xmx4g`; long-path folders go by the `\\?\` delete.

## Next

1. Read the sweep's report. Green: publish v0.1.32-alpha by the `release` skill (T301 and T302 are user-visible: Send diagnostics ends in Share and Open GitHub issue; Report recent stats; release notes by `tools/release-notes.py`). Red: a fix task per finding, from the report. Then the close steps above and the handoff.
2. Then the routing work, in the order of the research finding's Drive routing section (`docs/findings/2026-11-16-research-for-pending-ideas.md`): ADR 0063's amendment with the Outcomes of 0062 and 0063; a new block for the speed-profile idea and a new idea for the profile on the phone; the card offers the other route; pack facts (on the owner's yes).
3. Cheap phone fixes ready as Plans: the first launch that ignores a late fix, the days-old recording, the blue dots, gross gain per source, the pressure unit.

## Waiting on user

- **Answers in `docs/ideas/`**: two new Questions of 2026-11-16 (the sharp-bend warning, clock time and sun along a route); a Shortest drive (at rest recommended; your words of 2026-11-16 on route options are noted there, the Answer left blank); energy on a charge (is there an electric car, a hybrid or an e-bike to check against); the pace from your tracks (the hike half: is the fit in Route times what was meant); pack facts (one proposal, yes recommended).
- **On the phone, v0.1.31-alpha** (ADR 0031; it updates over v0.1.30-alpha and looks the same): the three names of Plan drives for and their help, the card's fuel line and its wording, the owner's own bug (two left turns onto a side road). Strings: the merge bodies of T282 and T285. In Settings the Extended readout row shows Off and Pressure both filled: by design, a matter for the owner's eye.
- Still from v0.1.29-alpha: the activity chip, Trip details; OpenFreeMap's terms (the owner emails them). Left for the owner: the empty folders `dystopo-wt/T275` (busy) and `C:\c`, a stray `msg.txt` in `dystopo-wt`.

## Open findings

- `docs/findings/2026-11-15-a-drive-as-a-speed-profile.md`: open; the first routing work after wave 35.
- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; cause unproven.
- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: open in its file.
