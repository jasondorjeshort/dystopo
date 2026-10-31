---
date: 2026-10-31
kind: feature
---

# When to cross: snowmelt timing for each stream crossing from the catchment's sun

T187 lists a route's stream crossings with their catchment; T181 can say how many hours of direct sun that catchment
gets by the hour of day. The mountaineer's rule is to cross melt-fed streams early, before the day's sun has melted
the snow above. Compute, per crossing, when the catchment above it comes into the sun and how much of it lies above
a snow line the user sets (or a default by month and latitude), and say "cross before 11:00" on the crossing's row
and in the spoken line, with the reason. Pair it with the storm warning's pressure trend for a rain spike. A moonshot
on the data side (no snow cover source offline) but cheap to build as a sun-timing rule, which is most of the signal.
Open: the snow line as a setting or a seasonal default; what to say when the catchment is small (nothing).

Recorded 2026-10-31 at the close of wave 23 from the coordinator's list, moonshots included at the owner's invitation.

Question: the app has no snow data offline, so "cross before 11:00" would be advice about a hazard computed from a
heuristic; do you want that instruction, or only the facts behind it ("the catchment above is in sun from 08:10")?
Recommendation: the facts first, as one task, and the instruction only on your word after the facts are seen on real
trips. What exists: `routing/WaterAlong.kt` gives each crossing a `catchmentKm2` read from the `drainage` layer (a number
at the line's downstream end, no outline), so the catchment's area on the ground must be traced: `dem/FlowGrid.kt`
(T165's flow directions) upstream from the crossing over a DEM window, capped by area. Then the sun: the fraction of the
traced cells in sun hour by hour from `render/CastShadow.kt` at a coarse zoom, giving "in sun from" and the hour half of
it is lit. A snow line as a user setting (ADR 0044: no default is defensible without data, so off unless set) would
add "60 percent above 2,500 m". The row in `app/routing/RouteWater.kt` and the spoken line in `WaterText.spoken` gain the
clause for crossings over `WaterParams.flowingFromKm2`; small catchments say nothing, as the idea says. The rain-spike
pairing with T178's trend is a separate question (a falling-pressure line already exists).

Answer:
