---
date: 2026-10-31
kind: feature
---

# After a storm warning: the quickest way off exposed ground

T178 says the pressure is falling fast. On a ridge that is the moment to get down. Combine the warning with the
terrain: where the walker is exposed (above the treeline or on open ground by the viewshed's openness, on a ridge by
the horizon), compute the quickest way to sheltered ground, on the way back along the track (T180) or forward along
the route, with the time at the fitted pace, and say it with the warning: "Pressure falling fast. 25 minutes down to
the trees along the way back." Below the treeline the warning stays as it is. Terrain words only, no lightning
science in the app's voice; the help text says it is a suggestion of where lower ground is. Open: the treeline from
the pack's landcover layer or a height by latitude; whether this is a card action ("Show the way down") rather than
spoken.

Recorded 2026-10-31 at the close of wave 23 from the coordinator's list, moonshots included at the owner's invitation.

Plan: one task, after the route-exposure idea's task (it reuses that task's ridge rule). In `shared`:
`nav/WayDown.kt` takes the walker's position, the way back (T180's `Retrace`/`WayBack` path in `nav/TurnAround.kt`)
and, when navigating, the route ahead, and answers whether the walker is exposed (on a ridge or summit by the ridge
rule, or on open ground: no mapped `wood` in the pack's `landcover` layer within 200 m and higher than the highest
mapped `wood` within 5 km, a local treeline read from the layer rather than a height by latitude) and, if so, the
first point along each direction that is not (inside mapped `wood`, or 100 m lower and off the ridge), with the
time to reach it at the clock's pace (`TurnPace`). Devtool: `devtool nav --way-down --path <gpx> --at <lat,lon>`.
Phone: when `PressureCard` (`app/readout/PressureCard.kt`) raises the warning and the walker is exposed, the card adds
the line "25 min down to the trees along the way back" (or "to lower ground"), the quicker direction first, a "Show
the way down" action that draws that stretch on the map, and the spoken line gains the same clause; below the treeline
nothing changes. The help text says it is where lower or wooded ground is, not where it is safe; no lightning advice in
the app's voice. No setting (it rides on the warning's own `pressureFallWarning`). `GET /readout` and `POST /readout/trend`
(`app/controls/ScaleBarRoutes.kt`, KDoc with them) report `wayDown`. Done: unit tests on a synthetic ridge with a wooded valley on one side; a replayed
Rainier walk above the treeline with a synthetic pressure fall (`PressureTrend` replay) showing the card and the drawn
stretch on the emulator; screenshots and the strings in the report.
