---
date: 2026-10-30
kind: feature
---

# Water sources along the route, with a flow class from the DEM

T165's flow accumulation (`shared/.../dem/FlowGrid.kt`, the `devtool drainage-pack` layer of T174)
already knows where water collects. Mark where a planned route crosses a stream, with a class from
the accumulated catchment ("stream in 1.2 km, likely flowing" against "gully, dry most of the
year"), on the route card and in the spoken directions, and list the crossings on the trip so a
hiker plans refills. Everything is offline and on the phone already; the drainage route crossings
were deferred by name at T165's close. Open: a seasonal confidence needs a climate source, deferred
with T165; whether a crossing of a mapped OSM waterway and a DEM-derived line are shown as one.
Recorded at the close of wave 22 from the coordinator's list; the owner asked for all of them.

Plan: one task, sharing no files with the position-button and readout-bar tasks. What exists: T165's lines and classes
(`dem/Drainage.kt` `StreamClass`) reach the phone only as the `drainage` source layer that `devtool drainage-pack` writes into an
outdoors pack (`tilebuild/PROFILE.md`: `class`, `catchment_km2`). The server's build path does not write it yet (T174's follow-up
on the board), so most phone packs carry only `waterway` (`class`, `intermittent`, `name`, `brunnel`) and the `outdoor_poi`
springs (z13+) and drinking water (z14). New in `shared/.../routing/WaterAlong.kt` (pure, unit-tested): from a route line and a
tile source, read the pack's last-zoom tiles under the line through `search/LayerFilter` and `mvt/MvtDecoder`, as
`RoutingGraphBuilder` does. Return each crossing of a `waterway` or `drainage` line, and the springs and drinking water within
100 m of the line, with `alongM`, a name and a class:
- mapped running: `river`, or `stream` without `intermittent`
- mapped seasonal: `intermittent=1`
- otherwise the DEM class with its catchment.
A DEM crossing within 30 m along the route of a mapped one is the same water: one item, the OSM name with the DEM catchment. A
crossing on a `brunnel=bridge` is marked as such (water below, maybe not reachable). Words keep the layer's own hedge ("likely
running", "seasonal", "likely dry"). Devtool: `water --gpx <route> --pack <outdoors pack>` prints the list as JSON and draws the route with
numbered crossings to a PNG. Tests: a synthetic tile with crossing lines in `commonTest`, and runs on two Rainier routes against
the pack with and without a `drainage-pack` copy. Phone: computed when a route is planned or navigation starts (`RoutePlanner`
beside `localJunctions`). It is never stored in the synced `path` or `directions`, so there is no format change and older phones are unaffected.
Shown as one route card line ("Water: 3 crossings, next likely running in 1.2 km") that opens the list, and on the trip card for a saved trip.
Navigating, the next crossing appears on the nav card, and one line per running or seasonal crossing is spoken at its lead
distance through a `NavSpeech.sayWater` modelled on `sayTurnAround` (same setting and mute, its own index in
`GET /nav/speech`). `GET /route` and `GET /nav` gain `water`. Settings: none (ADR 0044: one behaviour, read from a list; the spoken
line follows `navSpeech`) unless the survey (ADR 0039: how hiking apps show water along a route) finds apps split; the report
says where the build departs. Out of scope: a seasonal or climate confidence (deferred with T165); the server writing the layer
(the report counts how many more crossings the layer adds on Rainier, which sizes that follow-up); the browser viewer.
