# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-26, at the commit that carries this file (wave 49: four merged, three being verified, T390 building; the meter reset), by the main session in the second clone
Resume: this file, `docs/reference/machines.md` (its second-clone paragraph), the `wave` skill, the open wave `## Wave 49` in
`docs/TASKS.md` (its four opening paragraphs first), the last closed wave's record `docs/tasks/wave-48.md`.

## Now

- **Wave 49 is open: fifteen tasks, T375 to T388 and T390, plus T389.** Planned, read by a critic and launched on 2026-11-26 on the owner's words (the wave paragraph quotes them). **Merged:** T389 (940365fa), T376 (9feb7f02), T375 (bd8f3741), T379 (bd1e3b7e); each has its Status line on the board and its log entry.
- **In flight (launched 2026-11-27 04:05 UTC, after the meter's reset):** Sonnet verifiers on T384 (`task/T384`, 90773e93), T381 (`task/T381`, 5349dcc6, `-SweepThrough two-maps`) and T377 (`task/T377`, d74983b9, its `features` part whole); an Opus implementer on T390 (`task/T390`); a read-only research agent for T385 (its answer to `<scratchpad>/w49-plan/research-fix-time.md`). The implementers' reports are kept as `<scratchpad>/w49-plan/report-Tnn.txt` for the merge bodies. `dystopo-wt/tools-main` stands at 8f3b36f0.
- **Cost:** the 7-day meter reset on 2026-11-27 04:00 UTC and read 1 at 04:04 UTC (the hold is 97; the next reset is 2026-12-04 04:00 UTC by this session's usage file). The wave reached the hold at 97 on 2026-11-26 23:25 UTC and waited for the reset, as the owner said it could.
- **On the PC:** the main checkout is the phone session's and sits on the merged branch `task/gps-clock-log`; `dystopo-wt/sweep-w48` holds the next release's `-Prev`; `dystopo-wt/T365` holds the flight recordings. Two phones of the owner's dropped off wireless adb during T389's verify (cause not found).

## Next

1. On each verifier's MERGE: the merge sequence (T384 first if two are ready: the readout chain waits on it), a cleanup chore, and the chore moves `tools-main` to the new tip (all three touch `tools/`). A FIX goes back to its implementer by its id.
2. Launch as slots free (four Gradle agents at once, a verifier counting as one), each after the tasks in its brackets on the board: T387, T382, T383, then T378 (T377), T385 (T384, its research written into its entry), T386 (T377), T380, T388. Every brief carries the wave paragraph's `GRADLE_OPTS`, `-WaitForSlot 120`, the fixture (T377's `tile_area_fixture` and `AreaTiles.vectorTile`) and the Hatchery sentence, and for T387 the way to start the process with no activity (T379's Status line).
3. When the research for T385 reports: write its answer into T385's entry on the board (the threshold, the fallback with no elapsed stamp, what a track point's time is for a flagged fix).
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
