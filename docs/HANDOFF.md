# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-08, by main session (wave 30 closed: eleven tasks merged, v0.1.24-alpha published; wave 31 to plan)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` (Later and the
ideas of 2026-11-07), `docs/tasks/wave-30.md` once the chore writes it, ADR 0056, ADR 0057, ADR 0059.

## Now

- **Wave 30 is closed.** T229, T234 to T243 (eleven tasks, T242 and T243 added mid-wave) are merged in series; the
  post-wave sweep is green after T243; **v0.1.24-alpha is published** (tag on 42cdfe7c, versionCode 1299). The record
  move to `docs/tasks/wave-30.md` is the next chore; its follow-ups then land in Later. Reports and notes of the
  wave: `out/wave30-reports/` [no-check: gitignored].
- **Cost:** the 7-day meter 27 to 72 across the wave; the `wave` skill's figure is now 2.5 a task, 3 a wave, 2.5 a
  release. Free RAM 11 to 23 GB throughout; the guard stopped emulators five times at 8 or 12 GB and lost none.
- No task branch or worktree is open. The live server on 8765 is up. The gate research stays untracked in
  `reports/` and `research_notes/` [no-check: untracked].

## Next

1. A Haiku chore moves wave 30 to its record (`docs/tasks/README.md` recipe, `bash "$S" 30 30`); read the lifted
   follow-ups, strike what is taken, commit and push.
2. Plan wave 31 (the `wave` skill, with a critic): the candidates are the ideas of 2026-11-07 (the launch budget and
   the launch state machine; downloads as a synchronisation in cells, researched against a game downloader; the
   diagnostic dump; the flight reports' fourteen: the barometer off when flying, the turn-back card off by default,
   the tracks manager, the geodesic line route, the offline zoom fallback and basemap, the render cache), T242's
   snapshot, Layout C (the owner's Menu choice first), the 28 unprepared state objects (T240), the long-press
   card's 48 dp targets, the two ready Plans, a chore for T227's push-pack daemon and T231's lock gap.
3. A Hatchery-rooted session for the carry-backs (ADR 0057's rule, T190's two, T201's, the sentinel rule, T231's guard).

## Waiting on user

- On v0.1.24-alpha: the large download again and whether the start stays quick over it; a Startup freeze report if
  not; a freeze report from the flights if Report a problem lists one.
- For your eye (ADR 0031): at the largest font in landscape while navigating only Search and Settings remain, and
  upright with a warning and a route the position button, scale bar and readout hide (T239; Layout C's); the
  Offline maps list opens on one row in landscape at the largest font (T235: a separate Add-a-map screen or not);
  T237's wordings (the Storage headings, "Download for offline"); the long-press card's 28.5 dp actions.
- The name of the Report a problem button once it sends a dump; the top row's Menu (Layout C); the location
  permission at launch or on the tap (T220); "Keep north up" (T176); proposals 0054 and 0055; ADR 0053's categories.

## Open findings

- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; the cause open; a snapshot next.
- `docs/findings/2026-11-07-update-check-shows-githubs-raw-504.md`: a sentence per failure kind and one quiet retry.
