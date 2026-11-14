---
date: 2026-11-14
kind: feature
related: [2026-11-14-drive-routes-pay-for-turns.md]
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
