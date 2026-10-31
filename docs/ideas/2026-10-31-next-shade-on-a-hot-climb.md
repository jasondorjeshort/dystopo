---
date: 2026-10-31
kind: feature
---

# Next shade in 400 m: shade along the route at the time you reach it

T188 draws the shade for one instant; T164 knows when you reach each point of the route. Project the two: the
stretches of the route that will be in shade when you walk them, drawn on the route line and listed as "shade in
400 m for 10 minutes", with one spoken line before a long sunny traverse on a hot day, the way the water line speaks
before a dry stretch. The same projection gives "you lose the sun at the pass at 15:40", which the terrain-aware
deadline idea uses. Cheap: the shade tiles exist and the pace timing exists; the new part is sampling the route by
arrival time instead of one instant. Open: the temperature threshold that makes it worth saying (the station's
temperature is already fetched), or a plain toggle.

Recorded 2026-10-31 at the close of wave 23 from the coordinator's list, moonshots included at the owner's invitation.

Plan: one task. In `shared`: `routing/ShadeAlong.kt` samples a route every 25 m, gives each sample its arrival time
from a start instant and the route's pace (`CostModel.timeS` cumulated, the clock's `TurnPace`), and marches one ray
toward the sun at that instant (`Sun.position`, `dem/Horizon.kt`'s `RayMarch.maxTangent` over a `Terrain` read once
for the route's box with `Horizon.windowsAround`'s near and coarse layers): in terrain shade or in sun. Samples merge
into stretches (shorter than 100 m absorbed) with the time each stretch is reached, and the first sun-loss and sun-gain
on the route ("you lose the sun at 15:40, 3.2 km on") for the terrain-deadline idea. Terrain shade only, as T188's
overlay is; forest shade is out of scope and the wording says "terrain shade". Devtool: `devtool shade --gpx <route>
--start <ISO>` prints the stretches and writes a profile PNG. Phone: while navigating, the stretches recomputed from
the walker's progress and the clock every 10 minutes off the UI thread; the next one on the nav card ("Shade in 400 m
for 10 min"), the stretches drawn under the route line like `app/routing/WaterMarkers.kt`, and one spoken line before
a sunny stretch over 20 minutes through `app/nav/NavSpeech.kt`, modelled on `WaterText.dueSpoken`. ADR 0044 setting
`shadeSpoken`: Off, On hot days (default: the station temperature `PressureCard` already fetches, lowered to the
walker's height at 6.5 degrees per km, at 25 degrees C or more) or Always; the hot-day threshold is a survey item
(ADR 0039: what heat guidance hiking sources use), and the card line shows whatever the setting. `GET /nav` gains
`shadeAlong`. Done: unit tests on a synthetic east-west ridge (a north-side trail in shade at noon in winter, in sun
in summer), the Paradise to Panorama Point route's stretches checked against the T188 overlay's screenshot at the same
instants, the card and the line on the emulator replay.
