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
