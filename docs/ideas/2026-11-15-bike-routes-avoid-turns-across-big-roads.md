---
date: 2026-11-15
kind: feature
---

# Bike routes count a turn across a big road as a cost

Wave 33 gave drives a turn table and a driving side (`routing/TurnCost.kt`, `routing/DrivingSide.kt`, ADR 0062);
bike routes pay nothing for a turn. For a rider the turn across traffic on a primary road is a safety cost more
than a time cost. Wanted: the same mechanism under a bike table (a turn across a large road dear, onto or along a
cycleway free), which needs the bike search on directed-edge states as `routing/DriveSearch.kt` is for drives.
The owner, 2026-11-15: yes please.

## Open

- A setting or always on (ADR 0044: research how the cycling routers do it and whether riders split).
- The cost of moving the bike search to edge states (the drive's was about twice the states).

2026-11-16: the owner said "Sure" to the class of the road crossed as the drive's stand-in for how busy it is
(`2026-11-15-a-turn-across-traffic-costs-more-where-the-road-is-busy.md`), the measure pass 2 below uses; one
reading of "the class crossed" in `routing/TurnCost.kt` can serve both tables. That file also lists where traffic
counts might be had.

Plan: two passes, as the drive's were (T281, T282). Always on, no setting: the survey of 2026-11-15 found no
cycling app or router that exposes it as one; the routers charge a flat or angle-based cost per turn and a signal
cost, one cycling planner downrates turns across busy roads, and the route-choice study of 164 riders' GPS tracks
(Broach, Dill and Gliebe, 2012; read through summaries) values a turn at about 7 percent of trip distance and a
left turn across a busy unsignalised road at about 16. (1) Engine, values zero: `JunctionRoad`, `Junction` and
`TurnCost` take the mode instead of a hard-coded `CostModel(TravelMode.DRIVE)` (`routing/TurnCost.kt`),
`DriveSearch` runs for bike behind devtool `route --bike-turns` with a `TurnTable` of zeros, and the report gives
states per node and time against the node search on the public rides (the drive's was 2.0 to 2.5 times the
states). (2) Values: a bike table in equivalent metres, nothing for a turn onto or along a cycleway or under 30
degrees, a small cost for any other turn, and a larger one by the class of the road crossed or joined for a turn
across traffic (`DrivingSide`), capped in metres; first values stated as guesses, as ADR 0062's were; then on by
default, with the public rides before and after in the report. Not known to the graph: whether a crossing is
signalised (the stops and signals idea). Out of scope: hike, the overlay (drives only).
