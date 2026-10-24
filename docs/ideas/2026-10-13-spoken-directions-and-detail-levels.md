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
  change), filtering on the priority. The filter is built: `Directions.kt` takes a `level` and
  `/nav/directions` uses the default (priorities 1 and 2); only the user setting is missing.
- Cues beyond turns ("steep climb ahead") are later; "then" cues already exist in the written
  directions (T116).

## Open

- Voice: the platform text-to-speech engine, an offline-capable voice, ducking audio; the battery
  cost and the screen-off case (the battery idea).
- Testable through the existing replay (`POST /location/replay`) and the `/nav/status` events.

Question: build the voice option now, and with what wording? `Directions.kt` already carries type, priority and lead distance, so speaking them through the platform text-to-speech engine (off by default, tested by `POST /location/replay` and `/nav/status`) fits one pass with no format change; the setting's label, where it sits and the screen-off behaviour are yours. Recommendation: one pass for voice, off by default, speaking priority 1 and 2 as the screen shows, with its setting text proposed in the PR for your sign-off; the detail-level setting (only its UI is left, the filter exists) stays for the much later wave you named, and cues beyond turns after it.
Answer: build voice now as recommended: one pass, off by default, priority 1 and 2, setting text proposed in the PR; detail levels and further cues later (owner, 2026-10-23).
2026-10-23 (T126): voice landed: Settings › Navigation and tracking › Spoken directions, off by default, each shown direction (priorities 1 and 2) said once in the card's words, `GET /nav/speech`. The detail-level setting, cues beyond turns, a mute on the navigation card and speech with the screen off without a recording stay.
