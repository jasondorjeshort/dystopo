---
date: 2026-11-15
kind: feature
related: [2026-11-14-drive-speed-falls-on-winding-roads.md, 2026-11-14-drive-stops-and-signals-cost-time.md, 2026-11-16-the-clock-time-at-each-point-of-a-route-and-the-sun-there.md]
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

2026-11-16: pass one is built (T291: `routing/SpeedProfile.kt`, devtool `route --profile`; finding
`docs/findings/2026-11-15-a-drive-as-a-speed-profile.md`), so the Plan below is spent and this file wants a new
block. What follows it: the amendment of ADR 0063 and the card's line from the two routes' profiles (the board's
Later, T291's line), after the build-process wave the owner picked for next. Two ideas of this date stand on the
profile: `2026-11-16-the-clock-time-at-each-point-of-a-route-and-the-sun-there.md` and
`2026-11-16-a-warning-before-a-sharp-bend-while-driving.md`; the owner's answer on bends is in the winding-roads
idea, and how the profile's limits could be fitted to a driver is in
`2026-11-15-drive-and-hike-pace-learn-from-your-own-tracks.md`.

Plan: pass one prices a finished route and changes no search; it is also the check on T284's 33 % and 47 %. Sanity
check, survey of 2026-11-15: no published router carries a speed profile in its search (it couples neighbouring
edges); the forward and backward pass is standard for pricing a finished route. Published least-fuel savings
against the fastest route average 5 to 10 percent (one phone test: 6 to 9 percent for 3 minutes), so 33 and 47 on
town drives are probably the model's full regain per turn and not real. One implementer: a pure-Kotlin
`routing/SpeedProfile.kt` over a `PlannedRoute`'s points: a limit per point from the edge's speed
(`CostModel.speedKmh`), the bend (`sqrt(a * r)`, `a` 2.5 m/s2) and each priced turn's speed; one forward pass at
1.5 m/s2 and one backward at 2 m/s2 (first values, the survey's guesses, named as such); time is the integral and
fuel is `FuelModel`'s resistance and kinetic terms over the same profile. Devtool `route` prints `profile` (time,
fuel, the percentage against the least-time route's profile) beside today's figures. Done when: unit tests (a
straight road equals length over speed; a stop in the middle costs the textbook time; a short block never reaches
its speed); a finding with the six public drives and the two town drives under time and fuel, both models side by
side, and what it means for the card's line. Out of scope: the phone, the search, stops and signals, the card.
