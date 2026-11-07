---
date: 2026-11-07
kind: bug
related: [2026-10-13-auto-download-when-zoomed-in.md, 2026-11-07-downloads-as-a-synchronisation-in-cells.md]
---

# Zooming in offline loses the roads that wider zooms showed; serve the nearest cached ancestor instead of nothing

The owner, on a flight on 2026-11-07, in airplane mode with nothing downloaded beforehand, only some scrolling along
the route: "zooming in lost data (like highways) that were shown around the city at wider zooms. This is all in
airplane mode of course; functioning sanely without internet is where basically all other map apps fail and what we
want to do well at, but it wasn't great. This still must have downloaded something that we then discarded. It's
hard to balance discarding versus holding overlapping data but the result wasn't great."

What the code does: a tile the online cache lacks is answered 404 by the loopback server
(`androidApp/.../app/TileService.kt`, `notFound`; `online/OnlineService.kt`, the fetcher's offline path), and
MapLibre draws nothing for a tile it was told does not exist, where it would have kept the parent's data if the
request had simply not answered yet. Nothing was discarded: the wider zoom's tiles were still in the cache, and the
closer zoom's tiles had never been fetched. The cache budget (`cachebudget/CacheBudget.kt`) can also evict unpinned
tiles under pressure, but that is the second question.

## Open

- Offline, answer a missing vector or raster tile with the nearest cached ancestor, overzoomed (a vector tile can
  be served as is with the child's bounds, MapLibre clips; a raster or DEM tile is cropped and scaled), marked so
  the chip can say "from a wider zoom"; the same fallback T155's follow-up asks for DEM tiles that failed online.
- The sweep's device tour gets an offline step: fetch a view at z9, go offline, zoom to z14, assert the roads
  stay.
- The discard balance the owner names: what the budget evicts first when a view has both a wide and a close
  zoom cached (the cells idea's "sufficiently out of date" and pins).
