---
date: 2026-11-19
kind: feature
---

# A forecast of GPS coverage along a route

The GPS view draws the terrain's horizon from the DEM around the phone (`dem/Horizon.kt`). The same horizon at
points along a planned route, with the satellites' positions at the hour the route reaches them, says where
reception will be poor: a slot canyon, a deep valley, a north face at a given hour. The route card could say "GPS
likely poor for 2 km here" and mark the stretch; the position estimate for poor GPS could be warned ahead.

It needs the orbits offline: published almanacs or two-line element sets for the GNSS constellations, downloaded
with a pack and refreshed (a new data source: a `sources.json` entry first, with its licence), and an orbit
propagator in `shared` (pure Kotlin; SGP4 is published and simple, written from the specification, not copied).
A major choice (a data source the app leans on): a proposal before building.

## Open

- How far ahead the almanac stays good enough for "which satellites are up" (weeks for GPS; the others to check).
- The cost along a long route: the horizon at a point every few hundred metres, not every metre.
