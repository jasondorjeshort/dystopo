---
date: 2026-11-07
kind: bug
related: [2026-11-07-downloads-as-a-synchronisation-in-cells.md]
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

Came up again: 2026-11-24, the owner (screenshots from v0.1.43-alpha), at the phone's own place inside two drawn areas downloaded in direct mode, both rows reading "Map only" and "From direct://sources" in Offline maps (one under 1 GB at z8-15, one of tens of GB over a large part of the country): search says "Search needs an offline map here; this area has map tiles only", routes do not work, and the owner, online and holding the deepest download the app offers, called it "the original" issue. New: direct mode is now the common way a map gets onto the phone, so an area of tiles is the main case, not a side case; the routing graph from tiles is wanted as much as search; and the row's "Map only" does not tell the user that search and routes will not work there (say it on the row and in "Draw an area" before the download).

Came up again: 2026-11-24, the owner (v0.1.43-alpha, Horizon and Panorama screenshots at the same tile-only place). New: folded in from the idea of the blank Panorama and missing peaks at two overlapping downloads (deleted in this commit; git has it): the Panorama's blank was fixed by T340 (terrain draws on v0.1.43), and what is left is the peak read asking packs only (`HorizonData.kt:167`), so the Horizon view, the Panorama and the List name no peaks inside an area kept as tiles. This pass reads peak names from the area's vector tiles with search's index.

Priority: first in wave 49, by the owner's choice of 2026-11-24 (multiple choice); the pass takes the Horizon view's peaks from the same tile read, and the routing graph from tiles is measured and planned in it.

New: 2026-11-26, T377 (claude-opus-5-5): built search inside an area kept as tiles (an index from the area's cached vector tiles, search's own coverage, the rewritten sentences, the debug fixture `POST /areas/fixture/tiles`); left: the peaks from an area's tiles (T378) and the routing graph from tiles, still wanted (the owner, 2026-11-24), its projected size in T377's merge commit.

New: 2026-11-27, T378 (claude-opus-5-5): built the peaks from an area's tiles (the Horizon view, the Panorama and the List read an area kept as tiles through T377's read, no index, in ADR 0066's lookup order; the names line names the area); left: the routing graph from tiles, still wanted.

Plan: one pass, search first. Measure, on the emulator, a city-sized tile area saved by "Download this region" as
tiles: the tile count at the index's zooms, the time and heap to build `search/PlaceSearch.kt`'s index by decoding
those tiles from the online cache (the same decode `ensure` runs over a pack's archive), and the index's size; then
build it: `PlaceSearch` indexes a recorded tile area (`Areas.isRecorded`) from the cache, off the main thread,
cached on disk beside the area and rebuilt when the area's version changes, so `MapCoverage` reports such a view as
searchable. The routing graph from tiles is not this pass: the report gives the graph's projected size from the
same tile count against T149's limits and ADR 0049's server-built graph, for the coordinator to choose the next
pass. Done when: `check` green with an index-from-tiles test over fixture tiles; on the emulator a search inside a
tile-only Rainier-area region finds Paradise; the measurements in the report.
