---
date: 2026-11-15
kind: feature
related: [2026-11-15-the-card-offers-the-other-route.md]
---

# The route card says why this route, and a debug route prices an alternative

The owner's bug behind wave 33 (a side road reached by two left turns beat the main road) took a wave to explain,
and the fuel figures of T284 (33 % and 47 % on two town drives) can only be checked by hand computation. Wanted:
one line on a drive's card when the planned route is not the shortest way, such as "2 turns fewer than the shortest
way, 300 m longer", and a debug route and devtool flag that price a given alternative line edge by edge and turn
by turn (`routing/Router.kt`'s `CostModel`, `routing/TurnCost.kt`, devtool `RouteCommand.kt`), so "why not that
road" has an answer in JSON. The owner, 2026-11-15: sounds hard, but makes sense when the routes differ.

## Open

- What it is compared with: the shortest way, the least-time route, or the route the other preference gives (the
  related idea plans that one already).
- The cost breakdown alone (devtool and the debug route) is the cheap half and may be the first pass.

Plan: the cheap half, one implementer; the card's sentence is not built here. The survey of 2026-11-15 found no
app that says in words why a route was chosen; routers answer with a cost table per segment (distance, turn and
node cost columns), and the card that shows both routes (the related idea) is where the owner's "when the routes
differ" is met; this pass adds a dated line there with the "2 turns fewer, 300 m longer" wording. Build, in
`shared`: `TileRouter.price(mode, preference, line)`, which snaps a given line onto the graph as
`routing/TrackMatch.kt` does for the pace fit and sums it with the search's own pieces (`CostModel.partCost`,
`edgeTimeS`, `fuelJ`, `TurnCost.seconds`), returning per edge (class, name, length, speed, seconds, cost, fuel)
and per turn (angle, across traffic or not, class step, seconds, fuel). Devtool `route --breakdown` prints that
table for the planned route, and `--price <gpx>` prices a given line beside it with the totals' differences; the
phone's `GET /route?breakdown=1` gives the planned route's table. Done when: the breakdown's sums equal the
route's `timeS`, `turnCostS` and `fuelIndex` in a unit test on the fixtures; the two town drives of T284 printed
with the share of fuel that is turns. Owns `devtool`'s `RouteCommand.kt`, so not beside the speed-profile pass.
