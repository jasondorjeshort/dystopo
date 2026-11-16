---
date: 2026-11-14
kind: feature
related: []
---

# Stop signs and traffic signals cost a drive time

The owner, 2026-11-14: kept as a later follow-up of the turn-cost idea (its first Open item). OSM marks them as nodes
(`highway=stop`, `highway=traffic_signals`, `highway=give_way`); the tile profile does not keep them
(`tilebuild/PROFILE.md`), so the router cannot price them (`routing/Router.kt` header: "they do not know turn
restrictions, traffic or seasonal closures"). A route through five lights is slower than one through none, and a
small town's grid of stop signs is slower than its bypass.

Research first: how often the tags are present in the extracts the project builds (a count over a public area:
Bonneville, Rainier approaches, a Mont Blanc valley town, a dense town), and published per-stop and per-signal
delays. Then the profile keeps the nodes as flags on the graph's junctions (`RoutingGraphBuilder`, a format change,
ADR 0033), and the cost is added beside the turn cost. Done when: the count and the delays are in a finding, and a
drive through a lit town prices its lights in the route JSON.

## Open

- Whether a signal costs a fixed average (about 15 s) or less on a through road than on a turn.
- The extra size of the graph tiles (measure it).

2026-11-16: T291's profile left stops and signals out of its pass one; in `routing/SpeedProfile.kt` a stop is a
point whose speed is capped at zero, with a wait, so the cost needs no table of its own once the graph knows where
the stops are. The owner, the same day, on bends: "It's only actual turns that require a stop and change of
direction", which puts stops ahead of bends as what a drive's time is missing (the coordinator's reading).

Plan: pass one is the count and nothing else; the build waits for the pack and graph revision of
`2026-11-15-pack-facts-written-by-the-map-build.md` (one format change, not two). Found 2026-11-15: the graph is
built from the pack's `transportation` lines only (`RoutingGraphBuilder.build`), no layer carries these points and
the graph has no per-node flags, so the build is a profile change (points into a layer), a pack rebuild and a
graph version. Survey: one router charges 2 s a signal, 2 s a stop and 1 s a give-way, with `direction=` and
`stop=minor` honoured; another scales its turn seconds by a per-edge stop impact; world counts are 2.0 million
signals, 2.6 million stops and 1.7 million give-ways, uneven by region, with no published completeness figure.
Pass one, a chore with one finding (`finding` skill): count the three tags, and how many carry `direction=`, in
the cached extracts of `rainier`, `mont-blanc`, `bonneville` and one dense public town, per kilometre of drivable
road; state published per-signal and per-stop delays with sources (the survey could not read the engineering
manuals); say whether a town drive of wave 33 passes any. Out of scope: any code in `shared` or `tilebuild`.
