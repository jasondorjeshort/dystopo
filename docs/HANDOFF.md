# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-26, at the commit that carries this file (wave 49: three merged, four implementers in flight), by the main session in the second clone
Resume: this file, `docs/reference/machines.md` (its second-clone paragraph), the `wave` skill, the open wave `## Wave 49` in
`docs/TASKS.md` (its four opening paragraphs first), the last closed wave's record `docs/tasks/wave-48.md`.

## Now

- **Wave 49 is open: fifteen tasks, T375 to T388 and T390, plus T389.** Planned, read by a critic and launched on 2026-11-26 on the owner's words (the wave paragraph quotes them). **Merged:** T389 (940365fa), T376 (9feb7f02), T375 (bd8f3741); each has its Status line on the board and its log entry.
- **In flight, four Opus implementers launched 2026-11-26 about 21:55 UTC, no branch pushed yet:** T377 (`task/T377`), T379 (`task/T379`), T384 (`task/T384`), T381 (`task/T381`). A Haiku chore is making the tools' copy `dystopo-wt/tools-main` and cleaning up the three merged branches and their worktrees.
- **Cost:** the 7-day meter read 94 at 21:53 UTC against the hold of 97; it resets 2026-11-27 04:00 UTC (this session's usage file). The owner: "Go now. We can just hold for a few hours when the hold hits." At the hold the gate refuses every new agent, a verifier included; a fix round may resume, by its id, an agent launched below the hold (T376).
- **On the PC:** the main checkout is the phone session's and sits on the merged branch `task/gps-clock-log`; `dystopo-wt/sweep-w48` holds the next release's `-Prev`; `dystopo-wt/T365` holds the flight recordings. Two phones of the owner's dropped off wireless adb during T389's verify (cause not found).

## Next

1. As each implementer reports: a Sonnet verifier with the report in its brief, run from `dystopo-wt/tools-main` (T381's with `-SweepThrough two-maps`); at the hold the verifier waits for the reset. Then the merge sequence, and after a merge that touches `tools/` a chore moves `tools-main` to the new tip.
2. Launch as slots free, each after the tasks in its brackets on the board: T382, T383, then T378, T390, T385, T386, T380, T387, T388. Every brief carries the wave paragraph's `GRADLE_OPTS`, `-WaitForSlot 60`, the fixture and the Hatchery sentence; the briefs of this session are the pattern (scratchpad, not kept).
3. Before T385 launches: one read-only research agent (a fix's own time against its elapsed-realtime stamp: the platform's guidance, two open trackers); its answer goes into T385's entry.
4. Timed runs go alone: hold launches for T379's and T387's start-up runs when their implementers report them as not judged.
5. A small release may be cut once T384, T385, T379 and T381 are merged (the wave paragraph); otherwise the closing sweep, with `-Prev` from `dystopo-wt/sweep-w48`.
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
