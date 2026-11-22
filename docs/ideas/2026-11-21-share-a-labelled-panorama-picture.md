---
date: 2026-11-21
kind: feature
related: [2026-11-14-a-shared-map-picture-carries-its-place.md]
---

# Share the Panorama as a picture, with its names, its place and its bearing

From the planning of the Panorama view (the coordinator, 2026-11-21; the owner: "Record all of these"). The view of
`2026-11-20-a-panorama-view-a-scrolling-ribbon-of-the-horizon.md` draws the skyline with its peak names from the
elevation model; a picture of it, with a caption line saying where it stands and which way it looks ("From Paradise,
looking NNE"), is the thing people pass around from this kind of app. The app shares files today as GPX only
(`androidApp/.../app/SaveFile.kt`); a share action on the Panorama view would be its first shared picture, and the
trigger the place-tag idea waits for (`2026-11-14-a-shared-map-picture-carries-its-place.md`: the viewed place, off by
default, asked at the share).

Anchor: the Panorama screen of that idea's pass two [no-check: not built yet]; the devtool `panorama` command of its
pass one renders the same picture on the JVM, so a wider strip than the screen (the whole circle as one long image)
is possible for the shared form even though the view itself is a lens.

## Open

- The screen's frame as it is, or a rendering of its own (wider, with a caption and the credit line).
- A view from another place says so in the caption; a view at the fix reveals the position, so the caption's place is
  part of the same "Include the place" question as the tag.
- The credits a picture must carry (the elevation sources and the names' source, `sources.json`).

Plan: one pass, after the Panorama's pass three has merged (it works in `app/panorama/`); the anchor exists since wave
45 (`app/panorama/PanoramaScreen.kt`). First a read-only survey (ADR 0039, `benchmark.md`): how peak and map apps share
a labelled picture and how they ask about its place. Then a Share action in the Panorama's pane: the lens plot as it
is framed, drawn again off screen at a fixed width with its names and bearing tape, a caption ("Looking NNE"; "From
Paradise, looking NNE" only when the place is included) and the credit line of the sources that drew it
(`sources.json`, as the attribution line reads them), written as a PNG and sent through the existing FileProvider
(`ShareGpx.authority`, `SaveFile.kt`) as `image/png`. The place follows the provisional rule of
`2026-11-14-a-shared-map-picture-carries-its-place.md`, whose trigger this is: "Include the place" asked at the share,
off by default, and no place in the file's metadata in this pass (that file gets a dated line and keeps its question
of the tag). `POST /panorama` gains `share=1`, which writes the same PNG without the chooser. Verify: the PNG viewed
at the fix and at a chosen point, with and without the place, upright and sideways, at font scale 2.0; the caption
and the dialogue's strings in the report (ADR 0031). Out of scope: the whole circle as one long strip, the Horizon
view's round plot, the map.
