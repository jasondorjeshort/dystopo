---
date: 2026-10-30
status: resolved
area: nav / terrain-matched position
---

# A trail-graph particle filter holds a position to tens of metres without GPS; relief pins it, flat ground widens it

## Question

How well does matching the barometric altitude's shape to the DEM along the nearby trails keep a
position when the GPS is cut (the idea `docs/ideas/2026-10-29-terrain-matched-position-without-gps.md`,
task T166)? Where does a profile pin the position, and does flat ground widen the reported spread
rather than let a confident dot drift? The numbers decide whether the live source is built.

## What was tried

`TerrainFix` (`shared/.../nav/TerrainFix.kt`): particles on `RoutingGraph` edges near a seed fix, each
with its own stride scale and barometer-minus-DEM offset, advanced by the counted stride, branching at
junctions by the heading, weighted by the elevation residual against `DemElevation` (z13 of the DEM
pack) and by the heading against the edge's bearing. The estimate is the weighted centre; the spread
is the radius holding 90 % of the weight. `devtool terrain-fix` replays a line as the sensors would
see it: barometer = the line's elevation + 4 m/h drift + 0.5 m noise; step counter = the true
spacing x 1.07 x (1 + 0.5 |grade|) with 5 % noise; compass = the true bearing + 5 deg bias + 15 deg
noise; seed = the true position + 8 m per axis; the GPS course over the last 30 m before the cut.
Five seeds per cut, pooled; 2000 particles; 5 s steps.

Tracks: `shared/src/commonTest/resources/gpx/skyline-loop.gpx` (Paradise, synthetic, 8.1 km) and a
synthetic route along the pack's trails, Longmire to Paradise, 9.3 km, walked at Tobler's pace:
`devtool route --pack out/rainier-outdoors.pmtiles --from 46.7500,-121.8120 --to 46.7864,-121.7366 --gpx out/longmire-paradise.gpx --no-render`.
Graph and DEM: `out/rainier-outdoors.pmtiles` (3,793 edges) and `out/rainier-dem.pmtiles`. Each was
run as is, with `--no-elevation`, `--no-heading`, `--flat` (DEM and line one constant), and with a
pocketed phone's compass (`--heading-noise 40 --heading-bias 20`), with and without `--flat`.

## Result

Median / p90 error (m), coverage (share of steps whose spread held the error), median spread (m):

| Track, cut (m walked) | Terrain | Default sensors | Flat world | Pocket compass | Pocket, flat |
|---|---|---|---|---|---|
| Skyline, climb 0..2700 | +391 m, 14.5 % | 10.8 / 20.2, 0.992, 43 | 22.2 / 43.4, 0.999, 101 | 12.7 / 29.8, 0.994, 50 | 65.5 / 125.3, 0.999, 236 |
| Skyline, traverse 3700..5600 | -240 m, 13.0 % | 11.2 / 22.8, 0.992, 39 | 11.3 / 23.1, 1.0, 52 | 12.4 / 27.6, 0.978, 43 | 16.5 / 31.9, 0.996, 67 |
| Skyline, flattest 6400..7700 | 50 m relief, 9.0 % | 8.3 / 16.9, 0.994, 48 | 9.1 / 18.8, 0.997, 80 | 11.2 / 21.9, 0.995, 50 | 20.8 / 70.5, 0.999, 100 |
| Synthetic, climb 6000..9000 | +434 m, 14.9 % | 4.2 / 21.1, 0.998, 28 | 9.7 / 19.8, 0.999, 42 | 6.0 / 22.2, 0.997, 33 | 18.6 / 37.1, 0.999, 76 |
| Synthetic, valley floor 0..3000 | +152 m, 5.7 % | 7.5 / 13.9, 1.0, 69 | 17.8 / 43.9, 1.0, 146 | 14.6 / 29.0, 1.0, 86 | 40.7 / 120.3, 1.0, 212 |
| Synthetic, traverse 4800..6000 | +74 m, 6.5 % | 7.6 / 30.6, 1.0, 60 | 14.2 / 24.9, 1.0, 81 | 11.9 / 31.6, 1.0, 67 | 18.6 / 34.1, 1.0, 108 |

The ablations with the default compass: without the elevation term the medians were 19.1, 10.8, 11.4
(Skyline) and 7.1, 17.9, 12.9 (synthetic), spreads 44 to 151 m; without the heading term (elevation,
stride and the course at the seed only) 24.5, 27.6, 10.5 and 12.3, 34.4, 19.8, p90 up to 143 m and
single steps to 160 m where the cloud took a wrong branch at a junction, coverage 0.986 to 1.0.
With neither (stride alone on the graph), and the same on a flat world without heading, the cloud
fanned out over every branch: spreads of 0.5 to 1.3 km after 1 to 3 km walked, and on the long cuts
the truth sat on a minority branch, coverage 0.15 to 0.38.

Run time: about 1.1 microseconds per particle per step on the JVM (the Skyline climb, 901 steps x 2000
particles x 5 seeds, in 10.1 s); one step of 2000 particles every 5 s costs about 2 ms.

What this says:

- **Where it pins.** On the graph, the trail's bends do much of the work through the heading: a
  particle ahead or behind faces the wrong way at the next bend. Relief adds the along-track anchor
  the stride cannot give: the elevation term halved the median and the spread on the valley floor
  (5.7 % grade: 7.5 against 17.8 m, spread 69 against 146 m) and, with a poor compass, on the climb
  (12.7 against 65.5 m). A steady grade of about 5 % or more is enough to anchor; elevation alone pins
  climbs (medians 12 to 25 m) but loses junction choices.
- **Where it cannot.** Flat ground with a poor heading widens the spread rather than drifting: every
  run with a heading kept coverage at 0.978 or more while the flat world's median spread grew to 236 m.
  Without any heading the cloud covers every branch and its centre means nothing; the live source must
  report no fix once the spread passes a limit (a few hundred metres) rather than draw the centre.
- **The spread is conservative**: coverage 0.98 to 1.0 against the 0.9 it is the quantile of, the
  spread about twice the p90 error. Calibrating it needs real sensor logs, not this replay.
- **Optimistic in the replay**: the barometer equals the elevations the lines carry (the DEM's, at the
  Skyline fixture's rounded vertices or the graph's points) plus small noise and drift, with no gusts
  or temperature swings; the heading is the bearing of travel, which a pocketed phone does not give
  without step-direction estimation; the stride model is assumed. The Skyline fixture's vertices sit
  up to about 20 m off the mapped trail, which sets its error floor. Off-trail walking is not covered:
  the filter runs on the graph only.

Parameters (defaults of `TerrainFixOptions`) and why: 2000 particles (the cloud must hold several
branches at once; 1.1 microseconds each is cheap); stride scale prior 15 % and a walk of 3 % per root
100 m (an uncalibrated step length and strides that shorten on grades); per-step stride noise 5 %;
offset prior 3 m and walk 6 m per root hour (above the replayed 4 m/h drift, so the weather cannot
win); elevation sigma 6 m (z13 bilinear DEM against a trail line on a 15 % slope, plus the seed's 8 m
of position); a step's likelihood tempered by stride / 50 m (successive residuals along a trail are
correlated over about a DEM-feature length, so a standing walker gains no evidence); heading sigma
35 deg with a 0.1 floor and junction sigma 45 deg (the compass noise plus the gap between a chord and
the mapped trail's local bearing); the spread at the 0.9 quantile.

## Next

Not this task: the live source (pressure, step counter, heading of travel, the no-fix limit, the
battery rule) is the idea's rewritten Plan. Its first check is these numbers repeated on a real
barometer and step log, which this replay cannot give.
