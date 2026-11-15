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
