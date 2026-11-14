---
date: 2026-11-14
kind: feature
related: [2026-10-31-ask-the-map-by-voice.md, 2026-10-13-spoken-directions-and-detail-levels.md]
---

# The route ahead, spoken: the map for a screen reader and for a phone in the pocket

T270's accessibility check (`tools/android-a11y.py`, `GET /semantics`, `tools/a11y-baseline.json`) covers the
controls; the map itself is a picture a screen reader cannot read. The route card already holds what a walker
would be told: the water count, the steepest slope and the terrain under Trip details (T276), the turn-back time;
and `androidApp/.../app/nav/NavSpeech.kt` already speaks. Spoken on demand, and at a set distance while navigating:
"climbs 300 m over the next 2 km, a stream in 400 m, the steepest part after it". It differs from the two related
ideas, which answer a question or give turn directions: this describes the ground ahead, and its first user is
one who cannot see the map. The owner, 2026-11-14: "Eventually."

Recommendation (the coordinator): after the baseline's screen-reader findings are fixed (the map's controls
reachable under a full screen, the search field's label), since a spoken map behind a broken screen-reader path
helps nobody; and a survey first (ADR 0039) of how navigation aids for blind walkers phrase terrain.

Waits: the owner, 2026-11-14: "Eventually." No trigger named.
