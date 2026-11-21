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

Question: is a GPS coverage forecast along a route worth a proposal now, given it needs a new downloaded data source
(constellation almanacs or element sets, refreshed, with their terms in `sources.json`) and an orbit propagator
written from the specification? Recommended: not yet; leave it at rest until the per-point signal idea has recorded
real tracks, which would show whether terrain-blocked stretches are common and costly enough on your hikes to
warrant it. The terrain horizon alone (`dem/Horizon.kt`) can already mark deep, narrow stretches of a route without
orbits; if you want something sooner, that cheaper "sky open above N degrees" line on the route card is the step to
ask for.

Answer: the owner, 2026-11-21, by multiple choice: not yet (recommended).
