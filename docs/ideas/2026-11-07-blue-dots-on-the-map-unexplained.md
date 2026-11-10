---
date: 2026-11-07
kind: bug
---

# A blue dot appears on the map at times, far from the track's head, and nothing says what it is

The owner, on a second flight on 2026-11-07 (v0.1.22-alpha): "why do blue dots appear at times on the map? Very
unintuitive."

A later screenshot, out of airplane mode after landing, shows it: a plain blue disc with a white ring, a mile from
the recorded track's magenta head. That is the location puck (MapLibre's location component, fed by
`androidApp/.../app/MapController.kt`'s own listener), while the magenta head is the recorder's last accepted fix:
the two sources disagree, which is the first flight's "self marker stops" bug seen from the other side. When the
puck has no fix it is hidden, so it "appears at times". Other candidates, if a dot is seen elsewhere: the water markers along a planned route (T187,
`androidApp/.../app/routing/`, blue), the "I am at" and trail-disagreement marks (T213, T194), the grey give-up dot
of the terrain estimate (T203), the long-press marker, a peer's position (T186), a waypoint's selection ring, or
MapLibre's own location accuracy circle. Which of these the owner saw in a plane with no route planned is not
known; the screenshot shows the recorded track's start (green) and current (magenta) marks only.

## Open

- ~~One position on the map~~: built by T251 (the puck follows the recorder's kept fixes while a recording runs, greys
  after the activity's gap rule, and the long-press card's first line names it; `docs/reference/android-app.md`).
- Every mark on the map answers a tap with its name (a small label or the long-press menu's first line), so an
  unexplained dot can explain itself; the help registry (T218's `help/Help.kt`) lists the marks.

Plan: one pass, T251's mechanism widened. `tracking/LongPressMenu.kt`'s `markAt` asks, after `MapController.puckMark`,
`queryRenderedFeatures` in the fingertip box (as `tracking/TrackOverlay.kt` already does for its points) over every
overlay layer that draws a mark: the track's start, head and end marks, the dashed gap bridge, waypoints and their
selection ring, the route's ends and water markers, the "I am at" and trail-disagreement marks, the terrain
estimate's grey dot, peers' positions, the long-press marker; the first hit gives the card's first line (a name and
one fact: "Track start, 07:12", "Water, from the route's sources"). Each mark gets an entry in `MAP_MARKS_HELP`; a
test asserts every overlay layer id that draws a point has a name and a help entry, so a new mark cannot ship
unnamed. `LongPressMenu.json()`'s `mark` (in the debug routes that return it) carries the name. Done when: `check` green with that test; on the
emulator a press on each mark (a replayed track with a gap, a planned Rainier route with water, a waypoint) shows its
line in a screenshot. Out of scope: MapLibre's own accuracy circle (the puck's line covers it).
