# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-08, by main session (wave 30 closed: eleven tasks merged, v0.1.24-alpha published; wave 31 to plan)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` (Later and the
ideas of 2026-11-07), the wave 30 record once the chore writes it, ADR 0056, ADR 0057, ADR 0059.

## Now

- **Wave 30 is closed.** T229, T234 to T243 (eleven tasks, T242 and T243 added mid-wave) are merged in series; the
  post-wave sweep is green after T243; **v0.1.24-alpha is published** (tag on 42cdfe7c, versionCode 1299). The record
  move is the next chore; its follow-ups then land in Later. Reports and notes: `out/wave30-reports/` [no-check: gitignored].
- **Cost:** the 7-day meter 27 to 72 across the wave; the `wave` skill's figure is now 2.5 a task, 3 a wave, 2.5 a
  release. Free RAM 11 to 23 GB throughout; the guard stopped emulators five times at 8 or 12 GB and lost none.
- No task branch or worktree is open. The live server on 8765 is up. The gate research stays untracked [no-check: untracked].

## Next

1. A Haiku chore moves wave 30 to its record (`docs/tasks/README.md` recipe, `bash "$S" 30 30`); read the lifted
   follow-ups, strike what is taken, commit and push.
2. Plan wave 31 (the `wave` skill, with a critic): the candidates are the ideas of 2026-11-07 (the launch budget and
   the launch state machine; downloads as a synchronisation in cells, researched against a game downloader; the
   diagnostic dump; the flight reports' fourteen: the barometer off when flying, the turn-back card off by default,
   the tracks manager, the geodesic line route, the offline zoom fallback and basemap, the render cache), T242's
   snapshot, Layout C (the owner's Menu choice first), the 28 unprepared state objects (T240), the two ready Plans;
   then a Hatchery-rooted session for the carry-backs (ADR 0057's rule, T190's two, T201's, the sentinel, T231's guard).

## Waiting on user

- On v0.1.24-alpha: the large download again and whether the start stays quick over it; a Startup freeze report if
  not; a freeze report from the flights if Report a problem lists one.
- For your eye (ADR 0031): at the largest font in landscape while navigating only Search and Settings remain, and
  upright with a warning and a route the position button, scale bar and readout hide (T239; Layout C's); the
  Offline maps list opens on one row in landscape at the largest font (T235: a separate Add-a-map screen or not);
  T237's wordings (the Storage headings, "Download for offline"); the long-press card's 28.5 dp actions.
- The Report a problem button's name once it sends a dump; the top row's Menu; T220; T176; proposals 0054 and 0055.

## Open findings

- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; the cause open; a snapshot next.
- `docs/findings/2026-11-07-update-check-shows-githubs-raw-504.md`: a sentence per failure kind and one quiet retry.
