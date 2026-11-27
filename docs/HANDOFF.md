# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-26, at the commit that carries this file (wave 49: twelve merged; T387 in verification; T378, T380 and T388 building), by the main session in the second clone
Resume: this file, `docs/reference/machines.md` (its second-clone paragraph), the `wave` skill, the open wave `## Wave 49` in
`docs/TASKS.md` (its four opening paragraphs first), the last closed wave's record `docs/tasks/wave-48.md`.

## Now

- **Wave 49 is open: fifteen tasks, T375 to T388 and T390, plus T389.** Planned, read by a critic and launched on 2026-11-26 on the owner's words (the wave paragraph quotes them). **Merged (twelve of fifteen, and T389):** T389 (940365fa), T376 (9feb7f02), T375 (bd8f3741), T379 (bd1e3b7e), T384 (bee02156), T377 (47f65111), T381 (e1b568bf), T390 (4db7929e), T385 (44f8129c), T386 (841d0d60), T382 (9a4f0874), T383 (88016013); each has its Status line on the board and its log entry.
- **In flight (2026-11-27 09:24 UTC), every remaining task launched, four Gradle agents:** a Sonnet verifier on T387 (`task/T387`, 52d6b681; its implementer's report is `<scratchpad>/w49-plan/report-T387.txt`); Opus implementers on T378 (`task/T378`), T380 (`task/T380`) and T388 (`task/T388`), none pushed yet. Reports and round-1 verdicts are kept as `<scratchpad>/w49-plan/report-Tnn.txt` and `verify-Tnn.txt` for the merge bodies.
- **Cost:** the 7-day meter reset on 2026-11-27 04:00 UTC and read 1 at 04:04 UTC (the hold is 97; the next reset is 2026-12-04 04:00 UTC by this session's usage file). The wave reached the hold at 97 on 2026-11-26 23:25 UTC and waited for the reset, as the owner said it could.
- **On the PC:** the main checkout is the phone session's and sits on the merged branch `task/gps-clock-log`; `dystopo-wt/sweep-w48` holds the next release's `-Prev`; `dystopo-wt/T365` holds the flight recordings. Two phones of the owner's dropped off wireless adb during T389's verify (cause not found).

## Next

1. On each verdict: MERGE, the merge sequence (the merge run unpiped, its own result read); FIX, back to its implementer by its id. As T378, T380 and T388 report: a Sonnet verifier each, run from `dystopo-wt/tools-main` with the report in its brief, told to wait for the script's process to end (the summary's RESULT line is provisional) and to hand back only with a verdict. When `main` moves under a verified branch, its implementer merges `origin/main` into it and its verifier reads the delta (the coordinator, for one docs row): five branches of this wave needed that round, each for a block appended at the same end of the a11y tool's two files.
2. Nothing is left to launch. After the last merge: a cleanup chore for the merged branches and worktrees (T381, T390, T385, T386, T382, T383 and the last three) and the folder `dystopo-wt/T377-verify`; then the wave's Close (the `wave` skill): the README's status paragraph, the closing sweep alone on the PC with `-Prev` from `dystopo-wt/sweep-w48`, the release, the costs, the record, the brainstorm offer, the base's update.
3. The device tour was not shown green on T384's and T389's heads (load, by the verifiers' reading): the closing sweep reads it; if it is red there, look at T379's later start of the cache walk first.
4. Timed runs go alone: hold launches for T379's and T387's start-up runs when their implementers report them as not judged.
5. For the Close's record and offer: the a11y tool's append-at-the-end convention conflicts every time two branches add a screen (an idea: a file per task's screens); three verifiers handed back without a verdict while a long command ran and were resumed; the emulator guard stopped two emulators at 8 GB during `features` and start-up runs; the start-up budgets were judged by no task (the closing sweep's `startup` part reads them, alone).
6. Triage's scan: the earliest dated ADR prediction is due 2026-12-01; ADR 0070's two and ADR 0071's second are checked at this wave's close.

## Waiting on user

- **Your phones:** both dropped off wireless adb on 2026-11-26; reconnect them when you next need them.
- **For your eye after T389:** the glance view's figures at font 2.0 on its side are 55 px with the rows reserved (126 px at font 1.0 on its side).
- **Wave 48's wording and looks, v0.1.44-alpha on a phone, the flight recordings in `dystopo-wt/T365/out/flights/`, ADR 0067's step two** (its idea now has a Plan; say when): as the handoff of 2026-11-26 before this one listed them (`git show 64007414:docs/HANDOFF.md`).
- **Hatchery:** six ideas titled `For the base: ` wait here for Hatchery's triage; its clone sits beside this one, so it should already read them.

## Open findings

- `docs/findings/2026-11-26-gps-time-fault-restarts-the-readout-over-and-over.md`: open (T385).
- `docs/findings/2026-11-25-tile-cache-index-walk-loops-in-the-background.md` (T379, T390, T387) and `2026-11-25-the-map-layout-flips-at-font-2-with-the-bar-unfolded-and-the-turn-back-card.md` (T381): open, in this wave.
- `docs/findings/2026-11-26-the-error-of-a-guessed-flight-position-measured-from-two-recordings.md`, `2026-11-24-the-map-render-thread-never-rests-while-flights-are-drawn.md`: open.
- The five planning findings of 2026-11-23 (`docs/findings/2026-11-23-*.md`): open; their statuses were not set at the triage of 2026-11-26.
- `docs/findings/2026-11-22-research-for-the-clock-the-moving-horizon-and-the-full-screen-panorama.md`, `2026-11-20-aircraft-layer-sources-and-display.md`, `2026-11-18-github-rate-limit-reddened-two-sweep-parts.md`, `2026-11-16-research-for-pending-ideas.md`, `2026-11-15-a-drive-as-a-speed-profile.md`, `2026-11-05-emulator-private-bytes-grow-per-cold-launch.md` (guarded), `2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: open.
