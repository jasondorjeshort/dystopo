---
date: 2026-11-21
kind: feature
related: [2026-11-16-the-clock-time-at-each-point-of-a-route-and-the-sun-there.md, 2026-11-21-when-the-sun-or-moon-stands-on-a-named-peak.md]
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
- 2026-11-21, the coordinator's "sunlit map at a chosen hour" after wave 44, recorded on the owner's "we can record
  them all": the hour is built (T188's slider covers one day at the view's centre, shade tiles by the minute); what
  this clock adds is the date. One answer deserves a line of its own under it: "will this campsite get morning sun",
  as a row at a long-pressed point for the set date ("Direct sun 8:10 AM to 4:35 PM"), from `Sun.onTerrain` on that
  point's horizon, which the Horizon view's sun path already uses where the view stands.

2026-11-22, folded in at triage (the owner, 2026-11-22, by multiple choice at triage): one time slider scrubbed across the map, the Horizon view and the Panorama (sun, moon, satellites and flights moving together, the map's shade following), for "when is the light on that ridge".

Question: the one clock changes what every time-dependent layer reads and how a user knows a view is not now, and it
is more than one pass; does it go through a proposal with the survey as its Prior art (ADR 0039: how planners and sky
apps set and show a time that is not now) and an ADR, as the Panorama view did, or straight to a first pass?
Recommended: the proposal. Five other ideas hang on its shape (the shadow forecast, the sun on a peak, old photos, the
stars, the route's clock), `SunClock` (`app/options/SunShade.kt`) and the sky views' date (`HorizonUi.date`) are two
clocks to merge, and the rule that live layers hide or are marked when the clock is moved wants writing once. A
likely cut: pass one, the clock in `shared`, the band with "Back to now" and the two sky views reading it; pass two,
the map's shade and the "Direct sun" row at a long-pressed point.

Answer: the proposal first, with the survey as Prior art and an ADR; the likely cut above stands (the owner, 2026-11-22, by multiple choice at triage).
