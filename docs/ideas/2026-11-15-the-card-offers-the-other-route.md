---
date: 2026-11-15
kind: feature
related: [2026-11-15-the-route-card-says-why-this-route.md, 2026-11-14-drive-preference-fastest-balanced-or-shortest.md]
---

# The card offers the other route: pick the trade on the card, not in Settings

Since T285 a least-fuel drive plans the least-time route as well, in the background, only to print "about N % less
fuel than the least-time route, M min longer" (`FuelComparison` in the phone's `routing/RouteControls.kt`,
`RoutePlanner.planLeastTime`). The second route is thrown away. Wanted: both on the card and on the map (the other
one drawn faint), each with its time and its fuel against the other, and a tap chooses; Plan drives for becomes the
default the card opens on. The owner, 2026-11-15: do it, definitely.

## Open

- Under Least time the second plan (least fuel) would run for every drive: its cost on a long route, and whether
  it runs at once or on a tap.
- When the two are the same road the card says nothing, as now under 3 %.
- A route the server planned: the server would have to return both.
