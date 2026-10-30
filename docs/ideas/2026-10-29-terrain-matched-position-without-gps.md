---
date: 2026-10-29
kind: feature
related: [2026-10-25-track-activity-profiles-and-dropout-handling.md, 2026-10-13-battery-efficiency.md]
---

# A position from the terrain when GPS is gone

In a slot canyon, under a dense canopy or against a cliff the GPS fix drifts by hundreds of metres
or stops (the dropout handling idea cleans the track afterwards; this idea keeps the position live).
The phone carries what a GPS does not need: a barometer, a step counter, a compass with declination
(`shared/.../nav/Wmm.kt`, `controls/Heading.kt`), and the elevation of every point nearby
(`shared/.../dem/ElevationGrid.kt`, `routing/Elevation.kt` samples the DEM pack at z13). The trail
graph is on the phone too (`routing/RoutingGraph.kt`, `TiledGraph.kt`). Match the last few
minutes of barometric altitude and stride against the elevation profiles along the nearby trails and
the position follows, with no signal, like a reader matching a profile to a map by hand.

- **Inputs:** a pressure stream (nothing reads `TYPE_PRESSURE` today; `TrackStats.kt` only mentions
  the barometer in a comment), the step counter, the heading, the last good fix with its age
  (`tracking/Tracking.kt`, `LocationSource.kt`, where every fix already enters by one interface, so
  a terrain fix is just one more source with a quality mark).
- **Filter:** a particle filter over the graph's edges near the last fix: each particle walks along
  an edge at the stride rate in the heading's direction, is weighted by how well the DEM's elevation
  along its path matches the pressure altitude's shape (relative, so the weather's slow drift
  cancels), and the cloud's centre is the fix with its spread as the accuracy. Off the graph
  (bushwhack) the same filter runs over the grid.
- **Shown:** the puck turns from a GPS dot into a terrain estimate with a visible uncertainty and the
  word for it; the track records the source so the activity profile idea can treat it apart.
- Agent-testable: a unit test runs a synthetic pressure and step log along a Rainier trail profile
  with the GPS cut and asserts the estimate stays within the spread; a devtool command replays a GPX
  with its elevation field as the barometer and reports the error against the true fixes as JSON; on
  the phone the replay source (`tracking/Replay.kt`) feeds the same log through a debug route.

## Open

- How much the shape of a profile pins a position: a flat valley floor gives nothing and the
  estimate must say so (a wide spread, not a confident wrong dot). Measure on real tracks first.
- Calibration: pressure altitude needs a reference; take it from the last good fix's DEM elevation
  and re-anchor at every good fix.
- Battery: the barometer and step counter are cheap, the filter is not; run it only while the GPS
  reports poor accuracy or no fix for a minute, under the T150 background rule.
- Phones without a barometer get nothing beyond dead reckoning; say so in the diagnostics.
- Two or more waves: the first a measurement on recorded tracks, the second the live source.

Plan: the first pass is the measurement, one task, no phone code. In `shared/.../nav/` (pure Kotlin): a `TerrainFix` particle filter over `RoutingGraph` edges near a seed fix; particles advance by stride and heading, weighted by how the relative pressure-altitude shape of the last few minutes matches the DEM profile along their path (`routing/Elevation.kt`'s sampling), the cloud's centre and spread being the estimate. Devtool: `terrain-fix --gpx <file> --cut <from>..<to>` replays a GPX's elevation field as the barometer and its point spacing as strides, cuts the GPS over the named stretch and reports the error against the true fixes (median, p90, whether the spread covers the error) as JSON, over synthetic tracks and public ones near Rainier; a unit test on a synthetic profile along a Rainier trail keeps the estimate inside its spread. The report says where a profile's shape pins a position and where it does not (flat ground must widen the spread, not drift). Out of scope: the live source (`LocationSource`, `TYPE_PRESSURE`, the step counter, the puck, the battery rule), the second task if the numbers justify it.
