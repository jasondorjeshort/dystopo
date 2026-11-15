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

Plan: one chore-sized implementer; it is pass (1) of the related idea, which then keeps only its pass (2). The
likely cause, read 2026-11-15 and to be confirmed by the pass: with no stored fix, saved view or pack the app
opens a world view (`OpenView.choose`, `app/MapViewport.kt`), and a fix arriving within 10 s recentres it once
(`OpenView.recentres`), so an emulator that was never given a fix moves the map to the emulator's own default
position (T162's follow-up names that fix at about 4.6 s). `tools/android-emu.ps1` sets no fix today; the tap
scripts set their own (`android-firstrun.py`, `android-ui.py`), after the app may already have started. Build: a
`-Fix "lat,lon"` parameter of `tools/android-emu.ps1`, default Paradise at Rainier (the value
`android-firstrun.py` uses), sent with `adb -s <serial> emu geo fix` after boot and before the install and first
launch, and `-NoFix` for a part that wants none. Verify: a cold boot, the app launched with no pack, `/screenshot`
on Rainier; the a11y first-run screen, `bothunits`' second phone and the device tour's navigation-card frames of
a sweep show `areas.json` places; the first-run screen still reads as a new user's (its Open item) and the
report says so with the frame. Docs: `docs/reference/android-emulator.md`.
