---
date: 2026-11-22
kind: feature
related: [2026-11-21-which-way-is-it-the-panorama-facing-a-selected-place.md]
---

# Ghost marks: a hidden place shown in the Panorama at its true bearing

Status: **untriaged** (an idea of the coordinator's own from wave 46's work, offered when the owner asked "Any ideas out of this wave? Moonshots welcome." and recorded on the owner's word, 2026-11-22: "Record them all; we'll triage and think through them later."). The anchors are files wave 46 wrote or read; nothing else here was checked against the code or measured.

T334 answers "Can I see it from here?" with one line on the long-press card and left "the Panorama's mark for a hidden place" out of scope by name. This is that mark: a selected place that the terrain hides gets a faint, hollow mark in the Panorama where it would stand if the ground were glass, with the sight line's own words beside it ("Hidden by a ridge 2.1 mi away, 1436 ft below the line of sight"), and the ridge that hides it is the one drawn in front of the mark.

Anchors: `shared/src/commonMain/kotlin/io/github/jasondorjeshort/dystopo/horizon/SightLine.kt` (the answer and its figures), `shared/src/commonMain/kotlin/io/github/jasondorjeshort/dystopo/horizon/PanoramaScene.kt` and `androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app/panorama/PanoramaLayers.kt` (where a mark is drawn), `androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app/panorama/PanoramaTarget.kt` (a selected place as the target).

## Open

- Depends on the open Question of `docs/ideas/2026-11-21-which-way-is-it-the-panorama-facing-a-selected-place.md` (a place selected on the map as the Panorama's target at your own location).
- Whether peaks "just out of sight" in the Peaks list get the same ghost mark.
- A hidden place below the level line needs the ground below the skyline to make sense of it.
