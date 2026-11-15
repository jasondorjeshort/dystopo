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

Plan: one implementer, phone only, after the speed-profile pass has settled what the fuel percentages are (a card
that offers "47 % less fuel" must be right first). The survey of 2026-11-15 found this shape in the mainstream
app that plans for fuel: both routes shown when they differ, the saving and the time difference on each, a global
preference that sets which one opens selected. Build: the second plan is kept, not dropped
(`RouteSession.compareLater`, `RoutePlanner.planLeastTime` generalised to "the other preference", so a Least time
drive plans least fuel in the background on the same worker); `RouteOverlay.kt` gets a second source and line
pair drawn faint under the first; the drive card shows two rows, Least time and Least fuel, each with its time and
"about N % less fuel" or "M min longer" against the other, the chosen one marked; a tap on a row or on the faint
line swaps them for this trip only. One row, as today, when the two differ by under 3 percent and under a minute,
when the plan came from a server (the line of today stays), or under Quicker planning. A "working" row while the
second plan runs; its milliseconds and bytes reported on the public drives, cold and warm (T285's follow-up:
slower than the first when cold, no figure recorded). Debug: `GET /route` carries both, `POST /route/choose`.
Done when: unit tests of the rows and the swap, the sweep's `drive` step captures both lines and the swap. New
strings in the report (ADR 0031). Out of scope: the server returning two routes, hike and bike, a third route.
