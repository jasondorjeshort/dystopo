---
date: 2026-11-19
kind: feature
related: [2026-11-19-peak-names-on-the-sky-plots-skyline.md, 2026-11-16-the-clock-time-at-each-point-of-a-route-and-the-sun-there.md]
---

# Today's sun path across the skyline

The GPS view (T307) draws the sun where it is now and the land's skyline. Draw today's whole arc of the sun on the
plot, with the times it goes behind the terrain and comes out again: "behind the ridge at 15:40, back at 07:55".
The turn-around clock already computes a terrain sunset from the same horizon (`nav/NavSession.kt`,
`terrainSunsetMs`; `nav/Sun.kt`, `dem/Horizon.kt`), so this draws what the app already knows. It answers when the
tent gets morning sun, when a slope loses its light, how long the day is at the bottom of a valley. A date
picker (tomorrow, a trip's date) and the moon's path are the natural next steps.

## Open

- Whether the times are a line under the plot or labels on the arc.
- Agent-testable: the plot captured at a public place and date with a known terrain sunset (the turn-around
  route's figure).
