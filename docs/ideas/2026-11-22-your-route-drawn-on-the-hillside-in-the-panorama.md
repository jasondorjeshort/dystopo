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
