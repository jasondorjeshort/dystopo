---
date: 2026-11-22
kind: feature
related: [2026-11-10-window-seat-mode-with-the-fix-moved-up-to-now.md, 2026-11-21-the-view-from-a-routes-high-point.md]
---

# Moonshot: your route drawn on the hillside, through the Panorama's lens

Status: **untriaged** (an idea of the coordinator's own from wave 46's work, offered when the owner asked "Any ideas out of this wave? Moonshots welcome." and recorded on the owner's word, 2026-11-22: "Record them all; we'll triage and think through them later."). The anchors are files wave 46 wrote or read; nothing else here was checked against the code or measured.

The planned route or a recorded track projected through the lens (`shared/src/commonMain/kotlin/io/github/jasondorjeshort/dystopo/horizon/LensView.kt`) onto the land the Panorama draws, so that holding the phone up in Follow mode shows where the trail climbs that face, where it crosses the ridge and which stretch is behind it (dashed, as the sun's path is behind terrain). Each route point has a bearing, a distance and an elevation angle from the fix, which is all a layer needs (`androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app/panorama/PanoramaLayers.kt`); `shared/src/commonMain/kotlin/io/github/jasondorjeshort/dystopo/horizon/SightLine.kt`'s test says which points are hidden.

It needs more ground than the Panorama draws today: the layered skylines show ridges, not faces, so a line on "the hillside" wants the terrain below the skyline drawn (the window seat idea's second pass, `related:`).

## Open

- Without the ground drawn: is the line over the layered skylines already useful, or only confusing?
- The cost of testing every route point for sight against a kept horizon (cheap) or by rays (not).
- With the camera behind the drawing (ADR 0064, item 3) this is the picture people expect from such an app; before the camera it is the drawn version of it.

Question: build the route as a Panorama layer now, over the layered skylines (each route point projected through `LensView`, the stretches `SightLine` finds hidden dashed, as the sun's path is), or wait until the ground below the skyline is drawn (the window seat idea's second pass) or the camera is behind the drawing (ADR 0064, item 3)?
Recommendation: wait for the ground drawn; over ridgelines alone a line that climbs a face has nothing to lie on and reads as floating, and the hidden test should come from the one line-of-sight function (`2026-11-22-one-line-of-sight-function-in-shared.md`) rather than a per-point ray of its own.
Options:
a) Wait for the ground below the skyline and the one line-of-sight function; then a pass (recommended).
b) A first pass now over the skylines, with a frame each of a route in view and behind a ridge, for the owner to judge.
c) Wait for the camera behind the drawing.
d) Not worth building.

Answer: A, wait for the drawn ground and the line-of-sight function, then a pass (the owner, 2026-11-22, multiple choice).
