---
date: 2026-11-21
kind: feature
related: [2026-11-16-the-clock-time-at-each-point-of-a-route-and-the-sun-there.md, 2026-11-20-a-panorama-view-a-scrolling-ribbon-of-the-horizon.md]
---

# One clock for the whole app: set a time, and the map, the Horizon view and the Panorama show then

Proposed 2026-11-21 as "Sky from here, then" (a date and time on the long-press view: where the sun rises over that
ridge on Saturday, seen from the trailhead). The owner, 2026-11-21: "4, or a general system for us to set the time to
see future or past things, is an excellent idea that doesn't sound that hard but is a crazy change for users."

So: one app-wide "view time", now by default, that every time-dependent layer reads. Today each has its own notion:
the map's sun-and-shade control (T188, `app/options/SunShade.kt` `SunClock`, shadow tiles by the minute), the Horizon
view's sun path (today only, a known limit since v0.1.34), the satellites and flights (live only), and the route clock
idea (the time at each point of a route). With one clock: the Horizon and Panorama views draw the sun and moon for the
set time at any point (with "Sky from here", a planned sunrise from a chosen summit); the map's shade follows it; the
route clock becomes "start at" on the same clock.

The "crazy change for users" is the owner's point: a view that is not now must never be read as now. Live layers
(flights, satellites, your position's readout) are hidden or marked when the clock is moved, as the satellites and
flights are at a moved point (T317); a band like the place line says the time ("Saturday 6:40 AM, 2 days from now")
with "Back to now"; nothing persists across launches.

## Open

- Where the control lives (one on the map and one per view, or one shared strip), and whether the time and the place
  of "Sky from here" share one band.
- Which layers can honestly show the past or future (sun, moon, shade: yes; satellites: needs orbits; flights: no).
- Benchmark first (ADR 0039): how planners and astronomy apps set and show a non-current time.
