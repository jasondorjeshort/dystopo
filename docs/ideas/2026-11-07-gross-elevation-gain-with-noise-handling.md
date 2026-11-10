---
date: 2026-11-07
kind: feature
related: [2026-11-02-estimated-points-in-exports-and-totals.md, 2026-11-02-physical-limits-from-your-own-tracks.md]
---

# Gross elevation gain for recorded tracks and drawn paths, with the noise handled per source

The owner, 2026-11-07: "gross elevation gain has room here. If you go up 2000 feet, down 1000, then up 2000 more
that's 3000 gross gain. This can be noisy though if you lose 6 inches on every step or 5 feet on gps jitter, very
specific to either barometer or topo ground tracking data. Old hillmap.com had this very nice feature as you drew a
path, something very easy for us to do."

What exists: the planning card already shows a route's gain ("up 1318 ft" on the Rainier route in the sweep's
screenshots), from the terrain along the drawn path (`shared`'s `routing/` profile); a recorded track's statistics
come from T171's smoothed line (`shared`'s `store/` stats, `tracks/TrackStats` if present, `tracking/Tracking.kt`),
whose follow-up already names "GPS height drift adding a few percent of gain".

## Open

- One gain rule per source: barometer (step-level noise, a dead band of a metre or two), GNSS height (jitter of
  metres, a larger dead band or the terrain's height instead), terrain under the track (no noise, but the trail's
  true ups and downs between samples are lost); the rule stated on the statistics line.
- The same figure on the planning card while a path is drawn, updating with each vertex (the hillmap behaviour),
  and on the track card and the trip report.
- A test with a synthetic profile (2000 up, 1000 down, 2000 up with jitter) giving 3000 within a stated tolerance.

2026-11-10 (triage): gross gain already exists: `shared`'s `gpx/TrackStats.kt` (`ascentDescent`, turning-point
hysteresis, one 3 m threshold for every source), used by the planning card's summary (`model/RouteSummary.kt`),
`nav/NavPath.kt` and `nav/TurnAround.kt`. What is missing is the per-source rule and saying it.

Plan: one pass in `shared` plus the track card's line. `StatsOptions.elevationThresholdM` chosen by the source:
from the track's stored profile (`model/ActivityProfile.kt`, `ElevationSource` FUSED or GNSS) for a recorded track,
terrain for a planned path; the three thresholds in one place with their reasons (barometer-fused small, GNSS
larger, terrain smallest), set by a replay of the existing smoothing tests' fixtures rather than guessed; the
statistics line (track card, trip report) names the rule in a few words ("gain from barometer, 2 m steps
ignored"), strings listed. Tests: the synthetic 2000 up, 1000 down, 2000 up profile with each source's jitter gives
3000 m within the stated tolerance; the existing `TrackSmoothingTest` and route summary tests unchanged. Done when:
`check` green, the Rainier route's card figure and a replayed track's card viewed. Out of scope: a per-vertex figure
while drawing (the planning card already recomputes on each edit; a separate idea if it does not feel live).
