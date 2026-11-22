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
