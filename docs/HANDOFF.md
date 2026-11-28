# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-28, at the commit that carries this file (wave 49: v0.1.45-alpha published; the wave's close is under way), by the main session in the second clone
Resume: this file, `docs/reference/machines.md` (its second-clone paragraph), the `wave` skill, the open wave `## Wave 49` in
`docs/TASKS.md` (its four opening paragraphs first), the last closed wave's record `docs/tasks/wave-48.md`.

## Now

- **Wave 49: v0.1.45-alpha is published** (a pre-release, 2026-11-28 16:18 UTC, read back from GitHub; tag on c3788705): the fifteen tasks, T389 and the four fixes the closing sweeps asked for (T392, T391, T393, T394). The log entry of 2026-11-28 has the sweep's reading (three full runs, two counted reruns of `device-tour`), the draft's checks and the install from the draft. No hold stands on `main`.
- **In flight (2026-11-28 16:20 UTC):** nothing runs. The wave's close is left from its costs on (Next 1).
- **Cost:** the 7-day meter reset on 2026-11-27 04:00 UTC and read 1 at 04:04 UTC (the hold is 97; the next reset is 2026-12-04 04:00 UTC by this session's usage file). The wave reached the hold at 97 on 2026-11-26 23:25 UTC and waited for the reset, as the owner said it could.
- **On the PC:** no emulator, server or Gradle process of this session runs; the main checkout is the phone session's and sits on the merged branch `task/gps-clock-log`; `dystopo-wt/T365` holds the flight recordings. The reports, the notes as published and the release's check results are in this session's scratchpad folders `w49-plan/`, `release-0145/` and `release-0145-install/` [no-check: scratchpad folders].

## Next

1. The Close, in the `wave` skill's order: the costs into the wave paragraph's allowance sentence (the scratchpad's `w49-plan/costs.txt` [no-check: a scratchpad file], T375 to T394; `python -I tools/agent-cost.py <Tnn>` gives them again) and the per-task figure if the reading is more than five points off; a Haiku chore moves the wave to `docs/tasks/wave-49.md` [no-check: written at the close] with its follow-ups lifted into Later; the brainstorm offer and the rest review; the update from the base (a Haiku chore's `git fetch hatchery` first).
2. On the PC, for a chore: the sweep worktrees `dystopo-wt/sweep-w49` and `sweep-w49b` can go; `sweep-w49c` holds the release's sweep folder (`out\sweep-20261128-w49c-r2`, the next release's `-Prev`) and stays, as `sweep-w48` did, until the next release has its own. The live viewer on 8765 is down since the power cut.
3. Follow-ups for the next wave: the kept folder's write through a temporary document (ADR 0072's first prediction); the download restarted in the background (finding of 2026-11-27); `/screenshot` answered before the labels were placed, once in forty tour views; the emulator's adb transport closes during a screenshot in a tour's second run (T394's Status line, a finding to write); the `release` skill's step 5 does not say what `features` needs on a fresh emulator (the debug build beside the release, its location permission, the pack).
4. For the Close's record and offer: the a11y tool's append-at-the-end convention conflicts every time two branches add a screen (an idea: a file per task's screens); three verifiers handed back without a verdict while a long command ran and were resumed; the emulator guard stopped two emulators at 8 GB during `features` and start-up runs; the start-up budgets were judged by no task (the closing sweep's `startup` part reads them, alone).
5. Triage's scan: the earliest dated ADR prediction is due 2026-12-01; ADR 0070's two and ADR 0071's second are checked at this wave's close.

## Waiting on user

- **Your phones:** both dropped off wireless adb on 2026-11-26; reconnect them when you next need them.
- **For your eye, wave 49** (each merge commit's body has its sentences): the glance view while GPS searches and its position line (T384; the dimmed "accuracy" caption there is 2.7 to 1); the two GPS clock lines and where the reset sits (T385); the Detail sheet of a saved area (T386); the names a long press gives each mark, and which marks are blue (T383); search and peaks inside a downloaded area (T377, T378); a first launch that recentres at world zoom under the first-run card (T382); the kept folder's setup sentence, its file names and its lost line (T380).
- **Wave 48's wording and looks, v0.1.44-alpha on a phone, the flight recordings in `dystopo-wt/T365/out/flights/`, ADR 0067's step two** (its idea now has a Plan; say when): as the handoff of 2026-11-26 before this one listed them (`git show 64007414:docs/HANDOFF.md`).
- **Hatchery:** six ideas titled `For the base: ` wait here for Hatchery's triage; its clone sits beside this one, so it should already read them.

## Open findings

- `docs/findings/2026-11-27-an-areas-elevation-source-is-sometimes-missing-from-a-terrain-read.md`: open; T391 read one path in three runs and wrote its Next (a task for the next wave).
- `docs/findings/2026-11-27-a-download-restarted-in-the-background-waits-for-the-screen.md`: open (found by T387's verifier; a task for the next wave, and the row's "Continues by itself").
- `docs/findings/2026-11-25-tile-cache-index-walk-loops-in-the-background.md`: open for its Next 2 alone, the owner's day in the background on a phone (T379, T390 and T387 built the rest); the layout finding and the GPS clock finding are resolved (T381, T385).
- `docs/findings/2026-11-26-the-error-of-a-guessed-flight-position-measured-from-two-recordings.md`, `2026-11-24-the-map-render-thread-never-rests-while-flights-are-drawn.md`: open.
- The five planning findings of 2026-11-23 (`docs/findings/2026-11-23-*.md`): open; their statuses were not set at the triage of 2026-11-26.
- `docs/findings/2026-11-22-research-for-the-clock-the-moving-horizon-and-the-full-screen-panorama.md`, `2026-11-20-aircraft-layer-sources-and-display.md`, `2026-11-18-github-rate-limit-reddened-two-sweep-parts.md`, `2026-11-16-research-for-pending-ideas.md`, `2026-11-15-a-drive-as-a-speed-profile.md`, `2026-11-05-emulator-private-bytes-grow-per-cold-launch.md` (guarded), `2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: open.
