---
date: 2026-11-14
kind: bug
related: [2026-10-13-routing-modes-crow-bushwhack.md]
---

# Drive routes pay nothing for a turn, so two lefts onto a side road beat the main road

The owner, 2026-11-14, on v0.1.29-alpha: the drive search takes side roads "with slow turns that are marginally
shorter but a lot slower"; the case: a side road at the same speed limit, reached by two left turns, a 30 second
drive that is a few yards shorter than the road it leaves. Drive cost is travel time per edge only
(`routing/Router.kt`, the Drive paragraph of its header, T73): `length x DRIVE_REF_KMH / speed`, no cost for a
junction, a turn or a stop, and the search is a node-based bidirectional A* (`Search.expand`, `relax`), so it has no
memory of the edge it came in on. A few yards saved always wins, however many turns it takes. Not a labelling
problem: the speed limit is right; the model is missing the time a turn costs.

Plan: one pass in `routing/` (owner: `Router.kt`, `DriveOverlay.kt`, `RoutingGraph.kt` as needed). Add a drive-only
turn cost in seconds, expressed as equivalent metres at `DRIVE_REF_KMH` like the edge cost:
- straight on (bend under about 30 degrees), no cost; a right turn about 5 s; a left turn about 8 s (crossing
  traffic); a U-turn 20 s; entering a way of a lower class than the one left adds 2 s per class step; a turn onto a
  `service` or unpaved way 3 s more. Values are the first guess; the tour of tests below tunes them.
- The search state becomes the directed edge for drive (the incoming edge and the node), so the cost can read the
  angle between incoming and outgoing edges (`RoutingGraph` edge geometry: the last and first segments). Hike and
  bike keep the node search untouched. Expect two to three times the states on drive; the settle cap
  (`maxSettled`) and the two-level overlay (ADR 0029) have to be checked: the overlay's precomputed long-distance
  edges (`DriveOverlay.kt`) need the same turn cost between its own edges, or the overlay carries no turns and only
  the detail pieces do (recommended first: the overlay is for far stretches where one turn is noise).
- The A* potential stays admissible (turn costs only add). Turn restrictions are a separate matter (the help text
  says they are not known) and stay out of this.
- Not a setting (ADR 0044): every router prices a turn; the numbers are not a user's choice. Directions
  (`Directions.kt`) are unchanged.
- Test and debug: a synthetic graph in `commonTest` with the owner's shape (a main road and a side road reached by
  two left turns, 2 m shorter) that routes along the main road with turn costs and the side road without; the route
  JSON reports `turns` and `turnCostS`; `devtool` route on two public-area drives (Rainier approaches, a Mont Blanc
  valley town) before and after, the PNGs viewed, the time and distance in the report. Done when: `check` green and
  no public-area drive changes by more than a few percent of its time except where it drops a pointless detour.

## Open

- Whether a stop sign or signal (OSM `highway=stop` and `traffic_signals` nodes; the profile would need to keep
  them, `tilebuild/PROFILE.md`) gets its own cost: recommended as a follow-up, not this pass.
- The first numbers above: tuned against the owner's case once they name two endpoints in a public area or describe
  the shape (kept out of anything committed).
