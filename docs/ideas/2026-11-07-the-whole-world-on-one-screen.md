---
date: 2026-11-07
kind: feature
related: [2026-11-07-offline-zoom-keeps-what-wider-zooms-showed.md, 2026-11-07-line-routing-as-a-geodesic-with-no-limits.md]
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
- Tracks and line routes drawn across the antimeridian correctly (longitude unwrapping in the overlay, or the
  geodesic's samples split at the seam), with a test on a track that crosses it.
- "Fit the track" on the track card and the route card at any length, so one tap shows the whole flight.
- Not azimuthal: the owner's own ruling, for the caching's sake; revisit only as a one-off overlay, never as the
  map's projection.
