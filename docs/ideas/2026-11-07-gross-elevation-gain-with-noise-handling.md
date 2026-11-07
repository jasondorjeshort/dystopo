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
