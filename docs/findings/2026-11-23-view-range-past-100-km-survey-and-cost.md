---
date: 2026-11-23
status: open
area: sky views / view range
---

# The view range past 100 km: what other tools do, the geometry, and the cost read from the code

## Question

`../ideas/2026-11-22-the-view-range-may-be-too-short-for-peaks-80-miles-away.md`: the owner has seen peaks about
130 km away and the range stops at 100 km. How far should it go, as a step, automatic, or tall peaks only, and
what does a longer range cost?

## What was tried

One read-only agent (Sonnet-class) on `main` at 48bf56fe: the code read (estimates, nothing measured), thirteen
outside sources fetched in the run. Product names stay here; none goes into user-visible text.

## Result

**The code today.** The cap is `Viewshed.RANGES_KM` (10, 30, 100), `Viewshed.MAX_RANGE_M` with a `require` in
`Viewshed.plan`, and the `viewRange` validation in `AppSettings.kt`. The Horizon view reads peaks to its own
profile range, 30 km by default (`Horizon.DEFAULT_RANGE_M`), not to 100 km as the idea has it. The Panorama does
not run `Viewshed.compute`: `PanoramaData.readTerrain` uses `Viewshed.plan` and `Viewshed.terrain`, then
`LayeredSkyline.sweep` calls `Horizon.sweep` once per range (3, 10 and 30 km, then the view range);
`Viewshed.compute` serves only the map's "What can I see from here". Curvature and refraction are in already:
`Horizon.REFRACTION_K` 0.13, drop = d²(1 - k) / 2R, shared by the viewshed, the ray march and the sweep. DEM bands
in `Viewshed.plan`: cells of about 15 m to 5 km, 60 m to 30 km, 240 m beyond (z9 at 47° N); the far window is a
square, so its cells grow with the range squared. The peak read (`Viewshed.peakZoom`, clamped z9 to z12) reads
whole squares of tiles per zoom.

**What other tools do.** A page documenting one peak-identification app's API gives "valid range: 0..320000
(320km, 200mil)"; a guide to a web panorama generator says "The default range of sight ... is 300km"; a
visibility site has no fixed cap, includes curvature and refraction (about 14 % of the curvature drop) and shows
a farthest peak at 137 miles; photographed lines of sight reach 443 km (Finestrelles to Pic Gaspard) and a
claimed 493 km; an open-source panorama renderer draws every triangle with a coarser far mesh as future work;
terrain renderers use nested grids that coarsen with distance. All model refraction as a fixed fraction of
curvature; none documents a user-set coefficient.

**Geometry.** With k = 0.13 the horizon is 3.86 √h km (h in metres) and two heights see each other out to
3.86 (√e + √H). From a 1.7 m eye a peak at 130 km needs a summit about 1,050 m above the smooth sphere; the
tallest summits (4,400 to 4,800 m) are reachable to about 270 km from that eye and about 417 km from a 1,500 m
viewpoint. "The summit clears the curved horizon" is a necessary condition, since terrain only adds blockers,
so it is safe as a pre-filter.

**Cost at about 47° N, estimated.**

| Range | Far cells at z9 | MB | z9 tiles | With the cell about d / 480 | Peak tiles | Steps per ray |
|---|---|---|---|---|---|---|
| 100 km | 0.93 M | 3.7 | 25 | the same | 226 | 445 |
| 150 km | 2.1 M | 8.4 | 49 | about 5.8 MB | 290 | 465 |
| 200 km | 3.7 M | 15 | 81 | about 7.4 MB | 310 | 480 |
| 300 km | 8.3 M | 33 | 169 | about 9.6 MB | 390 | 500 |

The sweep's steps grow with the log of the range (5 to 13 % more); the cost is the terrain read and its memory.
The existing doc measures 100 km at about 9 MB, 54 tiles and 0.63 s. The map's overlay would go from a measured
5.0 s at 100 km to about 17 s at 200 km without a coarser far ring. Online, 200 km without it is about 80 z9
tiles of data against 25.

**Recommended.** Steps 10, 30, 100 and 200 km, the default still 30, the chips in the user's units. A coarser
far ring: the cell about d / 480 (z9 to 100 km, z8 to 200 km), one more edge and cell size in `Viewshed.plan`,
`MAX_RANGE_M` raised. A clearance pre-filter for peaks beyond 100 km (the formula above with a generous k such
as 0.20; a peak with no `ele` is kept); `Viewshed.peaks` and the ray test stay the real answer. The map's overlay
keeps its 100 km `require` (ADR 0066 keeps it on offline maps, and its sample cost is the steepest), said in the
setting's help. "Automatic" would swing from 72 km on flat ground to 400 km on a summit, so it joins the fixed
steps later if wanted and does not replace them. No new setting: the step extends `viewRange`, and the filter and
the far ring are geometry and cost, not a choice (ADR 0044).

## Next

Measure on a phone before it ships: `GET /panorama` `work` at 100 and 200 km from a valley and a summit (terrain
ms, tiles, bytes, network bytes, first skyline, scene ms in a drag, peak ms), the heap's peak with the far ring
on and off, `viewshed/bench` at 100 km as the regression check, and a range that runs past a map's edge.
`Terrain.read` was not read by the agent.
