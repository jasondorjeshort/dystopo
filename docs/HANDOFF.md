# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-28, at the commit that carries this file (wave 49: the closing sweep red in four parts; T392 merged, T391 building), by the main session in the second clone
Resume: this file, `docs/reference/machines.md` (its second-clone paragraph), the `wave` skill, the open wave `## Wave 49` in
`docs/TASKS.md` (its four opening paragraphs first), the last closed wave's record `docs/tasks/wave-48.md`.

## Now

- **Wave 49: the closing sweep is red** on 358a755a (`dystopo-wt/sweep-w49`, `out\sweep-20261127-w49`): `startup`, `two-maps`, `features` (`search-tiles`) and `features-sky` (`peaks-tiles`), each an assertion or a budget; everything else green. No release; the hold on `main` is lifted. The fifteen tasks and T389 are merged; T391 and T392 on the board are the fixes (the paragraph above them has the figures and the order).
- **In flight (2026-11-28 02:35 UTC):** T391's implementer (Opus; resumed for its builds and runs; its fixes are written: an area kept as tiles gives the tiles-only coverage whatever the network, the refresh behind reads as running from the moment it is asked, the probe's failures are counted apart, and the two fixture steps pin their own server, since the sweep stops the debug build's server before `features`). T392 is merged (55d38f08): the launch stages, green in sweep order. A Haiku chore cleans up T392 and moves `tools-main` to the tip.
- **Cost:** the 7-day meter reset on 2026-11-27 04:00 UTC and read 1 at 04:04 UTC (the hold is 97; the next reset is 2026-12-04 04:00 UTC by this session's usage file). The wave reached the hold at 97 on 2026-11-26 23:25 UTC and waited for the reset, as the owner said it could.
- **On the PC:** restarted after the power cut, so the live viewer on 8765 and every emulator are down; stale locks and AVDs of the two cut-off emulators are their agents' to clear. The main checkout is the phone session's and sits on the merged branch `task/gps-clock-log`; `dystopo-wt/sweep-w48` holds the next release's `-Prev`; `dystopo-wt/T365` holds the flight recordings. The reports and verdicts for the merge bodies are in the first session's scratchpad folder, `w49-plan/` (it survived).

## Next

1. T391's report, then a verifier (Sonnet; `-SweepThrough` is not needed for a step's change inside a part: the three parts alone, the feature parts twice), then the merge sequence.
2. Then a full closing sweep again under a hold (the `sweep` skill; `-Prev` `dystopo-wt/sweep-w48/out/sweep-20261126-w48`, the same eleven `-First` names), and green: the release (v0.1.45-alpha; the README's link and version), the hold lifted; the costs (the sixteen lines are in the scratchpad's `w49-plan/costs.txt` [no-check: a scratchpad file]; T391 and T392 to add), the record (`docs/tasks/wave-49.md` [no-check: written at the close]), the brainstorm offer and the rest review, the update from the base.
3. Follow-ups for the next wave: the kept folder's write through a temporary document (ADR 0072's first prediction); the download restarted in the background (finding of 2026-11-27).
4. For the Close's record and offer: the a11y tool's append-at-the-end convention conflicts every time two branches add a screen (an idea: a file per task's screens); three verifiers handed back without a verdict while a long command ran and were resumed; the emulator guard stopped two emulators at 8 GB during `features` and start-up runs; the start-up budgets were judged by no task (the closing sweep's `startup` part reads them, alone).
5. Triage's scan: the earliest dated ADR prediction is due 2026-12-01; ADR 0070's two and ADR 0071's second are checked at this wave's close.

## Waiting on user

- **Your phones:** both dropped off wireless adb on 2026-11-26; reconnect them when you next need them.
- **For your eye, wave 49** (each merge commit's body has its sentences): the glance view while GPS searches and its position line (T384; the dimmed "accuracy" caption there is 2.7 to 1); the two GPS clock lines and where the reset sits (T385); the Detail sheet of a saved area (T386); the names a long press gives each mark, and which marks are blue (T383); search and peaks inside a downloaded area (T377, T378); a first launch that recentres at world zoom under the first-run card (T382); the kept folder's setup sentence, its file names and its lost line (T380).
- **Wave 48's wording and looks, v0.1.44-alpha on a phone, the flight recordings in `dystopo-wt/T365/out/flights/`, ADR 0067's step two** (its idea now has a Plan; say when): as the handoff of 2026-11-26 before this one listed them (`git show 64007414:docs/HANDOFF.md`).
- **Hatchery:** six ideas titled `For the base: ` wait here for Hatchery's triage; its clone sits beside this one, so it should already read them.

## Open findings

- `docs/findings/2026-11-27-an-areas-elevation-source-is-sometimes-missing-from-a-terrain-read.md`: open; T391 takes it.
- `docs/findings/2026-11-27-a-download-restarted-in-the-background-waits-for-the-screen.md`: open (found by T387's verifier; a task for the next wave, and the row's "Continues by itself").
- `docs/findings/2026-11-25-tile-cache-index-walk-loops-in-the-background.md`: open for its Next 2 alone, the owner's day in the background on a phone (T379, T390 and T387 built the rest); the layout finding and the GPS clock finding are resolved (T381, T385).
- `docs/findings/2026-11-26-the-error-of-a-guessed-flight-position-measured-from-two-recordings.md`, `2026-11-24-the-map-render-thread-never-rests-while-flights-are-drawn.md`: open.
- The five planning findings of 2026-11-23 (`docs/findings/2026-11-23-*.md`): open; their statuses were not set at the triage of 2026-11-26.
- `docs/findings/2026-11-22-research-for-the-clock-the-moving-horizon-and-the-full-screen-panorama.md`, `2026-11-20-aircraft-layer-sources-and-display.md`, `2026-11-18-github-rate-limit-reddened-two-sweep-parts.md`, `2026-11-16-research-for-pending-ideas.md`, `2026-11-15-a-drive-as-a-speed-profile.md`, `2026-11-05-emulator-private-bytes-grow-per-cold-launch.md` (guarded), `2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: open.
