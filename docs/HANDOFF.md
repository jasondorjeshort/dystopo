# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-26, at the commit that carries this file (wave 49: nine merged; T386 in verification; T383, T387 and T382 building), by the main session in the second clone
Resume: this file, `docs/reference/machines.md` (its second-clone paragraph), the `wave` skill, the open wave `## Wave 49` in
`docs/TASKS.md` (its four opening paragraphs first), the last closed wave's record `docs/tasks/wave-48.md`.

## Now

- **Wave 49 is open: fifteen tasks, T375 to T388 and T390, plus T389.** Planned, read by a critic and launched on 2026-11-26 on the owner's words (the wave paragraph quotes them). **Merged (nine of fifteen, and T389):** T389 (940365fa), T376 (9feb7f02), T375 (bd8f3741), T379 (bd1e3b7e), T384 (bee02156), T377 (47f65111), T381 (e1b568bf), T390 (4db7929e), T385 (44f8129c); each has its Status line on the board and its log entry.
- **In flight (2026-11-27 07:58 UTC), four Gradle agents:** T386 (`task/T386`): its verifier's MERGE WITH NOTES on e1937352 is in, and its implementer is merging `origin/main` into the branch (a conflict in `android-online.md`) and fixing three size wordings; Opus implementers on T383 (`task/T383`), T387 (`task/T387`) and T382 (`task/T382`), none pushed yet. Reports and round-1 verdicts are kept as `<scratchpad>/w49-plan/report-Tnn.txt` and `verify-Tnn.txt` for the merge bodies.
- **Cost:** the 7-day meter reset on 2026-11-27 04:00 UTC and read 1 at 04:04 UTC (the hold is 97; the next reset is 2026-12-04 04:00 UTC by this session's usage file). The wave reached the hold at 97 on 2026-11-26 23:25 UTC and waited for the reset, as the owner said it could.
- **On the PC:** the main checkout is the phone session's and sits on the merged branch `task/gps-clock-log`; `dystopo-wt/sweep-w48` holds the next release's `-Prev`; `dystopo-wt/T365` holds the flight recordings. Two phones of the owner's dropped off wireless adb during T389's verify (cause not found).

## Next

1. T386: when its implementer reports the merged head, its verifier (by its id) reads that delta, then the merge sequence (run the merge unpiped and read its own result). As T383, T387 and T382 report: a Sonnet verifier each, run from `dystopo-wt/tools-main` with the report in its brief. When `main` moves under a verified branch, its implementer merges `origin/main` into it and the delta is read (by its verifier for code or tools, by the coordinator for one docs row). A cleanup chore is owed for T381's, T390's and T385's branches and worktrees and the folder `dystopo-wt/T377-verify`, and `tools-main` moves to the tip with it.
2. Launch as slots free (four Gradle agents at once, a verifier counting as one; the two emulators are the real queue): T378, T380, then T388 (after T383; T385 and T381 are in; its entry carries two follow-ups of T385). Every brief carries the wave paragraph's `GRADLE_OPTS`, `-WaitForSlot 180`, the Hatchery sentence, the packs copied into the worktree's own `out/`, no HEAD moved in either checkout, and for T378 the fixture and the shared read (T377's Status line).
3. The device tour was not shown green on T384's and T389's heads (load, by the verifiers' reading): the closing sweep reads it; if it is red there, look at T379's later start of the cache walk first.
4. Timed runs go alone: hold launches for T379's and T387's start-up runs when their implementers report them as not judged.
5. The small release's set is merged (T384, T385, T379, T381, with T390 and T377): the coordinator may hold launches for a sweep and publish from it (the wave paragraph), or go on to the closing sweep; either way `-Prev` is in `dystopo-wt/sweep-w48` and the sweep runs alone (ADR 0070).
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
