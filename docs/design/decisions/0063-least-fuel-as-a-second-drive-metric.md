# 0063. Least fuel as a second drive metric: a physics model of one car, a cell file per metric, one list of three

Date: 2026-11-14
Status: accepted (the wording and the card's line are the owner's to adjust after the build, ADR 0031)

Record: no proposal (the cell file stays a regenerable cache; the wave's second critic, objection 7). The survey of
2026-11-14 is kept outside the repository; the critic's fourteen objections are answered here and in the wave 33
paragraph of the board (`docs/tasks/wave-33.md` once the wave closes [no-check: written at the close]). Builds on
ADR 0062 (turn seconds, the directed-edge search) and ADR 0029 (the overlay).

## Context

The owner, 2026-11-14: "we should have 'most efficient algorithm', 'least gas' and 'least time' as my starting
points for selection". Least time is ADR 0062's search and the quick search is its Decision 9; least fuel had no
model. Surveyed: dedicated car navigators offer one list of fastest, shortest and least fuel with fastest the
default, publish no model and show no litres; the largest phone app holds its fuel route to about the same arrival
time and shows relative savings; open routers have no fuel metric but one kinematic profile; a combustion model
never earns fuel back downhill (idle is the floor); Customizable Route Planning keeps one partition and one set of
shortcut costs per metric. The survey advised keeping the search method out of the cost list; the owner asked for
one list, and the critic found that wording removes the confusion at no cost in code.

## Decision

1. **One list of three**, one synced key with stored values `time`, `fuel`, `quick`; an unknown stored or synced
   value reads as the default and is not written back. Row "Plan drives for"; first wording: "Least time" (default):
   "The quickest drive, counting the seconds turns cost."; "Least fuel": "Favours steady roads and fewer climbs and
   turns. An estimate for an average car; it can take longer."; "Quicker planning" (last): "Least time without
   counting turns. Long routes appear sooner; the drive may be a little slower." Quicker planning is the node search
   on the time metric; there is no quick form of least fuel. Devtool and the server take the same three as `prefer`.
2. **The model**, fuel energy per directed edge, one car that burns fuel:
   `fuel = P0 t + [ F u L + m g ascent - min(m g descent, F L / 2) ] / eta`, `F = Fr s + ka v^2`.
   `t` is the edge's drive time and `v = L / t` (so an unpaved edge is slower by the time model's own factor); `L`
   its length; a partial edge at a route's end takes ascent, descent and the cap in proportion. First values: m 1600
   kg, g 9.81, Fr 230 N, ka 0.4 N/(m/s)^2, eta 0.35, P0 6200 W of fuel; s 1.8 on unpaved, else 1; u by class: 1.0
   motorway and trunk, 1.05 primary, 1.1 secondary, 1.15 tertiary, 1.25 minor, 1.3 service and track.
3. **Invariants, enforced in the code.** Every u and s is 1 or more (a model built otherwise is refused). The floor
   is computed from the parameters, never written: the least of `P0 / v + F (u_min - 1/2) / eta` over all speeds
   (857.9 J/m at the first values, at about 63 km/h). The cost is joules over the least flat joules per metre
   (1323.9 at the first values, at about 50 km/h), and the potential's factor is the floor over that divisor (0.648).
   No directed edge costs less than the factor times its length.
4. **A turn's fuel** is `P0 x its seconds + max(0, m (v_out^2 - v_turn^2) / 2) / eta` with v_turn 20 km/h: never
   negative, also onto a 5 km/h edge. The turn function returns seconds; `CostModel` converts them per metric.
5. **Time.** A route's time is the sum of its edges' times and turn seconds under every metric.
6. **The overlay.** The metric is part of the cell key; one LRU serves both. A fuel cell file sits beside the time
   file (`<vector>-overlay-fuel.idx`), its header carrying the metric's id and a hash of the model's parameters; a
   mismatch is refused and rebuilt. Fuel cells are built only on demand: on the server the first fuel request
   queues one full background warm-up (level 1, level 2, the file), and until it ends a fuel drive uses level-1
   cells built in the search, as the phone always does. The time file is never touched by it.
7. **What is shown.** No litres, no kWh, no cost. After a least-fuel plan the phone plans the least-time route in
   sequence and shows "about N % less fuel than the least-time route, M min longer": N is
   `100 x (1 - fuel(fuel route) / fuel(time route))` under this model, rounded to a whole percent; M the difference
   of the two planned times, rounded to a minute; the minutes always accompany the percentage; the line is hidden
   under 3 percent and for a server-planned route, whose reply names the metric it used so the card can say when
   least fuel was not applied. The agent-readable card carries the metric and both numbers either way.
8. **Tuning and the known bias.** At steady speed the model prices a 105 km/h road about a third above a 65 km/h
   one per metre, so least fuel leaves a motorway for slower roads; that is what the physics says of a steady
   drive, and the unsteadiness factors stand in for the stops the graph cannot see. The bias is accepted and said
   ("it can take longer", the minutes on the card). Tuning may only raise a class's u, each change reported with the
   public or fixture drive that moved it; no factor is tuned against the model's own saving.

Out of scope, each with what revives it: vehicle types (a setting, once the owner asks; electric needs negative
edges and a potential shift, and every parameter set its own cells); shortest (its idea: a third metric of the same
form); a same-arrival limit or a blend (the owner finding least fuel too slow to use); speed-change charges between
edges and stops or signals (the stops idea); a height-aware potential (a fuel search over its limit); the
percentage for a server-planned route (the reply carrying both plans); least fuel in the server's editor operations
and the browser demo (a user of either asking).

Rejected: the search method as a separate switch (a second key and row, and a fuel-with-quick combination to build
or grey out, against the owner's words; wording does the job). A shorter-route proxy for fuel (ignores climb). A
learned per-vehicle model (needs data the app has not). Litres from an assumed car (claims more than is known). A
written floor constant (0.65 is inadmissible by 0.3 percent).

## Consequences

A second cost path through `CostModel`, the search and the overlay, direction-dependent where time was not; a second
cell file per pack on a server that is asked for fuel routes, and the build time again; a least-fuel plan on the
phone costs two searches. The model is one invented car: its routes are plausible, not measured, and the card
compares the model with itself. Changing any parameter rebuilds the fuel cells.

- Prediction, check when the task that builds the metric merges: fewer than half of the six public drives differ
  between least time and least fuel, so the fixture carries the proof.
- Prediction, check by 2027-01-15: the owner has changed at least one of the three labels or the card's line.
- Prediction, check when the phone task merges: the second plan adds less than the first plan's time again on the
  emulator's public drive.
