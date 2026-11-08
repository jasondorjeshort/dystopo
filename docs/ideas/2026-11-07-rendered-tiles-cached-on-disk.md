---
date: 2026-11-07
kind: feature
related: [2026-11-07-offline-zoom-keeps-what-wider-zooms-showed.md]
---

# Rendered map pieces cached on disk, invalidated by a settings change, cleared lazily as storage fills

The owner, on a second flight on 2026-11-07 (v0.1.22-alpha), over an area downloaded beforehand: "I can zoom in and
out around the cutoff and see individual map elements get redrawn. To avoid cpu use we really want to cache these,
even though they're invalidated by any settings change and should be lazily cleared out as storage fills."

What is cached today: vector and DEM tiles (the online tile cache, the packs), contour tiles and shade tiles made
by the loopback server (`androidApp/.../app/ContourCache.kt`, `options/ShadeService.kt`). What is not: the drawn
result. MapLibre renders every vector tile on the GPU each time it enters the view, parsing and laying out its
features and placing its labels, which is the redraw the owner watches at the cutoff; a label layout is the costly
part on a slow phone. The map options and the style change what a tile looks like, so a cache of drawn pieces
would be keyed by the style's version and the options, and emptied by the cache budget like the others.

## Open

- What can be cached at all: MapLibre keeps parsed tiles in memory only; a raster cache of drawn tiles (the server
  rendering vector tiles to PNG as the devtool does for its goldens) would trade GPU work for disk and lose
  rotation and tilt; label placement cannot be cached across views. Research (ADR 0044): how the established
  offline apps keep redraws cheap on a weak phone, and whether MapLibre's own tile cache size and `prefetchZoomDelta`
  settings are the lever before any new cache.
- The owner's lazy clearing and settings invalidation as the rule, whichever layer is cached.
- Measure first: the frame time and CPU at the cutoff on the emulator and on a phone, to say what the redraw costs.
