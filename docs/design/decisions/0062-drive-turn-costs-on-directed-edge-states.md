# 0062. Drive turn costs: a directed-edge search, a turn-exact overlay, an angle-shaped cost

Date: 2026-11-14
Status: accepted

Record: no proposal (the engine, the graph format and the route reply stay; the wave's critic, objection 19). The two
surveys of 2026-11-14 (ADR 0044, technical variant) and the critic's twenty-one objections are answered in the wave
paragraph of `docs/tasks/wave-33.md` once the wave closes, until then in `docs/TASKS.md` [no-check: the record is
written at the close]. Amends two lines of ADR 0029 (Bounded searches; "exact within the cells"); the rest of 0029
stands.

## Context

The owner, 2026-11-14, on v0.1.29-alpha: a drive leaves the main road for a same-speed side road reached by two left
turns because it is a few metres shorter. Drive cost was travel time per edge only, and the search was node-based
(`routing/Router.kt`, `Search`), so it could not know which edge it arrived by. The idea file guessed a
directed-edge state, no turns in the overlay, and three flat values.

Surveyed (reports kept outside the repository): Geisberger and Vetter 2011 and Customizable Route Planning (Delling,
Goldberg, Pajor, Werneck) for the search; the car profiles and costing documents of three open routers for the
values. Every source labels directed edges once turns are priced (an optimal path can pass one node twice); CRP keeps
no turn tables in the overlay and puts the turns inside a cell into its shortcut costs; the one router with real
default numbers uses a smooth curve on the angle, charges nothing where only two roads meet, makes the turn across
traffic two to three and a half times the kerb-side one, and adds a flat U-turn cost; none has a user switch; none
charges for joining a higher class. Measured elsewhere: about 2.3 edge states per node, 2.2 to 3.1 times the search
time, the overlay part of a query nearly unchanged.

## Decision

1. **State.** A drive search (`routing/DriveSearch.kt`) is a bidirectional A* on the implicit edge-expanded graph: in
   a base piece a state is a directed edge arrived at its head, on dense arrays; in an overlay piece a border node
   has an in label and an out label (shortcuts run in to out, the border crossing out to in), so no reversal is free
   at a border. The potential is read at the head node. Hike and bike keep the node search, unedited.
2. **Ends.** The start seeds both directions of its snapped edge at no cost; the goal is reached through the goal
   edge's predecessors, so the turn off the first edge and the turn onto the last are priced. Each leg of a
   multi-leg route starts free in either direction.
3. **The turn function** (`routing/TurnCost.kt`) is computed on the fly, never negative, from bearings taken over
   `Directions.BEARING_M` at the edges' ends (a lazily built cache; no table, no DYSG change). Parallel copies of one
   road where packs overlap count once; a reversal is read from geometry.
4. **The cost**, in seconds, converted like an edge's time. Nothing at a node of two or fewer distinct drivable
   roads. Nothing onto the through exit: the one exit that shares the incoming edge's name and class (its class when
   both are unnamed), when exactly one does. Else by angle: nothing under 30 degrees; kerb-side rising linearly to
   3 s at 90 degrees and 6 s at 150 and over; across traffic to 7 s at 90 and 9 s at 150 and over; a reversal 25 s,
   never refused. On a priced turn, 1 s per class step down (at most 3 s) and 2 s onto `service`, `track` or an
   unpaved edge from a paved one. These are first values; the task that fills the table reports each change with the
   route that moved it, and the table in the code is the record after that.
5. **The overlay is turn-exact.** The level-1 cell build and its unpack run the same edge search and turn function;
   level 2 is unchanged in code. The cell file's version goes up when the table becomes non-zero; an older file is
   refused and rebuilt. The residual: a border node that is itself a junction (within the join distance of a tile
   border, counted on all its drivable edges, twins included) leaves the turn at that node unpriced, and the step
   from a shortcut into a detail piece is priced as straight. The overlay's report counts such nodes. This replaces
   ADR 0029's "exact within the cells" with "exact within the cells except at border junctions, counted".
6. **Budget.** `maxSettled` keeps its sense and its message; a drive search's state limit is that number times the
   directed drive edges per drive node measured on the public packs (the task states the factor and the phone's
   search bytes at its limit, at most twice the old figure). This replaces the two fixed numbers in ADR 0029's
   Bounded searches line as the limit of a drive search.
7. **Time and report.** The planned time of a drive includes the turn seconds. Devtool `route` and the server's
   `searches` entry carry `turns` and `turnCostS` on drive routes only.
8. **The driving side** is a parameter of the turn function: a hand-written table of coarse boxes for the
   territories that keep left (`routing/DrivingSide.kt`), looked up once per search at the start point; unknown
   reads right-hand.
9. **The fast search stays an option** (the owner, 2026-11-14: "the fastest search should remain an option",
   overruling the surveys' one behaviour): the node search without turn costs can still plan a drive, by a
   `driveTurns` parameter of the router, a devtool switch and a request parameter, and by a user setting (default:
   turns counted). The owner the same day: "we should have 'most efficient algorithm', 'least gas' and 'least
   time' as my starting points for selection", so the setting is one choice of how a drive is planned: least time
   (this ADR's search, the default) and the quick search are built with the table; least fuel is a later task's,
   from its own survey, and gets its own record if its model or its overlay cost is a decision. The numbers of the
   table are not a user's choice.

Out of scope, each with what revives it: turn restrictions (the profile keeping restriction relations: an infinite
turn from a per-piece table fits this state); stop signs and signals (their idea: a node flag read by the turn
function); curvature and a fastest-or-shortest choice (their ideas); CRP's stalling (a drive search over its limit on
a real pack); navigation's ETA, which does not read the router's time (ADR 0029 deferred it); a remote plan's reply
carrying the two fields (a client that shows them); a pack-level driving-side flag from `tilebuild` (the boxes proved
wrong near a border); roundabouts and links, which the graph does not mark, priced by their angles alone (a route
the owner reports that circles or avoids one wrongly).

Rejected: node labels with per-junction entry tables (CRP's compact form: less memory, more code, and stalling to
pay for; revisit with the stalling item). No turns in the overlay (prices cells below the detail tiles, breaks the
same-route property). A precomputed turn table per junction (needed by contraction, not by this search). Three flat
values (a gentle bend would cost a T-junction). A charge for joining a higher class (no survey supports it; every
side-street exit would pay).

## Consequences

A drive's detail areas cost about two to three times the states and time; the far stretches barely change. Cells
rebuild once on every server after the version change. A stored drive leg keeps its old time until re-planned
(listed under ADR 0033 by the task). Two searches now live side by side; the equality of the drive search at zero
turn cost with the node search is the test that keeps them honest. The first values are guesses within the surveyed
range and will move.

- Prediction, check when the task that fills the table merges: the owner's shape routes along the main road, and no
  public-area drive's time rises by more than 5 percent beyond its own turn seconds.
- Prediction, check when the task that builds the search merges: the measured factor of directed drive edges per
  drive node on the public packs is between 2 and 3.
- Prediction, check by 2027-01-15: no value of the table's class terms has survived unchanged, or the owner has
  reported no drive route as wrong at a turn.
