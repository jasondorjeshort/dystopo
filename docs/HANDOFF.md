# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-27, at the commit that carries this file (wave 49: every task merged; the close is under way), by the main session in the second clone
Resume: this file, `docs/reference/machines.md` (its second-clone paragraph), the `wave` skill, the open wave `## Wave 49` in
`docs/TASKS.md` (its four opening paragraphs first), the last closed wave's record `docs/tasks/wave-48.md`.

## Now

- **Wave 49: every task is merged** (fifteen, T375 to T388 and T390, plus T389), the last T378 (398de1a4); each has its Status line on the board and its log entry. The wave is still on the board: its Close has begun (the README's status paragraph is in; ADR 0072 records T380's folder layout).
- **In flight (2026-11-27 21:28 UTC):** a Haiku cleanup chore (the branches, worktrees and AVDs of T378, T380 and T388; `tools-main` to the tip). Next the closing sweep, alone on the PC, in a new worktree `dystopo-wt/sweep-w49` under a hold on `main`, with `-Prev` `dystopo-wt/sweep-w48/out/sweep-20261126-w48` and `-First` kept-folder, coordinate-format, glance-searching, glance-position, glance-clock, area-detail, activity-covered, longpress, peaks-tiles, search-tiles, first-run (checked with `--check-first`).
- **Cost:** the 7-day meter reset on 2026-11-27 04:00 UTC and read 1 at 04:04 UTC (the hold is 97; the next reset is 2026-12-04 04:00 UTC by this session's usage file). The wave reached the hold at 97 on 2026-11-26 23:25 UTC and waited for the reset, as the owner said it could.
- **On the PC:** restarted after the power cut, so the live viewer on 8765 and every emulator are down; stale locks and AVDs of the two cut-off emulators are their agents' to clear. The main checkout is the phone session's and sits on the merged branch `task/gps-clock-log`; `dystopo-wt/sweep-w48` holds the next release's `-Prev`; `dystopo-wt/T365` holds the flight recordings. The reports and verdicts for the merge bodies are in the first session's scratchpad folder, `w49-plan/` (it survived).

## Next

1. The closing sweep (the `sweep` skill): set the hold, launch a Sonnet agent, read the summary and the sheets. Watch two things there: in the default mode the app read "offline" with the sweep's live server as its server in several of T378's runs (not explained; wave 48's sweep was green in that mode), and the `startup` part judges the budgets no task of this wave judged.
2. Green: the release (the `release` skill, v0.1.45-alpha; the README's link and version then), the hold lifted; then the costs (`python -I tools/agent-cost.py <Tnn>`), the record (a Haiku chore moves the wave to `docs/tasks/wave-49.md` [no-check: written at the close]), the brainstorm offer and the rest review, the update from the base. Follow-ups for the next wave: the kept folder's write through a temporary document (ADR 0072's first prediction); the two findings of 2026-11-27.
3. The device tour was not shown green on T384's and T389's heads (load, by the verifiers' reading): the closing sweep reads it; if it is red there, look at T379's later start of the cache walk first.
4. Timed runs go alone: hold launches for T379's and T387's start-up runs when their implementers report them as not judged.
5. For the Close's record and offer: the a11y tool's append-at-the-end convention conflicts every time two branches add a screen (an idea: a file per task's screens); three verifiers handed back without a verdict while a long command ran and were resumed; the emulator guard stopped two emulators at 8 GB during `features` and start-up runs; the start-up budgets were judged by no task (the closing sweep's `startup` part reads them, alone).
6. Triage's scan: the earliest dated ADR prediction is due 2026-12-01; ADR 0070's two and ADR 0071's second are checked at this wave's close.

## Waiting on user

- **Your phones:** both dropped off wireless adb on 2026-11-26; reconnect them when you next need them.
- **For your eye, wave 49** (each merge commit's body has its sentences): the glance view while GPS searches and its position line (T384; the dimmed "accuracy" caption there is 2.7 to 1); the two GPS clock lines and where the reset sits (T385); the Detail sheet of a saved area (T386); the names a long press gives each mark, and which marks are blue (T383); search and peaks inside a downloaded area (T377, T378); a first launch that recentres at world zoom under the first-run card (T382); the kept folder's setup sentence, its file names and its lost line (T380).
- **Wave 48's wording and looks, v0.1.44-alpha on a phone, the flight recordings in `dystopo-wt/T365/out/flights/`, ADR 0067's step two** (its idea now has a Plan; say when): as the handoff of 2026-11-26 before this one listed them (`git show 64007414:docs/HANDOFF.md`).
- **Hatchery:** six ideas titled `For the base: ` wait here for Hatchery's triage; its clone sits beside this one, so it should already read them.

## Open findings

- `docs/findings/2026-11-27-an-areas-elevation-source-is-sometimes-missing-from-a-terrain-read.md`: open (from T378's fix rounds; not yet tried with a real download).
- `docs/findings/2026-11-27-a-download-restarted-in-the-background-waits-for-the-screen.md`: open (found by T387's verifier; a task for the next wave, and the row's "Continues by itself").
- `docs/findings/2026-11-25-tile-cache-index-walk-loops-in-the-background.md`: open for its Next 2 alone, the owner's day in the background on a phone (T379, T390 and T387 built the rest); the layout finding and the GPS clock finding are resolved (T381, T385).
- `docs/findings/2026-11-26-the-error-of-a-guessed-flight-position-measured-from-two-recordings.md`, `2026-11-24-the-map-render-thread-never-rests-while-flights-are-drawn.md`: open.
- The five planning findings of 2026-11-23 (`docs/findings/2026-11-23-*.md`): open; their statuses were not set at the triage of 2026-11-26.
- `docs/findings/2026-11-22-research-for-the-clock-the-moving-horizon-and-the-full-screen-panorama.md`, `2026-11-20-aircraft-layer-sources-and-display.md`, `2026-11-18-github-rate-limit-reddened-two-sweep-parts.md`, `2026-11-16-research-for-pending-ideas.md`, `2026-11-15-a-drive-as-a-speed-profile.md`, `2026-11-05-emulator-private-bytes-grow-per-cold-launch.md` (guarded), `2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: open.
