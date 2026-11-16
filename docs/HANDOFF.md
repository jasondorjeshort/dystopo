# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-16 04:35 UTC, at the commit after e2ac5cda, by main session (wave 34 closed; v0.1.31-alpha published)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, the last closed wave's record
`docs/tasks/wave-34.md`, the board's Later in `docs/TASKS.md`, and the finding named under Now.

## Now

- **No wave is open and nothing is running.** Wave 34 (T287 to T291) is closed; no task branch is left. **v0.1.31-alpha is published** (tag on bfd6121d, versionCode 1733): tooling, the phone behaves as in v0.1.30-alpha. Its sweep is `out/sweep-20261115-2123` (the next close's `-Prev`): red on `a11y` alone, green on the rerun, a flake of the tool (the kB idea below). No frame of it is outside the public areas (T287).
- **The finding** (`docs/findings/2026-11-15-a-drive-as-a-speed-profile.md`, open): the card's 33 % and 47 % are 19 % and 28 % by the speed profile; ADR 0063's full regain per priced turn is the error; least fuel is about as quick; bends add 4 to 10 % to drive times. The release notes say the card's figure is overstated and the fix is next.
- The 7-day meter read 30 at the close against a figure of 18.5; the account ran other work beside the wave, so the per-task figure was left (the wave paragraph says so).
- The live viewer on 8765 is up and wanted (`out/coordinator-pids.txt`); the standalone server that file lists on 8800 is no longer running. The owner, 2026-11-15: wait out a 5-hour stall. Rules: no force push; an out-of-memory Kotlin compile clears with `"-Pkotlin.daemon.jvmargs=-Xmx4g"`; a worktree folder with long paths goes by the `\\?\` delete.

## Next

1. Plan the next wave (`wave` skill) from the finding and the T291 line of Later: re-triage the speed-profile idea; amend ADR 0063 (the per-turn regain goes: idle only or a capped regain, chosen against the profile; a cost change, so the fuel cell file's version and the card's figures move) and quote the card's line from the two routes' profiles; then Tier 2 (the card offers the other route; your own hike numbers; the winding-roads cap, whose measurement is met).
2. Triage three ideas of 2026-11-15 and 16: a first launch with no map ignores a fix after about four seconds; the a11y baseline misses a line when its size reads in kB (small, a tools task); the owner's idea on turns across busy roads (research first).
3. Small tooling from Later, wave 34's lines: the sweep's drive step restores the setting and the pack it leaves; `geo fix` with an altitude; the route sheet in the nightly sweep and a `montblanc` hike on its list.
4. Later tiers as agreed 2026-11-15: one pack and graph revision (a proposal, on the owner's yes; it also gives `montblanc` its road names); bike turns across big roads; the ADR chores (0062, 0063, 0061, 0053); the pressure-unit and Send diagnostics ideas.

## Waiting on user

- **Four Answers in `docs/ideas/`**: a Shortest drive (at rest recommended); energy on a charge (is there an electric car or e-bike to check against); the pace from your tracks (the hike fit exists in Route times: is it what was meant, and is the drive half wanted); pack facts (one proposal, yes recommended).
- **On the phone, v0.1.31-alpha** (ADR 0031; it updates over v0.1.30-alpha and looks the same): the three names of Plan drives for and their help, the card's fuel line and its wording, the owner's own bug (two left turns onto a side road). Strings: the merge bodies of T282 and T285; the sweep's frames of them are on the release's before-and-after sheet. In Settings the Extended readout row shows Off and Pressure both filled: by design (an on/off pair and a separate Pressure toggle), a matter for the owner's eye.
- Still from v0.1.29-alpha: the activity chip, Trip details; OpenFreeMap's terms (the owner emails them). Left for the owner: the empty folders `dystopo-wt/T275` (busy) and `C:\c`, a stray `msg.txt` in `dystopo-wt`.

## Open findings

- `docs/findings/2026-11-15-a-drive-as-a-speed-profile.md`: open; the next wave's first work.
- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; cause unproven.
- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: open in its file.
