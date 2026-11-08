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

- One position on the map: the puck and the track's head draw from one source while a recording runs (the sibling
  idea), and the puck says "no fix" rather than vanishing.
- Every mark on the map answers a tap with its name (a small label or the long-press menu's first line), so an
  unexplained dot can explain itself; the help registry (T218's `help/Help.kt`) lists the marks.
