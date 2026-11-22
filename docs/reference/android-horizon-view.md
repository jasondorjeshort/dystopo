---
verified: 2026-11-22
source: observed on an API 35 emulator at Paradise in the Rainier pack (T307, T308, T312 with T311's replay, T317 at the summit); moved from android-app.md in T308
---

# The Horizon view

The map view's counterpart (the owner, 2026-11-19): the sky around the phone on one square plot, with layers turned on
and off by toggle buttons. Code: `app/horizon/` (the screen, the peaks and the sun, the routes), `app/gnss/` (the
satellites layer, and the plot itself in `SkyPlot.kt`), `shared/.../horizon/` (the layers, the sun's path, the rim's
peaks). Debug routes: `android-debug-routes.md` (Horizon view, GPS view). The "Map | Horizon" switch is T309's, in one
place in every view since T315 (below).

## The switch (T309, T315)

The owner, 2026-11-20: "Yeah, I want this as a real button." A two-segment "Map | Horizon" control (`app/controls/ViewSwitch.kt`;
the survey in `docs/ideas/2026-11-19-the-horizon-view-beside-the-map-view.md` put it at the bottom). T315, the owner: the
toggle "in the same place in every view". One anchor, defined in `ViewSwitch.kt`: the window's bottom-left corner, 8 dp in
from the left inset and 8 dp up from the bottom inset (the system bars and the display cutout), each segment at least 48 dp
tall, its text at map chrome's capped font scale (`MapChrome`), the segments in the order `ViewSwitch.SEGMENTS`. T326:
three segments, "Map | Horizon | Panorama" (`SkyView`; the Panorama view is `android-panorama-view.md`); the anchor grew
to the right, 249 dp at font scale 1.0 and 294 dp at 2.0 (capped at 1.3). The same rectangle on the map (Map selected),
on the Horizon view and on the Panorama view, whatever cards are up, in both orientations and at every font scale.

- **On the map** the switch is not on the layout rule's ladder and never hides: the rule reserves its rectangle in its
  frame (`OverlayLayout.switchRect`, `horizonSwitch` in `GET /layout`), the credit line stands beside it at its right, and
  the scale bar, the readout strip and the position buttons stand above it. T326 (wave 45's choice (h)): where the
  credit's text does not fit whole beside the three segments at its two lines (three with the Flights layer's notice),
  measured in `about/AttributionLine.kt` (`OverlayLayout.creditRoomBeside`, `AttributionInsets.above`), the credit line
  stands above the switch across the window with as many lines as its text needs, and the lower controls above it
  (`OverlayInput.creditAbove`); it is never cut short for the switch. With the Rainier pack on a 411 dp phone it stands
  above at font scale 1.0 (two lines, three with the flights' notice) and at 2.0 (five lines with the notice). The cards and panels drawn by their own code
  (the route card, the trip card, the update offer, the flights card, the track panels, the first-run card) stand on
  `AttributionInsets.bottom`, the higher of the credit line and the switch (`ViewSwitch.floorDp`). T309's ladder rung and
  T310's `firstRunCard` hiding are gone; so is the switch's height in the route card's cap (`BottomPanels.mapRowDp`), which
  hid it at font scale 2.0 when the route was planned before the font was raised.
- **On the Horizon view** the same anchored control is drawn over the content. In portrait the scroll area runs under it
  and ends with the switch's height and 8 dp more of room, so the last row clears it at the end of the scroll; in landscape
  the square plot ends 8 dp above it (its side is the height less that room) and the right pane is clear of it. A flight's
  card in portrait stands above it. `GET /horizon` `drawn`: `switchPx` (in the Compose root, as `GET /layout`'s), and
  `paneOverSwitch`, `switchRoomDp`.

"Horizon" opens the view, "Map" closes it, as Back does once it has let go of a pick and a chosen point (T325: the view has no Close button; the owner, 2026-11-21: "it should just go away"). T321 (the owner, 2026-11-21: "If we select a different
location on the map and switch to horizon view we want the horizon from THAT location, not ours"): "Horizon" opens the view
at the map's selected place when there is one (`ViewSwitch.openAtSelection`, by `HorizonPlace.switchFrom`, one rule a
Panorama segment calls too): an open long-press card first (as its "Sky from here": on a named feature within a fingertip,
at the feature and named; the card closes), else a search result's marker while it is on the map (named; the marker
stays), else the fix. A peak picked inside the view is not the map's selection. T326: "Panorama" from the map does the
same; between Horizon and Panorama the view changes and keeps its place and a picked peak (`HorizonPlace.switchAction`).
Each segment is a tab (`selectable`, `Role.Tab`), tags `horizonSwitch.map`, `horizonSwitch.horizon` and
`horizonSwitch.panorama`; the group's label "Map, horizon or panorama view". Debug:
`POST /horizon/switch?to=map|horizon|panorama` answers both rectangles (`map`, `view`), `showing` and `viewPx`. No compass shortcut: MapLibre's compass shows only while the map is turned, and its
tap resets north. Every map frame of a sweep made before T315 differs by it (the switch, the credit line and every lower
control moved).

## The screen (T308)

The switch or Settings › Navigation and tracking › "Horizon view ›" (the page's last two rows: the link, then "Sky plot";
the row's id is still `gnssStatus`, "GPS status" before T308) opens it over the map, day or night as the map style is (the style's
paper, ink and terrain tokens, through `NightColors` at night; light status-bar icons at night). The plot is a square
`min(width, height)` of the window inside the system bars, fixed at the top in portrait (411 dp on a Pixel 6 AVD) and
at the left in landscape (363 dp); the rest is a heading that stays ("Horizon view" and its help; T325: no Close; a picked peak's
line under it) over an area that scrolls: "Show:" with the toggles (Satellites, Peaks, Sun, and T312's Flights while Flight tracking is on; a tick and a filled
button when on), "Sky plot:" (Follow the phone, North up), the terrain line and the band's legend, then the Peaks, Sun
and Satellites sections in that order (the satellites' list is the long one, so it comes last). Back lets go of the
menu, the confirmation, a picked flight, a picked peak and a chosen point, then closes. T325: the pane is built of parts a second
sky view hosts around its own plot (`app/horizon/SkyViewParts.kt`, `SkyViewSections.kt`, and the effects in one call,
`SkyViewEffects.kt`); `HorizonUi` stays the one open state. The plot keeps T307's rim (the horizon), rings at 30 and 60
degrees, N E S W with north marked, and the land's skyline as a band; it turns with the compass in "Follow the phone"
(`Heading.setGnssWanted`, held while the view is open and started).

Each toggle is a synced switch (`horizonSatellites`, `horizonPeaks`, `horizonSun`, `app.` in the sync log; on by
default; no settings row: the buttons set them through `SettingsRoutes.apply`). With Satellites off the GNSS is not
held (`GET /horizon` `gnss.held` false, `engine` off) and the view follows the map's own fix (`Gnss.position`): the
skyline, the peaks and the sun move after `POST /location`. With every layer off the plot shows the skyline alone.

**The terrain's horizon** is T307's: `Horizon.sweep` at the position (1 degree bearings, eye height 1.7 m, 30 km) over
`ShadeService.pyramidAt` (the offline map, else the online DEM), on a thread of its own, kept with the terrain it read
until the position moves 100 m. At Paradise it took 0.6 s on a settled emulator and 123 s on one just booted (T307 saw
129 s once).

**Peaks** (`HorizonData.peaksFor`): the named peaks of the active pack's `mountain_peak` layer within the profile's
range (`Viewshed.readPeaks`, as `ViewshedSession` reads them) tested on the same terrain and profile
(`Viewshed.peaks(terrain, horizon, peaks)`, the overload on a `HorizonProfile`: no viewshed sweep), once per horizon on
a thread of its own. T309 (the owner, 2026-11-20: "if there's a MUCH closer peak just barely out of sight we definitely
want to show it"): the **candidates** are the peaks in sight or just out of sight, hidden by no more than 60 m of height
(`PeakRim.HIDDEN_M`; a height, not an angle). The list has them all, closest first (the name, bold when the plot names
it; height, distance, compass point and bearing; "just out of sight" on a hidden one). The plot's names come from a
ranking and a placement meant for later layers too (`shared/.../horizon/HorizonLabels.kt`, every weight a first guess to
tune against real rims): the score `4.0 Dn + 2.5 Hn + 1.5 An + 1.0 Pn + 0.5 En` (distance `1 / (1 + d / 2 km)`, the
clearance over 1.5° (0.5 just out of sight), the apparent angle over 6°, the prominence on a log scale to 1000 m (the elevation over the median of
the candidates within 5 km), the elevation in the candidates' range), +10 for the highest skyline peak (always named),
×0.9 out of sight (the closest candidate also always named, T309's fix round for Alta Vista), × the kind's weight; then, highest first, each name at the nearest of the places around its triangle (four steps
inward along the radius, and beside it either side along the rim) where its box touches no name, leader line, ring
label, hour label or the sun's arc, and only where no name already placed
stands within the density gap in bearing (`4° × sqrt(nLocal / 5)`, at least 2°, nLocal the candidates within ±15°). A name set more than 10 dp from its triangle gets a thin leader line to it (the ink at reduced alpha, 1 dp), which
crosses no other name. A triangle is solid in sight and hollow just out of sight, at the peak's own apparent angle;
names are drawn over the satellites on a plate of the sky's colour (T308's stroke halo showed as a ghost "M" where
"Mount Rainier" crossed the skyline band's edge). A tap on a name or a row picks the peak: its line ("Little Tahoma, 11138 ft, 4.5 mi, NNE 14°")
under the heading and its name in the link colour; a second tap, the line's × or Back lets it go. At Paradise (T308): 149
peaks read from 47 tiles in 0.6 s, 20 on the skyline, 6 named under T308's rule (Little Tahoma, Unicorn Peak, Plummer
Peak, Chutla Peak, Osborne Mountain, Mount Rainier); T309's figures are in its merge commit.

**Sun** (`HorizonData.sunFor`, `shared/.../horizon/SunPath.kt`): the day's path at the point (the date whose solar noon
it is, `Sun.dayAt`, or a `date=` from the debug route), sampled every 5 minutes down to 1 degree below the horizon and
drawn at the sun's centre, solid in view and dashed behind the terrain, with a dot and the hour (the phone's zone) on
each whole hour; the sun now as T307 drew it. The section: where the sun is now, "Clears the terrain at …, goes behind
it at …" with any gaps behind a peak, the hours of sun, and the flat horizon's sunrise and sunset. The terrain times are
`Sun.onTerrain` on the plot's own profile (the upper limb's), the call the turn-around clock reaches through
`ShadeTiles.pointAnswer` (`ShadeService.terrainAt`): `GET /horizon?compare=1` at Paradise for 2026-11-19 gave the same
sunrise and sunset to the millisecond (`sunsetDifferenceMs` 0). Over the flat horizon while there is no profile.

The sweep opens the view in `android-ui.py features` (step `horizon`, by the map's switch since T309, the view's Map
segment closing it; frames `horizon`, `horizon-scrolled` and, T315, `horizon-end` at the end of the scroll; `gps` and
`gps-plot` before T308; T315: it fails unless the switch's rectangle on the view is the map's, `switchMapPx` and
`switchViewPx`, and nothing of the view lies under it at the end of the scroll, `underSwitch`) and `android-a11y.py`
(screen `horizon`, `gps` before T308).

## Another place (T317)

The owner, 2026-11-20: the skyline is computed from the DEM for any point, but a view of another place must never be read
as your own sky. "Sky from here" on the long-press card (after "What can I see from here"; `tracking/LongPressMenu.kt`)
opens the view for the pressed point: on a named feature (a peak, a saddle or a pass of the pack's `mountain_peak` layer,
z13 then z12 as T197 reads them) within a fingertip or 100 m, at the feature and named (`HorizonData.pointFor`). The terrain's
horizon, the peaks and the sun are the point's (`Gnss.horizonAt(lat, lon)`; T321: up to `Gnss.HORIZON_KEEP`, 3, horizons
are kept, the least recently used dropped first but never the one at the fix, so "Back to here" and a hop between points
sweep nothing again); the Satellites and Flights layers do not draw there (the GNSS the phone hears, the poll centred on its fix),
their buttons shown without a tick, muted and disabled, with "Satellites and flights show only at your location." under
them; their synced settings are not touched (`shared/.../horizon/HorizonPlace.kt`: `source`, `layers`, `disabled`).

- **The place line**, always shown: just below the plot in portrait, at the top of the right pane in landscape, above the
  heading. At the fix (or within 50 m of it, `HorizonPlace.HERE_M`): "Your location" beside a dot in the link colour.
  Elsewhere, bold on an amber band with a 2 dp edge: "At Mount Rainier, 7.6 km NNW of you" on a named feature, "12.0 km
  SSE of you" otherwise (16-point compass, units per ADR 0022; "At <name>" alone or the coordinates with no fix),
  recomputed every second as the fix moves (`HorizonPlace.line`, pure, `HorizonPlaceTest`). The plot's spoken label
  starts "Horizon from: <the line>".
- **"Back to here"** (a filled button at the band's right, tag `horizonBackToHere`) returns to the fix; Back does the
  same before it closes the view. A chosen point is held in memory only (`HorizonUi.point`): never snapped back on a
  timer, let go by Back and by the switch's "Map", and gone when the app restarts.
- **"View from here"** (T321): the picked peak's line under the heading carries a button of its own (at least 48 dp tall,
  tag `horizonViewFromHere`; the rest of the line still lets the peak go; above font scale 1.3 it stands under the line): the view moves to stand on the peak, named
  (`HorizonPlace.pointOf`, "At Little Tahoma, 7.3 km NNE of you"), the pick let go and the list scrolled to its top. From
  a chosen point it hops; the line is always measured from the fix.
- **Debug**: `POST /horizon/ui?lat=&lon=` and `?back=1`, `POST /horizon?viewFrom=<peak name>`, `POST /horizon/switch?to=horizon`
  (answers `from` and `point`), `GET /horizon` `point`, `placeLine`, `horizonSweeps` and `horizonsKept` (`android-debug-routes.md`).
  Sweep: `android-ui.py features` step `horizon` checks "Your location" at the fix, then `sky_from_here`: the map at the
  summit, a long-press, "Sky from here" (frame `horizon-point`), the place line, the Satellites toggle without a tick, and
  "Back to here" back to "Your location"; T321, by taps: `horizon_from_switch` (a long-press card open at the summit, the
  switch's "Horizon": frame `horizon-from-switch`, the card closed after) and `horizon_from_peak` (the switch with nothing
  selected at "Your location", frame `horizon-switch-here`; Mount Rainier's row picked, `horizon-peak-picked`; its "View from here": frame
  `horizon-from-peak`). `android-a11y.py` screen `horizon-elsewhere`: the view at the summit with a peak picked.

## Flights (T312)

The fourth layer, "Flights": T311's aircraft (`app/aircraft/AircraftPoller.kt`, feed data in memory only) on the plot.
Code: `shared/.../horizon/HorizonFlights.kt` (pure: the sky position, the terrain test, the tails, Sky range, the labels),
`HorizonLabels.placeLayers` (the two passes), `app/horizon/` (drawing, list, card, routes).

- **When:** the "Flights" button shows only while Flight tracking (T311's setting, off by default) is on; its own toggle
  `horizonFlights` is synced and on by default (`HorizonLayers.effective` / `buttons`). While the view is open, started and
  the layer on, it asks the poller as owner `horizon` (`AircraftPoller.request`, released on close or when the layer goes
  off) for the phone's position, rounded to 0.1° by the poller, at a radius by Sky range: Overhead 15 nm, Near 30 nm, All
  100 nm (185 km, where a cruising aircraft stands about 1° above a flat horizon). The map's own request is released while
  the view is open (T311), so one query is polled.
- **Where:** each aircraft at its bearing and elevation angle from the profile's own point and eye height (`groundM +
  observerHeightM`; the phone's fix without a profile) by T311's `AircraftGeo.elevationAngleDeg` (the earth's curvature,
  no refraction). Below 0° it sits on the rim. Aircraft on the ground are left out of the layer and the list (the owner,
  2026-11-20); one with no altitude has no angle and is left out (`noAltitude`).
- **Hollow** when hidden by the terrain, meaning below `HorizonProfile.angleAt(bearing)` **and** farther than the ridge's
  `distanceAt(bearing)` (a helicopter below the ridge line but in front of the ridge is in sight; the profile's refraction,
  k θ / 2, is added to the aircraft's angle for this test only), or when its position is stale (T311's rule, 30 s at the default refresh; T316 scales it with the interval, 1.5 intervals at 120 and 180 s, as the help says). The dot is
  filled in T311's altitude colour with an ink edge, or hollow (sky inside an altitude-coloured ring); its tail is its
  last 90 s (T311's trail), dashed while hollow. An emergency (7700, 7600, 7500, or the feed's emergency field) gets a red
  ring around its dot and its row's dot, a shape and not a colour alone; the row says "Emergency (7700)".
- **Labels:** "call sign > destination code" ("ASA1409 > SEA"), the call sign alone without a plausible route
  (`LabelKind.AIRCRAFT`). Placed in a second pass: the peaks' names are placed exactly as without flights, then the
  flights' names around them, the peaks' boxes, triangles and leader lines blocked, the closest flight not forced; so peak
  names never move with the aircraft, and no flight name covers a peak's. `HorizonUi.names` stays peaks-only. Round 2: flight
  names also keep off N E S W and the north mark (`RimGeometry.compassBoxes`, the second pass only); the ring labels
  (0°, 30°, 60°) are blocked in both passes.
- **List:** a "Flights" section after Peaks, closest first: the count line ("3 flights in the air around you, closest
  first.", or the range's own), the feed's message when it has one ("No flight data: offline" and T311's others), then
  each flight: its dot as drawn, the label, and kind · distance · compass bearing · angle · altitude (T311's formatter: the
  user's units and the flight level) · "behind the terrain" or the stale age.
- **Card:** a tap on a dot (within 24 dp), its name or its row opens T311's `FlightCard`, hosted inside `HorizonScreen`
  over the pane's lower part (the map's card would open under the view); its Close, a second tap or Back closes it.
- **Credit:** while the layer draws, the feed's credit (`AircraftPoller.credit`: "ADSB.lol contributors (ODbL)", or the
  replay's line) in the plot square's lower left corner, outside the circle (10 dp, sized like the plot's labels, so it does not grow
  onto the plot at large text; two lines at most).
- **Toggles:** "Show:" and the four buttons fit one row at the default font on a 411 dp phone (6 dp gaps, 10 dp padding;
  round 2); larger text wraps.
- **Sweep:** `android-ui.py features` step `horizonflights` (release, by taps: the Sky range row, then airplane mode and
  Flight tracking on, the view's Flights toggle and offline line); `android-ui.py horizonflights` (debug, T311's replay:
  plot, list, card, font scale 2.0, landscape); `android-a11y.py` screen `horizon-flights`.
- **Sky range** (Settings › Navigation and tracking, after Sky plot; `skyRange`, synced; ADR 0044: the apps split, the
  finding `docs/findings/2026-11-20-aircraft-layer-sources-and-display.md`): Overhead (above 45°), Near (within 50 km, a
  first guess), All (the default).

## The satellites (T307)

T307 built this as the GPS view; T308 made it the Horizon view's Satellites layer (`app/gnss/`; the verdict and the summary are `shared/.../gnss/GnssHealth.kt`). Its section of the scrolling area, top to bottom: the heading "Satellites" with its menu, the summary (satellites used of in view, the four best C/N0 and their mean, the systems, the band line "L1 + L5" or "L1 only" from the carrier frequencies, accuracy, fix age, height from the GPS and from the map, speed, course, time to first fix, the fix's time); "Check my GPS" and its verdict; the legend with the ramp in dB-Hz; the satellites heard, used first then by signal (two lines each above font scale 1.3). The layout, the setting and the verdict's figures come from the two surveys in the idea of 2026-11-19 (the idea file was deleted with T307; its text is in git). While the view is open with Satellites on, and only while the activity is started, it holds the GNSS (`Gnss.hold`): a `GnssStatus.Callback` and its own GPS listener on the recorder's looper, both removed when the last holder lets go; "Follow the phone" runs the app's one compass (`Heading.setGnssWanted`). Time to first fix is the app's own: from the first `MapController.startDeviceLocation` of the process to its first GPS fix. The terrain's horizon is `Horizon.sweep` at the position (1 degree bearings, eye height 1.7 m) over `ShadeService.pyramidAt` (the offline map, else the online DEM), on a thread of its own, kept until the position moves 100 m; at Paradise it took 0.5 to 2.3 s on a quiet emulator and 129 s once on one just booted under the map's first tiles; with no elevation data the plot has no band and the line says why. "Check my GPS" reads the status once a second for 120 s, stops with a message if the position moves more than 20 m, and judges: healthy (T4, the mean of the four strongest of the best system above the terrain, at least 35 dB-Hz, and at least 4 used), "May be a hardware fault" (T4 under 25, nothing above the terrain over 30, at least 8 heard above it), else weak signal; satellites behind the terrain (under its angle plus 2 degrees) never count against the phone; the first fix is shown, not judged. The fault verdict's 25 is uncalibrated. The menu's "Reset assistance data" asks first, then `sendExtraCommand` (`delete_aiding_data`, time and orbit injection; `ACCESS_LOCATION_EXTRA_COMMANDS`) and says whether the phone took it. The last check rides in Send diagnostics (`diagnostic-dump.md`, section `gnss`). On the plot each satellite is a shape per system filled on a colour-blind-safe C/N0 ramp, a thick outline when used in the fix, the number beside it, a ring when heard on a second band. Routes: `GET|POST /gnss`, `/gnss/ui`, `/gnss/check`, `/gnss/reset` (`android-debug-routes.md`).
