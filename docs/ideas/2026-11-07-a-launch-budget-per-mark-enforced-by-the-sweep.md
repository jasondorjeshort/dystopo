---
date: 2026-11-07
kind: feature
related: [2026-11-07-the-launch-as-a-staged-state-machine-in-shared.md]
---

# A launch budget per mark, timed every sweep and enforced by it

The owner, 2026-11-07: "A launch budget, timing, and improvement is a very good idea; lets definitely do that."

T228's startup trace (`androidApp/.../app/StartupTrace.kt`, `GET /debug/startup`) gives a figure per named mark
of the launch: each step of `DystopoApp.onCreate` and `MainActivity.onCreate`, the first composition's parts,
the first frame, the style, `fullyLoaded`. The launch inventory of wave 29 (`out/wave29-research/`) found twelve
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
