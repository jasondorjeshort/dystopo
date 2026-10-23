# 0040. Stops and reroute: a plan of stops beside the frozen route, and what navigation does off the line

Date: 2026-10-23
Status: under review

## Question

T114 built "Route to here" (the phone's fix to a pressed point) and deferred by name the rest of the
everyday routing flow (`docs/ideas/2026-10-13-route-waypoints-and-reroute.md`, answered by the owner on
2026-10-23: one proposal, one critic): an ordered list of stops with a travel mode per leg, editable
before and during the trip; what navigation does when the walker leaves the line (replan, lead back,
how long to wait, how often, and offline without graph data); the "Go" rule for a route that does not
start at the fix and the replan when a fresh fix lands far from a stale one; when a stop counts as
passed, how one is skipped, where a stop added mid-trip goes; and "Route to" from a search result. Two
things make this a major choice rather than a task: a plan of stops is a second kind of route beside
the frozen one phones sync today (a format other phones read, ADR 0033), and reroute shares the router
and the navigation session with the turn directions and the spoken cues (T102, T108, T126). The owner
also wants to know in which version step bushwhacking's format change (`docs/ideas/2026-10-13-routing-modes-crow-bushwhack.md`)
rides, since both touch the route phones sync. In ten years the plan should be the one route model the
phone, the server and the devtool share: a frozen line when the line is the intent (a saved trip, a
track), a plan of stops when the stops are, each stop's leg in its own mode, replanned from wherever
the walker is, and a phone two releases behind still shows the trip and follows it.

## Constraints that matter here

- Platforms: JVM and Android now, iOS later (`shared` commonMain stays pure Kotlin); licensing
  (`docs/PLANNING.md` › Licensing: permissive dependencies, LGPL only in `tilebuild`, no GPL);
  offline first; every feature agent-drivable (devtool command, debug route); agents write all code;
  the design goals (ADR 0025).
- The route a phone syncs is a `PutRoute` plus last-writer-wins fields: `path` (`RoutePath`: one mode,
  via points, one leg per consecutive pair, version 1 or 2), `waypoints` and `wpt:<id>`, and since T108
  `directions`, keyed to the path value it was made for and dropped by every replica alike when the path
  changes under it (`RouteDirections.forPath`, `Materializer.routePlan`). A field a build does not know
  is stored and forwarded, never materialised. A path version a build does not know makes the route the
  put's points as one fixed leg.
- A mode a build does not know is shown under its name, followed, and refused for editing with a
  reason (T108, ADR 0033 Decision 3); a mode is a string in the path, so a new one is not a format change.
- Navigation (T61, `Navigator`): off route is more than 50 m from the line on every fix for 10 s
  (`NavOptions.offRouteM`, `offRouteSeconds`), back on route within 30 m, arrival within 30 m of the end
  after half the line; a waypoint is reached 15 m before its projection along the line. The thresholds
  stay until this proposal decides otherwise (T114's finding, "Next").
- A reroute runs where a fresh plan would: the packs' graph tiles on the phone (`RoutePlanner`, about
  2 s for a Rainier hike including the first tile load), the server through `FallbackRouter` when an end
  lies beyond every pack and the phone is online. No Wi-Fi gate on any of it (ADR 0023).
- Flows, controls and wording are built in alpha and adjusted by the owner afterwards (ADR 0031); no
  other product's name in a user-visible string; a user-facing default in doubt follows the survey
  below or says why it departs (ADR 0039).
- Already ruled out: route to here from a long-press (built, T114); the two-point planning flow as the
  primary action (secondary since T114); a Wi-Fi gate of any kind (ADR 0023).

## Prior art

Benchmark survey of 2026-10-23 (ADR 0039; read-only, public help pages, release notes, forums and
tech press; vendor pages that answered 403 were read as search snippets and are marked so). The
owner's model for the stops list is Google Maps' ("best system I've seen, but it still isn't perfect").

| App | Stops list and editing | Reroute trigger, timing, setting | Passed, skipped, mid-trip insertion | Offline | Source |
|---|---|---|---|---|---|
| Google Maps | Up to 9 stops including the destination (desktop help; a 2019 thread says 8 plus 1); "Edit stops" with an X per stop and a drag handle; per-stop times in the list, the total updates on reorder (blogs); one mode per route; no undo; no auto-optimise | Automatic (observed); no threshold published | Navigation pauses at a stop (TechCrunch); "Add stop" in navigation (observed), placement not documented | Not documented | [help](https://support.google.com/maps/answer/144339), [cap](https://www.bgr.com/2222164/how-many-stops-plan-google-maps-route/), [pause](https://techcrunch.com/2024/07/31/google-maps-navigate-pause-button) |
| Apple Maps | Driving only; up to 14 stops (Apple's guide; a blog says 15); add by search or place card; a reorder handle before Go; swipe to delete | Automatic (observed); no numbers | Before Go a new stop becomes the final destination; during navigation "Add Stop" makes it the **next** stop (Apple's guide); cyclists end a detour with "Resume Route" | Not stated | [guide](https://support.apple.com/guide/iphone/change-or-add-stops-to-your-route-iph837d13d03/ios) (snippet), [blog](https://www.inputmag.com/guides/how-to-add-multiple-stops-apple-maps-ios-16) |
| Waze | One extra stop (blogs only) | Automatic (observed) | Not established | Not established | [blog](https://routific.com/blog/waze-route-planner) |
| HERE WeGo | Stops added and reordered before starting; "Add stop" while navigating | Not found | Not found | Offline maps; stops and reroute offline not stated | [help](https://help.here.com/en/support/solutions/articles/24000067126) (403, snippet) |
| Magic Earth | Multi-stop routes; a saved route keeps its order | Users report navigation ends after a missed destination and does not recover (forum) | Not found | Offline routing claimed | [forum](https://community.e.foundation/t/magic-earth-ending-route-when-reaching-destination/49062) |
| TomTom GO | Not established | A "when to reroute" preference and traffic replanning | Not established | Not established | [manual](https://download.tomtom.com/open/manuals/TomTom_Navigation_for_Android/html/en-gb/Routing-GONDSAnd.htm) (snippet) |
| OsmAnd | A destinations list: add, drag to reorder, delete, clear; "Sort door-to-door" keeps start and end; follow-track mode allows no intermediate stops | "Minimal distance to recalculate route", user-set per profile; a separate "recalculate in case of reverse direction"; users raise it to about 120 m so a coffee stop does not trigger; no time component | Not documented | Offline routing built in | [settings](https://docs.osmand.net/docs/user/navigation/guidance/navigation-settings), [route](https://osmand.net/docs/user/navigation/setup/route-navigation) |
| Organic Maps, CoMaps | "Add Stop" on a place; the 2026 release puts a new stop after the existing stops and before the destination (Android), with a setting to optimise the order; users do not discover Add Stop (issue 7224) | Not found | See the insertion rule | Not found | [support](https://www.comaps.app/support/how-can-i-add-intermediate-route-points/), [release](https://alternativeto.net/news/2026/10/organic-maps-brings-improved-alternative-routes-intermittent-water-areas-and-more/) |
| Mapy.com | Waypoints in the planner; a new one goes to the **end** (a known complaint) | "Route recalculation when leaving the route" setting; users say 30 to 40 m (forum) | Not found | Offline route calculation (OSM wiki, possibly dated) | [help](https://help.mapy.com/route-planning/transportation/), [forum](https://community.mapy.com/post/7036) |
| Locus Map | Via points on routes | Automatic recalculation with a distance, default 100 m; if the new route is not followed it replans again after 30 s; or no automatic recalculation: a straight guide line to the **nearest** point of the original route; a "strict" mode forcing every point ("bothersome"); separate notify distance (example 75 m) and allowed deviation (example 30 m, larger in rough terrain); recalculation to the finish or back to the original route, the user's choice | Strict mode keeps pointing at a missed point; a via point behind a T-turn can be missed | The guide line to the nearest route point needs no data | [alerts](https://www.locusmap.app/how-to-set-alerts-for-leaving-your-navigation-route/), [settings](https://docs.locusmap.app/doku.php/manual:user_guide:functions:navigation:settings) (snippet) |
| komoot | Not found | Phone not found; watch: an off-course notice with the distance, rerouting only with connectivity; Wear OS (2026) automatic recalculation | Not found | No reroute offline; the route stays shown relative to the walker; leaving-route warnings stay | [review](https://www.dcrainmaker.com/2026/01/komoots-offline-routing.html) |
| Ride with GPS | Not found | An "Off Course" alert repeated every 2 min until dismissed; an X on the cue card mutes it for a deliberate detour; tapping the notice sends you to the closest point of the route; QuickNav replans to the destination | Not found | Online reroute only; offline, bearing and distance to the nearest route point | [voice](https://support.ridewithgps.com/hc/en-us/articles/4419572706971-Voice-Navigation), [QuickNav](https://support.ridewithgps.com/hc/en-us/articles/19796957110171-QuickNav) (403, snippet) |
| Gaia GPS | Not found | Watch: "Off Route" at more than 500 ft | Not found | Not found | [watch](https://help.gaiagps.com/hc/en-us/articles/360058372613-Gaia-GPS-on-Apple-Watch) (snippet) |
| AllTrails | Not found | A paid wrong-turn alert at 50 m off the route, re-armed after rejoining; alerts only, no reroute | Not found | Works from downloaded maps | [alerts](https://support.alltrails.com/hc/en-us/articles/37213407013908-Wrong-turn-alerts) (403, snippet) |
| onX Backcountry | Not found | Online: automatic reroute after a missed turn | Not found | Cannot make new directions; prompts a return to the original route; a navigation started online keeps guiding | [help](https://support.onxmaps.com/hc/en-us/articles/29874323859341) (403, snippet) |
| Garmin handhelds | Not found | An Off Course alert with an on/off and a distance; no default stated | "Off Road Transitions": Distance advances to the next route point once within a set distance; the same setting holds the recalculation preference | Guidance is local | [fenix](https://www8.garmin.com/manuals/webhelp/fenix5x/EN-US/GUID-D63C6C77-7AC0-4C5F-AA53-44A8C6B1EB9D.html), [eTrex](https://www8.garmin.com/manuals/webhelp/eTrex22x-32x/EN-US/GUID-A6877ED3-1731-4BA5-AFE5-2F2E37498D5E.html) |

CalTopo, Outdooractive and Sygic: nothing established; skipped. Mapbox's navigation SDK exposes a
minimum count of off-course fixes and a proactive interval, without defaults ([config](https://docs.mapbox.com/ios/navigation/api/2.6.0/Configuration.html)).

**Common practice.** Stops: 8 to 15 in the road apps, added by search or a place card, a drag handle
to reorder, an X or a swipe to delete, the order the user's (optimising is an opt-in in OsmAnd and
Organic Maps). Reroute: automatic to the destination in every road app, with a distance setting where
one is exposed (OsmAnd, Mapy, Locus 100 m); the hiking apps alert without replanning (AllTrails 50 m,
Gaia's watch 150 m, Garmin). Offline without data: keep the old line and show the bearing and distance
to it (Ride with GPS, onX, Locus's guide line). Mid-trip: Apple makes the new stop the next one;
Organic Maps puts it before the destination; Mapy's end-of-list default is a known complaint.
**Outliers.** Locus alone documents both reactions (replan to the finish or back to the line), a 30 s
re-replan and a strict every-point mode; Ride with GPS a 2 min repeat and a one-tap mute for a
deliberate detour. **Numbers stated anywhere:** Locus 100 m and 30 s, AllTrails 50 m, Gaia 500 ft,
Mapy about 30 to 40 m, Ride with GPS 2 min; no source states how a stop counts as passed except
Garmin's "within a set distance", and none a rate limit beyond Locus's 30 s.
**What is missing in Google Maps' list, against the owner's four wishes.** A mode per leg: no app has
it (one mode per route everywhere; OsmAnd's modes are per profile). Per-leg distance and time: Google
shows per-stop times (blogs), per-leg distance is not confirmed anywhere. Undo: no app. Passed stops
kept visible: no app; Garmin and Locus advance past the point and hide it. All four would be new.

## Options

The format question is the one the options differ on; the policies (reroute, passed stops, insertion,
Go, search) are the same under every option and are decided in the Recommendation.

### A. The plan of stops as a keyed side field (`stops`) beside the path

The route stays what it is: `path` holds the mode, the via points and the legs; a plan of stops adds one
field, `stops`, keyed to the path value it describes exactly as `directions` is (`RouteDirections.pathKey`,
FNV-1a 64 of the quantised path). Version 1 of the value: the path key, then per via point a flag byte
(bit 0: this via point is a stop, not a shaping point; bit 1: named) and an optional name, then per leg
an optional mode string (null: the path's mode). A route with `stops` whose key matches its `path` is a
plan: its via points flagged as stops are the list, each leg is planned in its own mode, and the plan's
geometry is recomputed from wherever the walker is. `stops` absent or stale: a frozen route, as today.
Builds before this one store and forward the field and show the trip as the frozen route it also is
(the path decodes), follow it and may edit it; their edit writes a new `path`, the key no longer
matches, and every replica alike shows a frozen route with plain via points (the stop names and per-leg
modes are lost, the geometry is not). The browser's Trips box does the same until it writes the field.
The unsaved route on the card (`SessionRoute`, `files/route-session.json`) gains the same list under an
optional key; the passed and skipped state of a trip under way lives there too, never in the synced
route. Costs: one codec with tests in `shared/.../model/`, the field in `EntityKind.ROUTE.fields` and
compaction (last writer wins, like `directions`), a `stops` column in the track database (schema 6,
filled from the log once on first open, as `directions` was at schema 5), a sync-sim fixture written by
v0.1.10-alpha that round-trips byte for byte and gains the field. Risks: two values that must agree (the
key makes disagreement harmless, not impossible to write); a plan edited on an older phone degrades
silently to a frozen route (the walker still has the line). Who else: no app syncs this; the pattern is
T108's own, proven on `directions`.
Exit cost: drop the field and its column; every path stays valid; nothing migrates.
Moving parts: one field codec, one schema step, the `fields` set, the session file key, the editor
planning each leg in its mode, the trip summary adding legs by their mode, the directions per leg.

### B. Path version 3: the plan inside the path value

`RoutePath.encode` writes version 3 when a route is a plan: a flag byte (plan), per via point flags and
name, per leg a mode string, appended after version 2's leg times. One value, one key, no stale-field
rule. Builds before this one cannot decode version 3 and show the route as the put's points in one fixed
leg (the Materializer's rule for a newer format, as T89's version 2 did to builds before it): that is
the geometry at creation, stale after every later edit of the plan, since edits are `SetField` writes
and the put is not rewritten. Worse, an edit on that older phone writes a version 1 path, which wins by
last writer and replaces the plan on every replica with the older phone's fixed leg; the newer build
cannot tell that write from a deliberate one. Cost as A less the keying, plus every path decoder (the
phone, `serverlib`, the browser's bundle check in `/route/bundle`, `devtool`). Risks: the older-phone
edit above; a plan is a version bump away from "unknown path version" for every reader that lags.
Exit cost: rewrite every version 3 path to version 2 on first open (a migration over every route) and
drop the per-leg modes.
Moving parts: the path codec on three sides, the migration, the fixture, plus A's editor, summary and
directions changes. A separate entity kind for plans (a `PLAN` with code 5 holding stops, the route
derived) has the same older-build problem and a second entity to join; folded in here, not pursued.

### C. A plan is session-only; Keep freezes it

The stops list lives only in `RouteSession` and its file; Keep writes the frozen route a T60 trip is
today (via points for the stops, one mode). No format change, no migration, nothing for an older build
to misread. Costs: the list, the editor and the reroute as in A, without the codec. Risks: the owner's
first example ("drive to the trailhead, hike to the summit, drive home") cannot be saved as a plan, so a
kept trip has one mode and its time is wrong for two of three legs; a phone restart mid-trip keeps the
plan only through the session file; a trip planned on one phone cannot be followed as a plan on another.
Exit cost: none; A can be added later, and every session plan is already a `RoutePath` plus a list.
Moving parts: the session file key, the editor, the summary and the directions changes of A.

## Recommendation

**A**, built after this ADR as one task (the list, the editor and the field) and one task (reroute,
passed and skipped stops, the Go rule, search), in that order. The keyed side field is the pattern this
project already proved for `directions` and the only one of the three that lets a phone one release
behind follow the same trip and edit it without destroying the plan everywhere; a plan survives an
older phone's edit as a frozen route, which is what ADR 0033 Decision 2 asks (the user's data survives,
degraded, never misread). C is the fallback if the critic finds the field unsound; B is rejected for the
older-phone edit alone.

The policies, each with the practice it follows and where it departs (the survey's numbers are above):

| Decision | Recommended | Follows, departs |
|---|---|---|
| The list | One list on the route card: each stop a row with a drag handle, an X, a name (search result, waypoint or coordinates), and under it the leg to it: mode icon, distance, time; the mode chips act on the selected leg (the whole plan when none is selected); the list and the card's total agree. Cap 25 stops (the list at font scale 2.0 scrolls; the path's 256 KiB cap bounds it anyway) | Google's and Apple's list; the per-leg line and the leg mode are ours (no app has them) |
| Adding | A search result's card: "Route to" first, then "Add stop" while a route is on the card; the long-press card gains "Add stop" in the same case; a point dragged on the line stays a shaping point (T60), not a stop | Google, Apple, Organic Maps (search first); Organic Maps' discoverability issue argues for the card, not a menu |
| Mid-trip insertion | The new stop becomes the **next** stop; the row offers "Move to end"; the per-leg line shows what it costs. No silent cheapest insertion; "Optimise order" is a later opt-in | Apple's rule; against Mapy's end-of-list default (a known complaint) and the owner's "cheapest insertion" alternative, which no app does silently |
| Undo | One level, a 10 s snackbar after delete, reorder, insert and skip; session-only, never synced | No app has it (the owner's wish) |
| Reroute trigger | T61's event unchanged (more than 50 m for 10 s) starts a replan from the fix to the next unpassed stop; the rest of the plan is kept; the new first leg replaces the old. When the shortest way is back onto the old line the replanned leg rejoins it and the line visibly stays, so "prefer guiding back when close" needs no second rule. One replan per 30 s at most, none while one runs, none from a fix over `maxAccuracyM`; an off-route event with a replan pending shows the rejoin hint meanwhile | Road apps replan automatically; the distance is between Mapy's 30 to 40 m and Locus's 100 m, and AllTrails's 50 m; the 30 s is Locus's; the target (next stop, not the destination) is what a list of stops implies |
| Frozen routes | Never replanned automatically: T61's alert and rejoin hint stay (the line is the intent). The off-route banner offers "Route to the end from here", which starts a plan with the route's end as its one stop; alerts keep T65's snooze | Locus's two modes, Ride with GPS's mute; the hiking apps alert only |
| A setting | Settings › Navigation: "Replan when off route" (on by default for plans; frozen routes are never replanned, so the row says so) | HERE, Mapy, OsmAnd, komoot expose it |
| Offline without graph data | A replan that fails off the network or unconnected keeps the old line, the rejoin hint and a one-line notice; a button on the banner draws a straight line to the next stop as a crow leg. Never a silent straight line | Ride with GPS, onX, Locus's guide line; departs from the idea file's "fall back to a straight line with a notice" by making the line one tap, not automatic |
| Passed | A stop is passed when a fix lands within 30 m of it (`arriveM`), or when progress along the line is more than 100 m beyond its projection with the fix on the line (walked past it on purpose). A passed stop stays in the list, greyed with a check, its leg drawn fainter; navigation does not pause at it (the next direction simply follows) | Garmin's "within a set distance" is the only stated rule; the visible passed stops are the owner's wish; Google pauses, we do not (a hiker does not stop at a lake to tap Continue) |
| Skipped | Skip on the next stop (and any later one): its row is struck through, the plan replans from the fix to the stop after it; undo restores it. Never automatic | Nobody documents a skip; "Resume Route" is Apple's detour end |
| Go away from the start | A plan: Go always plans from the fix (its origin is the fix by definition); a fix more than 50 m from the first point replans the first leg at once and says so in one line. A frozen route whose start is more than 50 m from the fix asks once: "Plan from your location" (the route's end as one stop) or "Follow anyway" (T61 as today) | T114's finding, item 3; no app states a rule |
| Stale fix | A plan planned from a fix older than 120 s (`RouteOrigin.STALE_S`) replans its first leg when a fresh fix lands more than 50 m from it, with the same one line; a frozen route is not touched | No app documents one (T114); the number is T61's off-route distance |
| Where plans run | The editor plans each leg in its mode through the same `Router` (local tiles, the server through `FallbackRouter` when an end is beyond every pack and the phone is online); the navigation session takes a replaced path without losing its events, fix count or passed stops | ADR 0028, 0029; no gate (ADR 0023) |

**Agent surface.** Debug routes: `POST /route/stops` (a JSON list, or `add`, `insert`, `move`, `remove`,
`skip`, `mode` per leg), `GET /route` listing the stops with each leg's mode, distance, time and state;
`POST /nav/replan` forcing one; `/nav/status` with `stops` (passed, skipped, next) and `reroutes`
(count, the last trigger, the last one the rate limit held back); the search card's actions through the
existing search routes. On the JVM, `devtool nav replay --stops <lat,lon;...> --reroute` over a pack
(the Rainier pack with the Skyline off-route fixture), reporting every replan, every passed stop and the
final line, so the policy is tested without a device; `sync-sim` round-trips a bundle with `stops`.

**Bushwhacking's version step.** None is needed, for either change. A mode is a string in the path, so
`bushwhack` rides under T108's rule (a build from v0.1.8 shows it, follows it and refuses to edit it);
an off-trail stretch inside a leg is geometry like any other (a `TRAIL` leg's points), and its
directions need a new `Maneuver` (leave the trail, rejoin it) which an older build's `RouteDirections.decode`
refuses, so the whole directions value counts as absent there and the trip is still followed; anything
the cost model wants to keep per leg (the steepest slope, whether it crosses the slope-risk window) is a
keyed side field like `stops`. The two land independently with no path version. Only if the bushwhack
proposal needs a new `LegKind` (a leg drawn differently from trail and straight) is a path version
unavoidable; then it rides with the build of this proposal, which reserves the code, and the critic of
that proposal checks that a dash pattern per leg is not enough.

**Positions that are the owner's (ADR 0031: built from this text, shown in the PR, adjusted after):**
the list's layout and controls; the insertion default (next, with Move to end) and whether cheapest
insertion is offered at all; the undo's form and length; passed stops greyed and kept, skip as a row
action; automatic replanning for plans and never for frozen routes, and the setting's row; the offline
fallback (keep the line, a one-tap straight line) against the idea file's automatic straight line; the
Go dialogue and its two choices; the search card's actions and their order; the cap of 25; every string.
The coordinator's after the critic: the field and its keying, the schema step, the trigger numbers kept
from T61, the 30 s rate limit, the passed rule's 30 m and 100 m.

**First step.** The field codec and its fixture test in `shared`, then the list on the card with
`POST /route/stops` and `GET /route`, with the editor planning per leg; reroute follows in its own task.

## What would change this recommendation

- The sync simulator shows a common two-phone sequence in which the keyed field loses a plan both
  phones still think they have (then B's single value, with a refusal rule for a path write from an
  older build, or C until the browser writes the field too).
- The owner wants plans edited in the browser's Trips box in the same wave (then the server and
  `trips.js` write `stops` in the first task, not later).
- A cold replan on the phone measures over 5 s for a drive leg (then the replan runs asynchronously
  with a "Replanning..." state and the rate limit counts from its end; the design holds, the task grows).
- The per-leg line does not fit the list at font scale 2.0 with 25 stops (then a lower cap or a
  collapsed row).
- The owner prefers Google's pause at a stop to our continue (a one-line change in the passed rule).
- The bushwhack proposal needs a new `LegKind` (then the path version rides with this build, above).

## Claims to verify

- A route field a build does not know is stored, forwarded and never materialised: `EntityKind.fields`
  in `shared/.../sync/Ops.kt`, the skip in `Materializer` and `SyncReplica`, T108's codec test.
- The keyed pattern: `RouteDirections.pathKey` and `forPath`, `Materializer.routePlan` taking
  `directions` only for the current path; a `stops` value can reuse the key and the rule unchanged.
- An unknown path version makes the route the put's points as one fixed leg, and the put is not
  rewritten by later edits: `Materializer.routePlan`, `RoutePath.decode`, T89's note in `RoutePath.encode`.
- A mode a build does not know is shown, followed and refused for editing from v0.1.8: `TravelMode.labelOf`,
  `TravelMode.unknownModeMessage`, `RouteEditor`'s refusal, the T108 test.
- An unknown `Maneuver` code makes the whole `directions` value absent, not a crash: `RouteDirections.decode`
  throws, `decodeOrNull` returns null.
- `Navigator`'s rules as stated: 50 m for 10 s, 30 m back, 30 m arrival after half the line, 15 m
  waypoint reach along the line (`NavOptions`, `WAYPOINT_REACHED_M`).
- `RouteEditor` reroutes only the legs next to a change and snaps per mode (`snap(lat, lon, mode)`), so
  a per-leg mode needs the leg's mode passed to `Router.route` and nothing else in the search.
- `RouteSummary` adds a drive leg's router time (`RouteLeg.timeS`) and models the others from the path's
  one mode; per-leg modes need it to model each leg by its own `CostModel`.
- The track database's route table gained `directions` at schema 5 (`4.sqm`), filled from the log once
  (`.claude/rules/trackdb.md` for the steps a `stops` column follows).
- A phone's plan takes about 2 s including the first tile load (PLANNING › Routing, T76), so the 30 s
  limit is far above the plan time and the asynchronous case above is the drive overlay's, not a hike's.
- The survey's load-bearing facts: Apple inserts a mid-trip stop as the next one (Apple's guide,
  snippet); Locus's 100 m default and 30 s re-replan (Locus's blog); AllTrails's 50 m; Google's cap of 9
  including the destination (Google's help). The 403 pages were read as snippets; the critic may re-read.

## Critic's objections and responses

(none yet)
