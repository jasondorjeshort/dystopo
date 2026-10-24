---
date: 2026-10-13
kind: feature
related: [2026-10-13-route-waypoints-and-reroute.md]
---

# Crow and bushwhacking routing modes

Two more modes beside hike, bike and drive (`TravelMode`):

- **Crow** (done in T108): a straight line between the points, with distance, bearing and the
  elevation profile along it. No graph needed. Built as `TravelMode.CROW` (`routing/Crow.kt`): every
  leg straight (drawn dashed in a trip, like an off-network leg), a point every 25 m with the DEM's
  height, the initial bearing of each leg in the summary (`bearings`), one arrival as its directions,
  the hike model's time over the line; in `devtool route --mode crow`, the server's `/route?mode=crow`
  and the phone's mode chips with the line "A crow route is a straight line between the points, not a
  path to walk." A build before T108 shows a synced crow trip under its own mode name with the hike
  model's time, and an edit there routes the moved legs along trails as a hike.
- **Bushwhack** (open): prefers trails (the existing graph) but may leave them and cut across the
  gentlest terrain when that is much shorter, using the DEM for slope (and land cover where known:
  water, glaciers, cliffs, dense vegetation).

Today the router is graph-only; a point not near a trail gets no route (except as crow).

## Open

- Bushwhack cost model: an off-trail cost per metre scaled by slope (reuse the hike slope
  penalty), forbidden cells (water, steep cliffs, glacier), a penalty to leave and rejoin a trail
  so it does not zigzag. A raster search (grid A*) over the DEM window around the corridor,
  stitched to the trail graph at the ends.
- Safety: say plainly in the app that off-trail routes are an estimate; show the steepest slope
  on the route and whether it crosses the slope-risk window.
- This changes the router and the route format (edges that are not on the graph), so it is a
  proposal and critic before it is built. A trip in a new mode reaches a T108 build as a mode it
  does not know: shown under its name, followed, not edited (ADR 0033 Decision 3).

Question: the crow mode is built (T108); does bushwhacking come next, and when? It changes the router and the route format (edges off the graph), so this file already says proposal and critic first, and the off-trail safety wording is yours. Recommendation: hold it until the route-to stops and reroute proposal (`2026-10-13-route-waypoints-and-reroute.md`) is decided, since both change the route format phones sync and should land in one version step; then a proposal for the grid A* cost model.
Answer: hold until the stops and reroute proposal is decided, then a proposal for the grid A* cost model, as recommended (owner, 2026-10-23).
2026-10-23 (T132): the stops and reroute proposal, `docs/design/proposals/0040-stops-and-reroute.md`
(promoted to ADR 0040, `docs/design/decisions/0040-stops-and-reroute.md`), finds that bushwhacking needs no path version step: the mode is a string the T108 rule
already tolerates, off-trail stretches are leg geometry, new directions make the saved directions count
as absent on older builds, and per-leg data rides as a keyed side field; only a new `LegKind` would
need a version, and then it rides with 0040's build. The grid A* proposal is still the next step here.
