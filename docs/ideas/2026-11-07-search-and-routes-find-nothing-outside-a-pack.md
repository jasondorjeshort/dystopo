---
date: 2026-11-07
kind: bug
related: [2026-11-07-downloads-as-a-synchronisation-in-cells.md, 2026-11-07-tracks-and-routes-manager-and-the-missing-track.md]
---

# Search and routing find nothing where the map draws plenty: both index packs only, never the downloaded tiles

The owner, 2026-11-07 (v0.1.22-alpha), at a second airport: "Gate search worked in [the first airport] but not in
[the second], though both gates are on the map. This may be part of a pattern of map elements not being found
somehow. Other times routes aren't found even when they recently were and are very obvious." The screenshot
shows the gates E1 to E10 drawn and labelled, the chip reading "Online · no data here", and "No places match" for
"e5".

What the code does: the search index is built per vector archive, a pack's `.pmtiles`
(`androidApp/.../app/search/PlaceSearch.kt`, `ensure`: "decodes every tile at the top zoom and two lower ones" of
the archive; `shared`'s `search/`), and the routing graph likewise per pack (`routing/RoutePlanner.kt`, T149, T189).
A map the phone draws from the online tile cache, whether fetched by looking or saved by "Download this region"
as a tile area (T153's "Tiles only" path, every box over 3 degrees or 20,000 tiles), has no index and no graph: the
tiles are there, so the gates draw, and nothing reads them for search or routes. That is the pattern the owner
sees: where a pack covers the view (a server pack, an imported pair, or a small region that took the pack path),
search and routes work; where only tiles cover it, both fail, and nothing on screen says why. "No places match"
and a route that is not found read as the data's fault.

## Open

- Say why, first: a search over a view with no pack says "Search needs a map pack here; this area has map tiles
  only" (and the route card its equivalent, as T153's card already does when planning a download), with a link to
  Offline maps; the chip's card (T236) names whether the view has a pack.
- Then build from tiles: an index and a graph read from the cached tiles of a downloaded area (the cells idea's
  unit would bound the work), or the server building both for a tile area as it does for a pack (T189's shipped
  graph, ADR 0049); which, after a measurement of the index's and graph's size for a city-sized tile area on the
  phone (T149's limits).
- A device test: a view over an online-only airport, the gate drawn, `GET /search?q=` naming the reason.

2026-11-10 (triage): ~~say why, first~~ and the device test: built by T255 (`MapCoverage`, "Search needs an offline
map here; this area has map tiles only" with its Offline maps link, the route card's sentence, reason codes in
`GET /search` and the route reply). Left: searching and routing on tiles alone.

Plan: one pass, search first. Measure, on the emulator, a city-sized tile area saved by "Download this region" as
tiles: the tile count at the index's zooms, the time and heap to build `search/PlaceSearch.kt`'s index by decoding
those tiles from the online cache (the same decode `ensure` runs over a pack's archive), and the index's size; then
build it: `PlaceSearch` indexes a recorded tile area (`Areas.isRecorded`) from the cache, off the main thread,
cached on disk beside the area and rebuilt when the area's version changes, so `MapCoverage` reports such a view as
searchable. The routing graph from tiles is not this pass: the report gives the graph's projected size from the
same tile count against T149's limits and ADR 0049's server-built graph, for the coordinator to choose the next
pass. Done when: `check` green with an index-from-tiles test over fixture tiles; on the emulator a search inside a
tile-only Rainier-area region finds Paradise; the measurements in the report.
