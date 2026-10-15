---
date: 2026-10-13
kind: feature
status: done
pipeline: T93 built the plain button (wave 13: `controls/Recenter.kt`, `RecenterButton.kt`); T100 (wave 14) built the three orientation modes and the tap rule (`controls/MapOrientation.kt`, `Orientation.kt`, `Heading.kt`), one state shared with navigation's camera modes
related: [2026-10-13-map-scale-bar.md, 2026-10-13-turn-directions.md]
---

# Recenter button with a three-state heading mode

A button, lower right, that centres the map on the current GPS fix and cycles the map orientation:
north-up (the default), free (entered by rotating the map with a gesture, never by tapping), and
heading-up (only when a compass is available). Tapping while off-centre recentres first.

Today: T61 has follow, heading-up and free camera modes and a "Recenter" action, but only inside
navigation mode (`nav/NavOverlay.kt`). Outside it there is no way back to the fix after a pan.

## Open

- Icon design: the three states need clearly different icons. The model app has a big "N" with an
  up arrow for north-up, but its free-rotate and facing icons are hard to tell apart; do better
  there. Interface changes are fine in alpha (see the alpha idea).
- Interaction with the track bar and the route card, which already sit on the right edge
  (`TopChips` edges), and with the scale bar.
- Heading source (GPS course when moving, compass when slow) and running the compass sensor only
  while heading-up is active, for battery.
- A compass needle when the map is not north-up, tap to snap north (not decided; not needed if
  the button cycle already covers it).

## Decided in brainstorm (2026-10-13, owner)

The tap rule follows the backcountry navigation app the owner thinks has the best interface:

- Orientation modes: north-up (the default), free rotate, facing (heading-up, only with a
  compass). Free rotate is entered by actually rotating the map with a gesture, never by tapping.
- Panning away does not change the orientation mode. The first tap after a pan only recentres on
  the fix and keeps whatever mode was on (north, free rotate or facing). A second tap, with the map
  already centred, advances the orientation mode.
- Positive feedback: the icon shows the current mode, so the mode is known before tapping.

## Brainstorm

- 2026-10-13: look at how the common map apps lay this out (a compass needle that doubles as the
  orientation toggle, a locate button that cycles off, follow and follow-with-heading). Public
  documentation of the mainstream map apps and the backcountry navigation apps is enough; no names
  go into our own text.

## Built (T100, 2026-10-14)

- Icons: north-up a compass needle under an N; free rotate two turning arrows with a red pointer to
  north on the turned map; facing a beam from a dot. Blue while the camera follows the fix, dark
  after a pan (the next tap recentres), grey without a fix. Paths, not text, so font scale leaves
  them alone; the button keeps the name "Recenter" with the mode as its state description.
- The open points: the button keeps its corner above the bottom panels (T93); facing uses the
  course over ground above 1 m/s, else the compass, which runs only while facing is on and the app
  is in the foreground. No separate compass needle: the free-rotate icon shows north and a tap on
  a centred map snaps north. MapLibre's own compass (top right, shown on a rotated map) is still
  there, mostly under the status bar; whether to keep it is a follow-up.
