# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-16 15:00 UTC, at the commit after 3e91fadc, by main session (the owner's pick for the next wave; ideas recorded)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, the last closed wave's record
`docs/tasks/wave-34.md`, the board's Later in `docs/TASKS.md`, the finding named under Now, and the ideas
(`node .claude/hooks/queue-list.js ideas`).

## Now

- **No wave is open and nothing is running.** Wave 34 (T287 to T291) is closed; no task branch is left. **v0.1.31-alpha is published** (tag on bfd6121d, versionCode 1733): tooling, the phone behaves as in v0.1.30-alpha. Its sweep is `out/sweep-20261115-2123` (the next close's `-Prev`): red on `a11y` alone, green on the rerun, a flake of the tool. No frame of it is outside the public areas (T287).
- **The owner's pick for the next wave** (2026-11-16, for another session): "a cleanup of the build process itself. We've had a lot of ideas on how to make it easier and I think most haven't been done at all. This might not even lead to a release."
- **The finding** (`docs/findings/2026-11-15-a-drive-as-a-speed-profile.md`, open): the card's 33 % and 47 % are 19 % and 28 % by the speed profile; ADR 0063's full regain per priced turn is the error. Its fix now follows the cleanup wave; v0.1.31-alpha's notes and the README say the fix is next.
- The 7-day meter read 30 at wave 34's close against a figure of 18.5; the account ran other work beside the wave, so the per-task figure was left.
- The live viewer on 8765 is wanted (`out/coordinator-pids.txt`; up at 04:35 UTC, not read since); the standalone server that file lists on 8800 is not running. The owner, 2026-11-15: wait out a 5-hour stall. Rules: no force push; an out-of-memory Kotlin compile clears with `"-Pkotlin.daemon.jvmargs=-Xmx4g"`; a worktree folder with long paths goes by the `\\?\` delete.

## Next

1. Plan the build-process wave (`wave` skill) from the `kind: chore` ideas that carry a Plan and the tooling lines of Later (wave 34's). Read on 2026-11-16, each with a dated line from wave 34's close: the meter per agent by a hook, the release notes' mechanical sections by a script, sweep frames that carry their place (and a sheet that can leave a pair out), a new sweep part run in sweep order, the sweep's retry and a count of reruns, the Checks file and a Haiku-class verifier, the a11y baseline's kB line; from Later: the drive step restores what it leaves, `geo fix` with an altitude, the route sheet in the nightly sweep. Which of them, and whether "the build process" also means the Gradle build, is asked of the owner at planning.
2. Then the routing work: a new block for the speed-profile idea (its Plan is spent); amend ADR 0063 (idle only or a capped regain, chosen against the profile; the fuel cell file's version and the card's figures move) and quote the card's line from the two routes' profiles; then the card offers the other route (the owner, 2026-11-16: several options, each with its trade, and Plan drives for goes afterwards), your own hike numbers.
3. The drive half of the pace idea is answered (yes): a measurement first, the owner's drives matched to the road, on the phone only. It gates the winding-roads build (the owner on bends, in that idea).
4. Triage: a first launch with no map ignores a late fix; the two ideas of 2026-11-16 (clock time along a route with the sun; a warning before a sharp bend); turns across busy roads (research; possible traffic-count sources are listed in its file).
5. Later tiers as agreed 2026-11-15: one pack and graph revision (a proposal, on the owner's yes); bike turns across big roads; the ADR chores (0062, 0063, 0061, 0053); the pressure-unit and Send diagnostics ideas.

## Waiting on user

- **Answers in `docs/ideas/`**: a Shortest drive (at rest recommended; your words of 2026-11-16 on route options are noted there, the Answer left blank); energy on a charge (is there an electric car, a hybrid or an e-bike to check against); the pace from your tracks (the hike half: is the fit in Route times what was meant); pack facts (one proposal, yes recommended).
- **On the phone, v0.1.31-alpha** (ADR 0031; it updates over v0.1.30-alpha and looks the same): the three names of Plan drives for and their help, the card's fuel line and its wording, the owner's own bug (two left turns onto a side road). Strings: the merge bodies of T282 and T285. In Settings the Extended readout row shows Off and Pressure both filled: by design, a matter for the owner's eye.
- Still from v0.1.29-alpha: the activity chip, Trip details; OpenFreeMap's terms (the owner emails them). Left for the owner: the empty folders `dystopo-wt/T275` (busy) and `C:\c`, a stray `msg.txt` in `dystopo-wt`.

## Open findings

- `docs/findings/2026-11-15-a-drive-as-a-speed-profile.md`: open; the first routing work after the cleanup wave.
- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; cause unproven.
- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: open in its file.
