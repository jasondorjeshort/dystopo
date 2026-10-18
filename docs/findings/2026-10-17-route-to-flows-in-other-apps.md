---
date: 2026-10-17
status: open
area: android / routing / interface
---

# Map apps route from the phone's position to a picked point; most outdoor apps only draw a straight line to it

## Question

Dystopo's long-press card offers "Waypoint here", "Route from here" and "Cancel"; "Route from here" makes
the pressed point the origin and waits for a second long-press (`LongPressMenu`, `RouteSession`). Nothing
routes from where the user stands. The owner asked that the next task be "consistent with other tools like
google maps or waze", and noted that "many backcountry apps do not have routing but we have the map data so
we should include it even for walking" (idea `../ideas/2026-10-13-route-waypoints-and-reroute.md`). How
do established map and outdoor apps let a user route from their position to a point they pick?

## What was tried

2026-10-17: public help centres, manuals, release notes and tech press through WebSearch ("standard" first,
"extended" for the outdoor apps) and WebFetch, plus Organic Maps' English UI strings from its public
repository. Nothing was installed or tested. Read directly: Google Maps, Waze, HERE, Magic Earth, OsmAnd,
Mapy.com, Locus, Outdooractive, CalTopo, Gaia's blog, Garmin's web help. Search snippets only (403): komoot,
AllTrails, Ride with GPS, Gaia's help. Apple's iPhone guide served only its table of contents, so Apple
rests on tech press. Thin: Garmin Explore's phone app, BackCountry Navigator, and long-press routing in Waze,
HERE and Magic Earth. "Observed" means well-known behaviour the pages read do not state.

## Result

| App | Destination affordance | Origin default | Two-point planning | Stops | Walking routing | Off route |
|---|---|---|---|---|---|---|
| Google Maps | Touch and hold drops a red pin, or tap a place or search result, then "Directions". | "Your location"; tap it to change. | Change the origin; the route can then only be previewed ("Preview" replaces "Start"). | Up to 9 with the destination ("Edit stops"), drag to reorder. | Walking mode with an elevation chart (press report); walking directions are unavailable offline. | Reroutes (observed). |
| Apple Maps | Touch and hold drops a pin; its card has "Directions". | "My Location"; tap it to change. | Change the origin on the route screen. | Driving only, up to 15, drag handle to reorder, swipe to delete. | Walking shows the climb; iOS 18 custom hiking routes keep to trails (US); offline turn-by-turn since iOS 17. | Reroutes (observed). |
| Waze | Search, then "View routes" and "Go now"; a held pin only saves, previews or finds parking. | Current position. | Only "Plan a drive" (for later), where "Your location" can be replaced. | One stop per route, added while driving; only the first can be removed. | None: driving only. | Reroutes (observed). |
| HERE WeGo | Search a place, then "Directions"; long-press routing not documented. | "Your location"; tap to choose another. | Change the origin field. | Three-lines icon adds and arranges stops; "Add stop" while navigating. | Pedestrian mode, offline maps. | "Automatically rerouting" is a route preference. |
| Magic Earth | Search or map; long-press not documented. | "My Position" as the first stop. | Any list of stops; without "My Position" first the button reads "Demo", not "Navigate". | Multi-stop list with drag handles; saved routes keep the order. | Pedestrian; elevation-aware hiking is a Premium feature. | Not documented. |
| OsmAnd | Long tap opens the context menu; "Directions" to or from the point. | "My Position"; "Select on map", Favorites or Search to change. | "Directions from" a point; "Start navigation" discards the From point; a separate "Plan a route" tool. | "Add", "=" to reorder, "x" to remove, "Sort door-to-door"; the widget moves on after a passed point. | Pedestrian and hiking over OSM paths, offline; details show altitude range, slope, an elevation graph. | Recalculates past a set distance (setting). |
| Organic Maps | Tap or long tap a place, then "Route to". | "Your Location". | "Route from" plus "Route to"; "Navigation is only available from your current location", with an offer to replan from it. | "Add Stop", "Remove Stop", "Replace Stop", optional route optimisation. | Walking and cycling, offline; elevation profile with ascent, descent, difficulty. | Not documented. |
| Mapy.com | A point's "Route" button, or long-press and "Directions". | Start set to "My Location". | Change the start ("Choose a point on the map" gives "Position on map"). | Any number, by "+" or by dragging the line. | Foot modes Fast, Outdoor, Running, Sport stroller; profile with ascent, descent, min and max; plans offline. | "Recalculation of route when it is left" (setting). |
| Locus Map | Long-press, then the navigation button's "Navigate to". | GPS position (point-to-point navigation). | Route planner: tap to add points, straight lines by default or a routing profile. | Drag to reorder, delete in the itinerary, undo. | Foot profiles, offline router; the bar shows length, time, gain and loss. | A choice: guide line back, recalculate to the route, recalculate to the next point, or notify. |
| komoot | Planner: tap a map point, search, or a Highlight; "Set as destination". | Current location is one of the choices. | The planner always takes a start and a destination. | Add by tap or search, drag the line, reverse. | Sport types; tour shows distance, duration, elevation, way types, surfaces, profile. | "Automatic rerouting" toggle. |
| Ride with GPS | QuickNav: press and hold the map. | Where you are. | Route planner: "Current Location" or another start, then "Route to Here". | Planner points (not detailed). | Bike-oriented; walking not documented. | QuickNav replans; route navigation chimes "Off Course" every 2 min, tap to reroute (needs data). |
| Gaia GPS | A waypoint's "More", then "Guide Me". | Current location. | Route builder: Snap to Trail, Snap to Road, Straight Line. | Route points. | Turn-by-turn only on saved routes (offline); to a waypoint a straight line; "Driving Directions" hands off to another app. | Not documented. |
| CalTopo | Long press, then "Navigate To". | Current location. | Lines drawn with snap-to on trails. | Not applicable. | A straight line to the point, or follow a line forward or reverse. | Not documented. |
| AllTrails | A trail page; its "Directions" hands off to a road app for the trailhead. | Not applicable. | Custom routes snap to OSM paths (mobile editing in Peak). | Not documented. | Navigate follows a trail or custom route; turn-by-turn on eligible trails (paid). | Wrong-turn alert after about 50 m; no reroute. |
| onX Backcountry | A waypoint's "Go-To Waypoint". | Current location. | Route Builder: Snap To, or Point for off-trail. | Not documented. | A straight line to the waypoint; routes show distance, gain and loss. | Not documented. |
| BackCountry Navigator | A waypoint's GOTO. | Current location. | Draw a track or import GPX. | Not documented. | No routing documented. | Not documented. |
| Avenza Maps | A placemark's Navigation button. | Current location. | Draw and measure only. | Not applicable. | Bearing and distance only. | Not documented. |
| Garmin Explore | A waypoint's "Navigate" (an orange line). | Current location. | Routes from points; snapping only per forum posts. | Route points. | Phone app not documented. | Not documented. |
| Outdooractive | Planner: points on the map; long-press adds one. | "Use your current location" is an option. | The planner; "Follow paths" by default, "Connect points directly" per segment. | Drag in the list, "Delete". | Activity sets trail types and speed; distance, duration, gain, difficulty, profile; planning needs internet. | Not documented. |

