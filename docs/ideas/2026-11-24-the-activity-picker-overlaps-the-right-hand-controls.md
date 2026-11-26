---
date: 2026-11-24
kind: bug
---

# The activity picker overlaps the right-hand controls, and the map shows through it

Source: the owner, a screenshot from v0.1.43-alpha, 2026-11-24 (a place described generically; online, inside a drawn area kept as tiles).

Seen: with the activity picker open (Train, Boat, Flying and Cancel; `androidApp/.../app/tracking/ActivityPicker.kt`),
the picker's panel sits over the lower map with the map's labels showing through it, and the right-hand column (Hide,
Waypoint, Tracks, Record, a help "?" and Routes) runs down over it and over the picker's own row; the "?" sits on top
of the Tracks button; the compass and its lock sit low beside the readout, which shows only elevation and speed, both
dashed. The owner reads it as a layout issue arising from the view having map tiles only (the search line and the
coverage card stack down the left side at the same time).

Anchors: `ActivityPicker.kt`, the map's control column and its insets, the readout strip, `OnlineOverlay.kt`'s card.

## Open

- Reproduce on the emulator inside a tile-only area with the picker open (the a11y tool's bounds check would show the
  overlaps), at font 1.0 and 2.0.
- Whether the picker should be a sheet with an opaque background that the column yields to, as Offline maps' sheet is.

Plan: one pass, after `2026-11-26-the-map-layout-flips-at-the-largest-font-with-the-bar-unfolded.md` (both own
`app/MapLayout.kt` and `app/tracking/TrackControls.kt`), or folded into it. Checked at triage: the panel is drawn by
`ActivityPickerPanel` inside the track bar (`TrackControls.kt`) on `PickerPaper`, `0xEEFFFCF5`, which is 93 percent
opaque, so the map's labels show through by construction. (1) Reproduce on the emulator: a drawn area kept as tiles at
Mount Rainier (`POST /areas/region`), online, the search line and the coverage card up, `POST /tracks/picker?open=new`,
at font 1.0 and 2.0; `GET /semantics` and `GET /layout` name the overlaps. (2) Make the panel opaque and give it to the
layout rule as a box the right-hand column and the compass yield to (`shared/.../ui/OverlayLayout.kt` and its test,
`MapLayout.kt`), as Offline maps' sheet is placed; nothing draws over the picker's own row. (3) Add the state as an a11y
screen (`tools/android-a11y.py`, `tools/a11y-baseline.json`). Verify: the two frames viewed before and after, the a11y
part with no overlap line for the new screen, `OverlayLayoutTest` cases for the box at both scales. Out of scope: the
readout's dashed values in that frame (no fix: expected), the picker's content and wording.
