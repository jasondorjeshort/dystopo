---
date: 2026-11-07
kind: bug
related: [2026-10-13-auto-download-when-zoomed-in.md, 2026-11-07-downloads-as-a-synchronisation-in-cells.md, 2026-11-07-rendered-tiles-cached-on-disk.md]
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

On the second flight the same day (v0.1.22-alpha): "even tiles displayed minutes ago can disappear. I scrolled all
across the US and now only a fraction is visible. This isn't unique to dystopo; it's the exact same thing I see in
every offline navigation app. Later it all disappeared... and then all came back. It kept appearing and
disappearing throughout the flight. Once it disappeared and once appeared while I was watching (not touching)." The
screenshot shows a wide view with the lower-left quarter drawn and the rest bare. And over an area downloaded
beforehand: "even there it blanked out when I was zoomed too far out." A downloaded area has a lowest zoom (T157's
detail levels), below which nothing is on the phone, so a wide view over it is bare; and a view that comes and goes
while nothing is touched points at the loopback server's answers changing (a 404 after a timeout, a reload after a
burst of slow tiles, T188's note) rather than at the cache.

## Open

- Offline, answer a missing vector or raster tile with the nearest cached ancestor, overzoomed (a vector tile can
  be served as is with the child's bounds, MapLibre clips; a raster or DEM tile is cropped and scaled), marked so
  the chip can say "from a wider zoom"; the same fallback T155's follow-up asks for DEM tiles that failed online.
- A view that disappears while untouched: reproduce offline with the request log (`GET /requests`), reading what
  the server answered for a tile that was drawn and then was not; MapLibre's retry and its own tile cache size are
  the suspects.
- A wide view is never bare: a small low-zoom basemap of the world (z0 to z6 or so, a few tens of MB) bundled or
  fetched once with the first download, so zooming out over any downloaded area shows the continent; the
  downloaded area's lowest zoom shown on its row.
- The sweep's device tour gets an offline step: fetch a view at z9, go offline, zoom to z14, assert the roads
  stay.
- The discard balance the owner names: what the budget evicts first when a view has both a wide and a close
  zoom cached (the cells idea's "sufficiently out of date" and pins).

2026-11-10 (triage): T254 built the ancestor cut (vector and DEM, `MvtOverzoom`, `X-Dystopo-Wider-Zoom`, never
cached) and the tour's offline step, and found the disappearing view came from tiles never cached, not from cached
tiles dropped; T255 put "from a wider zoom" on the chip. Left: the world basemap, the lowest zoom on the area's row,
and the discard balance (ADR 0060's store and its eviction, not this file).

Plan: one pass, the bundled world basemap (cut from T254 by the wave 31 critic). First step measures: a tilebuild
recipe for a z0 to z5 vector world (`tilebuild/`, a layer subset in `tilebuild/PROFILE.md`; the extract sizes and a
Natural Earth build compared, as `out/wave31-research/offline-fallback.md` [no-check: gitignored] part 4 lists)
and a coarse DEM, their sizes reported; build only near that survey's target (under 10 MB for the vector set),
else the report proposes the zoom cap. Then a `sources.json` entry, the files added by `bundleMapAssets` in
`androidApp/build.gradle.kts`, the loopback server answering any tile at those zooms from the bundle when the cache
and packs lack it (`app/TileService.kt`), and the downloaded area's lowest zoom shown on its row in Offline maps.
Done when: `check` green with the licence check, the APK's size growth stated, the tour's offline step zoomed out
to z2 over Rainier with land drawn (screenshot). Out of scope: the minimum zoom under 3 (the whole-world idea).
