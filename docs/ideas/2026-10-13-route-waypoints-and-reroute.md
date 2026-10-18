---
date: 2026-10-13
kind: feature
status: planned
pipeline: T114 (wave 16) builds the destination-first flow with the fix as the origin; the stops list, reroute and passed-waypoint rules stay here, open
related: [2026-10-13-turn-directions.md, 2026-10-13-cannot-create-waypoints-android.md, 2026-10-13-routing-modes-crow-bushwhack.md]
---

# "Route to": a destination, waypoints, reroute, in every mode

The everyday flow of routing software, in all modes: give a destination and optional waypoints, go;
waypoints are dropped from the list as they are passed; if the user leaves the route, plan a new
one from where they are instead of only alerting. The saved, editable routes we have (T60) stay
as a separate, good thing.

Today: T60 routes with via points and waypoints (planned before the trip, saved and synced);
T61 navigation follows a route or track, detects off-route (50 m for 10 s), alerts, and shows a
hint back to the route; waypoint and arrival events are listed in `/nav/status`. There is no
"navigate to a point from here" flow that reroutes.

## Brainstorm (2026-10-13)

- Plan model: an ordered list of stops, the origin being the current fix; each leg may have its
  own mode, so one plan can be drive to the trailhead, hike to the summit, drive home.
- Editing, from the owner's experience with other map apps:
  - Long-press the map to insert a waypoint, then drag it to adjust (drag a point on the route
    line to add a via point, as the route editor in T60 already does).
  - A list of stops, reorderable by drag up and down, with delete and add; the list of stops in
    the best-known web map app is the model, "best system I've seen, but it still isn't perfect".
    Think about what is missing there: a stop's mode per leg, per-leg distance and time, an
    undo, and a clear way to see which stops are passed.
- Reroute: replan from the current fix to the next stop when off route, keeping the remaining
  stops; prefer guiding back to the old line when close; rate-limit; offline without graph data,
  fall back to a straight line with a notice.
- A frozen route (follow exactly this line, as saved routes are today) versus a plan of stops
  (geometry recomputed); a flag on the existing route so sync keeps working.
- Adding a stop mid-trip: cheapest insertion or end, with undo (not decided).

## Split (2026-10-17, owner)

The owner, on v0.1.8-alpha: a long-press offers a waypoint or "Route from here", which makes the
pressed point the origin and waits for a second press; there is no way to route from the phone's own
position. That piece needs no decision and was buried here with four that do, so four waves passed
it over. It is now T114 (wave 16): destination first, the origin defaulting to the fix, the
two-point planning flow kept as a secondary action, benchmarked against the mainstream and outdoor
apps (finding `docs/findings/2026-10-17-route-to-flows-in-other-apps.md`). What follows stays open.

## Open

- Entry: search result or long-press, "Route to", then add stops; the origin is the current fix
  (the entry and the origin are T114; the stops are not).
- Reroute policy: how long off route before replanning, when to prefer rejoining the old route,
  and what to do offline (graph tiles on the phone: ADR 0028 covers long distances).
- Passed-waypoint rule (distance and direction), and skipped ones.
- Shares the router and nav session with turn directions; the two should be planned together.
