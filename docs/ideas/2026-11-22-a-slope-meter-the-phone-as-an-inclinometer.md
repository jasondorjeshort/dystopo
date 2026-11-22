---
date: 2026-11-22
kind: feature
---

# A slope meter: the phone as an inclinometer, beside the map's slope for the same spot

Status: **untriaged** (an idea of the coordinator's own from wave 46's work, offered when the owner asked "Any ideas out of this wave? Moonshots welcome." and recorded on the owner's word, 2026-11-22: "Record them all; we'll triage and think through them later."). The anchors are files wave 46 wrote or read; nothing else here was checked against the code or measured.

Lay the phone's long edge along the snow, the trail or a ski pole and read the slope's angle; sight along its edge at a far slope and read that. The attitude source and its filter are there since wave 46 (`shared/src/commonMain/kotlin/io/github/jasondorjeshort/dystopo/horizon/PhoneAttitude.kt`, `shared/src/commonMain/kotlin/io/github/jasondorjeshort/dystopo/horizon/AttitudeFilter.kt`, resting within 0.3 degrees); the reading is the tilt or the roll, whichever axis lies along the slope. Beside it, the angle the terrain data gives at the fix, so the user sees measured against mapped.

Why: people in avalanche terrain carry a separate tool for exactly this, and the angles that matter (around 30 to 45 degrees) are the ones a map's slope shading resolves worst.

## Open

- A safety matter, not a preference: the wording must not suggest the app judges a slope safe; a research pass (ADR 0039) on how avalanche tools word and bound this comes first.
- The sensor's accuracy when the phone is in a case or the edge is not flat; a zeroing step on a level surface.
- Where it lives: a card of the readout, or a small tool beside "Check my GPS" (`docs/ideas/2026-11-19-check-my-compass-beside-check-my-gps.md` asks for a similar home).
