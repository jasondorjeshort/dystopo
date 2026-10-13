# Critic review of ADR 0028: long-distance routing by a drive overlay

Date: 2026-10-13
Status: review of an accepted ADR written after the build (T77, PR #83; ADR by T84)
Reviewer: critic (Opus 5.5), read-only; estimates are arithmetic, not runs

Not re-argued (ruled out before this review): GraphHopper or Valhalla on the server; a continental
graph on the phone.

## Part 1. Claims checked against the code and the log

| # | Claim in the ADR | Verdict | Evidence |
|---|---|---|---|
| 1 | Cell = one tile of one pack; border nodes where tertiary+ open to cars crosses; exact in-tile cost per pair | Confirmed | `DriveOverlay.build`: `onBorder` within 2 x `JOIN_M`, `MIN_CLASS = TERTIARY`, `usable` and not `MOTOR_DESTINATION`; one `TileSearch` per border node over the tile's whole drive network. |
| 2 | Overlay from 40 km; tiles within 5 km of the ends in full; full search if nothing found | Confirmed | `TileRouter.route`: `MIN_DISTANCE_M = 40_000`, detail = tiles in `TiledGraph.around(end, max(5 km, 2 x snap))`; retries with `View(tiles)` only on `NOT_CONNECTED`. |
| 3 | Shortcuts expanded into base edges, same points and names | Confirmed | `Overlay.unpack` re-runs the in-tile search; golden drive 81,937 m unchanged (T77 log). |
| 4 | LRU 4,096 cells on the phone, 65,536 on the server; keyed by `GraphSource` | Confirmed | `RoutePlanner.kt` (default `maxTiles`), `RouteRoutes.kt` (`maxTiles = 65_536`); `Key(source, x, y)`. |
| 5 | Phone: local first, server when "an end lies outside every pack" | Confirmed, see objection 2 | `FallbackRouter.route`: remote only if `!(covers(from) && covers(to))`. |
| 6 | Measured: the 570 km statewide drive, 47k nodes with the overlay against 1.17M | Confirmed as measured | PLANNING › Routing, "Long-drive numbers (T77)". What it shows about scaling: objection 3. |
| 7 | GraphHopper pulls `osmosis-osm-binary` (LGPL-3.0) | Consistent with the T77 log | Not re-checked here (ruled out). |

## Part 2. Objections

1. **Serious. The full-search fallback is unbounded, and the fixed end zones trigger it for the
   drives this app exists for.** Overlay cells keep only tertiary-or-larger border crossings, and
   the full-detail zone is the z11 tiles touching a 10 km box around each end. A trailhead at the
   end of a long forest road (unclassified or track) whose road leaves the detail tiles on a minor
   road has no overlay crossing, so the overlay search reports `NOT_CONNECTED` and `TileRouter`
   runs a plain bidirectional A* over every loaded pack, with no node, memory or time budget
   (`Router.kt`, the `if (long)` block). The same happens for a pair that is truly disconnected
   (an island, a ferry-only crossing): the server pays a full search to fail. On the statewide pack
   that full search was 1.17M nodes and 77 MB of search state; PLANNING's own extrapolation for a
   continental drive is "ten million nodes and gigabytes".
   *Resolve (task):* grow the detail set adaptively, searching from each end at the base level
   until it settles a `MIN_CLASS` border node (the end-cell rule of customizable route planning),
   instead of a fixed 5 km; put a settle budget on the full fallback and answer a clear failure
   when it is hit. Test: a synthetic grid where an end is 20 km up a minor road (DriveOverlayTest
   already has the grid).

2. **Serious. Two packs on the phone that do not join never reach the server.** A user holding a
   home pack and a destination pack plans the drive between them: both ends are covered, the local
   router answers `NOT_CONNECTED`, and `FallbackRouter` returns that failure without asking the
   server, which could have answered. That is design goal 5's ordinary case (ADR 0025), not an
   edge. *Resolve:* in drive mode, also go remote on `NOT_CONNECTED` when the ends are at least
   `MIN_DISTANCE_M` apart (or lie in different packs); one test in `RemoteRouterTest`.

3. **Minor. The scaling evidence cannot support the New York to Los Angeles extrapolation.** The
   A* heuristic is straight-line distance at 130 km/h (`CostModel.minWeight = 1.0` against
   `DRIVE_REF_KMH = 130`), while real long drives cost about 1.4 times that (detours, 100 to 115
   km/h). The region such a search settles is then roughly an ellipse of area about D squared:
   for the 570 km statewide drive (about 430 km straight) about 200,000 square km, roughly the whole
   state, so the statewide run measured "the pack", not a growth rate. Scaled to a 3,900 km drive
   the same rule covers the whole contiguous US: on the order of 36,000 z11 cells (inside the
   server's 65,536-cell LRU) and 1.5 to 2 million overlay nodes, not PLANNING's "a few thousand
   cells and well under a million"; about 10 s warm by linear scaling of 0.3 s, and minutes of CPU
   to warm cells after a restart. Probably still workable on a server, but the claim in PLANNING
   should be corrected and measured. *Resolve:* a devtool run on a multi-state set of packs, or a
   synthetic continental grid of graph tiles; decide the z8 level from that number. A tighter
   heuristic (the graph's real top speed) is a cheap gain either way.

4. **Minor. "Navigable offline after the corridor download" overstates what a corridor gives a
   drive.** ADR 0027 says a corridor carries no graph, and navigation has an off-route alert but
   no rerouting anywhere, so offline the phone follows the line; a missed exit cannot be planned
   around until the phone is online again. The default corridor is also hike-sized (ADR 0027
   review, objection 2). *Resolve:* say so in Consequences; when graph tiles are fetched from a
   server or peer (PLANNING "Not yet"), they become a manifest kind, which is why the manifest
   should skip unknown kinds now (ADR 0027 review, objection 3).

Hidden coupling: this ADR makes the server a required service for any drive beyond the phone's
packs, so a server with continuous pack coverage of the region becomes part of the product
(today, the owner's own server). That is consistent with ADR 0025 but worth stating, together with
the residual privacy fact that `RemoteRouter` sends both ends of the trip to that server (over
cleartext where `CleartextPolicy` allows); the user chose the server, and the trip card could say
"planned by <server>".

## Recommendation

The decision is right. Building a one-level overlay over the graph tiles the phone already snaps
to and names roads from is the only option on the table that keeps one road network, composes
across packs, and adds no LGPL code; the measurements show identical routes on every real drive
tried. Two behaviours should change soon, as one small task: an adaptive end zone with a budget
on the full fallback (objection 1), and remote fallback for disjoint local packs (objection 2).
Objection 3 is a correction to PLANNING and a measurement to schedule before anyone promises
coast-to-coast; the ADR's own supersede trigger (add a z8 level) covers the outcome. Residual
risks the ADR already lists stand: no turn restrictions, no test for packs loaded in another
order, cells not kept on disk. Confidence: high on 1 and 2 (read in `Router.kt` and
`RemoteRouter.kt`), moderate on the numbers in 3 (geometric estimate, not a run).
