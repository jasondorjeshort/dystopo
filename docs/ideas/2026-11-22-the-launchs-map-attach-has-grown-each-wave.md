---
date: 2026-11-22
kind: chore
---

# The launch's `map.attach` has grown each wave

T338's bisection (wave 46) found no single merge behind the `mapview` stage's red. It did find a slow
drift inside the stage's `map.attach` mark, which covers the `*.prepare()` calls and
`MapController.attach` in `MainActivity.onCreate`. Across the sweeps' `startup.json` records the mark
grew 16, 31, 31, 28, 34, 41, then 52 ms, from the sweep of 2026-11-20 to wave 46's closing sweep.
Each wave adds a holder or a bind there: `OrientationUi`, `GnssUi`, `ViewSwitch`, `PanoramaUi`,
`PanoramaData`, `AircraftUiState`, `DivergenceLayer.bind`, `AircraftLayer.bind`.

Wanted: those that need not run before the first frame are made lazy or deferred until after it,
with the mark measured before and after. The `mapview` ceiling (raised to 138 in T338 because the
host is slower) is then lowered by hand in the same commit.

Plan: Owns `androidApp/.../app/MainActivity.kt` (the block after `StartupTrace.mark("map.attach")`), the `prepare()`
of `OrientationUi`, `GnssUi`, `ViewSwitch`, `PanoramaUi`, `PanoramaData`, `AircraftUiState`, the binds inside
`MapController.attach` (`DivergenceLayer`, `AircraftLayer`), `tools/launch-budget.json`, `docs/reference/android-app.md`
(startup section). First split the mark: a `StartupTrace.mark` per prepare and one before `app.map.attach`, then
five cold launches with `tools/android-startup.py` on one emulator to find what grew (the style parse inside attach
is a suspect too). Defer what the first composition does not read: a lazy holder created on first main-thread
access, or posted after the first frame (`Choreographer`/`doOnPreDraw`); keep main-thread creation of Compose state.
Verify: five cold launches before and after on the same emulator, the per-mark table in the report; the device
tour (Panorama, Horizon, Flights still open and update); `./gradlew check`. Lower `mapview`'s `ceilingMs` only by
the measured drop, with a `lowered` note in the file. Out of scope: the relative-to-last-release check
(`2026-11-17-speed-checks-against-the-last-release-on-the-same-machine.md`), other stages.
