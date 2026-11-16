---
date: 2026-11-15
kind: feature
related: [2026-11-14-drive-preference-fastest-or-shortest.md, 2026-11-15-a-drive-is-a-speed-profile.md]
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
- 2026-11-15 (T290, from the idea "the route card says why this route", deleted with that task, whose cheap half it
  built): when the routes differ, each row may say why in the words of the cost table, such as "2 turns fewer, 300 m
  longer". The table is `TileRouter.price` (devtool `route --breakdown --against`, the phone's `GET
  /route?breakdown=1`); the survey of 2026-11-15 found no app that says in words why a route was chosen.
- 2026-11-16, the owner, on whether Plan drives for is a setting at all: "Rather than have this option it'd be better
  to give the user several options for the route choice itself. This path: +1% gas -1% time, or something. So
  probably this setting could go away once that's done." And yes to a tap on the fuel line that shows where the
  saving comes from (the bullet above). Read against the Plan below (the coordinator): its two rows, each with its
  trade against the other, are the first step and stand. Removing the setting and offering more than two routes come
  after it and are not in the Plan: where a third route comes from when there are two metrics is research (ADR
  0044). The percentages must be the profile's first (finding
  `docs/findings/2026-11-15-a-drive-as-a-speed-profile.md`: by the profile least fuel adds about -3 to 3 % of time
  on the two town drives, so many trades will read as next to nothing).

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
