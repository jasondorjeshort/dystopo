# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-16 02:23 UTC, at the commit after 7a0b69f3, by main session (wave 34: all five merged; the sweep is running)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, the "Wave 34" section of `docs/TASKS.md`,
the `sweep` and `release` skills, and the finding named under Now.

## Now

- **Wave 34: all five tasks are merged and logged** (T287 fa9c3e3e, T288 b64ac9ec, T289 944294c1, T290 6728b6cf, T291 657d0632; the README's status says so). **In flight when this was written:** the post-wave sweep from the main checkout (a Sonnet agent; the folder is the newest `out/sweep-20261116-*`) and a cleanup chore for T289's branch and T290's leftover folder and local branch (`git branch --list 'task/*'` and the `dystopo-wt` folder show what is left).
- **The finding** (`docs/findings/2026-11-15-a-drive-as-a-speed-profile.md`, open): the card's 33 % and 47 % are 19 % and 28 % by the speed profile; ADR 0063's full regain per priced turn is the error (T290: about four fifths of the saving was turn fuel, every paid turn at a junction of unnamed roads); least fuel is about as quick; bends add 4 to 10 % to drive times.
- v0.1.30-alpha is the published release; its sweep is `out/sweep-20261114-2352` (this close's `-Prev`). The live viewer is up and wanted (`out/coordinator-pids.txt`). The owner, 2026-11-15: wait out a 5-hour stall. Rules: no force push; an out-of-memory Kotlin compile clears with `"-Pkotlin.daemon.jvmargs=-Xmx4g"`; a worktree folder with long paths goes by the `\?\` delete.

## Next

1. Read the sweep agent's report. A flake (the `release` part's adb "device offline", the tour's cold direct-mode step) is rerun alone; a defect becomes a finding and the next wave's task. T288's drive step leaves "Quicker planning" set and the `montblanc` pack pushed: see whether a later part minded.
2. Green: v0.1.31-alpha by the `release` skill (ADR 0024), its notes saying the phone behaves as before; `-Prev` for the sheet is `out/sweep-20261114-2352`.
3. Close the wave: the meter's reading into the allowance sentence, the Haiku chore that moves wave 34 to `docs/tasks/wave-34.md` and lifts its follow-ups into Later, this file.
4. Next wave, from the finding: re-plan the speed-profile idea (triage); amend ADR 0063 (the per-turn regain goes: idle only or a capped regain, chosen against the profile; a cost change, so the fuel cell file's version and the card's figures move) and quote the card's line from the two routes' profiles; then Tier 2 (the card offers the other route; your own hike numbers; the winding-roads cap, whose measurement is met).
5. Two bug ideas of 2026-11-15 and 16 to triage: a first launch with no map ignores a fix after about four seconds; and the owner's idea on turns across busy roads (research first).
6. Later tiers as agreed 2026-11-15: one pack and graph revision (a proposal, on the owner's yes; it also gives `montblanc` its road names); bike turns across big roads; the ADR chores (0062, 0063, 0061, 0053); the pressure-unit and Send diagnostics ideas.

## Waiting on user

- **Four Answers in `docs/ideas/`**: a Shortest drive (at rest recommended); energy on a charge (is there an electric car or e-bike to check against); the pace from your tracks (the hike fit exists in Route times: is it what was meant, and is the drive half wanted); pack facts (one proposal, yes recommended).
- **On the phone, v0.1.30-alpha** (ADR 0031): the three names and their help, the card's fuel line and its wording, the owner's own bug (two left turns onto a side road). Strings: the merge bodies of T282 and T285.
- Still from v0.1.29-alpha: the activity chip, Trip details; OpenFreeMap's terms (the owner emails them). Left for the owner: the empty folders `dystopo-wt/T275` (busy) and `C:\c`, a stray `msg.txt` in `dystopo-wt`.

## Open findings

- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; cause unproven.
- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: open in its file.
