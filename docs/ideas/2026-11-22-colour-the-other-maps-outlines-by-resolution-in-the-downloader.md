---
date: 2026-11-22
kind: feature
related: [2026-11-22-edit-and-resize-a-downloaded-area-and-download-this-area-opens-the-polygon-editor.md, 2026-11-22-download-this-area-is-offered-where-a-finer-map-already-covers-the-view.md, 2026-11-22-a-much-smaller-detail-level-the-whole-us-in-under-10-gb.md]
---

# Colour the other maps' outlines by resolution in the downloader

From the owner (2026-11-22, v0.1.41-alpha, "related" to the downloader reports): "In the downloader you see the other
packs. These should be colour coded by resolution. Which colours is its own research project."

**Where it stands.** The Offline maps screen and the area picker draw every map's outline over the map, with "every
row's outline (a pack with no record by its bounds)" (`docs/reference/android-app.md` › Offline maps; `areas/AreaPicker.kt`,
`areas/AreasScreen.kt`). The owner's screenshot of the list shows them in one green dashed style: the US pack's slanted
polygon, the Chicago box and the Colorado blob at its western edge all look alike, so a coarse 30 GB map and a fine 8 GB
one cannot be told apart, and overlap (the owner's "two packs at one place") reads as one shape.

**The owner's example (2026-11-22).** In "Download this area" a full-detail box (a solid teal rectangle, the draft) lies
inside the Medium US pack, whose edge shows only as a thin dashed teal line crossing the lower right of the same frame:
"high resolution download within another, but no clear way to see the resolutions". The draft and the held pack share
one hue, and nothing says that the pack under the box is Medium and map only while the draft is Full with routing.

**What is wanted.** Each outline coloured by its **resolution** (the zoom range and elevation zoom the card shows:
"Zooms z8-14, elevation to z13", the three details Less, Medium and Full of ADR 0047, and the lower level the owner's
idea of the smaller detail asks for), so what the phone holds at a place, and at what detail, is visible while choosing
a new area. With the finest-first rule (`2026-11-22-a-downloaded-map-must-never-make-the-view-worse-than-online.md`),
the colour is also what the map will actually look like there.

**The colours are their own research project (the owner).** A brief for a read-only agent, under ADR 0039's benchmark
and ADR 0044's research rule (`brief` skill, `benchmark.md`): how the established offline and coverage maps colour
regions by quality or detail; a **sequential palette that stays ordered** for three or four levels, and tells apart on
the map's paper and in the night palette; colour-blind safe, with a second cue (line weight or dash pattern, a label with
the level's name) since colour alone fails; overlap, where two outlines of different detail cross (draw the finest on
top, or stack the labels); the legend (a small key on the screen, or the level written on the row, which the list's
rows already can). The project's own tokens (`StyleParityTest`, the night colours) and the `dataviz` skill's validated
palettes are the starting point. Output: a finding in `docs/findings/` with the chosen palette and the cues, then the
build (below).

**What more we would want to see.** The levels the colours must cover (three today, a fourth if the smaller detail is
built, and a pack with no record, whose detail comes from its metadata); whether the same colours apply to the chip's
"on this phone" card, the Offline maps rows (a swatch beside the state word) and the sweep's frames; the outline
of an area being drawn stays distinct from a saved map's.

Plan: first the research (a read-only agent, a finding), then one small task: the outline's colour and second cue from the
map's detail through one function in `app/areas/` with a test of the mapping (every detail has a colour, the night
palette's pairs differ), a swatch and the level's name on the row, a legend, the outline colours in the a11y frames, a
debug field on `GET /areas/rows` giving each row's detail and colour; strings in the report (ADR 0031).
