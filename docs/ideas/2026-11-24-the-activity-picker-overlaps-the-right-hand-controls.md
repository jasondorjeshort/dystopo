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
