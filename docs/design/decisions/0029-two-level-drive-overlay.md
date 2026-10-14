# 0029. Long drives: a second overlay level, cells on disk, bounded searches

Date: 2026-10-14
Status: accepted

Record: the critic review of ADR 0028 (`../proposals/0028-critic-review.md`, 2026-10-13); PLANNING ›
Routing (the T89 bullets and the corrected "Long-drive numbers"); the T89 log entry (2026-10-14, PR
#92). Supersedes ADR 0028 in part: its Decision (one overlay level, cells rebuilt after a restart, the
phone going remote only when an end lies outside every pack) and its Consequences. What still stands
from 0028: the choice of a partition overlay over our own z11 graph tiles on the server and the phone
alike, the rejected alternatives (GraphHopper, Valhalla, contraction hierarchies per pack) and their
reasons, and the T77 measurements.

## Context

ADR 0028 (2026-10-12) recorded one overlay level and named its own supersede trigger: add a z8 level
if long drives measure too slow with one. Its critic found two serious gaps, both read in the code:
the full-search fallback had no budget, so an end up a minor road or a truly disconnected pair made
the server search every pack to fail (objection 1); and a phone holding two packs that do not join
never asked the server (objection 2). The critic also corrected the continental estimate: one level
over the contiguous US is of the order of 36,000 cells and 1.5 to 2 million overlay nodes, about ten
times what PLANNING had said (objection 3). T89 built the answer and measured it on a statewide
Washington pack: Bellingham to Walla Walla (570 km) through 15 z8 and 82 z11 cells, 28 k nodes settled
against 498 k in full, 0.24 to 0.30 s warm, 0.70 to 0.77 s cold after a restart against 5.2 s with one
level and no cell file; the same route and distance as the full search. A verifier repeated the cell
file and restart behaviour on Rainier, not the statewide timings.

## Decision

- **Two levels.** Level 1 is ADR 0028's cell per z11 graph tile. Level 2 is one cell per z8 tile,
  built from the 64 level-1 cells inside it, keeping the level-1 border nodes on the z8 border and the
  cheapest drive between each pair through the level-1 shortcuts (`DriveOverlay`). A long drive
  searches its end zones in full, a z8 tile with no end area in it whose graph tiles all come from
  one pack through its level-2 cell when one is cached or on disk, and every other tile through its
  level-1 cell; a level-2 shortcut expands into level-1 shortcuts and those into the pack graph's
  edges.
- **Cells on disk.** The server's warm-up builds level 1, then level 2, and writes one cell file per
  vector pack, `<vector>-overlay.idx` (`OverlayCellFile`, "DYSO" v1), next to the graph file; a
  restarted server registers the file and reads cells on demand, building none. A search never builds
  level-2 cells itself: without the file it uses level 1. The phone has no warm-up and stays on level
  1 (a cell file shipped with a pack is deferred, below).
- **Bounded searches.** Every search, through the overlay or in full, stops after
  `TileRouter.maxSettled` settled junctions (1.5 million on the server, 600,000 on the phone) and
  fails with `search_limit` and a plain message.
- **Components before searching.** Graph files are DYSG v5: per-node component labels for foot and
  drive, each with an "open" bit when the component reaches a tile the pack lacks. When the network
  is one file, or either end's component is closed, ends in different components are refused at once
  with no search and no full fallback. Bridges and tunnels carry their level, and a cut at a tile
  border keeps it, so twins join only on the same level. v4 files are refused and rebuilt.
- **Damaged tiles.** A graph tile that fails to decode is remembered and skipped
  (`GraphTileCache.corrupt()`, `corruptGraphTiles` in `/route/status`), not retried per search.
- **The phone asks the server more often.** In drive mode `FallbackRouter` also goes remote when
  both ends are in local packs but the local search fails as not connected and the ends are at least
  `MIN_DISTANCE_M` (40 km) apart, or when the local search hits its budget.
- **One time source.** The router's per-road time is the drive time everywhere: a planned drive leg
  keeps it (`RouteLeg.timeS`, the synced route `path` at version 2 only when a leg carries a time),
  and the flat 45 km/h stays only for straight and imported legs.

Deferred, each with its trigger:
- The adaptive end zone (grow the full-detail area from each end until a tertiary border node is
  settled; objection 1, second half): when a real trailhead drive is seen to fall back to the full
  search or to hit the budget.
- Level-2 cells on the phone (the server's cell file travelling with a pack): when a phone-only long
  drive between local packs measures too slow at level 1.
- A component summary across packs (today a part one pack calls closed ignores another overlapping
  pack's roads, so a false refusal is possible): when such a refusal is seen.
- A continental measurement (a multi-state pack set or a synthetic continental grid) before anyone
  promises coast to coast; a tighter A* heuristic (the graph's real top speed) is a cheap gain then.
- Navigation's ETA from the router's time; turn restrictions stay out of scope.

## Consequences

- A restarted server answers long drives at once; the cost is one cell file per vector pack on disk
  (1.5 KB for Rainier, 3.3 MB for the statewide pack) and a longer warm-up after a pack is built.
- A disconnected or pathological request fails fast and says why, instead of exhausting the server;
  the price is that a legitimate drive needing more than the budget fails where it used to succeed
  slowly (none seen yet).
- The server stays a required service for drives beyond the phone's packs, and `RemoteRouter` sends
  both ends of the trip to the server the user chose (critic, hidden coupling). Offline, a downloaded
  corridor lets the phone follow the line; it carries no graph, so a missed exit cannot be re-planned
  until the phone is online (objection 4).
- Builds before T89 show a version 2 path as one fixed leg; drive trips stored before T89 keep the
  45 km/h time until re-planned.
- Supersede if the continental measurement shows two levels are not enough, if the adaptive end zone
  changes how ends are searched, or if the licence rule changes.
