---
date: 2026-11-14
kind: feature
related: [2026-11-14-drive-stops-and-signals-cost-time.md, 2026-11-16-a-warning-before-a-sharp-bend-while-driving.md]
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

2026-11-16, after T291 (finding `docs/findings/2026-11-15-a-drive-as-a-speed-profile.md`): the profile's bend cap
adds 3.7 to 9.6 % to the table's time on the four public mountain drives at a lateral 2.0 m/s2, and 5.4 and 6.7 %
on two of them at 2.5; no real driving time was read. The owner, the same day, on taking drive times from it: "Most
of the time in the US at least turn speed is already represented by the speed limit. If it's 35 on the
straightaway and you go into the turn and do 35 there's no need to slow down. It's only actual turns that require
a stop and change of direction. But extreme turns could have their own, calculable, max speed...though I guess this
depends on the bank." The coordinator's reading: the cap is already of that form (it binds only where
`sqrt(a * r)` is under the edge's speed), so what is in doubt is the value of `a` and whether the edge's speed is a
posted limit or a class default. The figures above lean on a low `a`: the survey in the Plan below gives 0.31 to
0.40 g as comfortable (3.0 to 3.9 m/s2), which was not run. So the build waits on a measured `a`: the owner's own
drives matched to the road (`2026-11-15-drive-and-hike-pace-learn-from-your-own-tracks.md`, the drive half, wanted
2026-11-16). The bank: with side friction `f` and bank `e` the speed goes as `sqrt(g * r * (e + f))`, so a bank
of 8 % beside an `f` of 0.2 to 0.3 raises it by 12 to 18 % (the coordinator's arithmetic, unsourced); no map
carries the bank, and that sits inside the spread of `a`. A posted advisory speed, where one is mapped
(`maxspeed:advisory`, seldom), would beat the geometry. The related warning:
`2026-11-16-a-warning-before-a-sharp-bend-while-driving.md`.

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
