---
date: 2026-11-15
kind: feature
related: [2026-11-14-drive-stops-and-signals-cost-time.md, 2026-11-15-a-drive-is-a-speed-profile.md, 2026-11-15-bike-routes-avoid-turns-across-big-roads.md]
---

# A turn across traffic costs more where the road crossed is busy, in time and in fuel

The owner, 2026-11-15, an idea for the future that may warrant research: a parcel carrier is said to plan its
rounds to avoid turns across traffic and to save fuel by it; such a turn in busy traffic means slowing further,
often stopping, and idling while waiting for a gap. How much depends on the traffic, which the project has no data
on. Today a drive's turn across traffic costs a fixed 4 s more than the kerb-side turn at a right angle whatever
road it crosses (`routing/TurnCost.kt`: `ACROSS_90_S` 7 s against `KERB_90_S` 3 s, ADR 0062), and its fuel differs
only by the idle over those seconds: the regain is the same for both sides (`routing/FuelModel.kt`, `turnJ`, ADR
0063). T291's speed profile gives the two sides different turn speeds and keeps the wait as seconds; the wait
itself stays one figure.

Wanted: the wait for a gap, and the stop it forces, scale with how busy the road crossed is likely to be, read
offline from what the graph has: the class of the road crossed, its lanes or speed where tagged, a signal at the
junction (the first related idea), town against open country. Least fuel then pays the stop and the idle, least
time the wait.

## Open

- Research first (ADR 0044): what the carrier has published (the saving claimed, whether it is turns avoided or
  the whole round's planning, and that its case is a round of many stops in towns, not one drive from A to B);
  published gap-acceptance and unsignalised-junction delay figures by the major road's flow; what the open
  routers charge for a turn across a road of a higher class.
- With no traffic data the road's class is a proxy and can be wrong both ways (a quiet trunk road at night, a busy
  residential street). How far a default may lean on it, and whether "avoid turns across busy roads" is then a
  setting (users split on it) or one behaviour.
- It comes after the speed profile's finding, which decides what a turn costs at all, and beside the stops and
  signals idea, which needs the same pack and graph revision to know where a signal is.
- 2026-11-16, the owner: "Sure" to the class of the road crossed as the first stand-in for how busy it is (with a
  mapped signal and the driving side), and asked whether traffic data could be found anywhere, given access.
  Candidates for the research pass, from the coordinator's memory and unverified (sources, coverage and licences
  are the pass's work; a source that is used needs its line in `sources.json`): (1) counts, not live traffic: road
  agencies publish the average daily traffic per road section (in the United States the federal highway
  performance data and the states' count maps; national count programmes elsewhere). Static, so it fits a pack, and
  it is the quantity a wait for a gap depends on; the work is joining the sections to the graph's roads. (2) Live or
  typical speeds from the commercial map services: a paid key, and terms that as a rule forbid keeping the data, so
  not offline. (3) Public live feeds (the states' traveller-information feeds, Europe's national access points):
  main roads only, online only. (4) Your own drives: the seconds stopped at turns by the class crossed
  (`2026-11-15-drive-and-hike-pace-learn-from-your-own-tracks.md`, the drive half). (5) Users' drives pooled: a new
  data flow, flagged, the owner's decision and not proposed here.
