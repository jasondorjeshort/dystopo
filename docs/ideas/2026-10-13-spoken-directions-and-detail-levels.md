---
date: 2026-10-13
kind: feature
related: [2026-10-13-route-waypoints-and-reroute.md, 2026-10-13-battery-efficiency.md]
---

# Spoken turn directions, a detail-level setting, and cues beyond turns

The part of the turn-directions idea (recorded 2026-10-13) that T102 left for a later task. The
written directions it built are `Directions.kt` in `shared` routing (PLANNING › Routing, Turn
directions): each instruction carries a type, a priority 1 to 3 (the apps show 1 and 2) and a lead
distance, so neither piece below needs a format change.

- Voice: an option to speak the directions, off by default.
- Detail level (owner, 2026-10-13, "much later wave"): a setting for how many directions are given,
  perhaps three levels (few: only real forks and road changes; normal; many: every junction and name
  change), filtering on the priority.
- Cues beyond turns ("steep climb ahead") are later.

## Open

- Voice: the platform text-to-speech engine, an offline-capable voice, ducking audio; the battery
  cost and the screen-off case (the battery idea).
- Testable through the existing replay (`POST /location/replay`) and the `/nav/status` events.
