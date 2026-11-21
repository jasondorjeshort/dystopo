---
date: 2026-11-16
kind: feature
related: [2026-11-01-pre-trip-briefing-for-a-route-and-a-date.md, 2026-11-15-a-drive-is-a-speed-profile.md]
---

# The clock time at each point of a route, and the sun there: for a drive as well as a hike

`routing/SpeedProfile.kt` (T291) gives a drive a time at every point of its line, not only a total, and
`routing/ShadeAlong.kt` takes a start instant and gives a route's shade stretches. Wanted: slide along the route
(its elevation profile, or the line on the map) and see the clock time you would be there, with the terrain lit
for that hour, so a planner sees which part of a mountain road or of a descent is in shadow, in low sun or after
dark, and what leaving an hour earlier changes. The owner, 2026-11-16: "Sounds cool, especially for mountain
drives." It differs from the related briefing idea, a page of figures for a route and a date: this is one control
on the route card and the map's light.

## Open

- A moonshot, recorded from conversation and not read against the phone's code: whether the map's hillshade can be
  relit for an hour on the phone, or only the route's own shade stretches drawn along its line (the cheap half).
- The phone does not use the profile yet (T291 was devtool only, `route --profile`), and a drive's time per point is
  only as good as the profile's limits: no real driving time has been read against it (finding
  `docs/findings/2026-11-15-a-drive-as-a-speed-profile.md`).
- What the sun is for on a drive: shade and ice on a winter road, low sun ahead, the daylight left at the far end.
  A survey first (ADR 0039) of what drivers and planners use.

2026-11-17, from conversation (a moonshot, not triaged): the terrain sunset along the route, not the horizon's: the time the sun actually drops behind the ridge at each point, from the DEM's horizon (as `routing/ShadeAlong.kt` and the horizon idea `2026-10-31-horizon-and-shade-shipped-as-derived-data.md` compute it), so the card can say when the sun leaves this trail, which in a valley can be an hour before the official sunset.

Question: build it for hikes now and add drives when the speed profile reaches the phone, or wait and build both
together? Found 2026-11-16 (triage): the map already relights the terrain for any instant (T188's sun-and-shade
control, `app/options/SunShade.kt` `SunClock`, shadow tiles by the minute) and `routing/ShadeAlong.kt` gives a
hike's time at every point (`timeAt`, used by `nav/NavShade.kt`), so the first Open point is answered; missing are
a control on the route card (no route elevation panel exists; the track's profile panel in
`tracking/TrackControls.kt` is the model) and a drive's per-point time on the phone (`routing/SpeedProfile.kt` is
devtool only). Recommended: hikes now, one pass: a slider on the card sets `SunClock` to the start time plus the time
to that point, with a marker on the line; drives follow the profile's port to the phone, since table times would put
a mountain drive's points minutes early.

Answer: the owner, 2026-11-21, by multiple choice: wait and build hikes and drives together (not the recommended hikes-first).
