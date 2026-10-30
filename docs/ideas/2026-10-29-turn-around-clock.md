---
date: 2026-10-29
kind: feature
related: [2026-10-29-route-times-fitted-to-your-tracks.md, 2026-10-29-sun-and-shade-on-terrain.md, 2026-10-13-route-waypoints-and-reroute.md]
---

# The turn-around clock

One number on the navigation screen: "turn back by 14:10 to be out in daylight", recomputed as the
user moves. It joins two ideas that each give half of it: the fitted route times (the user's own
pace up and down, `shared/.../routing/Router.kt`, `edgeTimeS`) and the terrain sunset at the
trailhead (the sun idea's point answer). Today the route card gives one arrival time for the whole
route and nothing about the way back; the question every day hiker actually has is when to stop
going up. The navigation overlay (`androidApp/.../nav/NavOverlay.kt`, `NavUiState.kt`) and the trip
card (`trips/`) are where it is shown.

- **Compute:** from the current position, the time to return along the route walked (or the
  planned route's remaining part when it is a loop), with the user's descent pace, against the
  chosen deadline: civil sunset at the end point, the terrain sunset from the sun idea where built,
  or a time the user types (a pickup, a last ferry). The clock is deadline minus return time minus
  a margin the user sets.
- **Shown:** the time on the nav bar with a colour as it nears, one notification at the margin and
  one at the time itself (the alert path T61's off-route alert uses); after it passes, the time by
  which the user reaches the end, so the number stays useful on the way down.
- **Spoken:** one line through `NavSpeech.kt` at the margin, under the spoken-directions idea's
  detail levels.
- Agent-testable: a unit test over a synthetic out-and-back with a known pace gives the expected
  time; `devtool nav` prints the clock for a route, a position and a deadline as JSON; the nav debug
  routes inject a position and read the state.

## Open

- Which deadline is the default: a benchmark question (ADR 0039) and probably a setting with the
  researched default (ADR 0044); civil twilight versus sunset is the kind of thing users split on.
- Without the fitted pace it runs on the fixed model and says so; without the terrain sunset it
  uses the flat horizon, which is late in a valley by half an hour or more, so the label states
  which.
- A route with no planned end (free tracking) returns along the recorded track; a point-to-point
  route with a car at each end has no turn-around and shows the arrival clock only.
- Small once its two parents exist: about half a wave; on its own with the fixed model and the flat
  horizon, still about half a wave and still worth having.
