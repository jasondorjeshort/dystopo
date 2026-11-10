---
date: 2026-11-07
kind: feature
related: [2026-11-07-the-launch-as-a-staged-state-machine-in-shared.md]
---

# A launch budget per mark, timed every sweep and enforced by it

The owner, 2026-11-07: "A launch budget, timing, and improvement is a very good idea; lets definitely do that."

T228's startup trace (`androidApp/.../app/StartupTrace.kt`, `GET /debug/startup`) gives a figure per named mark
of the launch: each step of `DystopoApp.onCreate` and `MainActivity.onCreate`, the first composition's parts,
the first frame, the style, `fullyLoaded`. The launch inventory of wave 29 (`out/wave29-research/` [no-check: gitignored]) found twelve
main-thread disk I/O sites on that path and T229 moved one; nothing stops a task adding a thirteenth, and no
sweep records whether a release is slower to its first frame than the last.

Wanted: a ceiling per mark (and for the first frame and the map drawn), kept in a file the device tour reads,
measured on the sweep's emulator over a few cold launches with a seeded track database and the Rainier packs,
written into `summary.json` as a table of medians against the ceilings, and a mark over its ceiling fails the
sweep. The per-release table becomes the record of the launch's improvement. The emulator's cold start swings
more than a small change (T228, T229), so the ceiling is per mark, not per total, and the sweep's own
history of medians, not one run, is what moves a ceiling down. The owner's phone trace is the figure that
matters; the emulator's is the regression guard.

## Open

- The seed and the number of launches that give a stable median on the emulator (T229 used 52 tracks and 5).
- Where the ceilings live (`tools/android-tour.txt` style, or the sweep's own file) and how one is lowered after
  an improvement lands.
- Whether the sweep's emulator growth per cold launch (finding of 2026-11-05) forces a reboot before this part.

Plan: one pass, after ADR 0061's step 1 merges (the ADR names the budget's marks as the stages and keeps renamed
marks as aliases in the budget's file). A new `tools/launch-budget.json` [no-check: new file] (a ceiling in ms per mark, for the first
frame and for `fullyLoaded`, each with the date and median it was set from, and the alias list); a sweep part
`startup` in `tools/sweep.ps1` that seeds 52 tracks and the Rainier packs as T229 did, runs `tools/android-startup.py
--launches 5` (cold; at most six a boot, per its docstring and the 2026-11-05 finding, so the part runs right after
a boot) and compares `markMedians` with the ceilings in `tools/sweep-summary.py`: a table in `summary.json`, a mark
over its ceiling a red part. The first ceilings are the medians of two sweeps plus 25 percent; a ceiling is lowered
by hand in the commit that lands an improvement, never raised without a line in the log. Done when: the part green
on `main`, red with a ceiling set below a known median, `python -I tools/test_android_startup.py` covering the
comparison. Out of scope: the owner's phone (the sibling idea's `-Phone`), any move of a launch step.
