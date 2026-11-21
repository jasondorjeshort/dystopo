---
date: 2026-11-21
kind: feature
related: [2026-11-20-a-panorama-view-a-scrolling-ribbon-of-the-horizon.md, 2026-11-14-a-shared-map-picture-carries-its-place.md]
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
