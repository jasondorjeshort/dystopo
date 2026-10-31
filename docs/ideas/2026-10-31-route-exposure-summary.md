---
date: 2026-10-31
kind: feature
related: [2026-10-30-season-aware-routing.md]
---

# A route's terrain exposure: steep ground, aspects, shade and ridge time

The pack has slope and aspect; T181 has shade by time of day; T182 has the horizon. Summarise a planned route the way
guidebooks and avalanche courses do: the distance and time on slopes over 30 degrees, the aspects of those slopes, the
time above the treeline or on ridges (the viewshed's openness), the time in shade at the planned hour, the crossings
of gullies that drain steep slopes above the trail. Shown as one line on the route card ("1.2 km on steep slopes,
north and east aspects, 40 min on the ridge") with a sheet of the pieces on the profile, and printed on T183's sheet.
This is information about the terrain, not a hazard rating: the wording says what the terrain is, never whether it is
safe, and the season-aware routing idea stays separate. Open: the thresholds (30 and 35 degrees are the usual
avalanche figures), and whether the summary belongs on the route card or in the trip's own page.

Recorded 2026-10-31 at the close of wave 23 from the coordinator's list, moonshots included at the owner's invitation.

Plan: one task, the terrain facts only. In `shared`: `routing/ExposureAlong.kt` (beside `routing/WaterAlong.kt`) samples
a planned route every 25 m over the pack's DEM at z14 and reports, from `render/Slope.kt`'s `Slope.degrees` and
`Slope.aspect` on a small window around each sample (the ground's slope, not the trail's grade): the distance and time
(the route's own pace, `CostModel.timeS`, as the card times it) on ground of 30 to 34 and of 35 degrees and over (the
edges `render/Slope.kt`'s slope bands already draw), the eight-point aspects of that ground, the time on ridges (a
sample higher than the mean of a 150 m ring by more than a threshold the task tunes on Rainier, a topographic position
index) and the time in terrain shade at the planned start (a `RayMarch` toward the sun per sample at its arrival
time, `dem/Horizon.kt`, as `render/CastShadow.kt` marches a cell). Devtool: `devtool exposure --gpx <route>` prints
the JSON and a profile PNG with the stretches marked. Phone: one line on the route card (`app/routing/RouteControls.kt`,
beside `WaterText.cardLine`: "1.2 km on slopes over 30 degrees, north and east; 40 min on ridges"), the stretches on a
sheet behind it, `GET /route` gaining `exposure`, and the line in T183's sheet margin (`app/print/PhoneSheet.kt`). The
wording names the terrain, never safety (no "avalanche", no "hazard"); the help text says what each figure measures.
Survey (ADR 0039) first: how the established topo apps and guidebooks summarise a route's steep ground; no setting
(30 and 35 degrees are the one published practice, ADR 0044). Done: unit tests on a synthetic cone (slope and aspect
by quarter) and a ridge; the Paradise to Panorama Point route's figures checked against the slope overlay's screenshot;
the card screenshot at font scales 1.0 and 2.0 (T184's note: the card already reaches the top chips at 2.0, so cap or
scroll it). Out of scope: the gully crossings (needs each crossing's catchment slope, after the stream-timing idea's
catchment trace), the treeline time (the `landcover` layer's `wood` coverage is too patchy to call ground open), and
any routing change (the season-aware routing idea).
