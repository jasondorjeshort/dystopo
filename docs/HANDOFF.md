# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-16 00:45 UTC, at the commit after 4678a946, by main session (wave 34 open; T287, T290 and T291 merged, T288 out, T289 to launch)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, the "Wave 34" section of `docs/TASKS.md`,
`docs/tasks/wave-33.md`, and `out/wave34-research/` (the critic's report and the speed-profile survey).

## Now

- **Wave 34 is open** (planned and pushed 2026-11-15; critic answered, twenty-four objections): two lanes, one agent per lane. Tools: T287, then T288, then T289 (each merge gates the next launch; all three edit `tools/sweep.ps1`). Routing: T290, then T291.
- **T287, T290 and T291 are merged and logged** (fa9c3e3e, 6728b6cf, 657d0632). **In flight:** T288's implementer (`task/T288`, emulators, not pushed yet). Cleanup owed at the close: the folder `dystopo-wt/T290` and the local branch `task/T290` (a process held a file; no agent hunts it while others build), and T291's if its chore reported the same.
- **The finding** (`docs/findings/2026-11-15-a-drive-as-a-speed-profile.md`, open): the card's 33 % and 47 % are 19 % and 28 % by the speed profile; ADR 0063's full regain per priced turn is the error (T290: about four fifths of the saving was turn fuel, every paid turn at a junction of unnamed roads); least fuel is about as quick; bends add 4 to 10 % to drive times.
- The 7-day meter read 8 at planning and 16 at the first launch (the critic and the survey); the 5-hour meter had just reset. The owner, 2026-11-15: wait out a 5-hour stall.
- v0.1.30-alpha is the published release; its sweep is `out/sweep-20261114-2352` (the close's `-Prev`). The live viewer is up and wanted (`out/coordinator-pids.txt`). Rules: no force push; an out-of-memory Kotlin compile clears with `"-Pkotlin.daemon.jvmargs=-Xmx4g"`; a worktree folder with long paths goes by the `\\?\` delete.

## Next

1. As each implementer reports: a Sonnet verifier (`brief` skill, `verifier.md`; a sweep's `-EmuPort` is even and at most 5682). Merge by the `wave` skill's sequence, then the cleanup chore.
2. After T288's merge: T289 (Opus; the last task to launch).
3. Whichever of T289, T290 and T291 merges last: its verifier runs the `route-sheet` part on the merged `main` (the Interacts line).
4. Close: README status, the sweep with `-Prev` and `-First "settings,drive"`, v0.1.31-alpha after green (ADR 0024; its notes say the phone behaves as before), the allowance reading, the wave's move to `docs/tasks/`.
5. Next wave, from the finding: re-plan the speed-profile idea (triage); amend ADR 0063 (the per-turn regain goes: idle only or a capped regain, chosen against the profile; a cost change, so the fuel cell file's version and the card's figures move) and quote the card's line from the two routes' profiles; then Tier 2 (the card offers the other route; your own hike numbers; the winding-roads cap, whose measurement is met).
6. Later tiers as agreed 2026-11-15: one pack and graph revision (a proposal, on the owner's yes); bike turns across big roads; the ADR chores (0062, 0063, 0061, 0053); the pressure-unit and Send diagnostics ideas.

## Waiting on user

- **Four Answers in `docs/ideas/`**: a Shortest drive (at rest recommended); energy on a charge (is there an electric car or e-bike to check against); the pace from your tracks (the hike fit exists in Route times: is it what was meant, and is the drive half wanted); pack facts (one proposal, yes recommended).
- **On the phone, v0.1.30-alpha** (ADR 0031): the three names and their help, the card's fuel line and its wording, the owner's own bug (two left turns onto a side road). Strings: the merge bodies of T282 and T285.
- Still from v0.1.29-alpha: the activity chip, Trip details; OpenFreeMap's terms (the owner emails them). Left for the owner: the empty folders `dystopo-wt/T275` (busy) and `C:\c`, a stray `msg.txt` in `dystopo-wt`.

## Open findings

- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; cause unproven.
- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: open in its file.
