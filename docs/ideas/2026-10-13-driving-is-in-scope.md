---
date: 2026-10-13
kind: question
status: done
pipeline: ADR 0032, T102 (the wording)
related: [2026-10-13-turn-directions.md, 2026-10-13-routing-modes-crow-bushwhack.md]
---

# Doctrine: hiking and biking first, driving kept and kept good

The product is hiking and biking centred, but driving directions are valuable for real use
(getting to the trailhead, road trips) and for debugging the router, so driving stays a
first-class mode. This is a statement of intent rather than a feature.

Tension to settle: T73 ships drive mode "stated plainly in the app as not a road navigator", and
T77 and ADR 0028 extend it to long drives. The owner's doctrine says drive is a supported mode
across routing, turn directions, waypoints and rerouting, not a trailhead helper.

Owner, 2026-10-13: if driving can be made first class, or second among equals after hiking, that
is good. Direction: hike first, drive a full second mode (not a trailhead helper). Not yet an ADR.

## Open

- Wording for the in-app statement of scope, and whether it still disclaims turn restrictions and
  traffic.
- If it is a rule, it goes in an ADR (a decision made in prose gets one) and the T73 wording in
  `routing/Router.kt` and the app is updated with it.
