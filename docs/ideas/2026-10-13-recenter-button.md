---
date: 2026-10-13
kind: feature
status: new
pipeline: T61 partial
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
