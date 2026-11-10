---
date: 2026-11-07
kind: feature
related: [2026-11-07-offline-zoom-keeps-what-wider-zooms-showed.md]
---

# More of the world on one screen: a flight halfway round it tracked in one view

The owner, after a third flight on 2026-11-07: "I'm not quite willing to say I want azimuthal maps as that would
break all tile caching. But I'd like to be able to see more of the world. If I fly halfway round the world, why
can't I see my whole route tracked on one screen?"

What limits it today: the map's minimum zoom is 3 (`androidApp/.../app/MapController.kt`, `setMinZoomPreference`),
so about a quarter of the world fits a phone screen; offline, the view is bare below a downloaded area's lowest
zoom (the sibling idea's bundled low-zoom basemap); and a track or a line route that crosses the antimeridian is
drawn across the whole map the wrong way on a Mercator map unless its longitudes are unwrapped. The projection
stays Mercator: tile caching, packs and every rendering step rest on it, and MapLibre can show zoom 0 to 2 on it,
the whole world, with the known stretching near the poles.

## Open

- The minimum zoom lowered to 0 or 1 with a world basemap on the phone (the sibling idea), and what breaks at
  those zooms (the contour and shade layers, the scale bar's figure, label density).
- Tracks drawn across the antimeridian correctly (longitude unwrapping in the overlay), with a test on a track that
  crosses it. Line routes are done: T256 splits the geodesic's samples at the seam (`Geodesic.withSeamPoints`,
  `Geodesic.splitPieces`, used by the route overlay and the browser's trip editor); a track can use the same split.
- "Fit the track" on the track card and the route card at any length, so one tap shows the whole flight.
- Not azimuthal: the owner's own ruling, for the caching's sake; revisit only as a one-off overlay, never as the
  map's projection.

Plan: one pass, after the sibling idea's world basemap lands. `tracking/TrackOverlay.kt` and the track's dashed gap
layer split their lines with `shared`'s `geo/Geodesic.splitPieces`, as `routing/RouteOverlay.kt` does, with a test
on a track that crosses 180°; a "Fit" action on the track card and the route card that fits the camera to the whole
line, unwrapping longitudes so a line across 180° fits its short way round (T256's follow-up, the camera fit);
`app/MapController.kt`'s minimum zoom lowered from 3 to 1, the contour and shade layers hidden below their useful
zoom, the scale bar's figure and label density read at z1 and z2 in screenshots. Done when: `check` green with the
split test; on the emulator a replayed track from Rainier across the Pacific draws with no wrap-around line, Fit
shows all of it in one view, and z1 shows the world from the bundled basemap offline. Out of scope: any other
projection.
