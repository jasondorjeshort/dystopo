---
date: 2026-11-15
kind: feature
related: [2026-11-14-drive-speed-falls-on-winding-roads.md, 2026-11-14-drive-stops-and-signals-cost-time.md]
---

# A drive is a speed profile: one pass gives time and fuel, and turns, bends and stops are its inputs

Today a drive's time is each edge at its class speed plus a table of seconds per turn (`routing/TurnCost.kt`, ADR
0062), and its fuel charges every priced turn the full regain from 20 km/h to the next edge's speed
(`routing/FuelModel.kt`, ADR 0063), which makes a town's turns dear (T284's 33 % and 47 %). Both are stand-ins for
one thing: the speed a car can hold. Wanted: along a route, a speed limit per point from the road's class, its
curvature, the turn's angle and a stop where one is mapped, then one forward and one backward pass under an
acceleration and a braking limit; time is the integral and fuel is the model's over the same profile. The two
related ideas (winding roads, stops and signals) become inputs instead of separate tables. The owner, 2026-11-15:
"Sure?" (a moonshot; to be sanity-checked).

## Open

- The search cannot carry speed as state cheaply: the profile may price a finished route exactly while the search
  keeps per-edge and per-turn costs derived from it. How far the two may differ.
- Research first (ADR 0044): how published routers model it.
