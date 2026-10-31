---
date: 2026-10-31
kind: feature
---

# The turn-back deadline from the terrain sunset where you will be

T163's clock counts to the flat-horizon sunset at your position, and its label says so. T181's horizon sweep now
gives the terrain sunset at any point, and T164's pace says where on the route you will be at each minute. Put them
together: the deadline becomes the terrain sunset at the point you reach then, so a valley trail that loses the sun
at 15:40 behind a ridge gives an earlier turn-back than the flat 17:54, and a summit that keeps it later gives a
later one. The nav card's label changes from "flat horizon" to "behind the ridge at 15:40". Also on the route
profile: the stretch walked in shade after the terrain sunset, and the point where the headlamp comes out. Open:
the cost of one horizon sweep per route point (sample every few hundred metres, cache by route); civil twilight
has no terrain variant (the sky lights the valley), so only the sunset deadline moves.

Recorded 2026-10-31 at the close of wave 23 from the coordinator's list, moonshots included at the owner's invitation.

Plan: one task, smaller than the idea reads. T163's clock times the return to the line's end (`NavSession.turnReading`
resolves the deadline at `path.points.last()`; `WayBackClock` at the trailhead), so "the sunset where you will be" at
the deadline is the end point's terrain sunset: one horizon sweep per navigation, not one per route point. In `shared`:
`DeadlineKind.TERRAIN` (`terrain`, label "terrain sunset") in `nav/TurnAround.kt`, and `Deadline.resolve` taking an
optional terrain day (`Sun.TerrainDay` from `ShadeTiles.pointAnswer`): its `sunsetMs`, the label "sun behind the
terrain at 15:40" when it is earlier than the flat sunset, falling back to the flat sunset with "flat horizon" when no
DEM covers the end or the answer is not back yet; civil twilight and a typed time stay as they are. Phone: the sweep
through `ShadeService.pointAnswer` off the UI thread once per path end and once per way-back anchor, cached by point and
date; the card shows the flat deadline until it lands; a fourth choice on the existing `turnDeadline` row with help text
saying a valley stays light after the sun leaves it (older builds read `terrain` as unparseable and use sunset). Default:
a benchmark survey (ADR 0039) of whether any watch or app counts down to a terrain sunset; unless it finds a clear
practice, `sunset` stays the default and `terrain` is a choice (ADR 0044: the research shows the split, so a setting).
Devtool: `nav --turn-around --path <gpx> --deadline terrain` reads the end's terrain from the pack (`--source`). `GET /nav/turnaround` gains `terrainSunsetMs` and
`flatSunsetMs`. Done: unit tests with a synthetic ridge west of the end (earlier deadline) and a summit end (later);
the Paradise end at a late-October date showing the two times; screenshots of the card. Out of scope: the route-profile
marks (the shade stretch after terrain sunset, the headlamp point), which belong with the next-shade idea's projection.
