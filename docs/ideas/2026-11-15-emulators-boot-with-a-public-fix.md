---
date: 2026-11-15
kind: chore
related: [2026-11-14-sweep-frames-carry-the-place-they-show.md]
---

# Every emulator boots with a fix set on a public area

Twice now (v0.1.29-alpha, v0.1.30-alpha) frames of the sweep showed a map outside `areas.json` and were left out
of the published sheet by hand: the a11y part's first-run screen, `bothunits`' second phone, and at the second
release the device tour's two navigation-card frames. The common thread is an emulator that has had no fix set;
the cause is not confirmed (the board's Later line). Cheap guard whatever the cause: `tools/android-emu.ps1` sets a
fix on a public area (Paradise, Rainier) as part of every boot, before the app is first launched. The related idea
is the check that would catch a frame anyway. The owner, 2026-11-15: okay.

## Open

- Whether a first-run screen is then still the first-run screen a new user sees (it shows "the map around me").
- Find the cause first if it is quick: where does a phone with no fix open its map, and why there.
