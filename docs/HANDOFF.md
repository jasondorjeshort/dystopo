# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-27, at the commit that carries this file (wave 49: fourteen merged; T378 in a fix round, T380 with its verifier), by the main session in the second clone
Resume: this file, `docs/reference/machines.md` (its second-clone paragraph), the `wave` skill, the open wave `## Wave 49` in
`docs/TASKS.md` (its four opening paragraphs first), the last closed wave's record `docs/tasks/wave-48.md`.

## Now

- **Wave 49 is open: fifteen tasks, T375 to T388 and T390, plus T389.** Planned, read by a critic and launched on 2026-11-26 on the owner's words (the wave paragraph quotes them). **Merged (thirteen of fifteen, and T389, fourteen branches in all):** every task but T378 and T380; the last merged is T388 (6c891584); each has its Status line on the board and its log entry.
- **In flight (2026-11-27 16:20 UTC):** T378 (`task/T378`, a258d879): its verifier's MERGE WITH NOTES is in (`<scratchpad>/w49-plan/verify-T378.txt`), and its Opus implementer is in a fix round the coordinator asked for: the new `peaks-tiles` step failed in the sweep's default mode (no terrain with the pack off; the fixture must hold elevation and need no network), plus a merge of `origin/main`; then its verifier reads the delta and runs `features-sky` whole in the default mode. T380 (`task/T380`, 14c65857): a Sonnet verifier running (report in `report-T380.txt` there). The PC lost power at about 12:25 UTC and was restarted; nothing merged or pushed was lost.
- **Cost:** the 7-day meter reset on 2026-11-27 04:00 UTC and read 1 at 04:04 UTC (the hold is 97; the next reset is 2026-12-04 04:00 UTC by this session's usage file). The wave reached the hold at 97 on 2026-11-26 23:25 UTC and waited for the reset, as the owner said it could.
- **On the PC:** restarted after the power cut, so the live viewer on 8765 and every emulator are down; stale locks and AVDs of the two cut-off emulators are their agents' to clear. The main checkout is the phone session's and sits on the merged branch `task/gps-clock-log`; `dystopo-wt/sweep-w48` holds the next release's `-Prev`; `dystopo-wt/T365` holds the flight recordings. The reports and verdicts for the merge bodies are in the first session's scratchpad folder, `w49-plan/` (it survived).

## Next

1. On each verdict: MERGE, the merge sequence (the merge run unpiped, its own result read); FIX, back to its implementer by its id. T380 touches `tools/android-ui.py` and the a11y tool's files, as T378 and T388 do: whichever merges second merges `origin/main` into its branch first and its verifier reads that delta.
2. After the last merge: a cleanup chore (the branches and worktrees of T378, T380 and T388, the held folder `dystopo-wt/T378-verify`, `tools-main` to the tip); then the wave's Close (the `wave` skill): the README's status paragraph, the closing sweep alone on the PC with `-Prev` from `dystopo-wt/sweep-w48`, the release, the costs, the record, the brainstorm offer, the base's update.
3. The device tour was not shown green on T384's and T389's heads (load, by the verifiers' reading): the closing sweep reads it; if it is red there, look at T379's later start of the cache walk first.
4. Timed runs go alone: hold launches for T379's and T387's start-up runs when their implementers report them as not judged.
5. For the Close's record and offer: the a11y tool's append-at-the-end convention conflicts every time two branches add a screen (an idea: a file per task's screens); three verifiers handed back without a verdict while a long command ran and were resumed; the emulator guard stopped two emulators at 8 GB during `features` and start-up runs; the start-up budgets were judged by no task (the closing sweep's `startup` part reads them, alone).
6. Triage's scan: the earliest dated ADR prediction is due 2026-12-01; ADR 0070's two and ADR 0071's second are checked at this wave's close.

## Waiting on user

- **Your phones:** both dropped off wireless adb on 2026-11-26; reconnect them when you next need them.
- **For your eye, wave 49** (each merge commit's body has its sentences): the glance view while GPS searches and its position line (T384; the dimmed "accuracy" caption there is 2.7 to 1); the two GPS clock lines and where the reset sits (T385); the Detail sheet of a saved area (T386); the names a long press gives each mark, and which marks are blue (T383); search and peaks inside a downloaded area (T377, T378); a first launch that recentres at world zoom under the first-run card (T382).
- **Wave 48's wording and looks, v0.1.44-alpha on a phone, the flight recordings in `dystopo-wt/T365/out/flights/`, ADR 0067's step two** (its idea now has a Plan; say when): as the handoff of 2026-11-26 before this one listed them (`git show 64007414:docs/HANDOFF.md`).
- **Hatchery:** six ideas titled `For the base: ` wait here for Hatchery's triage; its clone sits beside this one, so it should already read them.

## Open findings

- `docs/findings/2026-11-27-a-download-restarted-in-the-background-waits-for-the-screen.md`: open (found by T387's verifier; a task for the next wave, and the row's "Continues by itself").
- `docs/findings/2026-11-25-tile-cache-index-walk-loops-in-the-background.md`: open for its Next 2 alone, the owner's day in the background on a phone (T379, T390 and T387 built the rest); the layout finding and the GPS clock finding are resolved (T381, T385).
- `docs/findings/2026-11-26-the-error-of-a-guessed-flight-position-measured-from-two-recordings.md`, `2026-11-24-the-map-render-thread-never-rests-while-flights-are-drawn.md`: open.
- The five planning findings of 2026-11-23 (`docs/findings/2026-11-23-*.md`): open; their statuses were not set at the triage of 2026-11-26.
- `docs/findings/2026-11-22-research-for-the-clock-the-moving-horizon-and-the-full-screen-panorama.md`, `2026-11-20-aircraft-layer-sources-and-display.md`, `2026-11-18-github-rate-limit-reddened-two-sweep-parts.md`, `2026-11-16-research-for-pending-ideas.md`, `2026-11-15-a-drive-as-a-speed-profile.md`, `2026-11-05-emulator-private-bytes-grow-per-cold-launch.md` (guarded), `2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: open.