### What is conventional

- **The picked point is the destination.** A long-press pin, a tapped place or a search result gets one routing
  action, first on its card: "Directions" (Google, Apple, HERE, Mapy), "Route to" (Organic Maps), "Navigate to"
  (Locus, CalTopo), QuickNav's press and hold (Ride with GPS).
- **The origin defaults to the device and says so** ("Your location", "My Location", "My Position") in a field at
  the top of the route screen; tapping it picks another origin by search or on the map.
- **Planning between two picked points is the same screen with the origin changed**, not a separate mode, in
  every road app; only OsmAnd and Organic Maps also put "Route from" or "Directions from" on the point's card.
- **Turn-by-turn starts only from the fix.** Elsewhere the route is a preview: Google's "Preview", Magic Earth's
  "Demo", Organic Maps' "Navigation is only available from your current location" with an offer to replan,
  OsmAnd's "Start navigation" dropping the From point.
- **Stops come after the route exists** ("Add stop"), in a list with drag handles and a remove control; counts are
  capped (Waze 1, Google 9, Apple 15). Only OsmAnd documents what happens to a passed stop.
- **Road apps reroute automatically**; HERE, Mapy, OsmAnd and komoot expose it as a setting.

### Where apps differ

- **"Route from" on the card.** Google, Apple and HERE offer only the destination action; OsmAnd and Organic Maps
  offer both. A hiking app plans trips that start at a trailhead, often days ahead, so both belong, the
  destination action first (Organic Maps' order).
- **Off route.** Replan to the destination (road apps, QuickNav, Locus "point priority"), lead back to the line
  (Locus "route priority" or its guide line), or alert only (AllTrails, Ride with GPS without data). Replanning
  to the destination fits a route-to; leading back fits a kept route followed on purpose.
- **The planner.** Road apps have one route screen; komoot, Outdooractive, Locus, Gaia, onX and AllTrails have a
  separate planner where points are tapped on the map and each segment snaps to trails, with a straight segment
  as the fallback ("Connect points directly", Point, Straight Line). Dystopo's crow mode is that fallback.
- **The route card.** Road apps show time and distance (Google and Apple add the climb for walking); outdoor
  planners add ascent, descent, an elevation profile, difficulty and way types (Mapy, komoot, Outdooractive, OsmAnd).

### The outdoor apps are the exception, not the road apps

Gaia GPS, CalTopo, onX, Avenza, BackCountry Navigator and Garmin Explore take the user to a point only in a
straight line with bearing and distance ("Guide Me", "Navigate To", "Go-To Waypoint", GOTO). Trail routing
exists only in their route builders, and the route must be built and saved before guidance. AllTrails cannot
route to an arbitrary point at all, and Gaia and AllTrails hand the drive to the trailhead to another app. Only
the OSM general apps (OsmAnd, Organic Maps, Mapy, Locus) and Ride with GPS (bike) route from the fix to a picked
point along paths in one step. This is not a gap to fill with something new: every road navigation app does
exactly this, almost all in the same shape (owner, 2026-10-17); Dystopo does the standard thing, for walking too.

### No fix, offline, no data

No fix: Organic Maps says "Your location hasn't been determined yet" or "Please enable Location Services";
Google only previews; no app documents a stale-fix rule. Offline: OsmAnd, Organic Maps, Mapy, Locus and Apple
route walks; Google does not, Outdooractive cannot plan, Ride with GPS cannot reroute. No route: Organic Maps
says "Unable to calculate route" (routing options or incomplete OSM data); Gaia falls back to a straight line.

### Recommendation for Dystopo

1. **Long-press card, in this order: "Route to here", "Waypoint here", "Route from here", "Cancel".** "Route to
   here" plans at once from the phone's position to the pressed point in the card's last mode (hike by default):
   the destination-first action of Google, Apple, Mapy and Organic Maps, and the one-step trail route the
   outdoor apps leave out. "Waypoint here" keeps its place near the front (T109: it was hard to find). "Route from
   here" keeps today's two long-presses unchanged, as Organic Maps' "Route from" and OsmAnd's "Directions from" do.
2. **The route card names both ends**: "From: Your location" and "To: 46.85290, -121.76030" (a name when there
   is one). Tapping From offers "Your location" and "Choose on map" (the next long-press sets it), plus "Swap";
   Google, HERE and Waze use "Your location", Organic Maps "Choose on map" and "Change start".
3. **"Go" on a route that does not start at the fix**: if the fix is within the off-route distance of the route,
   follow it as T61 does; otherwise ask, with "Plan from your location" (same end) and "Follow anyway" (join
   it later, as when driving to a trailhead). Organic Maps offers the first; Ride with GPS offers the start,
   the nearest point or off course.
4. **No fix**: with location off, the card says so and offers to turn it on (Organic Maps). With no fix yet,
   the route waits ("Waiting for your location...", the current `start` phase) and plans on the first fix;
   "Route from here" still works meanwhile. **Stale fix** (no app documents one): plan from a last fix younger
   than about 2 min; an older one plans with its age shown ("Your location, 14 min ago") and replans once a
   fresh fix lands far from it. The task sets the numbers.
5. **No route**: keep today's messages (no trail data in this pack, network still building); when no path is
   found, say so and offer Crow with its notice (Gaia, Outdooractive and the idea file use a straight fallback).
6. **Walking card**: keep distance, ascent and descent, time, the trails it follows, directions and the mode
   chips; add the From and To line and an elevation profile on tap (Mapy, komoot, Outdooractive, OsmAnd,
   Organic Maps); put "Go" first in the action row (Google's "Start", Mapy's "Navigate", Waze's "Go now").

## Next

- The task decides the four labels and their order, the From and To line with "Choose on map" and Swap, the
  "Go" rule for a route away from the fix, the no-fix wait and the stale-fix numbers, and whether a search
  result gets the same "Route to here" (Google, Apple and Organic Maps route from search results alike).
- Open, for a later task: stops (convention: "Add stop" once a route exists, a list with drag handles and
  remove, a cap; the passed-stop rule is undocumented except in OsmAnd) and the reroute policy (replan to the
  destination for a route-to, lead back for a kept route, as Locus separates them; T61's 50 m for 10 s stays
  the trigger until then). The elevation profile may be its own task.

Sources, one line per app:
- Google [directions](https://support.google.com/maps/answer/144339), [pin](https://support.google.com/maps/answer/18539), [offline](https://support.google.com/maps/answer/6291838), [Preview](https://www.bgr.com/2208592/why-google-maps-says-preview-instead-of-start-how-to-fix/), [elevation](https://gearjunkie.com/technology/google-maps-elevation-walking-biking-directions)
- Apple [start](https://iphonelife.com/node/265016), [stops](https://appleinsider.com/articles/22/09/09/how-to-use-apple-maps-multi-stop-to-plan-routes-in-ios-16), [custom routes](https://tomsguide.com/phones/iphones/apple-maps-ios-18-lets-you-create-custom-routes-heres-how-to-do-it), [offline](https://www.techradar.com/news/apple-maps-will-finally-get-this-useful-google-maps-feature-in-ios-17)
- Waze [stops](https://support.google.com/waze/answer/6262564), [plan a drive](https://support.google.com/waze/answer/6262569), [pin](https://support.google.com/waze/answer/6262577); HERE [FAQ](https://help.here.com/faq/routing_and_navigation); Magic Earth [FAQ](https://www.magicearth.com/faq/en), [plans](https://www.magicearth.com/pricing)
- OsmAnd [route](https://osmand.net/docs/user/navigation/setup/route-navigation), [context menu](https://osmand.net/docs/user/map/map-context-menu), [details](https://osmand.net/docs/user/navigation/setup/route-details), [settings](https://osmand.net/docs/user/navigation/guidance/navigation-settings/)
- Organic Maps [strings](https://github.com/organicmaps/organicmaps/blob/master/android/app/src/main/res/values/strings.xml), [stops](https://github.com/orgs/organicmaps/discussions/4178)
- Mapy [planning](https://help.mapy.com/route-planning/transportation/), [navigation](https://help.mapy.com/navigation/), [start point](https://community.mapy.com/topic/1435/automatic-setting-start-point./1), [settings](https://help.mapy.com/settings/navigation/)
- Locus [navigate to](https://docs.locusmap.app/doku.php/manual:user_guide:functions:navigation:point-to-point), [navigation](https://docs.locusmap.app/doku.php/manual:user_guide:functions:navigation), [planner](https://docs.locusmap.app/doku.php/manual:user_guide:tracks:planning)
- komoot [app](https://support.komoot.com/hc/en-us/articles/10206792140826-Plan-routes-in-the-app), [web](https://support.komoot.com/hc/en-us/articles/10194270667034-Plan-routes-on-the-website), [rerouting](https://heise.de/-9854284)
- Ride with GPS [QuickNav](https://support.ridewithgps.com/hc/en-us/articles/19796957110171-QuickNav), [voice navigation](https://support.ridewithgps.com/hc/en-us/articles/4419572706971-Voice-Navigation), [planner](https://support.ridewithgps.com/hc/en-us/articles/4415456909339-Mobile-Route-Planner)
- Gaia [Guide Me](https://help.gaiagps.com/hc/en-us/articles/4409644299799-Guide-Me-Turn-by-Turn-Directions-for-Driving-Cycling-and-Hiking-Routes-in-the-iOS-App), [routes](https://help.gaiagps.com/hc/en-us/articles/115003523607-Create-Edit-and-Measure-Routes-in-the-iOS-App), [turn-by-turn](https://blog.gaiagps.com/turn-by-turn-directions/); CalTopo [navigation](https://training.caltopo.com/all_users/mobile/navigation), [lines](https://training.caltopo.com/all_users/objects/lines-and-polys)
- AllTrails [wrong turns](https://support.alltrails.com/hc/en-us/articles/37213407013908-Wrong-turn-alerts), [turn-by-turn](https://support.alltrails.com/hc/en-us/articles/50085350563476-Turn-by-turn-directions), [custom routes](https://support.alltrails.com/hc/en-us/articles/37270479773204-How-to-create-custom-routes)
- onX [Go-To](https://onxbackcountry.zendesk.com/hc/en-us/articles/360051748372-Using-the-Go-To-Waypoint-feature), [snap](https://onxbackcountry.zendesk.com/hc/en-us/articles/22965175493133-Building-Routes-that-snap-to-road-and-trails); BackCountry Navigator [help](https://crittermap.zendesk.com/hc/en-us/articles/215563763-Navigation-Waypoints-and-Tracks); Avenza [bearing](https://support.avenzamaps.com/hc/en-us/articles/4961374587924-Bearing-Compass-Heading-and-Course)
- Garmin Explore [waypoint](https://www8.garmin.com/manuals/webhelp/explorewebsiteInd/EN-US/GUID-7EEFBE7D-BCA6-4DD6-A49A-688A1E112DF8.html), [forum](https://forums.garmin.com/apps-software/mobile-apps-web/f/garmin-explore-ios/152744/how-to-create-a-route-that-follows-along-streets-and-trails-on-map); Outdooractive [planner](https://www.outdooractive.com/en/knowledgepage/how-does-the-route-planner-work-/50992312/)
