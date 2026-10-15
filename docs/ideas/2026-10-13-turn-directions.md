---
date: 2026-10-13
kind: feature
status: done
pipeline: T102 (text built; voice and the detail level later)
related: [2026-10-13-large-distance-units.md, 2026-10-13-route-waypoints-and-reroute.md, 2026-10-13-driving-is-in-scope.md]
---

# Turn directions in hike, bike and drive, with optional voice

Written instructions along a route: "Turn left in 30 feet onto Trail F", "Turn right in 1 mile
onto Broadway Avenue". Hike, bike and drive modes all get them. Later, an option to speak them,
off by default.

Today: navigation mode (T61) shows distance and ETA to the next waypoint and the end, and an
off-route alert; the router returns edges with class and names but not manoeuvres.

## Decided in brainstorm (2026-10-13, owner)

- Scope for the first version: instructions at real decisions only, trail name else ref else class,
  lead distance by mode and speed, text first, structured so a voice formatter can follow.
- Cues beyond turns ("steep climb ahead") are later.
- Much later wave: a setting for the level of detail of directions, perhaps three levels (for
  example few: only real forks and road changes; normal; many: every junction and name change).
  Design the instruction stream so each instruction has a priority the level can filter on.

## Open

- A manoeuvre layer in `shared/routing`: turn angle per junction, named-edge changes, merge of
  straight-through junctions, trail names (route ref, then name, then class), when a turn is not
  worth an instruction on a trail.
- Distances use the large-distance-units rule. Lead distance by mode (a drive needs miles of
  warning, a hike does not).
- Voice: the platform text-to-speech engine, an offline-capable voice, ducking audio. A setting,
  off by default; consider battery and the screen-off case.
- Testable through the existing replay (`POST /location/replay`) and `/nav/status` events.

## Built (T102, wave 14)

Text directions in hike, bike and drive: `shared/.../routing/Directions.kt` (the rules in PLANNING ›
Routing, Turn directions), on the route card ("Show N directions"), the navigation card and
`/nav/status`, in the server's `/route` reply and `devtool route`. Each instruction has a type, a
priority (1 to 3; the apps show 1 and 2) and a lead distance, so the two later pieces need no format
change. Not built, for a later task: voice (the open lines above), the detail-level setting, cues
beyond turns.
