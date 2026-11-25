# 0066. One lookup for what the phone holds here: the finest data first, the network where it is finer

Date: 2026-11-23
Status: accepted

Record: proposal `../proposals/0066-one-lookup-for-what-the-phone-holds-here.md` (its reader table, prior art,
options, build tasks and revision) and `../proposals/0066-critic-review.md` (REVISE, 14 objections, all answered);
the owner's answers of 2026-11-23 in the proposal's Questions; T340's merge (0c345ba1) and T352's report.

## Context

About thirty readers each answer "what is here?" in their own way: the map's tiles, the two sky views, the viewshed,
the shade, the readout, the pressure card, the route tools, search's coverage, the first-run rule and the status
chip. They disagree, and the owner's reports of 2026-11-22 (a blank Panorama and no peaks with two maps on the phone;
"no elevation data" with a map and the internet; a finer map making a view worse than online) are that disagreement.
Verified against the code at bca04230 by the proposal and its critic: `PackPrecedence` puts the map chosen last
before a deeper one; inside a map the DEM reader never asks the online source at any zoom; with any one map
installed, `TileService.style()` caps the online elevation source at that map's zoom everywhere; the readout, the
pressure card, the position estimate and the route tools read the primary map's elevation only; T340's
`MergedTerrain` puts every map ahead of the online source whatever its depth. The default direct online elevation
stores only to z12 (`DirectSources.DEFAULT_MAX_ZOOM`); z15 is the URL range, not stored detail. Prior art (nine
sources fetched): practice is split between offline-first and online-first, and three of the four apps that offer
both document a switch.

## Decision

1. **Depth first.** Everything the phone holds (offline maps, downloaded areas' held cells, the tile cache) is
   ranked by stored depth at the point or tile, whatever any setting says; the outdoors profile stays ahead for
   vector tiles. The map chosen last no longer wins over a deeper one; it sets where the app opens, unit seeding,
   the contour warm-up, the routing build order, and the tie between two maps of equal depth. Its action on the
   Offline maps list is renamed "Start here" (the owner's answer).
2. **The network where it is finer.** A per-device setting `onlineDetail`, "More detail from online", default on
   (the owner: "I want a setting, defaulting on, to use network data when the phone download is lower res"), in
   Settings > Sources beside Mobile data. Under it the network goes ahead of the phone's holdings only where the
   source's stored maximum (`DirectSources.maxZoom`, or a server's declared `demMaxZoom` / `osmMaxZoom`) is deeper
   than what the phone holds there. It gates network fetches only. A tile the phone holds answers at once and the
   fetch runs behind it; refreshes are batched. Mobile data and the session limit apply to the phone's own sources
   as now (ADR 0023; a configured server adds no new kind of download).
3. **One read for every reader.** The map's tiles, the sky views, the readout, the pressure card's ground height and
   the terrain-aided position estimate take the same rule; the position estimate reads only what is fetched or
   cached and starts no fetch. The viewshed stays on offline maps only (T340); online under the setting with a size
   cap is a later option, revived when the owner asks for it or a report shows the viewshed short of the sky views.
4. **The edge join.** DEM, shade and contour tiles across a map's edge are composed cell by cell, with one blend
   weight that depends only on a cell's position relative to the finer map's box, shared by `MergedTerrain` and the
   new `MergedTile`, so no seam falls on a tile boundary. Vector tiles stay one source per tile.
5. **The lookup.** One pure `shared/.../packs/Holdings.kt` (growing from T341's tile rule; T343's coverage function
   re-pointed to it) answers, for a point or a tile, what each holding stores, which serves, and what the network
   would add; `PackPrecedence` and `MergedTerrain` take their order from it; `GET /holdings` reports it. The coverage
   panel under the status chip is its first visible use.
6. **The build** is the proposal's seven tasks, for a wave after wave 47, in its order: the lookup and the order;
   the holdings and the network member (with the style's top zooms and `TerrainRead`'s stored range); the edge join;
   the point readers; the coverage readers; the panel; "Start here".

Out of scope: raising direct reading's stored zoom (a measurement first); the routing graph's own sources.

## Consequences

The finest data at a point wins everywhere, and a downloaded map no longer makes a view worse than online. Every
reader moves to one function, so a new source kind touches one file. Costs: a medium map's views fetch online where
a server stores deeper data (the proposal's figures are estimates; task 2 measures them); a reading that changes as
the signal comes and goes can move the pressure calibration, which the cache-only position estimate avoids; seven
tasks touch the busiest files (`TileService.kt`, `ShadeService.kt`) in sequence. What would supersede this: the
measured bytes or a flickering refresh (the setting then defaults off with the owner's agreement), or the owner
wanting the chosen map to win everywhere.

- Prediction, check when task 2 merges: a z14 view inside a medium map with a server set fetches under 10 MB, and a
  pan reloads no tile the phone already showed at the same depth. Outcome (T368's merge, 2026-11-25): the bytes
  held, 4.1 MB for a cold z14 view inside the fixture's coarse map with the server set (3.5 MB by the direct reader at
  its default depth). The pan held for the network and not for the phone's own server: no tile was fetched twice, but
  each refresh after a batch of deeper tiles re-asks every visible tile from the loopback (43 of 64 tile paths in a
  pan of 10 swipes, 57 contour tiles regenerated; at most one refresh per rest).
- Prediction, check by 2027-02-28: no new owner report of two views disagreeing on what the phone holds at a point.
