# 0045. An area's tiles are held as rows: no heap limit on an area, the pinned budget the one steer

Date: 2026-10-27
Status: accepted (T152; the owner's words of 2026-10-26 settle the one open question)

Record: task T152 (`docs/TASKS.md`, wave 21), the idea `docs/ideas/2026-10-26-downloads-without-a-size-cap.md`
(item 1 and Wanted), the wave 21 critic. Amends ADR 0041 §3 (the memory guard: "before any tile set is
filled, the bounding box's tile count over the zoom range is checked against a limit derived from the heap, and
a shape over it is refused"); the rest of §3 and every other section of ADR 0041 stand.

## Context

ADR 0041 §3 refused a polygon past `AreaLimit.maxTiles` (a quarter of the heap at 256 bytes a tile, about
590,000 tiles on a 576 MB heap) because every tile of an area was held in memory: a hash set per zoom for the
manifest and again for the pin cover, and the download expanded every tile into a work list sorted by zoom then
route. So a country at full detail had no size line ("Full detail" with no figure) and no Download, though the
pinned budget goes to 1 TB. The owner (2026-10-26): "I want to be able to download 100 GB if I want to." T140's
"256 B per tile" was an estimate, never measured on a phone; it is moot now.

## Decision

1. **Rows.** `TileSet` holds, per tile row y, the sorted spans of x it covers (spans never overlap or touch), so
   the manifest, the pin covers (`AreaPins`, held for every pinned area for the app's life) and the country
   sizes cost an area's rows, not its tiles: France at full detail is 1.44 million tiles in 7,164 rows,
   measured on the JVM in 70 ms and planned on the emulator in under half a second.
2. **The walk.** The download walks the manifest's rows (`AreaManifest.walk`, `TileRow`: zoom ascending, then
   route by name, then y, then x0; fixed for a manifest, so (z, route, y, x0) names a row and everything before
   it) with at most 24 tiles queued; failed tiles are kept as row sets per route and zoom and walked again at
   the end; contour tiles go row by row too. `GET /areas/download` `rows` reports the walk (planned, started,
   whole, the whole prefix, the current row), the unit a resume cursor can point at.
3. **No heap refusal.** `AreaDownload.plan` always carries the estimate (tiles times `AreaManifest.estimate`,
   "About ...") and Download proceeds whatever the area's size; the pinned budget (`pinnedCacheMb`) is the one
   limit, visible before anything starts (ADR 0023). The server's own `POST /area` drops its heap check too
   (its `DYSTOPO_AREA_MAX_TILES` stays, the operator's cap), and the phone answers a server's 413 with its own
   manifest, fetching the tiles from the server's tile routes as before.
4. **The per-tile structure that remains** is the online tile cache's index (`TileCache`: one entry per cached
   file, every tile on the phone, an area's or not, from the start-up scan on). It is what a stored tile costs,
   bounded by what the budget lets the phone store, so it stays. Its entry no longer holds a `File` (about half
   its heap); `AreaLimit.BYTES_PER_TILE` is now its cost (160 B, estimated from ART's layout) and
   `AreaLimit.maxTiles` the tiles a quarter of the heap holds of it (about 940,000 on 576 MB). A plan past that
   says so (`pastHeapLimit`, `heapTiles`) and proceeds.
5. **The session cap.** In direct mode an area download is exempt from the sources' session cap
   (`directSessionMb`, 300 MB): while one runs, source reads are neither stopped by the cap nor counted against
   it (`GET /online` `direct.sessionCapExempt`); the reads cannot be told apart by thread (a render's block reads
   run on the shared pool), so a map pan during a download is not capped either.

Out of scope: a resume cursor over the walk (T151), the top button through the picker (T153), the middle tier
(T157). Deferred: an index on disk or a compact index for the tile cache, revived when a phone's area downloads
pass `AreaLimit.maxTiles` in use or a heap reading shows the index near a quarter of the heap.

## Consequences

- A country, a state or 100 GB of tiles is planned and downloaded as any small area is; the budget's sentence is
  the only refusal, and the time line says how long.
- The remaining cost is per stored tile, not per planned one: about 160 B of heap per cached tile, so a 100 GB
  download of mountain DEM tiles (about 650,000 tiles at the 160 kB estimate) holds about 100 MB of index on top
  of the app; `pastHeapLimit` says when a plan passes the comfortable share.
- Per-tile CPU remains where code walks every tile of a manifest with cache lookups (`Areas.coverage`, polled by
  the Areas sheet, and `Areas.remove`): no structure grows, but a country's coverage costs a walk of its tiles.
- A direct-mode pan during a download escapes the session cap; small beside the download.
- Supersede when the tile cache index moves to disk (then no heap figure remains) or when areas are synced
  between phones.
- Prediction, check by 2027-04-27: no area download on a phone has ended in an `OutOfMemoryError` traced to the
  manifest, the pins or the walk.
