# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-28, at the commit that carries this file (wave 50 open: the T395 hotfix; v0.1.45-alpha published), by the main session in the second clone
Resume: this file, `docs/reference/machines.md` (its second-clone paragraph), the `wave` skill, the last closed wave's record
`docs/tasks/wave-49.md` (its paragraphs "After the ... closing sweep" and "Costs at the close"), the board's Later in `docs/TASKS.md`.

## Now

- **Wave 50 is open: a hotfix of one task, T395** (issue #35, an `OutOfMemoryError` of about 1 GB when a long press asks for the sun line on a phone with no DEM pack; the board's Wave 50 has the diagnosis the critic corrected). The owner chose "Hotfix now + release" (2026-11-28). Its implementer (Opus) runs in `dystopo-wt/T395` [no-check: a sibling folder] on emulator console 5570; next a verifier, the merge, a sweep, v0.1.46-alpha, and #35 closed from the release.
- **Wave 49 is closed and v0.1.45-alpha is published** (a pre-release, 2026-11-28 16:18 UTC, read back from GitHub; tag on c3788705): fifteen planned tasks, T389 and the four fixes its closing sweeps asked for (T392, T391, T393, T394). The log entries of 2026-11-28 have the sweep's reading (three full runs, two counted reruns of `device-tour`), the draft's checks and the install from the draft. The record is `docs/tasks/wave-49.md`; its follow-ups are in the board's Later, the done ones struck. No hold stands on `main`.
- **Cost:** the wave read 38 points of the 7-day meter (5 before the reset of 2026-11-27 04:00 UTC, 33 after) and 42.8M fresh tokens for its tasks' agents; the `wave` skill's per-task figure went from 1.5 to 1.75. The meter read 33 on 2026-11-28 16:20 UTC (the hold is 97; the next reset is 2026-12-04 04:00 UTC by this session's usage file).
- **The close's other steps:** the base has no commit past this project's base line (fetched 2026-11-28), so no update and no offer after one. A brainstorm offer on the owner's request after the close: nine lines, eight recorded as ideas of 2026-11-28 and one entry, none resting; no rest review (the last was 2026-11-26, under seven days). ADR 0070's first prediction held, its second was not met as measured (merges an hour rose 40%, not by half), ADR 0071's second held; each outcome is on its line.
- **On the PC:** no emulator, server or Gradle process of this session runs, and the live viewer on 8765 has been down since the power cut. The main checkout is the phone session's and sits on the merged branch `task/gps-clock-log`. `dystopo-wt/sweep-w49c` [no-check: a sibling folder] holds the release's sweep folder (`out\sweep-20261128-w49c-r2`, the next release's `-Prev`) and stays until the next release has its own; `dystopo-wt/T365` holds the flight recordings.

## Next

1. The next wave, on the owner's word. The owner, 2026-11-28: "We probably do need to do some work to improve the efficiency of our release work soon as these releases are getting very slow." (`docs/ideas/2026-11-28-releases-are-getting-very-slow.md` has where the 19 hours went; the resting sweep idea it names may be the owner's to wake). A triage first: nine ideas of 2026-11-28 are untriaged (`node .claude/hooks/queue-list.js ideas`); its scan finds the earliest dated ADR prediction due 2026-12-01, and ADR 0072's first (the kept folder's write through a temporary document) is due at the next wave's close.
2. Candidates for it, from the board's Later (the lines of wave 49): the two open findings of 2026-11-27 (a terrain read that misses an area's elevation source; a download restarted in the background that waits for the screen); the kept folder's write through a temporary document; `/screenshot` answering before the labels are placed; the emulator's adb transport closing during a screenshot in a tour's second run (a finding to write); the `release` skill's step 5 naming what `features` needs on a fresh emulator; the a11y tool's append convention; the index walks still starting during the first composition.
3. A chore when an agent next runs: remove the sweep worktrees `dystopo-wt/sweep-w49` and `sweep-w49b` [no-check: sibling folders] (`tools\worktree-remove.ps1`); `sweep-w48` stays while a sweep's `-DataDir` points at its `out`, or goes once `sweep-w49c`'s `out` is checked to hold the same packs and fixture; start the live viewer on 8765 if the owner wants it.

## Waiting on user

- **v0.1.45-alpha on your phone:** the update is offered by the app's own check. After a day with it left in the background, the read the board's Owner actions asks for (ADR 0071's first prediction: no `excessive cpu` exit, drain under 1 %).
- **Your phones:** both dropped off wireless adb on 2026-11-26; reconnect them when you next need them.
- **For your eye, wave 49** (each merge commit's body has its sentences; the release's before-and-after sheet shows the screens): the glance view while GPS searches and its position line (T384; the dimmed "accuracy" caption there is 2.7 to 1); the two GPS clock lines and where the reset sits (T385); the Detail sheet of a saved area (T386); the names a long press gives each mark, and which marks are blue (T383); search and peaks inside a downloaded area (T377, T378); a first launch that recentres at world zoom under the first-run card (T382); the kept folder's setup sentence, its file names and its lost line (T380).
- **Wave 48's wording and looks, the flight recordings in `dystopo-wt/T365/out/flights/`, ADR 0067's step two** (its idea now has a Plan; say when): as the handoff of 2026-11-26 listed them (`git show 64007414:docs/HANDOFF.md`).
- **Hatchery:** six ideas titled `For the base: ` wait here for Hatchery's triage; its clone sits beside this one, so it should already read them.

## Open findings

- `docs/findings/2026-11-27-an-areas-elevation-source-is-sometimes-missing-from-a-terrain-read.md`: open; T391 read one path in three runs and wrote its Next (a task for the next wave).
- `docs/findings/2026-11-27-a-download-restarted-in-the-background-waits-for-the-screen.md`: open (found by T387's verifier; a task for the next wave, and the row's "Continues by itself").
- `docs/findings/2026-11-25-tile-cache-index-walk-loops-in-the-background.md`: open for its Next 2 alone, the owner's day in the background on a phone (T379, T390 and T387 built the rest); the layout finding and the GPS clock finding are resolved (T381, T385).
- `docs/findings/2026-11-26-the-error-of-a-guessed-flight-position-measured-from-two-recordings.md`, `2026-11-24-the-map-render-thread-never-rests-while-flights-are-drawn.md`: open.
- The five planning findings of 2026-11-23 (`docs/findings/2026-11-23-*.md`): open; their statuses were not set at the triage of 2026-11-26.
- `docs/findings/2026-11-22-research-for-the-clock-the-moving-horizon-and-the-full-screen-panorama.md`, `2026-11-20-aircraft-layer-sources-and-display.md`, `2026-11-18-github-rate-limit-reddened-two-sweep-parts.md`, `2026-11-16-research-for-pending-ideas.md`, `2026-11-15-a-drive-as-a-speed-profile.md`, `2026-11-05-emulator-private-bytes-grow-per-cold-launch.md` (guarded), `2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: open.
