# 0028. Long-distance routing: a drive overlay over our own graph tiles, the server as a remote router

Date: 2026-10-12
Status: superseded by 0029 (in part)

Superseded in part by `0029-two-level-drive-overlay.md` (2026-10-14): the levels, cells on disk, bounded searches and when the phone goes remote; the overlay approach and the alternatives here stand.

Record: "Long distance: the proposal (T77, written before building; T84 turns it into an ADR)" and
the paragraphs after it in `docs/PLANNING.md` › Routing; the T77 log entry (2026-10-13 in the log,
PR #83). No separate critic pass was run: the alternatives were measured before building and the
result checked by an implementer and a verifier, and this ADR records the outcome.

## Context

Design goal 5 (ADR 0025): the drive from New York to Los Angeles and the ride between towns, without
the phone ever holding a continental graph. T76 had already cut the routing graph into z11 graph
tiles per pack behind a `Router` interface, so a route crosses packs; a full search across a state
settles over a million nodes. Constraints: no GPL anywhere and LGPL only in `tilebuild`
(`docs/PLANNING.md` › Licensing); the phone snaps to, names roads from and navigates on the pack
graphs, so a second road network would disagree with them.

## Decision

**A partition overlay over our own z11 graph tiles (one level of customizable route planning), on
the server and the phone alike, with the server as a remote router for the phone.**

- `shared/.../routing/DriveOverlay.kt`: each cell is one tile of one pack and keeps the tile's
  border nodes where a tertiary or larger road open to cars crosses, with the exact cheapest
  in-tile drive cost between each pair.
- Drives of 40 km or more (`MIN_DISTANCE_M`) search the tiles within 5 km of their ends in full and
  every other tile through its cells, then expand the shortcuts back into the tile's edges, so the
  result has the pack graph's points, names and numbers; when that finds nothing (a border crossed
  only on a minor road) the search runs again in full. Hike and bike never use it. `overlay=0`
  turns it off per request.
- Cells are built per tile on first use (LRU of 4,096 cells on the phone, 65,536 on the server); the
  server warms every tile of a pack in the background once its graph opens. Cells are keyed by the
  graph file's `GraphSource`. Exact within cells, approximate across them.
- The phone's router is a `FallbackRouter`: the packs on the device first; when that fails and an
  end lies outside every pack, `GET <server>/route` (`RemoteRouter`, through `CleartextPolicy`); the
  answer is kept as a trip's geometry, and the trip's corridor download (ADR 0027) makes it navigable
  offline.

### Alternatives (measured 2026-10-12, Washington extract)

1. **GraphHopper 9.1 on the server.** Import with car contraction hierarchies in 88 s, about 2 GB
   heap, 327 MB on disk; warm queries 3 to 15 ms; agrees with our router (Paradise to Sunrise 81.936
   km against 81,937 m). Rejected: it pulls `osmosis-osm-binary` (LGPL-3.0), which the licence rule
   allows only in `tilebuild`; it is a second network imported from the PBF, separate from the pack
   graphs the phone snaps to; it needs the whole region's PBF on the server.
2. **Valhalla** (MIT, C++) in a container. Not measured (no Docker on the machine); same
   second-network objection.
3. **Contraction hierarchies per region pack.** Two packs' hierarchies do not compose; joining them
   needs exactly the border overlay that was built instead.

### Measured result (T77)

Seattle to Yakima across three packs 232.0 km, 0.2 to 0.4 s warm (1.4 s cold with graphs on disk),
identical with the overlay off; statewide Bellingham to Walla Walla 570 km, 0.23 to 0.30 s with the
overlay (47k nodes) against 0.80 to 1.25 s without (1.17M nodes); the six Washington drives gave
identical routes either way; a synthetic worst-case grid costs 1 to 5% more on two of five drives.
New York to Los Angeles is an extrapolation, not a measurement.

## Consequences

- No GPL or LGPL dependency and no second network; the overlay composes across packs the way the
  tiles do.
- Long drives need a server (or packs on the phone covering the whole way); the phone navigates them
  offline only after the corridor download.
- Open: no second overlay level (z8); overlay cells are rebuilt after a server restart (not kept on
  disk); no test for two packs loaded in another order; turn restrictions and costs are out of scope.
- Supersede if a continental drive measures too slow with one level (add a z8 level), or if the
  licence rule changes.
