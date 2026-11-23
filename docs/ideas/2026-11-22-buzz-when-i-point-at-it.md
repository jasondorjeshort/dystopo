---
date: 2026-11-22
kind: feature
related: [2026-11-21-which-way-is-it-the-panorama-facing-a-selected-place.md]
---

# Buzz when I point at it: finding a target by feel

Status: **untriaged** (an idea of the coordinator's own from wave 46's work, offered when the owner asked "Any ideas out of this wave? Moonshots welcome." and recorded on the owner's word, 2026-11-22: "Record them all; we'll triage and think through them later."). The anchors are files wave 46 wrote or read; nothing else here was checked against the code or measured.

In the Panorama's Follow mode the phone already knows where it points (`shared/src/commonMain/kotlin/io/github/jasondorjeshort/dystopo/horizon/PhoneAttitude.kt`, `shared/src/commonMain/kotlin/io/github/jasondorjeshort/dystopo/horizon/AttitudeFilter.kt`) and how far the target lies off the view (`shared/src/commonMain/kotlin/io/github/jasondorjeshort/dystopo/horizon/OffView.kt`, the edge arrow's "Mount Rainier, 23° left"). Add touch: a tick as the phone sweeps past the target's bearing, a steady buzz while it is on it, the ticks coming faster as it nears. The target is whatever the Panorama can already hold (`androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app/panorama/PanoramaTarget.kt`): a peak, the sun, a satellite, a flight; and it would be worth more with a waypoint, the trailhead or the car as targets.

Why: it is the first use of the Panorama that needs no eyes on the screen (dark, glare, gloves, a low-vision user), and it answers "which way is it?" faster than reading a line of text.

## Open

- Whether it is a mode of the Panorama, or a small finder of its own that the map's selected place can open ("Point me at it") without the drawing.
- Tilt as well as bearing (a second kind of tick for up and down), or bearing only.
- A setting for the buzz (ADR 0044) and what TalkBack users get instead of, or beside, it.
- The compass's error: the buzz is only as good as the heading, so it should say so while the compass status is low (T331's weak-compass rule).

Question: build the buzz as a mode of the Panorama's Follow the phone (bearing only, on the target `PanoramaTarget` already holds, ticks closing in from the angle `OffView` gives, a steady buzz within a few degrees) behind a setting off by default, or as its own "Point me at it" finder opened from the map's selected place?
Recommendation: inside Follow first, bearing only, the buzz counted on `NavDevice`'s `vibrations` counter and read by a debug route so the emulator can test it; the finder from a selected place fits after "Which way is it?" (answered in `2026-11-21-which-way-is-it-the-panorama-facing-a-selected-place.md`) is built, its natural entry. The setting follows ADR 0044, the low-compass note T331's rule.
Options:
a) A mode of Follow, bearing only, a setting off by default (recommended).
b) Its own small finder from the selected place, without the drawing.
c) Both, with tilt as a second kind of tick.
d) Not worth building.

Answer: A, a Follow mode, bearing only, a setting off by default (the owner, 2026-11-22, multiple choice).
