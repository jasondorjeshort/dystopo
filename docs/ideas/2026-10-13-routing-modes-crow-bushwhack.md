---
date: 2026-10-13
kind: feature
status: new
pipeline: none
related: [2026-10-13-driving-is-in-scope.md, 2026-10-13-route-waypoints-and-reroute.md]
---

# Crow and bushwhacking routing modes

Two more modes beside hike, bike and drive (`TravelMode`):

- **Crow:** a straight line between the points, with distance, bearing and the elevation profile
  along it. No graph needed.
- **Bushwhack:** prefers trails (the existing graph) but may leave them and cut across the
  gentlest terrain when that is much shorter, using the DEM for slope (and land cover where known:
  water, glaciers, cliffs, dense vegetation).

Today the router is graph-only; a point not near a trail gets no route.

## Open

- Bushwhack cost model: an off-trail cost per metre scaled by slope (reuse the hike slope
  penalty), forbidden cells (water, steep cliffs, glacier), a penalty to leave and rejoin a trail
  so it does not zigzag. A raster search (grid A*) over the DEM window around the corridor,
  stitched to the trail graph at the ends.
- Safety: say plainly in the app that off-trail routes are an estimate; show the steepest slope
  on the route and whether it crosses the slope-risk window.
- This changes the router and the route format (edges that are not on the graph), so it is a
  proposal and critic before it is built.
