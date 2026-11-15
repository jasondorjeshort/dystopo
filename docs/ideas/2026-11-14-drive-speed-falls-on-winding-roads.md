---
date: 2026-11-14
kind: feature
related: [2026-11-14-drive-stops-and-signals-cost-time.md]
---

# Drive speed drops on winding roads, from the road's own geometry

The owner, 2026-11-14, after the turn-cost bug: the other drive-model ideas are kept to test, time and research
later. Drive time is length over the tagged `maxspeed` or a class default (`routing/Router.kt`, Drive paragraph,
T73: tertiary 55, minor 40 km/h), so a hairpin road with no tag is assumed to be as fast as a straight one of its
class, and a tagged limit says nothing about how fast a bend can be taken. A road's curvature is in the graph's edge
geometry; a cap on speed by bend radius (as a lateral-acceleration limit) would price it.

Research first (`benchmark.md`, technical variant): how routers that publish their method treat curvature (a speed
cap by bend radius, or by an `smoothness` and `surface` tag), what lateral acceleration they assume, and whether it
matters beside per-turn cost. Test: time the cap on public-area mountain drives (Rainier, Mont Blanc valley roads)
against real driving times where known, before and after, the PNGs viewed. Settle with the turn cost built first:
it fixes the owner's case, so this idea has to show a case it still gets wrong.

## Open

- Whether the cap needs a per-edge field in the graph tiles (a format change, ADR 0033) or reads the geometry at
  search time (slower).
- Whether a motorway or trunk is exempt (recommended: a gentle cap only below primary).

Plan: after the pass of `2026-11-15-a-drive-is-a-speed-profile.md`, whose finding gives the size of the error on
the public mountain drives; if no public drive moves by 5 percent this pass records that here as a dated line and
builds nothing. The survey of 2026-11-15: the car profiles of the published routers do not lower speed for
curvature (class, tagged limit, surface and turn penalties only; curvature is a motorcycle preference), so this
departs from common practice and has to earn it by that measurement. Comfortable lateral acceleration in the road
literature is 0.31 to 0.40 g. Build: a per-edge curve speed computed from the edge's geometry in memory
(`RoutingGraph`'s points, cached lazily as `endBearing` is; no graph format change), the least `sqrt(a * r)` over a
smoothed 25 m window with `a` 2.5 m/s2, applied in `CostModel.speedKmh` below primary only; the overlay's cell
versions go up one. Verify: unit tests on a synthetic hairpin and a straight; the six public drives of
`docs/reference/devtool-commands.md` before and after, PNGs viewed, against a published driving time where one
exists. Out of scope: surface and smoothness caps, the fuel model's own speed terms.
