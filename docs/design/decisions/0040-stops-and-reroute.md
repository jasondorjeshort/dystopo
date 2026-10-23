# 0040. Stops and reroute: a plan of stops as a keyed field beside the frozen route; plans replan, frozen routes lead back

Date: 2026-10-23
Status: accepted (the format and the policy, the coordinator's after the critic; the owner's positions
below are built as proposed under ADR 0031 and listed for veto before a build task is drafted)

Record: proposal `../proposals/0040-stops-and-reroute.md` (T132) and its critic review
`../proposals/0040-critic-review.md` (0 blocking, 4 serious, 8 minor, all taken); the owner's answers of
2026-10-23 in `docs/ideas/2026-10-13-route-waypoints-and-reroute.md` and
`2026-10-13-routing-modes-crow-bushwhack.md`; the benchmark survey in the proposal's Prior art (ADR 0039).

## Context

T114 routes from the phone's fix to a pressed point and deferred the rest of the everyday flow: a list of
stops, a travel mode per leg, replanning when the walker leaves the line, the Go rule away from the
start, passed and skipped stops, route to from search. A saved route (a trip, T60) is a frozen line
synced as `path` (mode, via points, legs; version 1 or 2) with `waypoints`, `wpt:<id>` and, since T108,
`directions` keyed to the path value and dropped alike on every replica when the path changes under it
(`RouteDirections.forPath`, `Materializer.routePlan`). A field a build does not know is stored and
forwarded, never materialised; a path version it does not know makes the route the put's points as one
fixed leg, and the put is never rewritten; a mode it does not know is shown, followed and refused for
editing. Navigation (T61) calls more than 50 m off the line for 10 s off route, back within 30 m,
arrival within 30 m after half the line, a waypoint reached 15 m before its projection while on route.
A plan on the phone takes about 2 s for a hike with its graph file built (T76) and 13.2 s for a drive
across packs (T77). Verified by the critic against the code: every mechanism above, with file and line
in the review. The survey found the road apps replan automatically to the destination (Locus 100 m and a
30 s re-replan, Mapy 30 to 40 m), the hiking apps alert without replanning (AllTrails 50 m), offline
they keep the line and point back to it, and no app has a mode per leg, per-leg distance and time, an
undo, or passed stops kept visible.

## Decision

**Format: the plan of stops is a keyed side field, `stops`, beside an unchanged `path` (option A).**

- `stops`, version 1: the path key (`RouteDirections.pathKey`, FNV-1a 64 of the quantised path value),
  then per via point a flag byte (bit 0 stop, not a shaping point; bit 1 named) and an optional name,
  then per leg an optional mode string (null: the path's mode). A route whose `stops` key matches its
  `path` is a plan: the flagged via points are the list, each leg is planned in its own mode, the
  geometry is recomputed from wherever the walker is. Absent or stale: a frozen route, as today.
  `path.mode` of a plan is its first leg's mode. Last writer wins and compaction as for `directions`;
  the field joins `EntityKind.ROUTE.fields`; the track database gains a `stops` column at the next free
  schema step, filled from the log once on first open (`.claude/rules/trackdb.md`).
- **One writer.** `SyncStore.setRoutePath(id, path, stops, directions, now)` writes a path and the
  plan's side fields in one transaction; a store-contract test on both stores fails a path written to a
  plan without them. Writers today: `Trips.edit`, `create`, `keep`, `import`, the browser's Trips box,
  the server's planner, `putRoutePlan` in both stores, `migrateDirections`; this decision adds Go's
  first-leg replan and the stale-fix replan and no other.
- **Degradation, never a misread.** A build before this one shows a plan as the frozen route its path
  also is, follows it and may edit it; that edit (or the browser's, until it writes the field) makes
  the key stale and every replica alike shows a frozen route with plain via points; names and leg modes
  are lost, the geometry is not. Two tests as T108's: the v0.1.10-alpha fixture materialises as a frozen
  route and re-encodes byte for byte; a `stops` write merged into a replica whose field set lacks it is
  ignored, kept and forwarded.
- **A replan writes nothing to the store.** The line under way, the passed and skipped state and the
  undo stack live in the navigation session and `files/route-session.json`; a kept plan's `path`
  changes only by an edit of its stops or modes. `devtool nav replay --reroute` asserts the store's
  operation count unchanged after a replan.
- **Expected exit.** At the first beta (ADR 0033 Decision 5) `stops` folds into a path version 3 (the
  proposal's option B) and the keyed field retires; until then one release behind is the case that
  matters. Dropping the field leaves every path valid.

**Replan policy.**

- A plan replans automatically from the fix to the next unpassed stop on T61's off-route event (more
  than 50 m for 10 s), keeping the rest of the plan; when the shortest way is back onto the old line the
  replanned leg rejoins it and the line visibly stays. Asynchronous from the start: on the planning
  worker while fixes keep coming, the banner shows "Replanning..." and the rejoin hint meanwhile. One
  automatic replan per 30 s counted from the last one's end, none while one runs, none from a fix over
  `maxAccuracyM`; the banner says how long until the next may run; "Replan now" on the banner is the
  lift (a tap ignores the limit; `POST /nav/replan` for agents). A setting in Settings › Navigation,
  "Replan when off route", on by default.
- A frozen route (a saved trip, a track) is never replanned automatically: T61's alert and rejoin hint
  stay; the banner offers "Route to the end from here", a plan with the route's end as its one stop.
- A replan that fails keeps the old line, the hint and one notice that names the failure: no graph tile
  under the walker ("No map data here to replan from") or no trail within `snapM` of the fix ("No trail
  near you to replan from", with the nearest trail and its distance as the router's failure gives
  them); a banner button draws a straight line to the next stop as a crow leg. Never a silent one.
- **Go away from the start.** A plan plans from the fix; a fix more than 50 m from the first point
  replans the first leg at once with one line saying so. A frozen route whose start is more than 50 m
  away asks once: "Plan from your location" or "Follow anyway". A plan made from a fix older than 120 s
  (`RouteOrigin.STALE_S`) replans its first leg when a fresh fix lands more than 50 m from it.
- **Reach rule**, one for stops and waypoints: reached 15 m before its projection along the line while
  on route (`WAYPOINT_REACHED_M`), or a fix within 30 m of the point (`arriveM`). A passed stop stays in
  the list, greyed with a check, its leg fainter; navigation does not pause. Skip is a row action on the
  next or any later stop (struck through, replanned to the one after); never automatic.
- **Legs and stops.** Each leg is planned by the same `Router` in its own mode (local tiles, the server
  through `FallbackRouter` when an end is beyond every pack and the phone is online; no gate, ADR 0023);
  a stop between two modes snaps to the network of the leg that leaves it, the arriving leg ending in a
  straight connector; a plan's directions are made whole from its legs, never spliced. The plan's
  offline download is the current path's corridor plus a box around each stop; a replan beyond it falls
  to the fallback above by design. Cap 25 stops, a constant; the 26th is refused with "A plan holds at
  most 25 stops".
- **Agent surface.** `POST /route/stops` (list, `add`, `insert`, `move`, `remove`, `skip`, `mode` per
  leg), `GET /route` with each leg's mode, distance, time and state, `POST /nav/replan`, `/nav/status`
  with `stops` and `reroutes`; `devtool nav replay --stops ... --reroute` over the Rainier pack with the
  Skyline off-route fixture; `sync-sim` round-trips a bundle with `stops`.

**The owner's positions (ADR 0031: built as proposed in alpha, shown in the PR, adjusted afterwards;
listed here so the owner can veto any before the build task is drafted).**

1. The list: one list on the route card, a row per stop with a drag handle, an X and a name, the leg to
   it under the row (mode icon, distance, time); the mode chips act on the selected leg.
2. Adding: a search result's card reads "Route to" first and "Add stop" while a route is on the card;
   the long-press card gains "Add stop" in the same case; a dragged point stays a shaping point.
3. Mid-trip insertion: the new stop becomes the next stop, with "Move to end" on the row; no silent
   cheapest insertion; "Optimise order" is a later opt-in.
4. Undo: one level, a 10 s snackbar after delete, reorder, insert and skip; never synced.
5. Passed stops greyed and kept, no pause at a stop; skip as a row action.
6. Automatic replanning for plans only, never for frozen routes; the setting's row; "Replan now" and the
   banner's held-back line.
7. Offline fallback: keep the line and the hint, a one-tap straight line (the idea file's automatic
   straight line is the alternative).
8. The Go dialogue for a frozen route and its two choices; the plan's one-line "replanned from here".
9. The cap of 25 and its message; every string above.

Deferred with its trigger: the browser's Trips box writing `stops` (when the owner wants plans edited
there; until then a browser edit degrades a plan); "Optimise order" (after the list ships); the fold into
a path version (the first beta).

## Consequences

- Two build tasks in order: the field, the list and the editor planning per leg; then reroute, passed
  and skipped stops, the Go rule and search. Each PR lists the format change and the migration (ADR
  0033), the strings, and the screenshots at font scale 1.0 and 2.0; the per-leg list with many stops
  at 2.0 is the known layout risk.
- Costs: one codec and schema step, a store call every path writer must use (the contract test is what
  keeps it true), a navigation session that takes a replaced path without losing its events, and a
  plan that an older phone or the browser silently turns into a frozen route.
- Bushwhacking (`docs/ideas/2026-10-13-routing-modes-crow-bushwhack.md`) depends on this decision in
  three ways: its mode string needs no path version (T108's rule); its off-trail directions need a new
  `Maneuver`, which older builds' `RouteDirections.decode` refuses, so the saved directions count as
  absent there; and a leg's mode inside a plan is invisible to an older build, so ADR 0033 Decision 3's
  refusal does not protect a bushwhack leg inside a plan (the degrade rule applies instead, and its
  safety wording cannot lean on the T108 refusal). Only a new `LegKind` changes the path in effect
  (`LegKind.of` throws, the whole path fails to decode, the older build shows the put's points); this
  build reserves `LegKind` code 4 (`OFF_TRAIL`) so that, if the bushwhack proposal needs it, no later
  version step is required.
- Coupling with 0041 (polygon areas): the plan's offline download is a corridor plus a box per stop
  until a polygon area exists; the two tasks do not share files.
- Rejected: option B, the plan inside a path version 3 (an older phone's edit would replace the plan on
  every replica by last writer, undetectably; it is the end state after the beta, not the start);
  option C, a session-only plan (the owner's first example, drive to a trailhead, hike, drive home,
  cannot be saved as a plan); a separate plan entity (B's problem plus a join); Google's pause at a
  stop; Mapy's end-of-list insertion; the idea file's automatic straight line offline.
- Supersede when the field folds into the path at the first beta, or when the sync simulator shows a
  common two-phone sequence that loses a plan both phones still think they have.
- Prediction, check at the reroute task's PR: a hike replan inside one pack with the graph file built
  measures under 2 s on the API 35 emulator; if over 5 s, the 30 s limit and the banner's timing are
  reconsidered.
